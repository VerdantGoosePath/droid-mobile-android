import { afterEach, describe, expect, it, vi } from 'vitest';
import { factoryRelayTarget, registerFactoryRelayCredential } from './relay';

vi.mock('./secure-channel', () => ({
  SecureRpcChannel: {
    begin: vi.fn(async () => ({
      hello: new Uint8Array([42]),
      channel: {
        finish: vi.fn((bytes: Uint8Array) => {
          if (bytes[0] !== 43) throw new Error('bad server hello');
        }),
        seal: vi.fn((plaintext: Uint8Array) => plaintext),
        open: vi.fn((ciphertext: Uint8Array) => ciphertext),
        destroy: vi.fn(),
      },
    })),
  },
}));

const ID = 'a34547ea-065c-43a9-857e-56e5a73a91f0';
const CLIENT_ID = 'b34547ea-065c-43a9-857e-56e5a73a91f0';
const AGENT = 'wss://relay.factory.ai/v0/computer/' + ID + '/agent';
const CLIENT = 'wss://relay.factory.ai/v0/computer/' + ID + '/client';
const IDENTITY = { algorithm: 'X25519' as const, publicKey: 'synthetic', keyId: 'synthetic' };

describe('Factory relay URL validation', () => {
  it('converts printed agent URL into the client endpoint', () => {
    expect(factoryRelayTarget(AGENT)).toEqual({ url: CLIENT, computerId: ID });
    expect(factoryRelayTarget(CLIENT)?.url).toBe(CLIENT);
  });
  it.each([
    'ws://relay.factory.ai/v0/computer/' + ID + '/client',
    'wss://evil.test/v0/computer/' + ID + '/client',
    'wss://relay.factory.ai.evil.test/v0/computer/' + ID + '/client',
    'wss://relay.factory.ai/v0/computer/' + ID + '/client?token=secret',
    'wss://user@relay.factory.ai/v0/computer/' + ID + '/client',
    'wss://relay.factory.ai/v0/computer/invalid/client',
  ])('rejects unsafe URLs: %s', (url) => {
    expect(factoryRelayTarget(url)).toBeNull();
  });
});

class MockSocket extends EventTarget {
  static CONNECTING = 0;
  static OPEN = 1;
  static CLOSING = 2;
  static CLOSED = 3;
  static sockets: MockSocket[] = [];
  readonly url: string;
  readyState = 0;
  bufferedAmount = 0;
  extensions = '';
  protocol = '';
  binaryType: WebSocket['binaryType'] = 'blob';
  sent: unknown[] = [];
  constructor(url: string | URL) {
    super();
    this.url = String(url);
    MockSocket.sockets.push(this);
  }
  send(data: unknown) { this.sent.push(data); }
  open() { this.readyState = 1; this.dispatchEvent(new Event('open')); }
  receive(data: string | Uint8Array) {
    this.dispatchEvent(new MessageEvent('message', {
      data: typeof data === 'string' ? data : data.buffer.slice(
        data.byteOffset, data.byteOffset + data.byteLength,
      ),
    }));
  }
  close(code = 1000, reason = '') {
    this.readyState = 3;
    this.dispatchEvent(new CloseEvent('close', { code, reason, wasClean: true }));
  }
}

const nativeWS = globalThis.WebSocket;
const nativeClose = globalThis.CloseEvent;
let release: (() => void) | null = null;
afterEach(() => {
  release?.();
  release = null;
  vi.unstubAllGlobals();
  vi.stubGlobal('WebSocket', nativeWS);
  vi.stubGlobal('CloseEvent', nativeClose);
  MockSocket.sockets = [];
});

function setup() {
  vi.stubGlobal('WebSocket', MockSocket as unknown as typeof WebSocket);
  vi.stubGlobal('CloseEvent', class TestCloseEvent extends Event {
    code: number;
    reason: string;
    wasClean: boolean;
    constructor(type: string, init: CloseEventInit = {}) {
      super(type);
      this.code = init.code ?? 1000;
      this.reason = init.reason ?? '';
      this.wasClean = init.wasClean ?? false;
    }
  });
  release = registerFactoryRelayCredential(CLIENT, 'fk-synthetic-test', IDENTITY);
  const socket = new WebSocket(CLIENT);
  return { socket, native: MockSocket.sockets[0] };
}

async function authenticate(native: MockSocket) {
  native.open();
  native.receive(JSON.stringify({ type: 'relay.auth_ok', clientId: CLIENT_ID }));
  await vi.waitFor(() => expect(native.sent.length).toBe(2));
  expect(native.sent[1]).toEqual(new Uint8Array([42]));
}

describe('Relay/Noise transport ordering and fail-closed policy', () => {
  it('sends relay authentication first and waits for pinned Noise before opening', async () => {
    const { socket, native } = setup();
    const onOpen = vi.fn();
    socket.addEventListener('open', onOpen);
    native.open();
    expect(native.sent).toEqual([JSON.stringify({
      method: 'relay.authenticate', apiKey: 'fk-synthetic-test',
    })]);
    expect(socket.readyState).toBe(WebSocket.CONNECTING);
    native.receive(JSON.stringify({ type: 'relay.auth_ok', clientId: CLIENT_ID }));
    await vi.waitFor(() => expect(native.sent.length).toBe(2));
    expect(onOpen).not.toHaveBeenCalled();
    native.receive(new Uint8Array([43]));
    await vi.waitFor(() => expect(onOpen).toHaveBeenCalledTimes(1));
    expect(socket.readyState).toBe(WebSocket.OPEN);
    socket.send(JSON.stringify({
      method: 'daemon.authenticate', params: { acceptEncodings: ['zstd'], apiKey: 'fk-synthetic-test' },
    }));
    const encrypted = native.sent[2] as Uint8Array;
    expect(JSON.parse(new TextDecoder().decode(encrypted))).toEqual({
      method: 'daemon.authenticate', params: { apiKey: 'fk-synthetic-test' },
    });
    const onMessage = vi.fn();
    const onError = vi.fn();
    socket.addEventListener('message', onMessage);
    socket.addEventListener('error', onError);
    native.receive(new TextEncoder().encode('{"result":"ok"}'));
    await vi.waitFor(() =>
      expect(onMessage.mock.calls.length + onError.mock.calls.length).toBeGreaterThan(0));
    expect(onError.mock.calls.map(([ev]) => ({
      type: ev.type, readyState: socket.readyState, nativeReadyState: native.readyState,
    }))).toEqual([]);
    expect(onMessage).toHaveBeenCalledTimes(1);
  });

  it('does not open on a missing relay client ID', async () => {
    const { socket, native } = setup();
    const opened = vi.fn();
    socket.addEventListener('open', opened);
    native.open();
    native.receive(JSON.stringify({ type: 'relay.auth_ok' }));
    await vi.waitFor(() => expect(native.readyState).toBe(WebSocket.CLOSED));
    expect(opened).not.toHaveBeenCalled();
  });

  it('never accepts unencrypted application frames', async () => {
    const { socket, native } = setup();
    await authenticate(native);
    native.receive(new Uint8Array([43]));
    await vi.waitFor(() => expect(socket.readyState).toBe(WebSocket.OPEN));
    const message = vi.fn();
    socket.addEventListener('message', message);
    native.receive('{"type":"notification","method":"daemon.session_notification"}');
    await vi.waitFor(() => expect(native.readyState).toBe(WebSocket.CLOSED));
    expect(message).not.toHaveBeenCalled();
  });

  it('rejects conflicting credentials for the same relay URL', () => {
    setup();
    expect(() => registerFactoryRelayCredential(CLIENT, 'fk-other', IDENTITY))
      .toThrow(/Conflicting/);
  });

  it('requires an authenticated computer identity before opening any socket', () => {
    vi.stubGlobal('WebSocket', MockSocket as unknown as typeof WebSocket);
    expect(() => registerFactoryRelayCredential(CLIENT, 'fk-key', null as never))
      .toThrow(/identity/i);
    expect(MockSocket.sockets).toHaveLength(0);
  });
});
