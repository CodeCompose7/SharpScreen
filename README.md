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
  signed with a Developer ID and notarized by Apple. Grant Screen Recording when
  asked.
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

### Smoother Wi-Fi

AirDrop, Handoff, Universal Control and iPhone Mirroring share the Mac's Wi-Fi through
AWDL, which takes the Mac off its channel about twice a second (~70-80 ms each); on
Wi-Fi the tablet then freezes briefly and catches up in a rush. The best fix is to set
your router's 5 GHz channel to **149** (44 in some countries), the channel AWDL uses:
the Mac then never has to leave it, and those features keep working. Measured here,
idle pings over 40 ms went from 7% to none and the longest display pause while
dragging from ~90 ms to ~35 ms. Otherwise turn those features off while streaming, or
run `sudo ifconfig awdl0 down` (macOS turns AWDL back on after a restart or when
AirDrop is used), connect the Mac by Ethernet, or use USB.

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
