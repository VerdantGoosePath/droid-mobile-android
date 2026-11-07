# Release and signing

Droid Mobile is an unofficial client. Release builds must use the same signing key to support
in-place updates.

## Build with one command

```powershell
npm run android:release
```

It runs, in order: the `google-services.json` copy from `secrets/`, the one-time keystore
creation (skipped when it exists), the web build with `VITE_DROID_BUILD=release`, `cap sync` with
`CAPACITOR_VARIANT=release`, `gradlew assembleRelease bundleRelease` and finally
`tools/dev/verify-release.ps1`. No password is typed or passed on the command line.

Outputs (same release key):

| Artifact | Path                                                                   |
| -------- | ---------------------------------------------------------------------- |
| APK      | `apps/mobile/android/app/build/outputs/apk/release/app-release.apk`    |
| AAB      | `apps/mobile/android/app/build/outputs/bundle/release/app-release.aab` |

Prerequisites are the ones in [android.md](android.md) (JDK 21, Android SDK with build-tools)
plus Node 24 and `npm install`. Both version fields live in `apps/mobile/package.json`:
`version` becomes `versionName` and `androidVersionCode` becomes `versionCode`. Raise
`androidVersionCode` there before every Play Store upload; installing a build over an installed one
with `adb install -r` keeps the saved connections as long as the keystore and the `versionCode`
direction (equal or higher) allow it.

## WSL and GitHub Actions signing

The permanent release key for this checkout is stored outside the repository at
`/home/elliott/.local/share/droid-mobile-signing/release.jks`, with its credentials in
`/home/elliott/.local/share/droid-mobile-signing/keystore.properties`. The directory is private
(mode 700) and both files are private (mode 600). Back up both files securely outside this machine;
losing either the key or its credentials prevents updates signed with this identity. Never commit
or share these files or their passwords.

For a local WSL build, set `DROID_KEYSTORE_PROPERTIES` to the absolute path of that properties
file. The signed GitHub Actions job requires repository secrets `DROID_RELEASE_KEYSTORE_B64`
(the base64-encoded JKS), `DROID_RELEASE_STORE_PASSWORD`, `DROID_RELEASE_KEY_ALIAS`, and
`DROID_RELEASE_KEY_PASSWORD`. It fails if any are missing and removes its temporary signing files
at the end of the job. Pull requests do not run the signed job or receive its signing secrets.
Use the same key for every release intended to update an existing installation.

The four secrets are configured on `VerdantGoosePath/droid-mobile-android`.
The public certificate SHA-256 is
`88:2A:EC:87:DC:E1:63:2D:0D:11:D3:BE:60:1C:71:06:AF:01:F1:55:47:A6:FE:64:2B:5D:57:EC:6A:85:7A:DA`.

Build from the repository root in WSL with Node 24 and JDK 21 on `PATH`:

```sh
export ANDROID_HOME="$HOME/Android/Sdk"
export DROID_KEYSTORE_PROPERTIES="$HOME/.local/share/droid-mobile-signing/keystore.properties"
npm ci
VITE_DROID_BUILD=release npm run build -w @droidmobile/mobile
(cd apps/mobile && CAPACITOR_VARIANT=release npx cap sync android)
bash apps/mobile/android/gradlew -p apps/mobile/android --no-daemon assembleRelease
"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --verbose --print-certs \
  apps/mobile/android/app/build/outputs/apk/release/app-release.apk
```

This fork's version 1.0.2 (version code 3) starts the permanent signing identity.
Earlier temporary-key builds must be uninstalled once before installing it;
subsequent builds signed with this key can update in place.


## Keystore creation

`tools/dev/new-release-keystore.ps1` (run by `npm run android:release`, or alone with
`npm run android:keystore`) creates, once:

- `secrets/release.keystore`: PKCS12, RSA 4096, alias `droidmobile-release`, valid about 27 years.
- `secrets/keystore.properties`: `storeFile`, `storePassword`, `keyAlias`, `keyPassword` (a random
  password generated on your machine and passed to `keytool` through the environment).

`secrets/` is git-ignored, as are `*.keystore`, `*.jks`, `keystore.properties` and
`google-services.json`. The script never overwrites an existing keystore: replacing the key would
stop already installed releases from updating. **Back both files up** in a place you control. If
you lose them you cannot update an installed or published app. Set
`DROID_KEYSTORE_PROPERTIES` to use a properties file somewhere else. Gradle refuses to build a
release artifact when no signing configuration is found; it never falls back to the debug key.

For Play App Signing, upload the AAB; Google re-signs the app for users and your key becomes the
upload key ([play-store.md](play-store.md)).

## Firebase config

`build-android.ps1` copies `secrets/google-services.json` (or the path in
`GOOGLE_SERVICES_JSON_PATH` from `.env.local`) to `apps/mobile/android/app/` before every build.
The copy is git-ignored. Without it the build still works but push does not
([notifications.md](notifications.md)). `verify-release.ps1` checks that its `package_name` equals
the application id.

## apksigner verify

Run the whole check with `npm run android:verify-release`. It prints PASS/FAIL per check and
exits non-zero on any failure. Only booleans, counts and certificate fingerprints are printed.

- `apksigner verify` (v2 and v3, one signer, not the debug certificate) and `zipalign -c 4`.
- `jarsigner -verify` for the AAB and equality of the AAB and APK signer SHA-256.
- Application id `com.droidmobile.client`, min SDK 24, target SDK 36, label `Droid Mobile`.
- No `usesCleartextTraffic`, no `debuggable`, `allowBackup="false"`.
- `assets/capacitor.config.json`: `https` scheme, no `server.cleartext` or `server.url`, WebView
  debugging off.
- No file in the APK with `factory` in its name.
- No API key, `fk-` token, service-account key id or PEM private key in any entry of the APK or
  AAB.

To run the signature check by hand:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\build-tools\36.0.0\apksigner.bat" verify --verbose --print-certs apps\mobile\android\app\build\outputs\apk\release\app-release.apk
```

It prints `Verifies` and the signer's SHA-256 digest. Use any installed build-tools version.

## Install on a phone

Debug and release builds share the application id but not the signature, so uninstall first:

```powershell
adb -s <serial> uninstall com.droidmobile.client
adb -s <serial> install apps\mobile\android\app\build\outputs\apk\release\app-release.apk
```

## Release transport policy

Release builds only connect over `wss://`. A `ws://` address (typed or from a pairing code) is
refused with a message before any socket is opened. The convenient secure path to a desktop
daemon is Tailscale Serve ([setup.md](setup.md#tailscale-serve-and-https-certificates)). Debug
builds keep `ws://` for the emulator and `adb reverse`; see [android.md](android.md).

## Repository hygiene

`npm run scan:secrets` scans the tracked tree and every revision for API-key shaped tokens
(outside unit-test fixtures), service-account JSON, PEM private keys, the key stored in
`.env.local`, and signing or Firebase files. It prints counts only and exits non-zero on any
match. Run it before every commit that you intend to share.
