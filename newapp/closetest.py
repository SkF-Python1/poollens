#!/usr/bin/env python3
"""Test morphological closing to bridge decorative cushion lines before CC."""
import sys
from PIL import Image

im = Image.open(sys.argv[1]).convert('RGB')
W, H = im.size
px = im.load()
STEP = 3
gw, gh = W // STEP, H // STEP

def felt(r, g, b):
    return b > 100 and g > 90 and b > r + 40 and g > r + 20

def largest_bbox(mask, radius):
    # closing: dilate then erode, Chebyshev radius in grid cells
    m = mask
    if radius:
        d = bytearray(gw * gh)
        for gy in range(gh):
            for gx in range(gw):
                if not m[gy * gw + gx]:
                    continue
                for dy in range(-radius, radius + 1):
                    yy = gy + dy
                    if not (0 <= yy < gh):
                        continue
                    for dx in range(-radius, radius + 1):
                        xx = gx + dx
                        if 0 <= xx < gw:
                            d[yy * gw + xx] = 1
        e = bytearray(gw * gh)
        for gy in range(gh):
            for gx in range(gw):
                if not d[gy * gw + gx]:
                    continue
                ok = True
                for dy in range(-radius, radius + 1):
                    yy = gy + dy
                    if not (0 <= yy < gh):
                        continue
                    for dx in range(-radius, radius + 1):
                        xx = gx + dx
                        if 0 <= xx < gw and not d[yy * gw + xx]:
                            ok = False
                            break
                    if not ok:
                        break
                if ok:
                    e[gy * gw + gx] = 1
        m = e

    seen = bytearray(gw * gh)
    best = []
    for s in range(gw * gh):
        if not m[s] or seen[s]:
            continue
        st = [s]; seen[s] = 1; pts = []
        while st:
            c = st.pop(); pts.append(c)
            cy, cx = divmod(c, gw)
            for n, ok in ((c-1, cx > 0), (c+1, cx < gw-1), (c-gw, cy > 0), (c+gw, cy < gh-1)):
                if ok and m[n] and not seen[n]:
                    seen[n] = 1; st.append(n)
        if len(pts) > len(best):
            best = pts
    xs = [p % gw for p in best]; ys = [p // gw for p in best]
    return min(xs)*STEP, max(xs)*STEP, min(ys)*STEP, max(ys)*STEP

mask = bytearray(gw * gh)
for gy in range(gh):
    for gx in range(gw):
        if felt(*px[gx * STEP, gy * STEP]):
            mask[gy * gw + gx] = 1

for rad in (0, 1, 2, 3):
    l, r, t, b = largest_bbox(mask, rad)
    print("closing r=%d : x %d..%d y %d..%d  w=%d h=%d aspect %.3f"
          % (rad, l, r, t, b, r - l, b - t, (r - l) / max(1, b - t)))
print("\ntarget       : x 400..1916 y 226..1014  w=1516 h=788 aspect 1.924")
