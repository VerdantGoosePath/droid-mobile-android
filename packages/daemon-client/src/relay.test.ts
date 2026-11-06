import { afterEach, describe, expect, it, vi } from 'vitest';
import { factoryRelayTarget, registerFactoryRelayCredential } from './relay';

const ID = 'a34547ea-065c-43a9-857e-56e5a73a91f0';
const AGENT = `wss://relay.factory.ai/v0/computer/${ID}/agent`;
const CLIENT = `wss://relay.factory.ai/v0/computer/${ID}/client`;

describe('Factory relay URL validation', () => {
  it('maps the printed agent URL to the client endpoint', () => {
    expect(factoryRelayTarget(AGENT)).toEqual({ url: CLIENT, computerId: ID });
    expect(factoryRelayTarget(CLIENT)?.url).toBe(CLIENT);
    expect(factoryRelayTarget(AGENT.replace('relay.factory.ai', 'relay.eu.factory.ai'))?.url)
      .toBe(CLIENT.replace('relay.factory.ai', 'relay.eu.factory.ai'));
  });
  it.each([
    'ws://relay.factory.ai/v0/computer/' + ID + '/client',
    'wss://evil.test/v0/computer/' + ID + '/client',
    'wss://relay.factory.ai.evil.test/v0/computer/' + ID + '/client',
    'wss://relay.factory.ai/v0/computer/' + ID + '/client?token=secret',
    'wss://relay.factory.ai/v0/computer/' + ID + '/client#secret',
    'wss://user@relay.factory.ai/v0/computer/' + ID + '/client',
    'wss://relay.factory.ai/v0/computer/not-a-uuid/client',
    'wss://relay.factory.ai/v0/computer/' + ID + '/tunnel',
  ])('rejects unsupported or unsafe relay endpoints: %s', (url) => {
    expect(factoryRelayTarget(url)).toBeNull();
  });
});

class MockSocket extends EventTarget {
  static readonly CONNECTING = 0;
  static readonly OPEN = 1;
  static readonly CLOSING = 2;
  static readonly CLOSED = 3;
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
  send(data: unknown): void { this.sent.push(data); }
  open(): void {
    this.readyState = 1;
    this.dispatchEvent(new Event('open'));
  }
  receive(data: string): void {
    this.dispatchEvent(new MessageEvent('message', { data }));
  }
  close(code = 1000, reason = ''): void {
    this.readyState = 3;
    this.dispatchEvent(new CloseEvent('close', { code, reason, wasClean: true }));
  }
}

const original = globalThis.WebSocket;
const originalCloseEvent = globalThis.CloseEvent;
let cleanup: (() => void) | null = null;

afterEach(() => {
  cleanup?.();
  cleanup = null;
  vi.stubGlobal('WebSocket', original);
  vi.stubGlobal('CloseEvent', originalCloseEvent);
  MockSocket.sockets = [];
});

describe('Factory relay authentication transport', () => {
  function setup(): { socket: WebSocket; native: MockSocket } {
    vi.stubGlobal('WebSocket', MockSocket as unknown as typeof WebSocket);
    vi.stubGlobal(
      'CloseEvent',
      class TestCloseEvent extends Event {
        readonly code: number;
        readonly reason: string;
        readonly wasClean: boolean;
        constructor(type: string, init: CloseEventInit = {}) {
          super(type);
          this.code = init.code ?? 1000;
          this.reason = init.reason ?? '';
          this.wasClean = init.wasClean ?? true;
        }
      },
    );
    cleanup = registerFactoryRelayCredential(CLIENT, 'fk-test123456');
    const socket = new WebSocket(CLIENT);
    return { socket, native: MockSocket.sockets[0] };
  }

  it('authenticates with relay before exposing the socket as open to the SDK', () => {
    const { socket, native } = setup();
    const open = vi.fn();
    const message = vi.fn();
    socket.addEventListener('open', open);
    socket.addEventListener('message', message);
    native.open();
    expect(native.sent).toEqual([JSON.stringify({ method: 'relay.authenticate', apiKey: 'fk-test123456' })]);
    expect(socket.readyState).toBe(WebSocket.CONNECTING);
    expect(open).not.toHaveBeenCalled();

    native.receive(JSON.stringify({ type: 'relay.auth_ok', clientId: ID }));
    expect(open).toHaveBeenCalledTimes(1);
    expect(socket.readyState).toBe(WebSocket.OPEN);
    socket.send('daemon.authenticate');
    expect(native.sent[1]).toBe('daemon.authenticate');

    native.receive(JSON.stringify({ type: 'response', id: '1', result: { ok: true } }));
    expect(message).toHaveBeenCalledTimes(1);
  });

  it('rejects relay authentication errors and never reports a successful open', () => {
    const { socket, native } = setup();
    const open = vi.fn();
    const error = vi.fn();
    socket.addEventListener('open', open);
    socket.addEventListener('error', error);
    native.open();
    native.receive(JSON.stringify({ type: 'relay.auth_error', code: 'unauthorized', message: 'denied' }));
    expect(open).not.toHaveBeenCalled();
    expect(error).toHaveBeenCalled();
    expect(socket.readyState).toBe(WebSocket.CLOSED);
  });

  it('restores native WebSocket for ordinary connections after cleanup', () => {
    const { native } = setup();
    expect(native.url).toBe(CLIENT);
    cleanup?.();
    cleanup = null;
    expect(globalThis.WebSocket).toBe(MockSocket);
    const direct = new WebSocket('wss://example.org');
    expect(direct).toBeInstanceOf(MockSocket);
  });
});
