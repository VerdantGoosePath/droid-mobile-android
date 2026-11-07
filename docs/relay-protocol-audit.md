# Relay protocol audit and connection repair

The security feature lives on `feat/factory-relay-remote-control`. Its initial
implementation is `ae7e6a0`; the transport and per-dial pinning follow in
`792bcb0` and `2582eea`. Discovery succeeded because it uses HTTPS, while
connection failed after the Noise handshake when the first RPC was encrypted.

## Source of truth

Preserved unmodified binaries in WSL under
`~/.local/share/droid-mobile-analysis/binaries/`:

| File | Version | SHA-256 |
| --- | --- | --- |
| `droid-linux` | 1.5.0 | `f33ec0fcd2ce2f0382fb709ba286f94a160707dea482e37ead48f42c4544fd6c` |
| `droid-windows.exe` | 1.4.1 | `2ed55f1862f04d381cf89f75d39b12b720019e2d144cf8e4af255a520523f76e` |
| `droid-desktop-windows.exe` | 1.4.1 | `7b3bae10a6224084855e66e3c6074f8e90cdd5d3003a830c1f1805e5dcc59336` |

The Windows executables came from `~/bin/droid.exe` and the Factory desktop
app's `resources/bin/droid.exe` under the Windows user profile. The Linux copy
came from `~/.local/bin/droid`. No binary, private computer identity, or account
credential is committed. All three contain the same secure-channel construction.

## Complete connection sequence

1. **Host registration and publication.** The CLI signs in, registers a computer,
   loads or creates its persistent X25519 identity, and publishes its public key,
   algorithm and BLAKE2b-128 fingerprint through authenticated `update-computer`
   before starting remote access. The private key stays on the computer.
2. **Host relay connection.** The daemon opens its `/agent` WebSocket and sends
   `relay.authenticate` using its resolved API key or WorkOS token and active
   organization. The relay acknowledges authentication. The host keeps this
   outbound socket running; it does not expose an inbound LAN service.
3. **Client discovery and pinning.** The app lists computers over authenticated
   HTTPS. For a connection it fetches `/api/v0/computers/{computerId}` again,
   checks the ID and approved `/client` relay URL, decodes the 32-byte public
   key and verifies `keyId = hex(BLAKE2b-128(publicKey))`. An `/agent` URL pasted
   into the app is converted to `/client`; it never supplies trusted key material.
4. **Client relay authentication.** The client opens WSS and sends the text
   `relay.authenticate` frame with its API key. The `relay.auth_ok` reply must
   supply a valid `clientId`. The relay forwards subsequent binary frames to
   the host associated with that client ID.
5. **Pinned Noise NK handshake.** Initialize
   `Noise_NK_25519_ChaChaPoly_SHA256`, mix the UTF-8 JSON array
   `["factory-secure-channel-v2", noiseName, "relay", "rpc", computerId, clientId]`
   into the handshake hash, then mix the pinned host public key. The client
   sends its ephemeral public key and an authenticated empty payload after
   `es` DH. The host validates it with its static private key, sends its
   ephemeral key and another authenticated empty payload after `ee` DH. Both
   hello envelopes are binary UTF-8 JSON with type, version `2`, and a canonical
   48-byte message encoded as URL-safe base64 without padding. A forged host,
   wrong context or modified hello fails authentication.
6. **Directional record keys.** Noise Split produces `k1` and `k2`. For each
   key, compute `PRK = HMAC-SHA256(key=finalHandshakeHash, message=splitKey)`,
   then `HMAC-SHA256(PRK, UTF8("factory-secure-channel-v2/secretstream/" + direction)
   || 0x01)`. Directions are `initiator-to-responder` and
   `responder-to-initiator`; host and client swap transmit/receive roles.
   This is HKDF Extract followed by a one-block Expand, with the transcript
   hash as the salt, consistent with [RFC 5869](https://www.rfc-editor.org/rfc/rfc5869.html#section-2.2).
7. **Encrypted daemon authentication.** Only now does the adapter emit `open`
   to the SDK. The SDK sends `daemon.authenticate` with the API key, protocol
   envelope, SDK caller and metadata. The adapter removes `acceptEncodings`
   because its reader does not implement compressed daemon messages. Each
   direction uses an independent XChaCha20-Poly1305 secretstream with associated
   data `factory-secure-channel-v2`. The first binary JSON record carries the
   24-byte stream header; every record carries ciphertext. Both fields use
   URL-safe base64 without padding. The encrypted authentication reply contains
   the authorized user and organization, followed by connection status.
8. **RPC, disconnect and reconnect.** Requests, replies and notifications stay
   encrypted on the same streams, with no repeated header and advancing state.
   Tampering, replay, plaintext or malformed records fail closed. Teardown
   erases private ephemeral/session key material and native stream state.
   Reconnecting resolves the API pin again, obtains a new relay client ID,
   and performs a fresh handshake and fresh streams.

## Defect, plan and correction

`NoiseState.split()` used `HMAC(splitKey, handshakeHash)` for Extract. The CLI
uses `HMAC(handshakeHash, splitKey)`. The correct Noise transcript and hello
therefore masked incorrect record keys: the daemon rejected the first encrypted
`daemon.authenticate`. The old Python-derived key constants and loopback server
repeated the same error, so those tests passed without proving interoperability.

The repair keeps the existing pinning and transport policy, corrects this one
HMAC call, and replaces both test suites' directional key constants with values
verified by executing the cryptographic responder routines extracted from all
three preserved binaries. Before the implementation change, the corrected unit
vectors produced three failures; afterward they passed.

`tools/dev/check-cli-secure-channel.mjs` hash-checks a supplied reviewed binary,
isolates only its cryptographic routines, and runs them with synthetic keys.
It checks both derived keys, three consecutive records in each direction
(including Unicode), and tamper rejection. This prevents the app and a fake
server from merely agreeing on the same incorrect protocol. Run with Node 24:

```sh
node tools/dev/check-cli-secure-channel.mjs ~/.local/share/droid-mobile-analysis/binaries/droid-linux
```

The verifier also accepts the two preserved Windows builds. A changed binary
hash requires a new review of the source boundaries before execution.

## Validation and limits

- All three binary responder checks pass, alongside the Noise/relay regression tests.
- Typechecking and targeted lint pass. The broad unit run passed 1,832 tests;
  25 Windows PC-helper tests fail under WSL because Windows PowerShell receives
  Linux temporary paths. Those tests are unrelated to the relay repair.
- Live remote connections to the user's Linux and Windows computers completed
  authenticated API lookup, pinned Noise, encrypted daemon authentication and
  encrypted `daemon.list_available_sessions`, receiving multiple stream records.
- Live smoke tests used the existing CLI WorkOS account in a private local
  harness; no token was printed or committed. The Android product continues
  to use the user's API key. The API-key relay adapter is exercised by tests.
- Release 1.0.2 (version code 3) compiled and assembled successfully. APK
  signature verification passed with v2/v3 and the permanent RSA-3072 key;
  zip alignment passed, and packaged Capacitor settings enforce HTTPS with
  WebView debugging disabled. Permanent signing is documented in
  [release.md](release.md); CI no longer generates temporary signing keys.
- A physical phone connection smoke test remains outstanding; no device was
  attached to WSL during the repair. Host interoperability does not certify
  every Android WebView or constitute a general security audit.
