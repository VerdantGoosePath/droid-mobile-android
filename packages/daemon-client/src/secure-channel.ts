/**
 * Factory secure-channel v2 wire protocol.
 *
 * Independent implementation of Noise_NK_25519_ChaChaPoly_SHA256, bound to
 * Factory's relay RPC context, followed by libsodium secretstream records.
 * The protocol was confirmed against the distributed Linux Droid CLI.
 *
 * Security invariants:
 * - Computer public key comes from authenticated Factory Computers API.
 * - The API's keyId must equal BLAKE2b-128(publicKey).
 * - Server hello is authenticated against that pinned identity.
 * - No plaintext fallback, including on malformed or unknown frames.
 * - Session keys and ephemeral private keys are zeroed on teardown.
 *
 * Cryptographic primitives are provided by libsodium, not implemented here.
 */
import sodium from 'libsodium-wrappers-sumo';

const CHANNEL_LABEL = 'factory-secure-channel-v2';
const NOISE_NAME = 'Noise_NK_25519_ChaChaPoly_SHA256';
const VERSION = 2;
const MAX_FRAME = 8 * 1024 * 1024;
const encoder = new TextEncoder();
const decoder = new TextDecoder('utf-8', { fatal: true });
const empty = new Uint8Array(0);
// libsodium accepts a UTF-8 string for associated data (as in Factory CLI).

export type PinnedComputerIdentity = {
  algorithm: 'X25519';
  publicKey: string; // Base64 encoded 32-byte X25519 key, from Factory API
  keyId: string; // BLAKE2b-128 fingerprint
};

export interface SecureContext {
  computerId: string;
  clientId: string;
}

function join(...parts: Uint8Array[]): Uint8Array {
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
  let i = 0;
  for (const part of parts) { out.set(part, i); i += part.length; }
  return out;
}

function sha256(bytes: Uint8Array): Uint8Array {
  return sodium.crypto_hash_sha256(bytes);
}

function hmac(key: Uint8Array, message: Uint8Array): Uint8Array {
  return sodium.crypto_auth_hmacsha256(message, key);
}

function hkdf(chainingKey: Uint8Array, material: Uint8Array): [Uint8Array, Uint8Array] {
  const prk = hmac(chainingKey, material);
  try {
    const first = hmac(prk, Uint8Array.of(1));
    const second = hmac(prk, join(first, Uint8Array.of(2)));
    return [first, second];
  } finally {
    sodium.memzero(prk);
  }
}

class NoiseState {
  chainingKey: Uint8Array;
  hash: Uint8Array;
  key: Uint8Array | null = null;
  nonce = 0;

  constructor(staticPublicKey: Uint8Array, ctx: SecureContext) {
    const name = encoder.encode(NOISE_NAME);
    const initial = name.length <= 32
      ? join(name, new Uint8Array(32 - name.length))
      : sha256(name);
    this.chainingKey = initial.slice();
    this.hash = initial;
    this.mixHash(encoder.encode(JSON.stringify([
      CHANNEL_LABEL, NOISE_NAME, 'relay', 'rpc', ctx.computerId, ctx.clientId,
    ])));
    this.mixHash(staticPublicKey);
  }

  mixHash(data: Uint8Array): void {
    const next = sha256(join(this.hash, data));
    sodium.memzero(this.hash);
    this.hash = next;
  }

  mixKey(material: Uint8Array): void {
    const [ck, k] = hkdf(this.chainingKey, material);
    sodium.memzero(this.chainingKey);
    if (this.key) sodium.memzero(this.key);
    this.chainingKey = ck;
    this.key = k;
    this.nonce = 0;
  }

  private nextNonce(): Uint8Array {
    if (!Number.isSafeInteger(this.nonce) || this.nonce < 0)
      throw new Error('Secure channel nonce exhausted');
    const bytes = new Uint8Array(12);
    const view = new DataView(bytes.buffer);
    view.setUint32(4, this.nonce % 0x100000000, true);
    view.setUint32(8, Math.floor(this.nonce / 0x100000000), true);
    this.nonce++;
    return bytes;
  }

  encryptAndHash(data: Uint8Array): Uint8Array {
    const ciphertext = this.key
      ? sodium.crypto_aead_chacha20poly1305_ietf_encrypt(
        data, this.hash, null, this.nextNonce(), this.key,
      )
      : data;
    this.mixHash(ciphertext);
    return ciphertext;
  }

  decryptAndHash(ciphertext: Uint8Array): Uint8Array {
    const plaintext = this.key
      ? sodium.crypto_aead_chacha20poly1305_ietf_decrypt(
        null, ciphertext, this.hash, this.nextNonce(), this.key,
      )
      : ciphertext;
    this.mixHash(ciphertext);
    return plaintext;
  }

  split(): { transmit: Uint8Array; receive: Uint8Array } {
    const [a, b] = hkdf(this.chainingKey, empty);
    const derive = (key: Uint8Array, direction: string) => {
      // Factory binds Split output with HKDF-Extract(salt=handshakeHash, IKM=key).
      // Reversing these HMAC arguments still completes Noise but breaks every record.
      const first = hmac(this.hash, key);
      try {
        return hmac(first, join(encoder.encode(CHANNEL_LABEL + '/secretstream/' + direction), Uint8Array.of(1)));
      } finally {
        sodium.memzero(first);
      }
    };
    try {
      return {
        transmit: derive(a, 'initiator-to-responder'),
        receive: derive(b, 'responder-to-initiator'),
      };
    } finally {
      sodium.memzero(a);
      sodium.memzero(b);
    }
  }

  destroy(): void {
    sodium.memzero(this.chainingKey);
    sodium.memzero(this.hash);
    if (this.key) sodium.memzero(this.key);
    this.key = null;
  }
}

function parseBinaryJson(bytes: Uint8Array): Record<string, unknown> {
  if (!bytes.length || bytes.length > MAX_FRAME) throw new Error('Invalid secure frame length');
  const value: unknown = JSON.parse(decoder.decode(bytes));
  if (value === null || Array.isArray(value) || typeof value !== 'object')
    throw new Error('Invalid secure frame JSON');
  return value as Record<string, unknown>;
}

function parseHello(bytes: Uint8Array): Uint8Array {
  const parsed = parseBinaryJson(bytes);
  if (
    Object.keys(parsed).sort().join(',') !== 'message,type,version' ||
    parsed.type !== 'secure_channel_server_hello' ||
    parsed.version !== VERSION ||
    typeof parsed.message !== 'string' ||
    !/^[A-Za-z0-9_-]{64}$/.test(parsed.message)
  ) throw new Error('Invalid secure-channel server hello');
  const decoded = sodium.from_base64(parsed.message, sodium.base64_variants.URLSAFE_NO_PADDING);
  if (decoded.length !== 48 ||
      sodium.to_base64(decoded, sodium.base64_variants.URLSAFE_NO_PADDING) !== parsed.message)
    throw new Error('Non-canonical Noise server hello');
  return decoded;
}

/** Never trust an identity supplied by the relay or from a WebSocket frame. */
export async function validatePinnedIdentity(identity: PinnedComputerIdentity): Promise<Uint8Array> {
  await sodium.ready;
  if (!identity || identity.algorithm !== 'X25519' ||
      typeof identity.publicKey !== 'string' || typeof identity.keyId !== 'string')
    throw new Error('Factory computer does not expose a pinned X25519 identity');
  let publicKey: Uint8Array;
  try { publicKey = sodium.from_base64(identity.publicKey, sodium.base64_variants.ORIGINAL); }
  catch { throw new Error('Computer identity has invalid base64'); }
  if (publicKey.length !== 32)
    throw new Error('Computer identity must be 32 bytes');
  const keyId = sodium.to_hex(sodium.crypto_generichash(16, publicKey, null));
  if (identity.keyId !== keyId)
    throw new Error('Computer identity key ID does not match its public key');
  return publicKey;
}

/** The CLI's wrappers return a native WASM pointer, not a JS byte array. */
function releaseSecretstreamState(state: unknown): void {
  if (state === null || state === undefined) return;
  if (state instanceof Uint8Array) {
    sodium.memzero(state);
    return;
  }
  if (typeof state !== 'number' || !Number.isSafeInteger(state) || state <= 0)
    throw new Error('Invalid native secretstream state');
  const raw = (sodium as unknown as {
    libsodium?: {
      HEAPU8: Uint8Array;
      _free(ptr: number): void;
      _crypto_secretstream_xchacha20poly1305_statebytes(): number;
    };
  }).libsodium;
  if (!raw || !(raw.HEAPU8 instanceof Uint8Array) ||
      typeof raw._free !== 'function' ||
      typeof raw._crypto_secretstream_xchacha20poly1305_statebytes !== 'function')
    throw new Error('Native secretstream state cannot be securely erased');
  const size = raw._crypto_secretstream_xchacha20poly1305_statebytes();
  if (size <= 0 || state + size > raw.HEAPU8.length)
    throw new Error('Invalid native secretstream state bounds');
  sodium.memzero(raw.HEAPU8.subarray(state, state + size));
  raw._free(state);
}

export class SecureRpcChannel {
  private readonly sym: NoiseState;
  private ephemeralPrivate: Uint8Array | null;
  private txKey: Uint8Array | null = null;
  private rxKey: Uint8Array | null = null;
  private txState: Parameters<typeof sodium.crypto_secretstream_xchacha20poly1305_push>[0] | null = null;
  private rxState: Parameters<typeof sodium.crypto_secretstream_xchacha20poly1305_pull>[0] | null = null;
  private transmitHeader: Uint8Array | null = null;
  private ready = false;
  private disposed = false;

  private constructor(sym: NoiseState, privateKey: Uint8Array) {
    this.sym = sym;
    this.ephemeralPrivate = privateKey;
  }

  /**
   * The optional pair exists exclusively for deterministic independent test
   * vectors. Production always uses libsodium's cryptographic RNG.
   */
  static async begin(
    identity: PinnedComputerIdentity,
    context: SecureContext,
    pairForTest?: { publicKey: Uint8Array; privateKey: Uint8Array },
  ): Promise<{ channel: SecureRpcChannel; hello: Uint8Array }> {
    const pinned = await validatePinnedIdentity(identity);
    if (!/^[0-9a-f-]{36}$/i.test(context.computerId) ||
        !/^[0-9a-f-]{36}$/i.test(context.clientId))
      throw new Error('Invalid secure-channel relay identifiers');
    const pair = pairForTest ?? sodium.crypto_box_keypair();
    if (pair.publicKey.length !== 32 || pair.privateKey.length !== 32)
      throw new Error('Invalid ephemeral keypair');
    const sym = new NoiseState(pinned, context);
    const privateKey = pair.privateKey.slice();
    if (!pairForTest) sodium.memzero(pair.privateKey);
    try {
      sym.mixHash(pair.publicKey);
      const shared = sodium.crypto_scalarmult(privateKey, pinned);
      try { sym.mixKey(shared); } finally { sodium.memzero(shared); }
      const helloMessage = join(pair.publicKey, sym.encryptAndHash(empty));
      const hello = encoder.encode(JSON.stringify({
        type: 'secure_channel_client_hello',
        version: VERSION,
        message: sodium.to_base64(helloMessage, sodium.base64_variants.URLSAFE_NO_PADDING),
      }));
      return { channel: new SecureRpcChannel(sym, privateKey), hello };
    } catch (error) {
      sodium.memzero(privateKey);
      sym.destroy();
      throw error;
    }
  }

  /** Verifies the server hello against the pinned static identity. */
  finish(serverHello: Uint8Array): void {
    if (this.disposed || this.ready || !this.ephemeralPrivate)
      throw new Error('Secure channel is not awaiting a server hello');
    try {
      const hello = parseHello(serverHello);
      const serverEphemeral = hello.subarray(0, 32);
      this.sym.mixHash(serverEphemeral);
      const shared = sodium.crypto_scalarmult(this.ephemeralPrivate, serverEphemeral);
      try { this.sym.mixKey(shared); } finally { sodium.memzero(shared); }
      const plaintext = this.sym.decryptAndHash(hello.subarray(32));
      if (plaintext.length !== 0) throw new Error('Unexpected Noise handshake payload');
      const keys = this.sym.split();
      this.txKey = keys.transmit;
      this.rxKey = keys.receive;
      this.ready = true;
    } catch {
      this.destroy();
      throw new Error('Secure-channel handshake rejected');
    } finally {
      if (this.ephemeralPrivate) sodium.memzero(this.ephemeralPrivate);
      this.ephemeralPrivate = null;
      this.sym.destroy();
    }
  }

  /** All application frames are encrypted binary JSON envelopes. */
  seal(plaintext: Uint8Array): Uint8Array {
    if (!this.ready || this.disposed || !this.txKey)
      throw new Error('Secure channel is not established');
    if (plaintext.length > MAX_FRAME) throw new Error('Secure frame too large');
    try {
      if (!this.txState) {
        const init = sodium.crypto_secretstream_xchacha20poly1305_init_push(this.txKey);
        this.txState = init.state;
        this.transmitHeader = init.header;
        sodium.memzero(this.txKey);
      }
      const ciphertext = sodium.crypto_secretstream_xchacha20poly1305_push(
        this.txState, decoder.decode(plaintext), CHANNEL_LABEL, sodium.crypto_secretstream_xchacha20poly1305_TAG_MESSAGE,
      );
      const record = {
        ...(this.transmitHeader ? {
          header: sodium.to_base64(this.transmitHeader),
        } : {}),
        ciphertext: sodium.to_base64(ciphertext),
      };
      this.transmitHeader = null;
      return encoder.encode(JSON.stringify(record));
    } catch {
      this.destroy();
      throw new Error('Unable to encrypt secure-channel record');
    }
  }

  /** Rejects unauthenticated, malformed, oversized and finalized records. */
  open(binaryFrame: Uint8Array): Uint8Array {
    if (!this.ready || this.disposed || !this.rxKey)
      throw new Error('Secure channel is not established');
    try {
      const record = parseBinaryJson(binaryFrame);
      if (Object.keys(record).some((key) => key !== 'header' && key !== 'ciphertext') ||
          typeof record.ciphertext !== 'string' ||
          typeof record.header !== 'undefined' && typeof record.header !== 'string')
        throw new Error('Invalid encrypted record fields');
      if (!this.rxState) {
        if (typeof record.header !== 'string' || !/^[A-Za-z0-9_-]{32}$/.test(record.header))
          throw new Error('First encrypted record needs a stream header');
        const header = sodium.from_base64(record.header, sodium.base64_variants.URLSAFE_NO_PADDING);
        if (header.length !== sodium.crypto_secretstream_xchacha20poly1305_HEADERBYTES ||
            sodium.to_base64(header, sodium.base64_variants.URLSAFE_NO_PADDING) !== record.header)
          throw new Error('Invalid or non-canonical encrypted stream header');
        this.rxState = sodium.crypto_secretstream_xchacha20poly1305_init_pull(header, this.rxKey);
        sodium.memzero(this.rxKey);
      } else if (record.header !== undefined) {
        throw new Error('Duplicate encrypted stream header');
      }
      const ciphertext = sodium.from_base64(record.ciphertext, sodium.base64_variants.URLSAFE_NO_PADDING);
      if (ciphertext.length < sodium.crypto_secretstream_xchacha20poly1305_ABYTES)
        throw new Error('Encrypted record too small');
      const plaintext = sodium.crypto_secretstream_xchacha20poly1305_pull(this.rxState, ciphertext, CHANNEL_LABEL);
      if (!plaintext || plaintext.tag === sodium.crypto_secretstream_xchacha20poly1305_TAG_FINAL)
        throw new Error('Encrypted record rejected or stream finalized');
      return plaintext.message;
    } catch (error) {
      try { this.destroy(); } catch { /* always report the authentication failure */ }
      throw new Error('Secure-channel record authentication failed', { cause: error });
    }
  }

  destroy(): void {
    if (this.disposed) return;
    this.disposed = true;
    this.ready = false;
    if (this.ephemeralPrivate) sodium.memzero(this.ephemeralPrivate);
    if (this.txKey) sodium.memzero(this.txKey);
    if (this.rxKey) sodium.memzero(this.rxKey);
    this.ephemeralPrivate = this.txKey = this.rxKey = null;
    this.transmitHeader = null;
    // The CLI's native secretstream state is a WASM pointer: erase before free.
    const receiveState = this.rxState;
    const transmitState = this.txState;
    this.txState = this.rxState = null;
    try {
      releaseSecretstreamState(receiveState);
    } finally {
      releaseSecretstreamState(transmitState);
    }
    this.sym.destroy();
  }
}
