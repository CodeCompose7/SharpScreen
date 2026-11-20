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

## Frame pacing and panel refresh (2026-11-18)

- Mac `FramePacer`: one frame per 1/120 s slot. Still screen 120-121 fps
  (ScreenCaptureKit itself sends ~1-3 new frames + ~72 idle ticks a second);
  window drag 119-121 fps, of which 72-108 are new frames and the rest repeats;
  0 frames skipped for a busy encoder; frame age 5.7-9.6 ms.
- Tablet panel: Samsung adaptive refresh had dropped to **96 Hz** under the
  120 fps stream, which caused visible stutter. With `preferredDisplayModeId`
  set while streaming it stays at 120 Hz (5/5 samples during motion).
- macOS renders a dragged window at up to ~108 distinct frames a second on
  the virtual display, so that, not the link or encoder, bounds motion
  smoothness now.

## Why motion felt unlike a real display (2026-11-18)

### Not the virtual display's frame rate

New frames per second captured while a window is dragged:

| Captured display | New frames/s |
|---|---|
| Main physical monitor, 2560x1600 @120 Hz | 88-108 (mostly 100-107) |
| SharpScreen virtual display @120 Hz | 72-108 |

macOS draws a window drag at ~100 fps on either, so the virtual display is not
the bottleneck here. (A synthetic test window animated from an NSView display
link at 120 ticks/s yielded only ~60 captured frames on both displays, so that
pattern's own Core Animation updates are capped; it is useful as a steady 60 fps
source, not as a probe of the compositor.) Queue depth 4 vs 8, a serial vs
concurrent sample queue, `minimumFrameInterval`, the undocumented
`CGVirtualDisplaySettings.refreshDeadline`, CGDisplayStream instead of
ScreenCaptureKit and IOPMAssertionDeclareUserActivity changed nothing.
Reports of a 60 Hz cap on CGVirtualDisplay (BetterDisplay discussions #121,
#133, #4280) did not reproduce for window drags on macOS 27.2.

### Burst arrival on the tablet: Nagle + delayed ACK

`PacingDiag` on the tablet (2 s windows, steady 60 new + 60 repeat frames/s):

| | before | TCP_QUICKACK per message |
|---|---|---|
| transit jitter p99 | 33-45 ms | **11-15 ms** |
| transit jitter max | 37-50 ms | **11-16 ms** |
| render interval max | 45-60 ms | **18-24 ms** |
| two frames in one vsync (<4 ms) | 11-30% | **2-9%** |
| a frame held > 12.5 ms | 8-19% | 4-10% |

Ruled out on the way: 1 s keyframes (60 s changed nothing), the Mac side
(worst frame age ~20 ms, Network.framework send completion <= 0.3 ms), adb's USB
transport (raw 120 Hz echo: p99 3.8 ms at 16 B, 12.4 ms at 50 KB), and the
receive thread's priority. The 40-50 ms gaps and the ~42 ms ping RTT matched
Linux's delayed-ACK timer: adbd's local TCP socket to the app keeps Nagle on,
the app only receives, so its bare ACKs were delayed and adbd's small writes
waited for them.

## Latency budget after the fixes (2026-11-18)

Steady 60 new frames/s from the test window, Tab S11 Ultra over USB.

### Decoder

| Decoder | Operating rate | Decode (queue -> output) avg / max |
|---|---|---|
| c2.mtk.hevc.decoder (first in MediaCodecList) | 120 | 9.4-9.6 / 11-14 ms |
| c2.mtk.hevc.decoder.lowlatency | 120 | 9.1-9.5 / 12-13 ms |
| c2.mtk.hevc.decoder.lowlatency | Short.MAX_VALUE | **7.1-7.6 / 9-11 ms** |

### Repeat frames: cost and benefit roughly cancel

| Mode | Mac age of new frames avg / max | Decode avg | Sum | Panel |
|---|---|---|---|---|
| Pacing on (default) | 7.2-8.3 / 11-13 ms | 7.3-7.7 ms | ~15.3 ms | 120 fps |
| Pacing off (idle ticks encoded) | 5.4-5.7 / 6-8 ms | 8.4-8.6 ms | ~14.0 ms | ~90 fps |
| No repeats (`diag.noRepeats`) | 5.4-9.0 / 7-13 ms | 9.2-9.8 ms | ~15.1 ms | 60-70 fps |

Repeats delay a new frame's encode by ~2.3 ms on average, but they keep the
tablet's decoder busy, and it decodes 1-2 ms faster at 120 inputs/s than at 60-90
(it clocks down with load). Net difference ~1 ms, so pacing stays on.

## M1a: GL compositor vs direct decoder output (2026-11-18)

Same conditions (60 new + 60 repeat frames/s). GL path: decoder ->
SurfaceTexture -> GL draw on the SurfaceView window, swap interval 0.

| | Direct | GL compositor |
|---|---|---|
| Decode (queue -> output) | 7.5-7.8 ms | 6.6-6.8 ms |
| Release -> frame available (handoff) | not observable | 7.1-7.5 ms |
| Draw + swap | - | 1.8-2.0 ms (max 3-5) |
| Two frames in one vsync (<4 ms) | 8-10% | 4-6% |
| Frame held > 12.5 ms | 10-12% | 5-7% |

The 7 ms handoff did not move when the release carried an explicit "now"
timestamp, so it is taken to be the codec's own surface-output step, present
on the direct path too where nothing reports it. On that assumption (not
verified: FrameTimeline only reports dump-relative times) the GL path costs
~1.9 ms of draw+swap, offset by ~0.9 ms faster decode, within the +2 ms gate,
and paces frames more evenly. Picture verified upright and correct.

## M1b: lossless tiles (2026-11-18)

Still desktop on the virtual display, tablet screenshot (`adb screencap`)
against the host's own 32BGRA capture of the same moment, per 64x64 tile
(stats overlay, settings button and menu bar excluded):

| | HEVC only | Lossless tiles shown |
|---|---|---|
| Tiles 100% pixel-exact | 70 / 1282 (5%) | **1274 / 1282 (99.4%)** |
| Text/UI tiles 100% exact | **0 / 655** | **653 / 655** |
| Median pixel-exact share per tile | 40.6% | 100% |
| Mean absolute error | 2.07 | 0.61 |

With the test window animating, 1318 of 1363 tiles were lossless and ~290
tiles/s cycled between video and lossless around the moving bar and the menu
bar, at 120-200 KB/s of tile data. 32BGRA capture adds ~1.5-2 ms of encode time
on the host (new-frame age ~14-16 ms vs ~12-13 ms with 420v in this setup).

### Time to a full lossless screen after a reset

From a tile reset (reconnect or client resync) until 99% of tiles are valid
(a menu bar clock keeps a few changing), logged by the host:

| | Still 2960x1848 desktop |
|---|---|
| Reset marks every tile changed now | 135-988 ms (6 runs) |
| Reset keeps the change history | **27-38 ms (14 runs)** |

The transfer itself was ~25 ms; the rest was waiting for a frame captured
after the reset, which a still screen only sends when something small
changes. The host now records changes with or without a client, so the
frame it already holds is known to be current.

Photo wallpaper at 2560x1600 barely compresses with LZ4 (~6.8 MB per full
screen), so the per-tick byte cap binds. 4 reconnects per cap:

| Cap per 8.3 ms tick | Full fill | Video frame age during fill |
|---|---|---|
| 512 KB | 111-136 ms | avg 11-12 ms, max 12-22 ms |
| **1 MB** | 76-95 ms | avg 8-12 ms, max 11-20 ms |
| 2 MB | 73-92 ms | avg 8-11 ms, max 11-19 ms |

Past ~750 KB the 4 ms work cap binds instead, so 2 MB gains nothing. Encoding
tiles on several cores would lift that, but only saves ~20 ms on photo-heavy
full fills. A display rebuilt mid-connection (2960x1848 to 2560x1600 by
profile switch) went on with a 40x25 grid, 994/1000 tiles valid.

### Dragging with lossless tiles: a separate BGRA stream

A scripted window sweep on the virtual display (1200 pt/s, display-link
driven, ~112 moves/s, 8 s per run), tablet launched with and without
`diag.lossless`. Mac capture-to-send of new frames:

| | Lossless off | Lossless on |
|---|---|---|
| Video stream switched to 32BGRA | 5.7 ms | 7.1 ms |
| Video 420v + 30 fps BGRA tile stream | 6.15 ms | 6.01 ms |

Tablet frame pacing, decoder handoff (~7 ms, as in M1a) and draw+swap did not
differ with tiles on or off. Tile traffic while dragging: ~600 KB/s.

Two ScreenCaptureKit behaviours shaped it:

- On-demand screenshots (SCScreenshotManager, ~38 ms each) made the video
  stream report the whole screen dirty after each one, so tiles never stayed
  valid: dropped.
- Starting the tile stream does the same once (~50 ms later) without a new
  tile frame, and the stream's first frame carries the older time of the last
  composite. Starting tile tracking with streaming, before any client, and
  counting frames from the stream start: every connection fills in 17-35 ms
  (20 runs), the first one after an app start included.

With the tile stream, video against the host's BGRA is within 1-2 levels per
brightness bin (one colour edge bin at +7 R, chroma subsampling); tiles stay
1274/1282 pixel exact (text/UI 602/604).

## Vsync-aligned presentation (2026-11-18)

Measured with atrace on the tablet while a scripted window swept the virtual
display: SurfaceFlinger's latch and present of each SurfaceView buffer, mapped
to the host content time the app writes into the trace at each swap
(`ssPresent n=… host=…`, only while app tracing is on). A "step" is two
consecutive frames with new content; it is off when the time between their
presents differs from the time between their content on the Mac by more than
half a vsync.

| | Steps off | Content never shown | Content -> present (p50, relative) |
|---|---|---|---|
| Present when drawn (before) | 48.5%, 50.9% | 9, 8 of ~240 | 0 |
| Scheduled, p50 offset | **11.9%, 10.0%, 14.3%, 15.9%** | 14, 9, 17, 14 | -6 to 0 ms |
| Scheduled, p70 | 17.1%, 15.4% | 13, 15 | +8 ms |
| Scheduled, p85 | 9.9%, 4.8% | 18, 15 | +8 ms |

- The host now sends each frame's content time (ScreenCaptureKit's
  presentation time; a repeat carries its source's) instead of its encode
  start. Content arrives on the Mac's 8.33 ms grid.
- The client asks for the first vsync at or after content time + the median
  of how soon recent frames could make a vsync (Choreographer.FrameData
  deadlines), never the same vsync as the frame before.
- A first version used the 90th percentile: frames waited 13-32 ms in the
  window's queue, which holds only a few (`a:3` in the trace), and newer
  frames pushed waiting ones out unseen.
- Lossless tile messages presented on their own (one extra present per
  invalidate or tile batch). That backed the queue up: queued -> shown 26 ms
  with tiles vs 19-20 ms without, and more frames never shown. They now ride
  on the next video frame (12 ms fallback).

`diag.vsyncPresent=false` and `diag.presentPercentile=N` are per-launch tablet
extras for A/B.

## Wi-Fi (2026-11-18)

Tablet (802.11ac, RSSI -41, 780 Mbps link) and Mac both on Wi-Fi to the same
5 GHz access point, so every packet crosses the air twice. Release builds,
scripted window sweep:

| | Baseline | After the fixes below |
|---|---|---|
| Wait added by vsync scheduling (avg) | 42-45 ms | 7-9 ms |
| Tablet display gap, max | 85-96 ms | 84-91 ms |
| Mac send completion, max (dragging) | 53-89 ms | 18-80 ms |

- Vsync scheduling spaced every frame of a Wi-Fi burst onto its own vsync,
  chaining up to 42 ms of wait; it may now push a frame by one vsync at most.
- The tablet holds a Wi-Fi low-latency lock while connected wirelessly.
- Tiles are capped at 128 KB per refine tick on Wi-Fi (1 MB over USB).

The ~90 ms gaps are not the app's: plain ping from the Mac shows the same
spikes every ~440 ms, to the tablet (p50 7.4, p99 74.8, max 78.9 ms) and to
the router alone (p50 4.0, p99 72.4, max 75.8 ms), with AWDL (AirDrop,
Handoff, Universal Control) active on the Mac.

With AWDL off (`sudo ifconfig awdl0 down`, until reboot or the next AirDrop):

| | AWDL on | AWDL off |
|---|---|---|
| Ping Mac -> router p99 / max | 72.4 / 75.8 ms | 8.3 / 11.7 ms |
| Ping Mac -> tablet p99 / max | 74.8 / 78.9 ms | 13.9 / 38.1 ms |
| Tablet display gap, max | 84-96 ms | 17-49 ms |
| Mac send completion, max | 18-89 ms | 0.4-1.0 ms |
| Transit jitter while dragging, p90 / p99 | 36-78 / 75-108 ms | 12 / 20-21 ms |

## Video colour with 32BGRA capture (2026-11-18)

Dragging a window left a dark halo: the tiles around it fall back to video,
and the video was darker than the lossless tiles next to it. Tablet video vs the
host's capture, mean difference by brightness (video only, still screen):

| Host level | Before | After tagging BGRA as BT.709 |
|---|---|---|
| 96-127 | -9 to -13 | -0.3 to -0.5 |
| 128-159 | -9 to -11 | -0.4 to -0.8 |
| 160-191 | -8 to -9 | -0.5 to -0.9 |
| 224-255 | -1.5 | -1.3 |

ScreenCaptureKit's 32BGRA buffers carry the display colour space; against the
session's BT.709 tags VideoToolbox also converted the transfer function. With the
buffers tagged BT.709 only the matrix applies, which is what the tablet inverts.

## USB without ADB: Android Open Accessory (2026-11-19, issue #3)

Spike: `scripts/aoa_spike.c` (libusb, Mac) against the debug app's
`AoaSpikeActivity`. Tab S11 Ultra (SM-X930, Android 17), USB-C on the Mac.

- USB tethering is no way out on this tablet: its tethering function is RNDIS
  (`mUsbTetheringFunction: RNDIS`, no NCM), which macOS has no driver for.
- The AOA handshake works: protocol version 2, the tablet re-enumerates as
  18d1:2d01 (accessory + adb) in ~2 s and **stays at SuperSpeed (5 Gb/s,
  1024-byte bulk packets)**. Android asks once whether SharpScreen should handle
  the accessory ("always open" can be ticked).
- Bulk throughput, 256 MB each, two runs:

| | Mac -> tablet | tablet -> Mac |
|---|---|---|
| AOA | 68.6-69.8 MB/s | 173-175 MB/s |
| ADB forward (`nc` echo, both directions at once) | ~193 MB/s | ~193 MB/s |

  Mac -> tablet is bound by f_accessory: one 16 KB read at a time on the
  tablet (every read returned exactly 16384 bytes).
- Round trips (echo, Mac clock):

| Size | AOA p50 / p99 | ADB p50 / p99 |
|---|---|---|
| 100 B (touch) | 0.19-0.22 / 0.75-0.83 ms | 1.57 / 2.77 ms |
| 30 KB (P-frame) | 0.61-0.63 / 1.70-1.89 ms | not comparable* |
| 300 KB (I-frame) | 5.6-5.8 / 9.8-10.5 ms | 7.3 / 52 ms* |

  *The ADB side echoes through `toybox nc -L cat`, whose small writes meet
  delayed ACKs (30 KB p50 47 ms); only the 100 B row is a fair comparison.

**With USB debugging off** (developer options untouched otherwise, USB mode
"File transfer") the same handshake gives 18d1:2d00, accessory only, still
SuperSpeed, and the link is faster (three runs):

| | Mac -> tablet | tablet -> Mac | 100 B p50 / p99 | 30 KB p50 / p99 | 300 KB p50 / p99 |
|---|---|---|---|---|---|
| AOA, no ADB | 103-105 MB/s | 165-166 MB/s | 0.17 / 0.77-0.80 ms | 0.37-0.41 / 1.1-1.2 ms | 3.8-3.9 / 10-14 ms |

The starting USB mode does not matter: from "File transfer" (mtp,adb) the
switch and the numbers were the same as from charging-only.

What it means: video (≤ 4 MB/s) fits easily and latency is lower than ADB.
Lossless tile fills run at ~70 MB/s (with ADB on) to ~105 MB/s (ADB off)
instead of the 1 MB/tick (120 MB/s) cap. Not yet tried: Samsung Auto Blocker
on. The tablet leaves accessory mode only when the cable is unplugged (or, with
ADB, `svc usb setFunctions`).

### In the app (2026-11-19)

AccessoryLink (Mac, IOUSBHost) and AccessoryLink.kt (tablet) relay the
streaming TCP connection over the accessory, framed by UsbLink (session ids so a
finished stream never leaks into the next). USB debugging on (18d1:2d01):

- 120 fps, average frame age 11-12 ms (max < 20 ms), as over adb.
- Lossless tiles capped at 384 KB/tick (46 MB/s, below the link's 69 MB/s so video
  never queues behind tiles): a full 2960x1848 refill (13.6 MB) takes 288-291 ms
  instead of 76-95 ms over adb at 1 MB/tick.
- Quitting the Mac app resets the device: the tablet leaves accessory mode at once
  and the app sees the accessory detach. Killing the tablet app makes the Mac's next
  write time out (3 s); the Mac then resets the device and waits for Reconnect.

## Wi-Fi vs USB, again (2026-11-19, issue #9)

Same 5 GHz access point, tablet RSSI -36 / 780 Mbps, AWDL down on the Mac,
release builds, a window swept across the tablet display for 15-20 s.

| | USB accessory | Wi-Fi |
|---|---|---|
| Mac frame age (content -> sent), avg / max | 12-13 / 19-20 ms | 12-13 / 19-25 ms |
| Tablet decoder latency, avg | ~7 ms | ~7 ms |
| Transit jitter in motion, p50 / p90 / p99 | 8-12 / 18-24 / 26-33 ms | 8-11 / 17-20 / 25-30 ms |
| Vsync scheduling wait, avg | 4-6 ms | 5-6 ms |
| Decode-to-draw handoff, per-2 s max | 15-21 ms | 22-38 ms |

The jitter is the Mac's (capture and encode timing), not the network's: it is
the same over USB. Wi-Fi adds ~3 ms one way (ping while streaming, both ways:
p50 6.3-6.6, p99 ~9, max 12 ms round trip) and somewhat larger occasional
spikes.

Idle Wi-Fi is another matter: with no stream running, power saving gave round
trips of p50 21 / p90 197 / max 435 ms tablet -> Mac and p50 8 / p90 58 /
max 498 ms Mac -> tablet (router p99 33, max 151 ms). A running stream keeps
both radios awake.

### Dragging a large window: the server's sockets (issue #9)

A large, text-filled window swept across the tablet display showed what a
small one did not: over Wi-Fi the Mac's sends stalled for 24-107 ms (USB:
under 1 ms) and the tablet's display stopped for 60-140 ms, at only 7-10 Mbps
of video plus 0.6-2 MB/s of tiles. Ping stayed at p99 ~11 ms, and the tablet
read the socket at once.

The tablet's kernel counted 15-27% of the stream's TCP segments arriving out of
order (`TcpExt:TCPOFOQueue`). A packet capture on the Mac's en0 showed the Mac
sending in order (12 resends in 15 s) while the tablet reported a hole in 25%
of its ACKs (1,805 holes, filled by the original segment within p90 2.4 ms,
max 17 ms); the Mac's TCP took the 1,397 duplicate ACKs for loss and slowed
down, and frames queued behind it.

Only connections Network.framework's NWListener accepted did this, on a random
share of connections whatever their parameters, and they reached the tablet in
much larger bursts (~3,000 segments per 30 MB instead of ~21,000). A plain BSD
socket server, accepted the same way, stayed at 0% in every run. The server
now listens and sends on BSD sockets (`SocketListener`, `SocketConnection`):

| Big window over Wi-Fi | NWListener | BSD sockets |
|---|---|---|
| Segments out of order | 15-27% | 0-0.1% |
| Mac send completion, max | 55-107 ms | 1.2-1.4 ms |
| Decode-to-draw handoff, per-2 s max | 56-98 ms | 24-43 ms |
| Tablet display stop, max | 82-140 ms | 46-96 ms |

Not the cause, measured along the way: the Wi-Fi low-latency lock, TCP_QUICKACK
on the tablet, tile volume, keyframes, Mac CPU/GPU load, the WMM marking,
peer-to-peer (AWDL) on the listener.

A still screen sent ~2.7 Mbps over Wi-Fi, mostly a ~200 KB keyframe a second
(1.1 Mbps with keyframes every 10 s). Keyframes now come every 10 s over
Wi-Fi (1 s over USB); the stream socket is marked interactive video (WMM
AC_VI) on the Mac and EF (AC_VO) on the tablet. Neither moved the numbers
on this uncontended network.

### What was left: keyframes the tablet asked for (issue #9, 2026-11-19)

With the BSD sockets in, the Mac now logs how much the stream socket holds
(its queue plus the kernel's unacknowledged bytes) and the largest frame, each
second. During the big-window sweep the socket held 540-900 KB in most
seconds, and every such second carried a 560-670 KB keyframe: the tablet asked
for one ("last keyframe 1505 ms ago") 1.5 s after each keyframe, so the 10 s
Wi-Fi interval never applied while anything moved. A keyframe of a moving
screen is ~3x a still one's, and at Wi-Fi rates each one is 100+ ms of air
time that the frames behind it wait out.

The tablet's stale-keyframe guard now waits 12 s (above the Mac's longest
interval); the decoder still asks at once on errors. A decoder input pool
emptied by a Wi-Fi burst now waits up to 20 ms for a buffer instead of
dropping the frame and forcing another keyframe.

| Big window over Wi-Fi | Before | After |
|---|---|---|
| Keyframes while moving | every 1.5 s, 560-670 KB | every 10 s |
| Video while moving, average | 7.8 Mbps (9.5-12 in keyframe seconds) | 5.0-5.5 Mbps |
| Socket queue, per-second max | 540-900 KB in most seconds | 90-215 KB (keyframe or new-window seconds: 490-800 KB) |
| Tablet display stop, per-2 s max | 84-102 ms | 83-102 ms |

The display stops of ~85-90 ms did not change, and they show up on a still
screen too (84-91 ms per 2 s with nothing moving). The Mac's AWDL (AirDrop,
Handoff, Universal Control) was active: idle pings to the tablet, with no
stream, went over 40 ms 9 times in 100, p99 ~77 ms, the same size as the
stops. AWDL moves the Mac's Wi-Fi to its own channel about twice a second
(the app's Wi-Fi notice); USB has no such gaps.

Measured and dropped: a smaller send buffer (SO_SNDBUF 64 or 128 KB: the
queue moved into the app, display stops grew to 116-145 ms), and holding
lossless tiles while the socket holds over 64 KB (no difference, with or
without the keyframe fix).

### The router on AWDL's channel (2026-11-19)

The ~0.46 s rhythm held with nothing streaming: the Mac's pings to the router
(not just the tablet) spiked to 60-76 ms every 0.46 s, so the gap was on the
Mac's Wi-Fi. The Mac sat on channel 36 (80 MHz); AWDL was active (it comes back
after `ifconfig awdl0 down` once macOS wants it). With the router moved to
channel 149 (80 MHz), the channel AWDL uses, and AWDL still active:

| Wi-Fi, AWDL active | Channel 36 | Channel 149 |
|---|---|---|
| Idle ping Mac -> router, >40 ms | 34 of 500 (60-76 ms every 0.46 s) | 0 of 1000, max 9.4 ms |
| Idle ping Mac -> tablet, >40 ms | 37 of 500 | 0 of 1000, p99 23 ms, max 30 ms |
| Big-window drag: tablet display stop, per-2 s max | 86-91 ms (worst 90-107) | 35-36 ms (worst 43-59) |
| Ping during the drag, p99 | 72-82 ms | 37-47 ms |

Raising the tablet's present percentile from p50 to p95 (a ~55 ms playout
buffer) had not hidden the stops on channel 36 (86-91 ms either way): only a
buffer longer than the gap would, at that much more latency. The Mac's Wi-Fi
notice now recommends channel 149 (44 in some countries) first.

### Lossless tiles while the screen moves (2026-11-19)

On channel 149, lossless tiles were most of what was left. Big-window drag,
tablet app launched with `--ez diag.lossless`:

| Wi-Fi, channel 149 | Tiles on (~3.8 MB/s) | Tiles off |
|---|---|---|
| Tablet display stop, per-2 s max (avg) | 32-36 ms | 25 ms |
| Frames shown <4 ms after the previous | 18-20% | 10% |
| Ping during the drag, p90 | 24-26 ms | 10 ms |

A lower Wi-Fi budget for all tiles (diag.tileKBPerTickWireless) helped only by
slowing the sharpening: 16 KB a tick gave 24-30 ms stops but a full screen of
tiles took 5.5 s after a reset instead of 0.8 s (32 KB: no change, the cap
hardly bound). Tiles now take 16 KB a tick over Wi-Fi only while the screen
moves (a frame with new pixels went out in the last 100 ms) and the full
128 KB once it is still. New build against the installed one, alternating,
two drags each, twice:

| Wi-Fi, channel 149 | Full budget always | Small budget while moving |
|---|---|---|
| Tiles during the drag | 3.7-3.9 MB/s | 2.2 MB/s |
| Tablet display stop, per-2 s max (avg) | 33-40 ms (one run 54) | 26-31 ms |
| Frames shown <4 ms after the previous | 27-30% | 12-18% |
| Ping during the drag, p90 | 25-32 ms | 12-17 ms |
| Full screen of tiles after a reset | 0.81 s | 0.95-0.99 s |

## Latency breakdown, Mac screen to tablet glass (2026-11-20, issue #13)

A tablet launched with `--ez diag.frameTiming true` reports, for each new
frame it draws, the time from arrival to decoder output, from there to its
draw, and from the draw to the vsync it asked for. The Mac matches the report
to its own times for that frame and logs a `Latency` line every 2 s. Each side
only measures intervals on its own clock; the network share is the round trip
minus the tablet's part, halved.

The reference is the frame's content time: its presentation time on the Mac's
virtual display (ScreenCaptureKit's PTS), so the total is how far the tablet
runs behind the Mac's own screen. The encoder often starts before that vsync,
so the first stage is negative.

Big-window drag, Tab S11 Ultra at 120 Hz, p50 (p90) in ms, 7 windows of 2 s:

| Stage | Wi-Fi, channel 149 | USB (adb reverse) |
|---|---|---|
| Mac vsync -> encode start | -2.5 to -0.4 (0.2-1.0) | -3.5 to -2.5 (0.4-0.7) |
| Encode | 5.4-12.3 (7.6-16.7) | 11.0-11.7 (14.4-17.0) |
| Encoded -> socket | 0.0 | 0.0 |
| Network, one way | 5.6-5.8 (6.9-21.8) | 2.1-2.3 (2.8-3.2) |
| Decode | 3.1-5.8 (7.9-9.1) | 2.1-3.7 (7.7-8.3) |
| Decoder output -> draw | 1.3-3.6 (4.2-5.9) | 2.9-4.3 (5.9-6.8) |
| Draw -> its vsync | 20.6-27.7 (27.1-32.0) | 21.9-23.2 (27.1-29.6) |
| **Total** | **44.3-44.6** (45.4-60.4) | **42.4-43.1** (43.3-43.7) |

- The totals hardly move because the tablet presents each frame at the Mac's
  content time plus a fixed offset (PresentScheduler, p50): what one stage
  saves, the wait for the vsync absorbs. Only lowering the earliest a frame
  can be shown lowers the total. USB's 3.5 ms shorter network leg shows up as
  only 1.5 ms.
- The biggest share is the tablet's draw to vsync, 21-23 ms: the scheduler's
  wait plus the time SurfaceFlinger needs before a queued buffer is on screen.
- Encoding takes 11-12 ms, twice the 5.4 ms of the first seconds of the drag
  (and of the earlier 60 fps test window). Worth a look.
- The network leg over Wi-Fi is 5.7 ms; over USB 2.2 ms.

### The tablet's draw -> vsync: mostly SurfaceFlinger (2026-11-20)

The tablet presents each frame at the Mac's content time plus an offset, the
p50 of how soon recent frames could make a vsync. Lowering the percentile
shows frames sooner. Judder by `scripts/present_judder.py`: the app logs each
new frame's swap and content time, `dumpsys SurfaceFlinger --latency` gives
each buffer's actual present, and a step is off when two consecutive new
frames reach the screen at a spacing more than half a vsync away from their
spacing on the Mac (a stricter matching than the atrace runs above, so the
baseline is higher). USB, big-window drag, alternating, two runs each:

| Present at | Steps off | App swap -> on screen, p50 | Total behind the Mac, p50 |
|---|---|---|---|
| p50 (stock) | 28.2%, 32.5% | 19.4-19.5 ms | 41.7-42.0 ms |
| p25 | 36.4%, 47.6% | 17.9-18.3 ms | 38.0-39.8 ms |
| p1 (as soon as possible) | 51.4%, 44.3% | 16.9-17.1 ms | 37.8-38.0 ms |

Even shown as soon as possible, a frame takes ~17 ms from the app's swap to
the screen: two vsyncs of SurfaceFlinger's pipeline at 120 Hz, which an app
cannot shorten (front-buffer rendering would, at the price of tearing on a
moving full-screen picture). The scheduler's own wait is the remaining 2-4 ms,
and it buys the smoothness: giving it up takes judder from ~30% to ~45-50% of
steps. p50 stays.

### Encoding: ~12 ms, and what did not change it (2026-11-20)

Big-window drag over USB, ~90-110 new frames a second plus repeats to 120.
"Encode" is VideoToolbox submit -> output (Latency line); the wait/service
split below logged, per frame, the time behind the previous frame's output
and the rest.

| Tried | Encode p50 (p90) | Total behind the Mac, p50 |
|---|---|---|
| Stock | 11.1-12.4 (15-17.7) | 38-43 |
| Lossless tiles off (tablet `diag.lossless false`) | 11.6-12.1 | 42.4-42.6 |
| No repeat frames (`diag.noRepeats`) | 8.6-8.9 (11.5-11.8) | 41.7-44.6 |
| Repeats anchored to the next vsync + 1.5 ms instead of a free-running timer | 11.9-13.2 | 39.6-47.7 |
| PrioritizeEncodingSpeedOverQuality | 9.1-10.3 | 37-44.5 |
| EnableLowLatencyRateControl | **53-54** | 95-97 |
| ExpectedFrameRate 240, MaximumRealTimeFrameRate 240, RealTime off | no change (~12) | - |
| Capture CGColorSpace removed; a plain memcpy copy of the capture; App Nap off | no change | - |

- On a still screen (repeats only, one every 8.33 ms) each frame takes ~12.7
  ms from submit to output, ~4.3 ms of it after the next frame was already
  submitted: the encoder keeps about 1.5 frames in flight at 120 fps at
  2960x1848. During the drag the split is ~8.4 ms service plus ~2-3 ms behind
  the previous frame.
- A standalone VideoToolbox benchmark with the same settings gave 5.5-6.1 ms
  when paced with usleep (which runs late here, ~90 fps) and ~108 ms when paced
  precisely, from a timer or a spin loop. It does not stand in for the app, so
  no conclusion is drawn from it.
- Without repeats the encode stage drops ~3 ms, but the tablet decodes slower
  at the lower input rate and the total did not improve. Nothing here was
  kept; the encode stage looks like the hardware encoder's own latency at
  this size and rate.

## Lossless tiles after a drag stops (2026-11-20, issue #14)

`Sharpen:` lines: once 99% of the tiles are lossless after a big change (16+
tiles in one dirty-rect batch), how long after it. The per-second pipeline
line adds each tile's last change -> lossless send. A large text window on the
SharpScreen display was dragged 3 s and held 3 s, four times, over USB without
ADB (384 KB of tiles a tick); the fifth value of each run is the window
closing.

| Settle / tile capture | Drag stop -> 99% lossless, typical | Tiles sent / invalidated per run | New-frame age avg |
|---|---|---|---|
| 100 ms / 30 fps (before) | 139-150 ms | 7959-8348 / ~8650 | 10.8-11.6 ms |
| 50 ms / 30 fps | 89-99 ms | ~8650 / ~8650 | 12.1-12.5 ms |
| **40 ms / 30 fps (USB now)** | **80-97 ms** | 8756-9255 / same | 9.0-11.9 ms |
| 30 ms / 30 fps | 75-88 ms | 7939-8654 / ~8650 | 11.5-11.8 ms |
| 100 ms / 120 fps | 196-778 ms | - | - |
| 50 ms / 120 fps | 220-869 ms | - | - |

- The settle is most of the wait, and shortening it costs ~5% more tiles and
  no video latency. Below 40 ms the 30 fps tile capture (up to 33 ms) dominates.
  USB settles after 40 ms now; Wi-Fi keeps 100 ms.
- A faster tile capture made it slower, not faster.
- In some sessions a quarter to all of the stops took 400-900 ms: settled tiles
  waited on a tile frame newer than their change, and the tile stream sent none
  until something else changed. Stalls come in streaks per session; the tile
  stream's queue depth (3, 5, 6, 8) did not settle it either way. Asking the
  tile stream for a fresh frame with `updateConfiguration` made each request
  count as a whole-screen change (tiles sent 65 -> 234-276 MB a run, typical
  stop 218-225 ms), like the on-demand screenshots tried before; dropped. Stamping
  changes with the video frame's composite time instead of its delivery made no
  difference; dropped.

## Tablet power on a still screen (2026-11-20, issue #16)

Streaming a still screen, the tablet decoded, drew and presented the host's
120 repeat frames a second. Over USB (accessory, charging) the battery
current was -812 mA streaming a still screen, -849 mA while dragging, and
+128 mA with the app in the background: ~0.9 A (~3.5 W) for showing a picture
that did not change, more than the Mac's port supplied.

Now the tablet decodes a repeat (same content time as the frame on screen,
same surface size) but does not draw or present it, and the Mac repeats only
10 times a second once nothing new has come for 50 ms (back to every slot with
the next new pixels). Debug app over USB (adb), the SharpScreen display still
(a caret or clock still changed it 1-3 times a second), CPU from `top` (of 800%):

| Still screen | Frames sent/s | App | SurfaceFlinger | Decoder (c2) | Composer |
|---|---|---|---|---|---|
| Before | 120-121 | 93-97% | 28-31% | 33-36% | 10-12% |
| After | 15-23 | 28-33% | 17-18% | 7-10% | 4-5% |

- While dragging nothing changes (new pixels every slot): app 93-98%,
  SurfaceFlinger 33-35% either way.
- Drag latency (Mac screen -> tablet glass, p50) was 34.4 or 38.4 ms per
  session either way, before 34.4 / 38.3 and after 38.5 / 34.4: the same
  half-vsync step between sessions seen before, not the change.
- Battery current over USB was not usable for an A/B here: while charging it
  swung between +300 and +1270 mA on the same setup.
- `--ez diag.drawRepeats true` (tablet) and `diag.idleRepeatFps 0` (Mac)
  restore the old behaviour.

### Variable and fixed refresh rate (2026-11-20, issue #16)

Settings > Streaming > Tablet Refresh Rate (variable by default; the code
calls the modes powerSaving and performance). In variable the Mac repeats 10 times a second on a still screen and the tablet,
once it has presented fewer than 6 new frames in the last second, asks for
30 Hz: the panel then ran at 30 Hz on a still screen and 120 Hz while
dragging (SurfaceFlinger's refresh rate). Releasing the 120 Hz mode pin alone
left the panel at 120 Hz; it took a 30 Hz vote on the video surface
(`Surface.setFrameRate`) and on the window (`preferredRefreshRate`, the stats
overlay redraws it twice a second), with the present scheduler's per-vsync
callbacks paused. Three new frames within 60 ms ask for 120 Hz back.

The scheduler first kept its offset from the 30 Hz period: a drag starting
from still ran 44-54 ms behind the Mac (p50, first 2 s, max 97-110 ms). It now
relearns on resume: first-window max 20-44 ms variable, 42-64 ms fixed.

Battery current with the cable out, Wi-Fi, still screen, stats overlay on,
the debug app logging `BatteryManager` current every 5 s (`--ez
diag.powerLog true`), the mode switched on the Mac every 90 s (first 15 s of
each phase dropped):

| Mode | Average current |
|---|---|
| Variable | -712 mA, -715 mA |
| Fixed | -980 mA, -925 mA |

About 240 mA (~25%) less with a variable rate, ~0.9 W at 3.9 V.

## Tablet decoders

`c2.mtk.hevc.decoder.lowlatency`, `c2.mtk.av1.decoder.lowlatency`, VP9, AVC;
HEVC and AV1 advertise 3840x2160 @120. The client reports a decode limit of
2960x1848 at 30/60/120 fps. The Mac has no hardware AV1 encoder.
