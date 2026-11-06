import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import sodium from 'libsodium-wrappers-sumo';
import { registerFactoryRelayCredential } from './relay';
import type { PinnedComputerIdentity } from './secure-channel';

/**
 * Loopback fake of the relay + computer. Unlike relay.test.ts, this test does
 * NOT mock the secure-channel module: the full Noise/secretstream wire traffic
 * is checked against keys independently derived with Python cryptography.
 */
const COMPUTER_ID = 'a34547ea-065c-43a9-857e-56e5a73a91f0';
const CLIENT_ID = 'b34547ea-065c-43a9-857e-56e5a73a91f0';
const URL = 'wss://relay.factory.ai/v0/computer/' + COMPUTER_ID + '/client';
const PIN: PinnedComputerIdentity = {
  algorithm: 'X25519',
  publicKey: 'e06Qm75//kTEZaIgA31gjuNYl9Me+XLwf3SJLLD3PxM=',
  keyId: 'f475e9e6f74465367aa2fe554c85f316',
};
const CLIENT_HELLO = 'D6poTtKIZ7l_Smot7l34zpdOdrcBjj8iocTPJnhXDyBDE_uHcn6BliivPx-6NPzI';
const SERVER_HELLO = 'ew1H2TQn-DERYHgcfHM_2J-IlwrvSQ2KoO4ZpMuKGxQttS66xpDu2iBqBnrCaDEq';
const CLIENT_TX_KEY = '8af1a9acd701075bc06a015017badb3324bf4326bc72d4d386a0e8c94d8e9568';
const CLIENT_RX_KEY = '0074155e51693b11c7edfeaeb967c358d95f9078e8af2a60e8bca4c0a8b89c13';
const encoder = new TextEncoder();
const decoder = new TextDecoder();

const nativeWS = globalThis.WebSocket;
const nativeCloseEvent = globalThis.CloseEvent;
let release: (() => void) | undefined;
let socket: WebSocket | undefined;

class FakeRelayAndDaemon extends EventTarget {
  static CONNECTING = 0;
  static OPEN = 1;
  static CLOSING = 2;
  static CLOSED = 3;
  static latest: FakeRelayAndDaemon | undefined;
  readonly url: string;
  readyState = 0;
  bufferedAmount = 0;
  extensions = '';
  protocol = '';
  binaryType: WebSocket['binaryType'] = 'arraybuffer';
  sent: Array<string | Uint8Array> = [];
  clientHello: string | undefined;
  daemonRequests: string[] = [];
  rejectRecords = false;

  constructor(url: string | URL) {
    super();
    this.url = String(url);
    FakeRelayAndDaemon.latest = this;
  }
  start(): void {
    this.readyState = FakeRelayAndDaemon.OPEN;
    this.dispatchEvent(new Event('open'));
  }
  private deliverText(message: string): void {
    this.dispatchEvent(new MessageEvent('message', { data: message }));
  }
  private deliverBinary(bytes: Uint8Array): void {
    const copy = Uint8Array.from(bytes);
    this.dispatchEvent(new MessageEvent('message', { data: copy.buffer }));
  }
  send(data: string | ArrayBufferLike | Blob | ArrayBufferView): void {
    if (typeof data === 'string') {
      this.sent.push(data);
      const parsed = JSON.parse(data) as Record<string, unknown>;
      if (parsed.method !== 'relay.authenticate') throw new Error('Plaintext daemon request!');
      if (parsed.apiKey !== 'fk-synthetic-test') throw new Error('Wrong relay authentication');
      queueMicrotask(() => this.deliverText(JSON.stringify({
        type: 'relay.auth_ok', clientId: CLIENT_ID,
      })));
      return;
    }
    if (!ArrayBuffer.isView(data)) throw new Error('Expected encrypted binary record');
    const bytes = Uint8Array.from(new Uint8Array(data.buffer, data.byteOffset, data.byteLength));
    this.sent.push(bytes);
    const record = JSON.parse(decoder.decode(bytes)) as Record<string, unknown>;
    if (record.type === 'secure_channel_client_hello') {
      this.clientHello = String(record.message);
      queueMicrotask(() => this.deliverBinary(encoder.encode(JSON.stringify({
        type: 'secure_channel_server_hello', version: 2, message: SERVER_HELLO,
      }))));
      return;
    }
    if (record.ciphertext && typeof record.ciphertext === 'string') {
      if (typeof record.header !== 'string') throw new Error('First record missing header');
      const reader = sodium.crypto_secretstream_xchacha20poly1305_init_pull(
        sodium.from_base64(record.header),
        sodium.from_hex(CLIENT_TX_KEY),
      );
      const opened = sodium.crypto_secretstream_xchacha20poly1305_pull(
        reader, sodium.from_base64(record.ciphertext), 'factory-secure-channel-v2',
      );
      if (!opened) throw new Error('Cannot authenticate mobile RPC record');
      const request = decoder.decode(opened.message);
      this.daemonRequests.push(request);
      const msg = JSON.parse(request) as Record<string, unknown>;
      const outbound = sodium.crypto_secretstream_xchacha20poly1305_init_push(
        sodium.from_hex(CLIENT_RX_KEY),
      );
      const response = JSON.stringify({
        jsonrpc: '2.0', id: msg.id ?? 1, result: { authenticated: true },
      });
      const cipher = sodium.crypto_secretstream_xchacha20poly1305_push(
        outbound.state, response, 'factory-secure-channel-v2',
        sodium.crypto_secretstream_xchacha20poly1305_TAG_MESSAGE,
      );
      const reply = {
        header: sodium.to_base64(outbound.header),
        ciphertext: sodium.to_base64(cipher),
      };
      if (this.rejectRecords) reply.ciphertext = 'corrupted';
      queueMicrotask(() => this.deliverBinary(encoder.encode(JSON.stringify(reply))));
      return;
    }
    throw new Error('Unexpected client protocol record');
  }
  close(code = 1000, reason = ''): void {
    this.readyState = FakeRelayAndDaemon.CLOSED;
    this.dispatchEvent(new CloseEvent('close', { code, reason, wasClean: true }));
  }
}

beforeAll(async () => { await sodium.ready; });
afterEach(() => {
  socket?.close();
  release?.();
  release = undefined;
  socket = undefined;
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  vi.stubGlobal('WebSocket', nativeWS);
  vi.stubGlobal('CloseEvent', nativeCloseEvent);
  FakeRelayAndDaemon.latest = undefined;
});

function begin(identity = PIN): FakeRelayAndDaemon {
  // The fake server hello was calculated for this deterministic client key.
  // Production code uses crypto_box_keypair() and its cryptographic RNG.
  vi.spyOn(sodium, 'crypto_box_keypair').mockImplementation(() => {
    const privateKey = new Uint8Array(32).fill(0x22);
    return { privateKey, publicKey: sodium.crypto_scalarmult_base(privateKey) };
  });
  vi.stubGlobal('WebSocket', FakeRelayAndDaemon as unknown as typeof WebSocket);
  vi.stubGlobal('CloseEvent', class FakeCloseEvent extends Event {
    code: number;
    reason: string;
    wasClean: boolean;
    constructor(type: string, options: CloseEventInit = {}) {
      super(type);
      this.code = options.code ?? 1000;
      this.reason = options.reason ?? '';
      this.wasClean = options.wasClean ?? false;
    }
  });
  release = registerFactoryRelayCredential(URL, 'fk-synthetic-test', identity);
  socket = new WebSocket(URL);
  const native = FakeRelayAndDaemon.latest;
  if (!native) throw new Error('Expected a relay WebSocket');
  return native;
}

describe('Full in-memory Factory relay + pinned secure channel', () => {
  it('handshakes, encrypts daemon RPC and authenticates an encrypted reply', async () => {
    const native = begin();
    const opened = vi.fn();
    const received = vi.fn();
    const errors = vi.fn();
    socket!.addEventListener('open', opened);
    socket!.addEventListener('message', received);
    socket!.addEventListener('error', errors);
    native.start();
    await vi.waitFor(() => expect(opened).toHaveBeenCalledTimes(1));
    expect(native.clientHello).toBe(CLIENT_HELLO);

    socket!.send(JSON.stringify({
      jsonrpc: '2.0', id: 27, method: 'daemon.authenticate',
      params: { apiKey: 'fk-synthetic-test' },
    }));
    await vi.waitFor(() => expect(received).toHaveBeenCalledTimes(1));
    expect(errors).not.toHaveBeenCalled();
    expect(native.daemonRequests).toHaveLength(1);
    expect(JSON.parse(native.daemonRequests[0])).toMatchObject({
      method: 'daemon.authenticate', id: 27,
    });
    expect(JSON.parse((received.mock.calls[0][0] as MessageEvent).data)).toMatchObject({
      id: 27, result: { authenticated: true },
    });
    expect(native.sent).toHaveLength(3);
    expect(decoder.decode(native.sent[2] as Uint8Array)).not.toContain('daemon.authenticate');
  });

  it('never reveals a ready socket if the server cannot prove the pinned key', async () => {
    const wrong: PinnedComputerIdentity = {
      ...PIN,
      // Different static key with an intentionally wrong fingerprint.
      publicKey: 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=',
    };
    const native = begin(wrong);
    const opened = vi.fn();
    const errors = vi.fn();
    socket!.addEventListener('open', opened);
    socket!.addEventListener('error', errors);
    native.start();
    await vi.waitFor(() => expect(native.readyState).toBe(FakeRelayAndDaemon.CLOSED));
    expect(opened).not.toHaveBeenCalled();
    expect(errors).toHaveBeenCalled();
    expect(native.daemonRequests).toHaveLength(0);
  });
});
