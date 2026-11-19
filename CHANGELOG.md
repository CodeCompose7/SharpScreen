# Changelog

All notable changes to SharpScreen are documented here.
Side Screen's history up to the fork is in the git log and in
[its changelog](https://github.com/tranvuongquocdat/SideScreen/blob/main/CHANGELOG.md).

## [Unreleased]

## [0.4.1] - 2026-11-19 — The USB check means a computer

### Fixed
- **The tablet's USB check turns green only for a computer.** "USB cable
  connected" (now "Connected to a computer by USB") also went green on a wall
  charger or with a USB device plugged into the tablet; it now needs a USB data
  link to a host.

## [0.4.0] - 2026-11-19 — USB without ADB

Install this version on the tablet once more by hand or through the update card;
from then on plugging the tablet into a Mac that is streaming in USB mode is
enough.

### Added
- **USB without ADB** (#3). In USB mode the Mac switches an approved tablet into
  USB accessory mode (Android Open Accessory): no developer options or USB
  debugging on the tablet, and no `adb` on the Mac. The Mac asks once per
  tablet; the tablet asks once whether SharpScreen should open, then connects by
  itself and returns to normal when streaming stops or the cable comes out.
  Measured on a Tab S11 Ultra: 120 fps, the same frame age as over adb, a full
  lossless refill in ~290 ms. Tablets left on ADB connect as before.
- **One tablet at a time over USB.** With several tablets plugged in, only the
  one in use gets a connection (accessory link or adb reverse); pick it in the
  menu or in Settings ▸ Connection ▸ USB Devices. Before, a second tablet's app
  could cut the first one's stream every two seconds.
- **Each tablet's USB connection method** in Settings ▸ Connection ▸ USB
  Devices: Without ADB (USB accessory), ADB, or Ask Again. While a tablet uses
  the accessory link, the ADB reverse row reads "Not needed".

## [0.3.3] - 2026-11-19 — The display is called SharpScreen

### Fixed
- **The tablet shows up as "SharpScreen" in System Settings ▸ Displays**
  (it still said "SideScreen") (#5).

## [0.3.2] - 2026-11-19 — Both apps update themselves

Install this version by hand once; from here on the Mac and tablet apps offer new
versions themselves.

### Added
- **The tablet app updates itself** from GitHub Releases. Each release carries
  `android-update.json` (version, APK size and SHA-256); the app checks it once a
  day on the home screen (never while streaming; Settings ▸ About ▸ Check
  automatically turns it off), shows a card when a new version is out, and on
  Update downloads the APK, checks its size and SHA-256 and installs it. Android
  asks once to allow SharpScreen to install apps. Settings ▸ About ▸ Check for
  updates checks right away. Versions up to 0.3.1 cannot update themselves, so the
  first release with this is installed by hand once.

### Fixed
- **Check for Updates was always disabled** on the Mac (#4). Sparkle, inherited
  from Side Screen, had no SharpScreen feed and was switched off. It now reads
  SharpScreen's own appcast, attached to each GitHub Release and signed with a
  SharpScreen EdDSA key: the Mac app checks once a day and can download, verify
  and install a new version itself. Versions up to 0.3.1 have no feed, so the
  first release with it is installed by hand once.

## [0.3.1] - 2026-11-19 — Eight languages, notarized

The Mac app is notarized by Apple from this release on.

### Added
- **Six more languages** in both apps: Japanese, German, French, Russian,
  Simplified Chinese and Spanish, alongside English and Korean (#2). The Mac app
  has its own language setting (General ▸ Language: System or any of the eight,
  applied on restart); the tablet app's Settings lists all eight.

### Fixed
- The tablet app's pairing steps and hints named Mac screens that changed in the
  redesign (a Wireless tab, "Listening", "Reset Token"); they now match the Mac
  app in each language.

## [0.3.0] - 2026-11-19 — Korean, a redesign, steadier frames

Includes everything in 0.3.0-beta.1 below. Measurements are in
[docs/measurements.md](docs/measurements.md).

### Added
- **Korean and English.** Both apps are fully translated. The Mac app follows
  the macOS language (per-app language works too); the tablet app has its own
  language setting (System default, English, 한국어).
- **Redesigned apps.** The Mac settings window has tabs (Connection, Display,
  Streaming, General, About) and a status card with Start/Stop. The tablet app
  has a darker look in the icon's colours, a pill USB/Wireless switch, a
  checklist that says what to do, and a Settings screen (language, USB host and
  port, which are now saved, and About).
- **Menu bar popup.** The Mac menu bar item opens a small panel (status with a
  start/stop switch, USB/Wireless, Settings, Quit) right under the menu bar, and
  stays selected while it is open (macOS 27's expanded-interface session). Its
  icon is the app icon's two panes in one colour: solid while a tablet is
  connected, faint otherwise.
- The Mac's wireless settings warn when AWDL (AirDrop, Handoff, Universal
  Control) is on: it took the Mac's Wi-Fi off-channel twice a second, ~80 ms
  each time (ping p99 72 ms -> 8 ms with it off).
- The tablet app shows its version beside the name, and its in-app logo is the
  new icon.

### Changed
- **Wireless latency.** Vsync-aligned presentation no longer spaces a Wi-Fi
  burst out one frame per vsync (extra wait 42 ms -> 7 ms); the tablet holds a
  Wi-Fi low-latency lock while streaming wirelessly; lossless tiles go out in
  smaller bursts over Wi-Fi (128 KB per tick).

### Fixed
- The tablet's "USB cable connected" check was red while plugged in whenever
  the battery was not charging (Samsung battery protection stops at ~90%, or a
  Mac port gives little power); it now checks the plug, not charging.

## [0.3.0-beta.1] - 2026-11-18 — Even frame pacing, lighter lossless

Pre-release. Measurements are in [docs/measurements.md](docs/measurements.md).

### Added
- **Vsync-aligned presentation.** The Mac sends when each frame's pixels were
  composited; the tablet shows every frame on the vsync that keeps the Mac's
  spacing. Dragging a window, frames shown at a different spacing than they were
  composited went from ~50% to 1-16%, with no added latency.
- **New app icon.** Debug builds are named "SharpScreen Dev" with a DEV badge.

### Changed
- **Lossless tiles no longer slow video.** Video is captured as 420v again;
  tiles read a separate 30 fps BGRA capture. Mac capture-to-send while dragging:
  7.1 ms -> 6.0 ms (the same as without tiles).
- Lossless tile messages are drawn with the next video frame instead of on their
  own. The extra presents backed up the tablet's display queue (queued -> shown
  26 ms -> 19 ms) and pushed frames out unseen.
- After a reconnect a still screen is fully sharp in 17-35 ms (was 0.1-1.6 s).
- Up to 1 MB of tiles per refine tick (photo-heavy full refill 111-136 ms ->
  76-95 ms).

### Fixed
- Lossless tiles stopped after the encode size changed or a tablet profile
  rebuilt the display, until the tablet reconnected.

### Changed (release process)
- **Android release key.** Release APKs are signed with a SharpScreen release key
  (`CN=SharpScreen`) instead of the Android debug key; `scripts/build_android_release.sh`
  builds and verifies them, and RELEASING.md documents the whole release. The v0.2.0
  Release's APK was replaced with the release-key build shortly after publishing;
  whoever installed the earlier debug-signed upload must uninstall it once.

## [0.2.0] - 2026-11-18 — Lossless text, steady 120 Hz

First SharpScreen release. Measurements behind every change are in
[docs/measurements.md](docs/measurements.md).

### Added
- **Lossless tiles (M1).** Still regions are re-sent as LZ4-compressed BGRA
  64x64 tiles and composited over the HEVC video on the tablet's new GL
  compositor; moving regions stay on HEVC. Still text/UI tiles went from 0/655 to
  653/655 pixel-identical with the Mac.
- **Steady 120 fps.** A frame pacer fills slots ScreenCaptureKit leaves empty,
  one frame per 1/120 s.
- Diagnostics (all off by default): capture switches (`diag.*` defaults on the
  Mac), per-frame worst-case latency, tablet-side frame pacing log (PacingDiag),
  `scripts/measure_adb_throughput.py`.

### Fixed
- **Stutter over USB.** adbd's Nagle plus the tablet's delayed ACKs held frames
  for ~40 ms; the tablet now acks every message at once (transit jitter p99
  33-45 ms -> 11-15 ms, ping RTT ~42 ms -> ~8 ms).
- The tablet panel dropped to 96 Hz under a 120 fps stream; it is held at its top
  refresh rate while streaming.
- The low-latency decoder is preferred and run at full speed (decode ~9.4 -> ~7.3 ms).
- Video around a dragged window looked darker than the lossless tiles (BGRA frames
  are now tagged BT.709).

### Changed
- Capture samples are handled on a serial queue; the receive loop on the tablet
  runs on its own URGENT_DISPLAY thread.
- In-app links point to this repository.

### Changed
- Forked from Side Screen at 73b8067 (1.1.1 plus website commits).
- New identity: SharpScreen 0.1.0, Mac bundle id `io.sharpscreen.mac`, Android package
  `io.sharpscreen.app`, pairing scheme `sharpscreen://`, log `/tmp/sharpscreen.log`.
- Sparkle auto-update is off until SharpScreen publishes its own appcast.

### Removed
- Side Screen's website, store assets, privacy policy, contributing guide and
  release/Play publishing workflows.
