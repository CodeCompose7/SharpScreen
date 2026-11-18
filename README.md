# SharpScreen

Use an Android tablet as a real second display for your Mac, over USB-C or Wi-Fi.

SharpScreen is a hard fork of [Side Screen](https://github.com/tranvuongquocdat/SideScreen)
by Tran Vuong Quoc Dat (MIT). It keeps everything Side Screen does (virtual display,
HiDPI, rotation, custom resolutions, up to 120 Hz, touch input, USB via `adb reverse`,
Wi-Fi pairing) and owns both ends of the stream, so the Mac host and the Android client
can change together.

## Goal

Pixel-sharp text without giving up smooth motion:

- **Moving content** (scrolling, video): hardware HEVC, as in Side Screen.
- **Still content**: changed regions re-sent losslessly (LZ4), so text and UI match the
  Mac pixel for pixel instead of carrying 4:2:0 video artifacts.

## Status

| Milestone | Scope | State |
|---|---|---|
| M0 | Fork, own identity (`io.sharpscreen.mac`, `io.sharpscreen.app`), baseline measurements | in progress |
| M1 | Lossless LZ4 region updates on Mac and Android | planned |
| M2 | Automatic HEVC / lossless switching | planned |

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
