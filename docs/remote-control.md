# Factory Remote Control connection (experimental fork)

This fork connects Droid Mobile to an existing `droid daemon --remote-control`
through Factory's hosted WebSocket relay. No Tailscale, reverse proxy,
LAN port forwarding, inbound firewall rule, or PC helper is needed.

> **Experimental:** this path is implemented from Factory's publicly documented
> Computers API and protocol observations of the distributed CLI binaries.
> It has not yet been verified against an authenticated live Factory account.
> The official client may negotiate an additional X25519 end-to-end secure
> channel that this branch **does not implement**. TLS protects traffic to the
> Factory relay, but does not provide equivalent end-to-end protections.
> Do not use for sensitive repositories until this is resolved and reviewed.

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
URL. This alternative does not require the computer-listing API.

The connection remains dependent on your PC staying online and keeping
`droid daemon --remote-control` running.

## Implementation

The connection has two sequential stages:

1. `relay.authenticate` with `apiKey`, expecting `relay.auth_ok`.
2. `daemon.authenticate` performed by `@factory/droid-sdk` only after the
   authenticated WebSocket is exposed as open.

Because the current public high-level `connectToDaemon()` facade does not
offer an injectable transport, the relay adapter registers a scoped WebSocket
constructor that delays the `open` event until relay authentication succeeds.
It restores the native constructor after connection credentials are released.

The Factory API exposes optional `computerIdentity` (X25519) alongside the
client URL. The current implementation detects and warns about the presence of
that identity but does not negotiate the extra secure channel. Implementing
and validating it is a release-blocking security task.

## Build and signing

`.github/workflows/android-apk.yml` builds a **release-variant** APK and
verifies its signature. The default CI key is **ephemeral**: install it as a
fresh app; future runs will have different signing certificates and require
uninstall/reinstall, which discards application data.

For stable upgrade-compatible APKs, configure these four GitHub Actions
repository secrets before rerunning the build:

- `DROID_RELEASE_KEYSTORE_B64`: base64-encoded persistent Android JKS file
- `DROID_RELEASE_STORE_PASSWORD`: keystore password
- `DROID_RELEASE_KEY_ALIAS`: signing key alias
- `DROID_RELEASE_KEY_PASSWORD`: key password

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
