# M1: lossless tiles over the HEVC stream

Status: design, 2026-11-18. Numbers come from [measurements](../measurements.md).

## Goal

Text and UI on the tablet should match the Mac pixel for pixel once they stop
moving, while motion keeps today's HEVC path (2960x1848 @120 fps, frame age
5-12 ms). HEVC Main is 4:2:0: chroma is stored at quarter resolution, which is
what blurs coloured text and thin lines. A lossless BGRA copy of still regions
removes that without paying for lossless motion.

## Budget

`adb reverse` carries ~2.4 Gbit/s (~300 MB/s). A full 2960x1848 BGRA frame is
21.9 MB: 14 fps raw, ~55 fps at 4:1 LZ4. So lossless data is limited to tiles
that have **stopped changing**, sent as a "refine" pass after motion, and capped
per frame so it never delays video. Typical refine after a scroll stops: a
handful of screens' worth of text tiles at 4-10:1, i.e. 2-5 MB, spread over
50-150 ms.

## Model

The panel is a grid of 64x64 tiles (47 x 29 = 1363 tiles on 2960x1848). Each
tile on the tablet shows either the **video** layer or the **lossless canvas**,
chosen by a per-tile valid bit.

```
Mac                                        Tablet
SCStream (BGRA) ──► HEVC encoder ─┐        decoder ──► SurfaceTexture (OES) ─┐
      │                           ├─ TCP ─►                                   ├─► GL compose ─► screen
      └─ dirty rects ─► tile map ─┤        LZ4 tiles ──► canvas texture ──────┤
                         │        │        valid bits ──► mask texture ───────┘
                         └─ LZ4 ──┘
```

Mac, per captured frame:

1. Map `SCStreamFrameInfo.dirtyRects` to tiles. A dirty tile becomes
   `moving` with `lastChange = now` and loses its valid bit.
2. The video frame goes out as today, preceded by an **invalidate** message
   listing the tiles that just lost validity (only when the set is non-empty).
3. Tiles in `moving` for longer than `settle` (start: 100 ms) with no change
   are queued for refine, nearest the cursor / most recently changed first.
4. Each pacer tick sends up to `refineBudget` bytes (start: 512 KB, ~60 MB/s,
   20% of the link) of **lossless tile** messages: the tile's BGRA pixels from
   the latest captured frame, LZ4 compressed (Compression.framework
   `COMPRESSION_LZ4_RAW`, the plain LZ4 block format). Sent tiles become
   `valid`.

Tablet, in stream order (one TCP connection, so ordering is free):

- invalidate: clear the tiles' bits in the mask.
- lossless tile: LZ4-decode into the canvas texture (`glTexSubImage2D` of one
  64x64 region), set the bit.
- video frame: decode as today; the compositor draws video where the bit is
  clear and canvas where it is set.

Because an invalidate always precedes the video frame that changed the tile,
and a refine is taken from a frame at least as new as the last video frame,
the tablet never shows stale lossless pixels over newer video.

## Wire protocol (additive, negotiated)

| Type | Dir | Payload |
|---|---|---|
| 19 `clientSupportsLosslessTiles` | client -> host | 1 byte: max tile protocol version (1). Host only sends 20/21 after this. |
| 20 `tileInvalidate` | host -> client | u16 count, then count x u16 tile index (row-major) |
| 21 `losslessTiles` | host -> client | u16 tileSize, u16 count, then count x {u16 index, u32 rawLen, u32 lz4Len, lz4 bytes} |

All integers big-endian, like the existing frame header. The host also resends
`displayConfig` semantics unchanged; a config change clears the whole mask.

## Mac changes

- Capture in `kCVPixelFormatType_32BGRA` instead of 420v. VideoToolbox converts
  BGRA to the encoder's YUV on its own; measure encode time (M1b gate: frame
  age stays within +2 ms).
- `TileTracker`: tile states, dirty-rect mapping, refine queue (pure logic,
  unit tested).
- `LosslessTileEncoder`: crop + LZ4 per tile, runs on its own queue, reads the
  last captured `CVPixelBuffer` (kept by `FramePacer`).
- Send through `StreamingServer` on the same connection, after the video
  frame of the tick, within the byte budget.

## Android changes

- Replace "decoder renders straight to SurfaceView" with a GL compositor on its
  own EGL thread: decoder -> `SurfaceTexture` (external OES texture); RGBA
  canvas texture at panel size; R8 mask texture (47x29). Fragment shader picks
  canvas or video per tile. Flip/rotation move into the vertex transform, so
  the TextureView path goes away.
- LZ4 decode via NDK (`lz4.c`, BSD-2) behind a small JNI call; decode straight
  into a direct `ByteBuffer` that feeds `glTexSubImage2D`.
- Render on `onFrameAvailable` and on tile updates; no vsync wait beyond the
  swap.

## Phases and gates

| Phase | Work | Gate |
|---|---|---|
| M1a | Android GL compositor, video only (no protocol change) | frame age and visible latency within +2 ms of SurfaceView; 120 fps holds. **Passed 2026-11-18** (draw+swap 1.9 ms, decode -0.9 ms; see measurements) |
| M1b | BGRA capture, TileTracker, types 19-21, LZ4 both ends, debug tint of lossless tiles | text tiles become lossless after `settle`; video unaffected. **Done 2026-11-18** |
| M1c | Tune `settle`, `refineBudget`, tile size; objective check | tablet `screencap` vs Mac capture: >= 99% of still-text pixels identical. **Met: 653/655 text tiles exact** (tuning still open) |

M1a goes first because it is the riskiest step for latency: SurfaceView lets
the hardware compose the decoder output directly, a GL pass adds a copy. If it
costs more than the gate, the fallback is an overlay layer (a second, mostly
transparent SurfaceView above the video) instead of a GL compositor.

## Known gaps after M1b

- ~~A change of encode size mid-session keeps the host's old tile grid.~~
  Fixed: the refiner rebuilds the grid when the captured frame size changes,
  and a display rebuilt under a connected client (profile switch) carries the
  lossless opt-in over to its new capture.
- Rotation and flip still use the TextureView path, which has no lossless layer.
- ~~The 32BGRA capture costs ~1.5-2 ms of encode time.~~ Fixed: video stays
  420v and tiles read a second 32BGRA stream capped at 30 fps, started with
  streaming (starting a capture stream marks the whole screen dirty once).

## Out of scope for M1

Wi-Fi tuning (budget there is far smaller), damage-aware HEVC encoding, 4:4:4
HEVC (the tablet decoder does not advertise it), AV1 (no hardware encoder on
the Mac).
