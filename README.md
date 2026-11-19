# SharpScreen

Use an Android tablet as a real second display for your Mac, over USB-C or Wi-Fi.

SharpScreen is a hard fork of [Side Screen](https://github.com/tranvuongquocdat/SideScreen)
by Tran Vuong Quoc Dat (MIT). It keeps everything Side Screen does (virtual display,
HiDPI, rotation, custom resolutions, up to 120 Hz, touch input, USB via `adb reverse`,
Wi-Fi pairing) and owns both ends of the stream, so the Mac host and the Android client
can change together.

## Download

Grab the Mac app (`SharpScreen-<version>.dmg`) and the Android app
(`SharpScreen-<version>.apk`) from [Releases](https://github.com/CodeCompose7/SharpScreen/releases).

- **Mac** (macOS 13+): open the DMG and drag SharpScreen to Applications. Builds are
  signed with a Developer ID but not notarized yet, so the first launch needs
  right-click > Open (or System Settings > Privacy & Security > Open Anyway). Grant
  Screen Recording when asked.
- **Android** (8+): enable "Install unknown apps" for your browser or file manager and
  open the APK.

### USB without ADB

Start streaming in USB mode and plug in the tablet. The Mac asks once whether to connect
that tablet without ADB; the tablet then switches into USB accessory mode (Android Open
Accessory), asks once whether SharpScreen should open, and connects by itself. No
developer options on the tablet and no `adb` on the Mac. The tablet returns to normal
when streaming stops or the cable comes out.

Tablets you keep on ADB still work as before (Developer options > USB debugging on the
tablet, `adb` on the Mac). With several tablets plugged in, one streams at a time; pick
it in the menu or in Settings > Connection.

## Goal

Pixel-sharp text without giving up smooth motion:

- **Moving content** (scrolling, video): hardware HEVC, as in Side Screen.
- **Still content**: changed regions re-sent losslessly (LZ4), so text and UI match the
  Mac pixel for pixel instead of carrying 4:2:0 video artifacts.

## Status

| Milestone | Scope | State |
|---|---|---|
| M0 | Fork, own identity (`io.sharpscreen.mac`, `io.sharpscreen.app`), baseline measurements | done ([measurements](docs/measurements.md)) |
| M1 | Lossless LZ4 tiles over HEVC on Mac and Android ([design](docs/design/M1-lossless-tiles.md)) | done in 0.2.0 |
| M2 | Tuning, separate tile capture, rotation support | planned |

## Build

Mac host (macOS 13+):

```bash
./scripts/build_mac.sh        # SharpScreen.app + DMG, ad-hoc signed
```

Android client (Android 8+, JDK 17+ and Android SDK 36):

```bash
cd AndroidClient
./gradlew installDebug        # installs io.sharpscreen.app.debug on the connected device
```

SharpScreen uses its own bundle id, package name, pairing scheme (`sharpscreen://`)
and log file (`/tmp/sharpscreen.log`), so it installs next to Side Screen. Both use
port 54321, so run one at a time.

## License

MIT. See [LICENSE](LICENSE); the original Side Screen copyright is kept.
