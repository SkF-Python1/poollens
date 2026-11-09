#!/usr/bin/env python3
"""Blob analysis on the felt region to find real ball centres."""
import sys
from PIL import Image

im = Image.open(sys.argv[1]).convert('RGB')
W, H = im.size
px = im.load()

def is_felt(r, g, b):
    return b > 100 and g > 90 and b > r + 40 and g > r + 20

# Use the app's reported table rectangle when supplied, else derive one.
if len(sys.argv) > 5:
    minx, miny, maxx, maxy = map(int, sys.argv[2:6])
    print("using supplied table %d,%d - %d,%d" % (minx, miny, maxx, maxy))
else:
    minx, miny, maxx, maxy = W, H, -1, -1
    for y in range(0, H, 3):
        for x in range(0, W, 3):
            r, g, b = px[x, y]
            if is_felt(r, g, b):
                minx = min(minx, x); maxx = max(maxx, x)
                miny = min(miny, y); maxy = max(maxy, y)
    print("table %d,%d - %d,%d" % (minx, miny, maxx, maxy))

# Scan non-felt blobs inside the table.
step = 3
gw, gh = W // step, H // step
mask = [[False] * gw for _ in range(gh)]
for gy in range(gh):
    y = gy * step
    if not (miny <= y <= maxy):
        continue
    for gx in range(gw):
        x = gx * step
        if not (minx <= x <= maxx):
            continue
        r, g, b = px[x, y]
        if is_felt(r, g, b):
            continue
        mx, mn = max(r, g, b), min(r, g, b)
        mask[gy][gx] = True

seen = [[False] * gw for _ in range(gh)]
blobs = []
for gy in range(gh):
    for gx in range(gw):
        if not mask[gy][gx] or seen[gy][gx]:
            continue
        stack = [(gx, gy)]
        seen[gy][gx] = True
        pts = []
        while stack:
            cx, cy = stack.pop()
            pts.append((cx, cy))
            for dx, dy in ((1,0),(-1,0),(0,1),(0,-1)):
                nx, ny = cx+dx, cy+dy
                if 0 <= nx < gw and 0 <= ny < gh and mask[ny][nx] and not seen[ny][nx]:
                    seen[ny][nx] = True
                    stack.append((nx, ny))
        if len(pts) < 6:
            continue
        xs = [p[0] for p in pts]; ys = [p[1] for p in pts]
        bw = max(xs)-min(xs)+1; bh = max(ys)-min(ys)+1
        asp = max(bw, bh) / max(1, min(bw, bh))
        fill = len(pts) / max(1, bw*bh)
        cx = (min(xs)+max(xs))//2 * step
        cy = (min(ys)+max(ys))//2 * step
        rad = max(bw, bh) * step // 2
        if asp <= 1.7 and fill >= 0.5 and 10 <= rad <= 60:
            r, g, b = px[cx, cy]
            blobs.append((cx, cy, rad, len(pts), (r,g,b)))

blobs.sort(key=lambda t: -t[1])
print("candidate balls: %d" % len(blobs))
for cx, cy, rad, n, col in blobs:
    print("  (%4d,%4d) r=%2d n=%4d rgb=%s" % (cx, cy, rad, n, col))
