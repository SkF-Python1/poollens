#!/usr/bin/env python3
"""Replica of the new findTable: largest felt component, no trimming."""
import sys
from PIL import Image

im = Image.open(sys.argv[1]).convert('RGB')
W, H = im.size
px = im.load()
STEP = 3
gw, gh = W // STEP, H // STEP

def is_felt(r, g, b):
    return b > 100 and g > 90 and b > r + 40 and g > r + 20

felt = bytearray(gw * gh)
for gy in range(gh):
    for gx in range(gw):
        if is_felt(*px[gx * STEP, gy * STEP]):
            felt[gy * gw + gx] = 1

seen = bytearray(gw * gh)
best_pts = []
for s in range(gw * gh):
    if not felt[s] or seen[s]:
        continue
    stack = [s]; seen[s] = 1; pts = []
    while stack:
        cur = stack.pop(); pts.append(cur)
        cy, cx = divmod(cur, gw)
        for n, ok in ((cur - 1, cx > 0), (cur + 1, cx < gw - 1),
                      (cur - gw, cy > 0), (cur + gw, cy < gh - 1)):
            if ok and felt[n] and not seen[n]:
                seen[n] = 1; stack.append(n)
    if len(pts) > len(best_pts):
        best_pts = pts

total = gw * gh
print("largest felt component: %d px (%.1f%% of grid)" % (len(best_pts), 100.0 * len(best_pts) / total))
if len(best_pts) < total / 50:
    print("-> REJECT (too small)")
    sys.exit()

xs = [p % gw for p in best_pts]
ys = [p // gw for p in best_pts]
minX, maxX, minY, maxY = min(xs), max(xs), min(ys), max(ys)
bw = maxX - minX + 1
bh = maxY - minY + 1
aspect = max(bw, bh) / max(1, min(bw, bh))
print("bbox grid x %d..%d y %d..%d  (%d x %d) aspect %.2f"
      % (minX, maxX, minY, maxY, bw, bh, aspect))
print("bbox full  x %d..%d y %d..%d" % (minX*STEP, maxX*STEP, minY*STEP, maxY*STEP))

if aspect < 1.35 or aspect > 2.6:
    print("-> REJECT (aspect)")
elif bh < 8 or bw < 8:
    print("-> REJECT (size)")
else:
    print("-> ACCEPT table %d,%d - %d,%d" % (minX*STEP, minY*STEP, maxX*STEP, maxY*STEP))
