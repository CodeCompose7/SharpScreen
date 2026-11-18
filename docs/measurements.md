# Measurements

Hardware: MacBook Pro M5 Max (macOS 27.2), Galaxy Tab S11 Ultra (SM-X930,
MT6991, Android 17, panel 2960x1848 @120 Hz) over a USB4 40 Gbps cable.
The tablet's USB-C port negotiates USB 3.2 Gen 1 (5 Gbps) with the Mac.

## USB link

| What | Result | How |
|---|---|---|
| Negotiated link | 5 Gb/s (USB 3.2 Gen 1) | `system_profiler SPUSBHostDataType` |
| `adb reverse` throughput, Mac -> tablet | **~2.4 Gbit/s (~300 MB/s)**, 3 runs of 512 MiB: 2477 / 2370 / 2353 Mbit/s | `scripts/measure_adb_throughput.py` (2026-11-18) |

At 300 MB/s a full 2960x1848 BGRA frame (21.9 MB) fits about 14 times per
second uncompressed, ~55/s at 4:1 LZ4 and ~137/s at 10:1. Lossless updates
therefore have to be limited to changed regions and still content; full-screen
motion stays on HEVC.

## Encoder (Side Screen 1.1.1 pipeline, 2960x1848 @120 fps, HEVC)

"frame age" is the host's own metric: encoder submit to network send.

| Rate control | Encoder | frame age | fps (content dependent) |
|---|---|---|---|
| Stock VBR + Quality | `ave.hevc` | **5-7 ms** | 86-109 |
| Low-latency rate control | `hevc.rtvc` | 30-50 ms | 89-99 |

Low-latency rate control halves the 1 s keyframe peak (2.5 MB -> 1.4 MB in a
synthetic probe) but costs 25-45 ms here, so SharpScreen keeps the stock
encoder. It also hangs VideoToolbox at an AverageBitRate of exactly Int32.max.

## SharpScreen 0.1.0 end to end (M0)

Mac `io.sharpscreen.mac` 0.1.0 + Android `io.sharpscreen.app.debug` 0.1.0 over
USB, 1480x924 HiDPI, 120 Hz, 300 Mbps, Quality High (2026-11-18):

- Stream 2960x1848 @120 fps, HEVC on `ave.hevc`, no "Encode size" downscale
  (pixel-exact with the panel), decoder limit reported 2960x1848.
- Pipeline: 70-94 fps (content dependent), frame age 5.5-12 ms, 0 dropped.

Same behaviour as Side Screen 1.1.1, so the fork changed nothing on the hot path.

## Tablet decoders

`c2.mtk.hevc.decoder.lowlatency`, `c2.mtk.av1.decoder.lowlatency`, VP9, AVC;
HEVC and AV1 advertise 3840x2160 @120. The client reports a decode limit of
2960x1848 at 30/60/120 fps. The Mac has no hardware AV1 encoder.
