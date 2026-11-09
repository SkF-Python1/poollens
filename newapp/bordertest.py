#!/usr/bin/env python3
"""Test: bbox-of-largest-component vs coverage-profile for table extents.

Balls resting on a cushion remove felt pixels from that column/row, which
shrinks a naive bounding box. A coverage profile is immune because a ball only
affects a small fraction of any column."""
import sys
from PIL import Image

im = Image.open(sys.argv[1]).convert('RGB')
W, H = im.size
px = im.load()
STEP = 3
gw, gh = W // STEP, H // STEP

def felt(r, g, b):
    return b > 100 and g > 90 and b > r + 40 and g > r + 20

m = bytearray(gw * gh)
for gy in range(gh):
    for gx in range(gw):
        if felt(*px[gx * STEP, gy * STEP]):
            m[gy * gw + gx] = 1

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

xs = [p % gw for p in best]
ys = [p // gw for p in best]
print("A) bbox of largest component : x %d..%d  y %d..%d"
      % (min(xs)*STEP, max(xs)*STEP, min(ys)*STEP, max(ys)*STEP))

# B) coverage profile over the component
colC = [0]*gw; rowC = [0]*gh
for p in best:
    cy, cx = divmod(p, gw)
    colC[cx] += 1; rowC[cy] += 1

for frac in (0.10, 0.20, 0.25, 0.35, 0.50):
    cf = max(colC)*frac; rf = max(rowC)*frac
    cl = next((i for i in range(gw) if colC[i] > cf), -1)
    cr = next((i for i in range(gw-1, -1, -1) if colC[i] > cf), -1)
    ct = next((i for i in range(gh) if rowC[i] > rf), -1)
    cb = next((i for i in range(gh-1, -1, -1) if rowC[i] > rf), -1)
    print("B) coverage >%.0f%% of max        : x %d..%d  y %d..%d"
          % (frac*100, cl*STEP, cr*STEP, ct*STEP, cb*STEP))
