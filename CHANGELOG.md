# Changelog

All notable changes to SharpScreen are documented here.
Side Screen's history up to the fork is in the git log and in
[its changelog](https://github.com/tranvuongquocdat/SideScreen/blob/main/CHANGELOG.md).

## [Unreleased]

### Changed
- **Android release key.** Release APKs are now signed with a SharpScreen release
  key (`CN=SharpScreen`) instead of the Android debug key; `scripts/build_android_release.sh`
  builds and verifies them, and RELEASING.md documents the whole release. The 0.2.0
  APK was debug-signed, so it has to be uninstalled once before installing the next
  version.

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
