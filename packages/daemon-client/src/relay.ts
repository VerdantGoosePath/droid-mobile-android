/**
 * Factory-hosted Remote Control transport.
 *
 * The public Computers API supplies relayClientUrl; the CLI's --remote-control
 * output prints relayAgentUrl instead. They are different halves of the relay.
 *
 * The SDK's high-level facade presently only offers direct daemon WebSocket
 * connections. This adapter preserves that facade by making a Factory relay
 * socket look open only AFTER its separate relay.authenticate exchange.
 * The SDK then performs daemon.authenticate on the same socket.
 *
 * No API key is ever added to a URL or placed in persistent web storage.
 * No TLS or secure-channel errors are bypassed.
 */
const RELAY_HOSTS = new Set(['relay.factory.ai', 'relay.eu.factory.ai']);
const REMOTE_PATH =
  /^\/v0\/computer\/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\/(agent|client)\/?$/i;
const AUTH_TIMEOUT_MS = 10_000;

export interface FactoryRelayTarget {
  url: string;
  computerId: string;
}

/** Convert a printed /agent address into the client-side endpoint. */
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
    ) {
      return null;
    }
    const match = REMOTE_PATH.exec(parsed.pathname);
    if (!match) return null;
    const computerId = match[1].toLowerCase();
    return {
      url: `wss://${parsed.hostname.toLowerCase()}/v0/computer/${computerId}/client`,
      computerId,
    };
  } catch {
    return null;
  }
}

type RelayAuthMessage =
  | { type: 'relay.auth_ok'; clientId?: string }
  | { type: 'relay.auth_error'; code?: string; message?: string };

/** Only these relay frames are consumed; daemon frames flow to the SDK unchanged. */
function relayAuthResponse(data: unknown): RelayAuthMessage | null {
  if (typeof data !== 'string') return null;
  try {
    const parsed: unknown = JSON.parse(data);
    if (typeof parsed !== 'object' || parsed === null) return null;
    const value = parsed as Record<string, unknown>;
    if (value.type === 'relay.auth_ok') {
      return { type: 'relay.auth_ok', ...(typeof value.clientId === 'string' ? { clientId: value.clientId } : {}) };
    }
    if (value.type === 'relay.auth_error') {
      return {
        type: 'relay.auth_error',
        ...(typeof value.code === 'string' ? { code: value.code } : {}),
        ...(typeof value.message === 'string' ? { message: value.message } : {}),
      };
    }
    return null;
  } catch {
    return null;
  }
}

type NativeSocket = typeof WebSocket;

class AuthenticatedRelaySocket extends EventTarget {
  static readonly CONNECTING = 0;
  static readonly OPEN = 1;
  static readonly CLOSING = 2;
  static readonly CLOSED = 3;

  readonly CONNECTING = 0;
  readonly OPEN = 1;
  readonly CLOSING = 2;
  readonly CLOSED = 3;
  readonly url: string;
  onopen: ((this: WebSocket, ev: Event) => unknown) | null = null;
  onclose: ((this: WebSocket, ev: CloseEvent) => unknown) | null = null;
  onerror: ((this: WebSocket, ev: Event) => unknown) | null = null;
  onmessage: ((this: WebSocket, ev: MessageEvent) => unknown) | null = null;

  private readonly raw: WebSocket;
  private authenticated = false;
  private authTimer: ReturnType<typeof setTimeout> | null = null;
  private finished = false;

  constructor(target: string, apiKey: string, Native: NativeSocket, protocols?: string | string[]) {
    super();
    this.url = target;
    this.raw = protocols === undefined ? new Native(target) : new Native(target, protocols);

    this.raw.addEventListener('open', () => {
      try {
        // Two-step authentication: relay.authenticate, THEN daemon.authenticate by SDK.
        this.raw.send(JSON.stringify({ method: 'relay.authenticate', apiKey }));
        this.authTimer = setTimeout(() => this.abort(), AUTH_TIMEOUT_MS);
      } catch {
        this.abort();
      }
    });
    this.raw.addEventListener('message', (event: MessageEvent) => {
      if (this.finished) return;
      if (!this.authenticated) {
        const response = relayAuthResponse(event.data);
        if (!response) {
          this.abort();
          return;
        }
        if (response.type === 'relay.auth_error') {
          this.abort();
          return;
        }
        this.authenticated = true;
        this.clearTimer();
        this.emit(new Event('open'));
        return;
      }
      this.emit(new MessageEvent('message', { data: event.data, origin: event.origin }));
    });
    this.raw.addEventListener('error', () => {
      if (!this.finished) this.emit(new Event('error'));
    });
    this.raw.addEventListener('close', (event: CloseEvent) => {
      if (this.finished) return;
      this.finished = true;
      this.clearTimer();
      this.emit(new CloseEvent('close', { code: event.code, reason: event.reason, wasClean: event.wasClean }));
    });
  }

  private emit(event: Event): void {
    super.dispatchEvent(event);
    const callbacks = {
      open: this.onopen,
      close: this.onclose,
      error: this.onerror,
      message: this.onmessage,
    } as Record<string, ((this: WebSocket, event: Event) => unknown) | null>;
    callbacks[event.type]?.call(this as unknown as WebSocket, event);
  }

  private clearTimer(): void {
    if (this.authTimer !== null) clearTimeout(this.authTimer);
    this.authTimer = null;
  }

  private abort(): void {
    if (this.finished) return;
    this.emit(new Event('error'));
    try {
      this.raw.close(4005, 'Relay authentication failed');
    } catch {
      this.raw.close();
    }
  }

  get readyState(): number {
    if (this.finished) return WebSocket.CLOSED;
    if (this.raw.readyState === WebSocket.CLOSING) return WebSocket.CLOSING;
    if (this.raw.readyState === WebSocket.CLOSED) return WebSocket.CLOSED;
    return this.authenticated ? WebSocket.OPEN : WebSocket.CONNECTING;
  }
  get bufferedAmount(): number { return this.raw.bufferedAmount; }
  get extensions(): string { return this.raw.extensions; }
  get protocol(): string { return this.raw.protocol; }
  get binaryType(): WebSocket['binaryType'] { return this.raw.binaryType; }
  set binaryType(type: WebSocket['binaryType']) { this.raw.binaryType = type; }

  send(data: string | ArrayBufferLike | Blob | ArrayBufferView): void {
    if (this.readyState !== WebSocket.OPEN) {
      throw new DOMException('Relay authentication is not complete', 'InvalidStateError');
    }
    this.raw.send(data as Parameters<WebSocket['send']>[0]);
  }

  close(code?: number, reason?: string): void {
    this.clearTimer();
    this.raw.close(code, reason);
  }
}

/**
 * Register one in-memory credential for all sockets to this computer.
 * The SDK creates its WebSockets internally, including probe/terminal sidecars,
 * so this scoped constructor intercept is needed until it provides a transport
 * factory for connectToDaemon().
 */
const credentials = new Map<string, Array<{ key: string }>>();
let originalWebSocket: NativeSocket | null = null;

function installFactoryRelayWebSocket(): void {
  if (originalWebSocket !== null) return;
  const Native = globalThis.WebSocket;
  originalWebSocket = Native;
  const Wrapped = function (this: WebSocket, address: string | URL, protocols?: string | string[]) {
    const url = String(address);
    const saved = credentials.get(url)?.at(-1);
    if (saved) {
      return new AuthenticatedRelaySocket(url, saved.key, Native, protocols) as unknown as WebSocket;
    }
    return protocols === undefined ? new Native(address) : new Native(address, protocols);
  } as unknown as NativeSocket;
  Object.assign(Wrapped, {
    CONNECTING: Native.CONNECTING,
    OPEN: Native.OPEN,
    CLOSING: Native.CLOSING,
    CLOSED: Native.CLOSED,
  });
  globalThis.WebSocket = Wrapped;
}

export function registerFactoryRelayCredential(url: string, apiKey: string): () => void {
  if (!factoryRelayTarget(url)) return () => {};
  if (!globalThis.WebSocket) throw new Error('WebSocket is unavailable');
  installFactoryRelayWebSocket();
  const key = { key: apiKey };
  const list = credentials.get(url) ?? [];
  list.push(key);
  credentials.set(url, list);
  let released = false;
  return () => {
    if (released) return;
    released = true;
    const list = credentials.get(url);
    if (list) {
      const index = list.indexOf(key);
      if (index >= 0) list.splice(index, 1);
      if (list.length === 0) credentials.delete(url);
    }
    if (credentials.size === 0 && originalWebSocket !== null) {
      globalThis.WebSocket = originalWebSocket;
      originalWebSocket = null;
    }
  };
}
