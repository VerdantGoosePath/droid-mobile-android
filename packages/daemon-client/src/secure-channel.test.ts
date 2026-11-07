import { beforeAll, describe, expect, it } from 'vitest';
import sodium from 'libsodium-wrappers-sumo';
import {
  SecureRpcChannel, validatePinnedIdentity,
} from './secure-channel';
import type { PinnedComputerIdentity, SecureContext } from './secure-channel';

// Noise transcript originally computed with Python cryptography. Session keys
// corrected and verified against the actual preserved Linux and Windows CLI
// responders by tools/dev/check-cli-secure-channel.mjs (see docs/relay-protocol-audit.md).
// Client ephemeral scalar 0x22*32, server ephemeral 0x33*32,
// static server scalar 0x11*32. No value is an actual Factory credential.
const COMPUTER_ID = 'a34547ea-065c-43a9-857e-56e5a73a91f0';
const CLIENT_ID = 'b34547ea-065c-43a9-857e-56e5a73a91f0';
const identity: PinnedComputerIdentity = {
  algorithm: 'X25519',
  publicKey: 'e06Qm75//kTEZaIgA31gjuNYl9Me+XLwf3SJLLD3PxM=',
  keyId: 'f475e9e6f74465367aa2fe554c85f316',
};
const context: SecureContext = { computerId: COMPUTER_ID, clientId: CLIENT_ID };
const CLIENT_HELLO =
  'D6poTtKIZ7l_Smot7l34zpdOdrcBjj8iocTPJnhXDyBDE_uHcn6BliivPx-6NPzI';
const SERVER_HELLO =
  'ew1H2TQn-DERYHgcfHM_2J-IlwrvSQ2KoO4ZpMuKGxQttS66xpDu2iBqBnrCaDEq';
const CLIENT_TX_KEY = '8782383c1299dc8229e302ba30a64aeb7cf17b268cc10324a23fd9237e3acb0d';
const CLIENT_RX_KEY = 'b18a706f4845eac27479727a138c9013cfe02bffec9bd1f3dfc74cb2bd049052';
const encoder = new TextEncoder();
const decoder = new TextDecoder();
const asFrame = (record: object) => encoder.encode(JSON.stringify(record));

beforeAll(async () => { await sodium.ready; });

function pairForVector() {
  const privateKey = new Uint8Array(32).fill(0x22);
  return {
    privateKey, publicKey: sodium.crypto_scalarmult_base(privateKey),
  };
}

async function beginForVector() {
  return SecureRpcChannel.begin(identity, context, pairForVector());
}

function serverHello(message = SERVER_HELLO, version = 2) {
  return asFrame({ type: 'secure_channel_server_hello', version, message });
}

function encryptServerRecord(plaintext: Uint8Array) {
  const key = sodium.from_hex(CLIENT_RX_KEY);
  const { state, header } = sodium.crypto_secretstream_xchacha20poly1305_init_push(key);
  const ciphertext = sodium.crypto_secretstream_xchacha20poly1305_push(
    state, decoder.decode(plaintext), 'factory-secure-channel-v2',
    sodium.crypto_secretstream_xchacha20poly1305_TAG_MESSAGE,
  );
  return { header: sodium.to_base64(header), ciphertext: sodium.to_base64(ciphertext) };
}

describe('Factory secure-channel v2 independent vectors', () => {
  it('validates the computer identity by BLAKE2b-128 key fingerprint', async () => {
    const pub = await validatePinnedIdentity(identity);
    expect(pub.length).toBe(32);
    await expect(validatePinnedIdentity({ ...identity, keyId: 'a'.repeat(32) }))
      .rejects.toThrow(/does not match/);
    await expect(validatePinnedIdentity({ ...identity, publicKey: 'AA==' }))
      .rejects.toThrow(/32 bytes/);
  });

  it('produces the exact Noise NK hello from independent Python implementation', async () => {
    const { channel, hello } = await beginForVector();
    expect(JSON.parse(decoder.decode(hello))).toEqual({
      type: 'secure_channel_client_hello',
      version: 2,
      message: CLIENT_HELLO,
    });
    channel.destroy();
  });

  it('authenticates the independent server hello and derives correct transmit key', async () => {
    const { channel } = await beginForVector();
    channel.finish(serverHello());
    const plaintext = encoder.encode('sensitive daemon request');
    const sealed = JSON.parse(decoder.decode(channel.seal(plaintext))) as {
      header: string; ciphertext: string;
    };
    expect(JSON.stringify(sealed)).not.toContain('sensitive daemon request');
    const stream = sodium.crypto_secretstream_xchacha20poly1305_init_pull(
      sodium.from_base64(sealed.header),
      sodium.from_hex(CLIENT_TX_KEY),
    );
    const opened = sodium.crypto_secretstream_xchacha20poly1305_pull(
      stream, sodium.from_base64(sealed.ciphertext), 'factory-secure-channel-v2',
    );
    expect(opened && decoder.decode(opened.message)).toBe('sensitive daemon request');
    channel.destroy();
  });

  it('decrypts server records only under the independent receive key', async () => {
    const { channel } = await beginForVector();
    channel.finish(serverHello());
    const record = encryptServerRecord(encoder.encode('authenticated reply'));
    expect(decoder.decode(channel.open(asFrame(record)))).toBe('authenticated reply');
    channel.destroy();
  });

  it('rejects a wrong pinned static identity and corrupted Noise hello', async () => {
    const { channel } = await beginForVector();
    const mutated = SERVER_HELLO.slice(0, -2) + 'AA';
    expect(() => channel.finish(serverHello(mutated))).toThrow();
    expect(() => channel.seal(encoder.encode('should not send'))).toThrow();
  });

  it('rejects a protocol-version downgrade', async () => {
    const { channel } = await beginForVector();
    expect(() => channel.finish(serverHello(SERVER_HELLO, 1))).toThrow();
  });

  it('rejects plaintext frames, modified ciphertext, and replayed stream headers', async () => {
    const { channel } = await beginForVector();
    channel.finish(serverHello());
    expect(() => channel.open(encoder.encode('not JSON'))).toThrow();
    const { channel: next } = await beginForVector();
    next.finish(serverHello());
    const record = encryptServerRecord(encoder.encode('reply'));
    const decoded = sodium.from_base64(record.ciphertext);
    decoded[decoded.length - 1] ^= 1;
    expect(() => next.open(asFrame({ ...record, ciphertext: sodium.to_base64(decoded) }))).toThrow();
    const { channel: replay } = await beginForVector();
    replay.finish(serverHello());
    const valid = encryptServerRecord(encoder.encode('reply'));
    replay.open(asFrame(valid));
    expect(() => replay.open(asFrame(valid))).toThrow();
  });

  it('rejects an unauthenticated finalization record', async () => {
    const { channel } = await beginForVector();
    channel.finish(serverHello());
    const key = sodium.from_hex(CLIENT_RX_KEY);
    const { state, header } = sodium.crypto_secretstream_xchacha20poly1305_init_push(key);
    const ciphertext = sodium.crypto_secretstream_xchacha20poly1305_push(
      state, 'close', 'factory-secure-channel-v2',
      sodium.crypto_secretstream_xchacha20poly1305_TAG_FINAL,
    );
    expect(() => channel.open(asFrame({
      header: sodium.to_base64(header),
      ciphertext: sodium.to_base64(ciphertext),
    }))).toThrow();
  });
});
