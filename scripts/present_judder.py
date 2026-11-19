# -*- coding: utf-8 -*-
"""Presentation judder on the tablet (issue #13).

Matches the app's PresentTrace lines (swap end, host content time; logged with
`--ez diag.frameTiming true`) to `dumpsys SurfaceFlinger --latency <layer>`
(desired present, actual present, frame ready per buffer, last ~128 frames),
then reports:
  - steps off: consecutive new frames whose on-screen spacing differs from
    their spacing on the Mac by more than half a vsync
  - swap -> glass: the app's swap to the actual present

  adb logcat -c; (drag something on the SharpScreen display)
  while dragging: adb shell "dumpsys SurfaceFlinger --latency '<layer>'" >> sf.txt  (every ~0.7 s)
  adb logcat -d -s PresentTrace > pt.txt
  python3 scripts/present_judder.py pt.txt sf.txt

The layer is the app's `SurfaceView[...](BLAST)#N` from
`dumpsys SurfaceFlinger --list`.
"""
import sys, bisect
trace_file, sf_file = sys.argv[1], sys.argv[2]
period = 8_333_333
frames = []
for line in open(trace_file, errors='ignore'):
    if 'PresentTrace' not in line: continue
    parts = line.strip().split()
    try: swap, host = int(parts[-2]), int(parts[-1])
    except: continue
    frames.append((swap, host))
sf = {}
for line in open(sf_file, errors='ignore'):
    p = line.split()
    if len(p) == 3 and p[0].isdigit():
        d, a, r = map(int, p)
        if a > 0 and a < 1 << 62: sf[r] = a
readies = sorted(sf)
matched = []
for swap, host in frames:
    i = bisect.bisect_left(readies, swap - 1_000_000)
    if i < len(readies) and readies[i] - swap < 8_000_000:
        matched.append((swap, host, sf[readies[i]]))
lo, hi = (readies[0], readies[-1]) if readies else (0, 0)
inwin = [f for f in frames if lo <= f[0] <= hi]
shown = len(matched)
off = steps = 0
for (s0, h0, a0), (s1, h1, a1) in zip(matched, matched[1:]):
    if s1 - s0 > 100_000_000: continue
    steps += 1
    if abs((a1 - a0) - (h1 - h0)) > period / 2: off += 1
glass = sorted((a - s) / 1e6 for s, h, a in matched)
q = lambda v, f: v[min(len(v) - 1, int((len(v) - 1) * f))] if v else 0
print(f"steps off {100*off/max(steps,1):.1f}% ({off}/{steps}), frames in window {len(inwin)}, matched {shown}, swap->glass p50 {q(glass,.5):.1f} p90 {q(glass,.9):.1f} ms")
