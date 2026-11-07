# Factory Remote Control connection (experimental fork)

This fork connects Droid Mobile to an existing `droid daemon --remote-control`
through Factory's hosted WebSocket relay. No Tailscale, reverse proxy,
LAN port forwarding, inbound firewall rule, or PC helper is needed.

> **Experimental:** the secure channel has now been checked against preserved
> Linux and Windows CLI responder implementations and live remote connections
> to both operating systems. See the [protocol audit](relay-protocol-audit.md)
> for the key-derivation repair, full pairing sequence and validation limits.
> A physical Android phone connection smoke test is still outstanding.

## On the PC

1. Install Droid CLI and sign in interactively (`droid`, then `/login`).
2. Run `droid computer register my-pc` if your machine is not registered.
3. Run `droid daemon --remote-control` and leave it running.
4. Optionally run `droid computer list` to verify registration.

The daemon prints a URL ending in `/agent`. That URL is **for the PC**, not the
Android app. Factory's API returns a separate `relayClientUrl` for clients.

## On Android

1. Install the signed APK attached to the **Android APK (signed)** GitHub Actions
   workflow run for this branch. APKs are inside a downloadable artifact ZIP.
2. Open Droid Mobile and enter a Factory API key (`fk-…`). This is **not**
   the device-code login used by the Droid CLI; the app does not reuse CLI tokens.
3. Tap **Find registered computers**. The Android app calls the documented
   `GET https://api.factory.ai/api/v0/computers` using the supplied key.
4. Tap the desired computer. The app uses its `relayClientUrl` and opens a
   relay WebSocket, then authenticates the daemon on the same connection.

Alternatively, paste the `wss://relay.factory.ai/v0/computer/…/agent` address
shown by the CLI directly into the URL field. The app recognises the Factory
relay host and converts `/agent` to `/client`. It never puts the API key in the
URL. This alternative skips the list UI but still requires the authenticated
computer-detail API for cryptographic identity verification.

The connection remains dependent on your PC staying online and keeping
`droid daemon --remote-control` running.

## Implementation

The connection has **four sequential stages**:

1. Resolve `relayClientUrl` and its `computerIdentity` over authenticated
   HTTPS with `GET /api/v0/computers/{computerId}`, validating the relay
   host, computer ID, X25519 public key and BLAKE2b-128 `keyId`.
2. Establish `wss://relay.factory.ai/…/client`, send `relay.authenticate`
   with the API key and require a `relay.auth_ok` **with `clientId`**.
3. Perform Factory secure-channel version 2: bind the Noise NK
   (Noise_NK_25519_ChaChaPoly_SHA256) transcript to both the computer
   and relay-assigned client ID, verify the computer identity, derive
   directional keys and initialize XChaCha20-Poly1305 secretstream.
4. Expose the WebSocket as open to `@factory/droid-sdk` only after
   the secure channel authenticates the PC. `daemon.authenticate` and all
   subsequent JSON-RPC messages travel inside encrypted binary records.

The client rejects plaintext downgrade attempts and invalid/unknown secure
records. It **does not** silently retry using an unencrypted relay. The
high-level `connectToDaemon()` facade does not expose a compatible
transport injection point, so the scoped WebSocket adapter intercepts
connections to the exact verified relay URL and restores the native
constructor on teardown.

**Important:** A pasted `/agent` URL is only a computer identifier and
address. The app **still calls the Factory Computers API** to independently
retrieve its pinned identity before connecting. If this lookup fails or the
identity is absent/mismatched, the connection is refused.

## Build and signing

`.github/workflows/android-apk.yml` builds a **release-variant** APK and
verifies its signature using the permanent repository signing key. The signed
job runs only on trusted push/manual events and requires all four signing secrets:

- `DROID_RELEASE_KEYSTORE_B64`: base64-encoded persistent Android JKS file
- `DROID_RELEASE_STORE_PASSWORD`: keystore password
- `DROID_RELEASE_KEY_ALIAS`: signing key alias
- `DROID_RELEASE_KEY_PASSWORD`: key password

These secrets are configured for this fork. Missing signing configuration fails
the build; there is no temporary-key fallback. See [release.md](release.md) for
the WSL signing location and local build instructions.

Store the actual signing key in a private password manager or secure backup,
**never** in the repository. Reuse the same JKS on every release.

The prebuilt APK has no working third-party Firebase push configuration unless
you supply your own `google-services.json` while building. Local notifications
and the foreground connection service are separate features.

## References

- [Factory Remote Control](https://docs.factory.com/remote-control)
- [Factory Computers REST API](https://docs.factory.com/api-reference/computers)
- [Factory TypeScript SDK](https://github.com/Factory-AI/droid-sdk-typescript)
- [Commit policy](commit-policy.md)
