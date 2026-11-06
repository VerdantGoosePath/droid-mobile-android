/**
 * Authenticated and pinned Factory Remote Control WebSocket adapter.
 *
 * Relay authentication (text) precedes Noise secure-channel negotiation
 * (binary), which precedes application JSON-RPC (encrypted binary records).
 * The public SDK sees a WebSocket open ONLY after the pinned Noise handshake.
 *
 * Fail closed: never dispatch unencrypted application frames.
 */
import type { PinnedComputerIdentity } from './secure-channel';
import type { SecureRpcChannel } from './secure-channel';

const RELAY_HOSTS = new Set(['relay.factory.ai', 'relay.eu.factory.ai']);
const REMOTE_PATH =
  /^\/v0\/computer\/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\/(agent|client)\/?$/i;
const AUTH_TIMEOUT_MS = 10_000;
const SECURE_TIMEOUT_MS = 10_000;
const MAX_FRAME_BYTES = 8 * 1024 * 1024;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export interface FactoryRelayTarget {
  url: string;
  computerId: string;
}

export function factoryRelayTarget(address: string): FactoryRelayTarget | null {
  try {
    const parsed = new URL(address.trim());
    if (
      parsed.protocol !== 'wss:' ||
      !RELAY_HOSTS.has(parsed.hostname.toLowerCase()) ||
      parsed.port ||
      parsed.username ||
      parsed.password ||
      parsed.search ||
      parsed.hash
    ) return null;
    const match = REMOTE_PATH.exec(parsed.pathname);
    if (!match) return null;
    const computerId = match[1].toLowerCase();
    return {
      url: 'wss://' + parsed.hostname.toLowerCase() + '/v0/computer/' + computerId + '/client',
      computerId,
    };
  } catch {
    return null;
  }
}

type NativeSocket = typeof WebSocket;
type Phase = 'relay' | 'loading-crypto' | 'secure-hello' | 'ready' | 'closed';
interface Credential {
  apiKey: string;
  identity: PinnedComputerIdentity;
  computerId: string;
}

function isAuthOk(data: unknown): string | null {
  if (typeof data !== 'string') return null;
  try {
    const frame: unknown = JSON.parse(data);
    if (!frame || typeof frame !== 'object') return null;
    const value = frame as Record<string, unknown>;
    return value.type === 'relay.auth_ok' &&
      typeof value.clientId === 'string' && UUID.test(value.clientId)
      ? value.clientId : null;
  } catch {
    return null;
  }
}

function isFactoryAuthError(data: unknown): boolean {
  if (typeof data !== 'string') return false;
  try {
    const frame: unknown = JSON.parse(data);
    return !!frame && typeof frame === 'object' &&
      (frame as Record<string, unknown>).type === 'relay.auth_error';
  } catch {
    return false;
  }
}

function bytesOf(data: unknown): Uint8Array | null {
  // WebView bridges and test environments can expose ArrayBuffers from
  // another JS realm, where `instanceof ArrayBuffer` is false.
  if (data instanceof ArrayBuffer ||
      Object.prototype.toString.call(data) === '[object ArrayBuffer]')
    return new Uint8Array(data as ArrayBuffer);
  if (ArrayBuffer.isView(data))
    return new Uint8Array(data.buffer, data.byteOffset, data.byteLength);
  return null;
}

/**
 * Prevent the server from sending compression formats unsupported by the
 * wrapped SDK message reader. Optional negotiation belongs to daemon auth.
 */
function withoutCompressionOffer(message: string): string {
  try {
    const parsed: unknown = JSON.parse(message);
    if (!parsed || typeof parsed !== 'object') return message;
    const frame = parsed as Record<string, unknown>;
    if (frame.method !== 'daemon.authenticate') return message;
    if (!frame.params || typeof frame.params !== 'object') return message;
    const params = { ...(frame.params as Record<string, unknown>) };
    delete params.acceptEncodings;
    return JSON.stringify({ ...frame, params });
  } catch {
    return message;
  }
}

class SecuredRelaySocket extends EventTarget {
  static readonly CONNECTING = 0;
  static readonly OPEN = 1;
  static readonly CLOSING = 2;
  static readonly CLOSED = 3;
  readonly CONNECTING = 0;
  readonly OPEN = 1;
  readonly CLOSING = 2;
  readonly CLOSED = 3;

  readonly url: string;
  readonly raw: WebSocket;
  onopen: ((this: WebSocket, ev: Event) => unknown) | null = null;
  onerror: ((this: WebSocket, ev: Event) => unknown) | null = null;
  onmessage: ((this: WebSocket, ev: MessageEvent) => unknown) | null = null;
  onclose: ((this: WebSocket, ev: CloseEvent) => unknown) | null = null;

  private phase: Phase = 'relay';
  private channel: SecureRpcChannel | null = null;
  private timeout: ReturnType<typeof setTimeout> | null = null;
  private finished = false;

  constructor(target: FactoryRelayTarget, credential: Credential,
              Native: NativeSocket, protocols?: string | string[]) {
    super();
    this.url = target.url;
    if (target.computerId !== credential.computerId)
      throw new Error('Factory computer identity does not match relay URL');
    if (!credential.identity || credential.identity.algorithm !== 'X25519')
      throw new Error('Pinned computer X25519 identity is required');
    this.raw = protocols === undefined
      ? new Native(target.url)
      : new Native(target.url, protocols);
    this.raw.binaryType = 'arraybuffer';

    this.raw.addEventListener('open', () => {
      if (this.finished) return;
      this.arm(AUTH_TIMEOUT_MS);
      try {
        this.raw.send(JSON.stringify({
          method: 'relay.authenticate',
          apiKey: credential.apiKey,
        }));
      } catch {
        this.abort();
      }
    });
    this.raw.addEventListener('message', (event: MessageEvent) => {
      if (this.finished) return;
      void this.receive(event.data, credential, target).catch(() => this.abort());
    });
    this.raw.addEventListener('error', () => {
      if (!this.finished) this.emit(new Event('error'));
    });
    this.raw.addEventListener('close', (event: CloseEvent) => {
      if (this.finished) return;
      this.finished = true;
      this.phase = 'closed';
      this.clearTimeout();
      this.channel?.destroy();
      this.channel = null;
      this.emit(new CloseEvent('close', {
        code: event.code, reason: event.reason, wasClean: event.wasClean,
      }));
    });
  }

  private arm(ms: number): void {
    this.clearTimeout();
    this.timeout = setTimeout(() => this.abort(), ms);
  }
  private clearTimeout(): void {
    if (this.timeout !== null) clearTimeout(this.timeout);
    this.timeout = null;
  }

  private emit(event: Event): void {
    super.dispatchEvent(event);
    const callbacks = {
      open: this.onopen,
      error: this.onerror,
      message: this.onmessage,
      close: this.onclose,
    } as Record<string, ((this: WebSocket, ev: Event) => unknown) | null>;
    callbacks[event.type]?.call(this as unknown as WebSocket, event);
  }

  private async receive(
    data: unknown, credential: Credential, target: FactoryRelayTarget,
  ): Promise<void> {
    if (this.finished) return;
    switch (this.phase) {
      case 'relay': {
        if (isFactoryAuthError(data)) throw new Error('Factory relay rejected authentication');
        const clientId = isAuthOk(data);
        if (!clientId) throw new Error('Relay authentication response lacks a valid client ID');
        this.phase = 'loading-crypto';
        this.arm(SECURE_TIMEOUT_MS);
        // Heavy libsodium bundle is loaded only for Factory relay sessions.
        const { SecureRpcChannel } = await import('./secure-channel');
        if (this.finished || this.phase !== 'loading-crypto') return;
        const { channel, hello } = await SecureRpcChannel.begin(credential.identity, {
          computerId: target.computerId, clientId,
        });
        if (this.finished || this.phase !== 'loading-crypto') {
          channel.destroy();
          return;
        }
        this.channel = channel;
        this.phase = 'secure-hello';
        this.raw.send(new Uint8Array(hello));
        break;
      }
      case 'loading-crypto':
        // The relay must not deliver application data before the hello.
        throw new Error('Unexpected frame during cryptographic initialization');
      case 'secure-hello': {
        const bytes = bytesOf(data);
        if (!bytes || !this.channel) throw new Error('Expected binary Noise server hello');
        this.channel.finish(bytes);
        this.phase = 'ready';
        this.clearTimeout();
        this.emit(new Event('open'));
        break;
      }
      case 'ready': {
        const bytes = bytesOf(data);
        if (!bytes || bytes.length > MAX_FRAME_BYTES || !this.channel)
          throw new Error('Peer attempted plaintext downgrade or oversized secure record');
        const message = this.channel.open(bytes);
        const text = new TextDecoder('utf-8', { fatal: true }).decode(message);
        this.emit(new MessageEvent('message', { data: text }));
        break;
      }
      case 'closed':
        break;
    }
  }

  private abort(): void {
    if (this.finished) return;
    this.clearTimeout();
    this.channel?.destroy();
    this.channel = null;
    this.emit(new Event('error'));
    try { this.raw.close(4005, 'Secure relay connection rejected'); }
    catch { try { this.raw.close(); } catch { /* close is best-effort */ } }
  }

  get readyState(): number {
    if (this.finished || this.raw.readyState === WebSocket.CLOSED) return WebSocket.CLOSED;
    if (this.raw.readyState === WebSocket.CLOSING) return WebSocket.CLOSING;
    return this.phase === 'ready' ? WebSocket.OPEN : WebSocket.CONNECTING;
  }
  get bufferedAmount(): number { return this.raw.bufferedAmount; }
  get extensions(): string { return this.raw.extensions; }
  get protocol(): string { return this.raw.protocol; }
  get binaryType(): WebSocket['binaryType'] { return this.raw.binaryType; }
  set binaryType(value: WebSocket['binaryType']) {
    // The real socket must always deliver binary encrypted records as bytes.
    if (value === 'arraybuffer') this.raw.binaryType = 'arraybuffer';
  }

  send(data: string | ArrayBufferLike | Blob | ArrayBufferView): void {
    if (this.readyState !== WebSocket.OPEN || !this.channel)
      throw new DOMException('Secure channel not ready', 'InvalidStateError');
    if (typeof data !== 'string')
      throw new Error('Binary daemon requests are not supported by secure relay adapter');
    const plaintext = new TextEncoder().encode(withoutCompressionOffer(data));
    this.raw.send(new Uint8Array(this.channel.seal(plaintext)));
  }

  close(code?: number, reason?: string): void {
    this.clearTimeout();
    this.channel?.destroy();
    this.channel = null;
    this.raw.close(code, reason);
  }
}

const credentials = new Map<string, { credential: Credential; refs: number }>();
let originalWebSocket: NativeSocket | null = null;

function installFactoryRelayWebSocket(): void {
  if (originalWebSocket !== null) return;
  const Native = globalThis.WebSocket;
  if (!Native) throw new Error('WebSocket is unavailable');
  originalWebSocket = Native;
  const Wrapped = function (
    this: WebSocket, address: string | URL, protocols?: string | string[],
  ) {
    const url = String(address);
    const entry = credentials.get(url);
    if (entry) {
      const target = factoryRelayTarget(url);
      if (!target) throw new Error('Invalid Factory relay URL');
      return new SecuredRelaySocket(target, entry.credential, Native, protocols) as unknown as WebSocket;
    }
    return protocols === undefined ? new Native(address) : new Native(address, protocols);
  } as unknown as NativeSocket;
  Object.assign(Wrapped, {
    CONNECTING: Native.CONNECTING, OPEN: Native.OPEN,
    CLOSING: Native.CLOSING, CLOSED: Native.CLOSED,
  });
  globalThis.WebSocket = Wrapped;
}

/**
 * Register the trusted computer identity BEFORE the SDK constructs any sockets.
 * No relay socket is allowed to connect without the identity returned by API.
 */
export function registerFactoryRelayCredential(
  url: string, apiKey: string, identity: PinnedComputerIdentity,
): () => void {
  const target = factoryRelayTarget(url);
  if (!target || target.url !== url) throw new Error('Invalid client relay URL');
  if (!identity || identity.algorithm !== 'X25519' ||
      typeof identity.publicKey !== 'string' || typeof identity.keyId !== 'string')
    throw new Error('Pinned X25519 identity is mandatory for Factory relay');
  const existing = credentials.get(url);
  if (existing) {
    if (existing.credential.apiKey !== apiKey ||
        existing.credential.identity.keyId !== identity.keyId ||
        existing.credential.identity.publicKey !== identity.publicKey)
      throw new Error('Conflicting Factory relay credentials on the same computer');
    existing.refs++;
  } else {
    credentials.set(url, {
      credential: { apiKey, identity: { ...identity }, computerId: target.computerId },
      refs: 1,
    });
  }
  installFactoryRelayWebSocket();
  let released = false;
  return () => {
    if (released) return;
    released = true;
    const entry = credentials.get(url);
    if (entry && --entry.refs <= 0) credentials.delete(url);
    if (credentials.size === 0 && originalWebSocket !== null) {
      globalThis.WebSocket = originalWebSocket;
      originalWebSocket = null;
    }
  };
}
