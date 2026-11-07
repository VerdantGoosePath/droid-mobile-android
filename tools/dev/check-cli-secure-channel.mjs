/**
 * Reproduce interoperability against preserved Droid CLI implementations.
 * Run with Node 24: node tools/dev/check-cli-secure-channel.mjs /path/to/droid
 * No CLI code, credentials, or real computer private keys are stored in git.
 */
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import process from 'node:process';
import { TextEncoder, TextDecoder } from 'node:util';
import sodium from 'libsodium-wrappers-sumo';
import { SecureRpcChannel } from '../../packages/daemon-client/src/secure-channel.ts';

const binary = readFileSync(process.argv[2]);
// Never evaluate source from an arbitrary executable. Only these reviewed builds.
const builds = {
  f33ec0fcd2ce2f0382fb709ba286f94a160707dea482e37ead48f42c4544fd6c:
    { version: 'Linux 1.5.0', start: 'var yA=2,DA=', end: 'var Md=H()', sodium: 'Z', responder: 's2H', channel: '$B' },
  '2ed55f1862f04d381cf89f75d39b12b720019e2d144cf8e4af255a520523f76e':
    { version: 'Windows CLI 1.4.1', start: 'var pe=2,Qe=', end: 'var od=H()', sodium: 'X', responder: 'a9H', channel: '_6' },
  '7b3bae10a6224084855e66e3c6074f8e90cdd5d3003a830c1f1805e5dcc59336':
    { version: 'Windows desktop 1.4.1', start: 'var pe=2,Qe=', end: 'var od=H()', sodium: 'X', responder: 'a9H', channel: '_6' },
};
const build = builds[createHash('sha256').update(binary).digest('hex')];
assert.ok(build, 'Unreviewed CLI binary: inspect it before adding its hash and symbol boundaries');
const source = binary.toString('utf8');
const start = source.indexOf(build.start);
const end = source.indexOf(build.end, start);
assert.ok(start >= 0 && end > start, 'Reviewed secure-channel implementation must be present');
await sodium.ready;
const keypair = (byte) => {
  const privateKey = new Uint8Array(32).fill(byte);
  return { privateKey, publicKey: sodium.crypto_scalarmult_base(privateKey) };
};
// Evaluate only the isolated cryptographic routines, with synthetic key material.
const cli = new Function(build.sodium, source.slice(start, end) +
  `;return { respond: ${build.responder}, Channel: ${build.channel} };`)({
  ...sodium, crypto_box_keypair: () => keypair(0x33),
});
const staticPair = keypair(0x11);
const keyId = sodium.to_hex(sodium.crypto_generichash(16, staticPair.publicKey, null));
const identity = {
  algorithm: 'X25519', keyId,
  publicKey: sodium.to_base64(staticPair.publicKey, sodium.base64_variants.ORIGINAL),
};
const context = {
  computerId: 'a34547ea-065c-43a9-857e-56e5a73a91f0',
  clientId: 'b34547ea-065c-43a9-857e-56e5a73a91f0',
};
const encoder = new TextEncoder();
const decoder = new TextDecoder();
const { channel: mobile, hello } = await SecureRpcChannel.begin(identity, context, keypair(0x22));
const result = await cli.respond({
  hello: JSON.parse(decoder.decode(hello)),
  identity: { ...staticPair, keyId },
  context: { ...context, transport: 'relay', purpose: 'rpc' },
});
// These constants also anchor the browser unit and relay tests.
assert.equal(sodium.to_hex(result.keys.receive),
  '8782383c1299dc8229e302ba30a64aeb7cf17b268cc10324a23fd9237e3acb0d');
assert.equal(sodium.to_hex(result.keys.transmit),
  'b18a706f4845eac27479727a138c9013cfe02bffec9bd1f3dfc74cb2bd049052');
mobile.finish(encoder.encode(JSON.stringify(result.hello)));
const daemon = await cli.Channel.create(result.keys);
try {
  // Subsequent records deliberately have no header; exercise the ongoing streams.
  for (const text of ['daemon.authenticate', 'daemon.list_available_sessions', 'Unicode: 🔐 café']) {
    const request = JSON.parse(decoder.decode(mobile.seal(encoder.encode(text))));
    assert.equal(decoder.decode(daemon.open(request).message), text);
    const reply = daemon.seal('reply: ' + text);
    assert.equal(decoder.decode(mobile.open(encoder.encode(JSON.stringify(reply)))), 'reply: ' + text);
  }
  const tampered = daemon.seal('tamper me');
  const bytes = sodium.from_base64(tampered.ciphertext);
  bytes[0] ^= 1;
  tampered.ciphertext = sodium.to_base64(bytes);
  assert.throws(() => mobile.open(encoder.encode(JSON.stringify(tampered))), /authentication failed/);
} finally {
  mobile.destroy();
  daemon.destroy();
}
process.stdout.write(`${build.version}: Noise, both derived keys, bidirectional multi-record streams and tamper rejection passed.\n`);
