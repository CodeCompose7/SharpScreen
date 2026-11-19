# Releasing SharpScreen

Releases use git flow and attach both apps to a GitHub Release.

## Signing keys

| App | Key | Where |
|---|---|---|
| Mac | Developer ID Application certificate | login keychain (`codesign` picks it by name) |
| Android | Release key, alias `sharpscreen`, PKCS12, RSA 4096, valid to 2056 | `~/.android/sharpscreen-release.jks`; password in the keychain item `sharpscreen-android-release` |
| Mac updates (Sparkle) | EdDSA (ed25519) key for update archives; public key `JtLmNcbx4yImlNjQ6NSbpR3kEpTgdhkh7hn4z2gm73Y=` is `SUPublicEDKey` in the app | login keychain (Sparkle's `generate_keys` item); exported copy `~/.sparkle/sharpscreen-sparkle-private-key` |
| Mac notarization | notarytool keychain profile `sharpscreen-notary` (Apple ID app-specific password, team YQV3R5Y48H) | login keychain |

Android release certificate: `CN=SharpScreen, O=SharpScreen`,
SHA-256 `a8:4c:88:ac:4e:fd:89:83:98:d3:10:7f:9b:68:a3:1c:fc:f3:fc:4e:e6:d1:b7:51:f4:18:30:d3:6a:87:c7:89`.

**Back up the Android keystore and its password together** (for example in a
password manager). Android only installs an update signed with the same key, so
losing either means every user has to uninstall and reinstall. The password can
be read back with:

```bash
security find-generic-password -s sharpscreen-android-release -w
```

The v0.2.0 Release first carried an APK signed with the Android debug key; at
09:37 UTC on 2026-11-18 it was replaced by the same build signed with the release
key. Anyone who installed the earlier upload has to uninstall it once before
installing the current 0.2.0 or any later version.

Back up the key alias (`sharpscreen`) with the keystore and password. The
certificate itself is inside the keystore and its SHA-256 is derived from it, so
neither needs a separate backup; the fingerprint above is for checking APKs.

**Back up the Sparkle private key** (`~/.sparkle/sharpscreen-sparkle-private-key`)
the same way. Installed Macs only accept updates signed with it; losing it
means shipping a new key in a release that users install by hand. Restore it on
another Mac with `MacHost/.build/artifacts/sparkle/Sparkle/bin/generate_keys -f <file>`.

## Steps

1. `git flow release start <version>` from `develop`.
2. Set `VERSION`, move the `[Unreleased]` notes in `CHANGELOG.md` under the new version, commit.
3. Build both apps (outputs land in `dist/<version>/`, which git ignores):

   ```bash
   SHARPSCREEN_SIGN_IDENTITY="Developer ID Application" ./scripts/build_mac.sh
   cp SharpScreen-<version>-mac-universal.dmg dist/<version>/SharpScreen-<version>.dmg
   ./scripts/build_android_release.sh
   ```

   `build_android_release.sh` stops if the APK is not signed with the release key.
   Then notarize the DMG, write the Sparkle appcast for it and the Android update
   manifest for the APK, and checksum:
   ```bash
   NOTARY_PROFILE=sharpscreen-notary ./scripts/notarize.sh dist/<version>/SharpScreen-<version>.dmg
   ./scripts/make_appcast.sh <version>        # dist/<version>/appcast.xml, signed with the Sparkle key
   ./scripts/make_android_update.sh <version> # dist/<version>/android-update.json: APK version, size, SHA-256
   (cd dist/<version> && shasum -a 256 SharpScreen-<version>.dmg SharpScreen-<version>.apk > SHA256SUMS.txt)
   ```
   Notarize before `make_appcast.sh`: stapling changes the DMG, and the appcast
   signs the exact file users download. Likewise run `make_android_update.sh` on the
   final APK: the tablet app installs an update only when its size and SHA-256 match
   `android-update.json`, and Android only when it is signed with the release key.
4. Install both on real hardware and check that a stream connects.
5. `git flow release finish <version> -T v<version> -m "SharpScreen <version>" --no-sign`
   (tags use a `v` prefix: Side Screen's history already has plain `0.x.y` tags).
6. Push and publish. This Mac's SSH key belongs to another GitHub account, so push
   over HTTPS with the gh login (needs the `workflow` scope):

   ```bash
   git -c credential.helper= -c 'credential.helper=!gh auth git-credential' push origin main develop refs/tags/v<version>
   gh release create v<version> dist/<version>/SharpScreen-<version>.dmg dist/<version>/SharpScreen-<version>.apk \
     dist/<version>/SHA256SUMS.txt dist/<version>/appcast.xml dist/<version>/android-update.json --repo CodeCompose7/SharpScreen --verify-tag \
     --title "SharpScreen <version>" --notes-file <notes>
   ```

7. For daily use rebuild the Mac app with the Apple Development identity, which
   keeps the Screen Recording permission across builds:

   ```bash
   SHARPSCREEN_SIGN_IDENTITY="Apple Development" ./scripts/build_mac.sh
   ```
