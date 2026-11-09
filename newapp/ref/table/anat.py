#!/usr/bin/env python3
"""Analyse the official 8BP table sprite: find the felt rect and the six
pockets, so the detector can locate a real table by its pockets instead of
guessing from colour."""
import sys
from PIL import Image

path = sys.argv[1] if len(sys.argv) > 1 else 'singletableLondon-ipadhd.png'
im = Image.open(path).convert('RGBA')
W, H = im.size
px = im.load()
print("sprite %dx%d  aspect %.3f" % (W, H, W / H))

def felt(r, g, b):
    return b > 90 and g > 80 and b > r + 35 and g > r + 15

def dark(r, g, b):
    return max(r, g, b) < 46

# Felt bounding box
minx, miny, maxx, maxy = W, H, -1, -1
for y in range(H):
    for x in range(W):
        r, g, b, a = px[x, y]
        if a > 128 and felt(r, g, b):
            minx = min(minx, x); maxx = max(maxx, x)
            miny = min(miny, y); maxy = max(maxy, y)
print("felt bbox x %d..%d  y %d..%d   (%dx%d)"
      % (minx, maxx, miny, maxy, maxx - minx, maxy - miny))

# Pockets: dark blobs inside the felt bbox
mask = [[False] * W for _ in range(H)]
for y in range(miny, maxy + 1):
    for x in range(minx, maxx + 1):
        r, g, b, a = px[x, y]
        if a > 128 and dark(r, g, b):
            mask[y][x] = True

seen = [[False] * W for _ in range(H)]
pockets = []
sys.setrecursionlimit(100000)
for y in range(miny, maxy + 1):
    for x in range(minx, maxx + 1):
        if not mask[y][x] or seen[y][x]:
            continue
        stack = [(x, y)]
        seen[y][x] = True
        pts = []
        while stack:
            cx, cy = stack.pop()
            pts.append((cx, cy))
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                nx, ny = cx + dx, cy + dy
                if (minx <= nx <= maxx and miny <= ny <= maxy
                        and mask[ny][nx] and not seen[ny][nx]):
                    seen[ny][nx] = True
                    stack.append((nx, ny))
        if len(pts) < 200:
            continue
        xs = [p[0] for p in pts]
        ys = [p[1] for p in pts]
        bw = max(xs) - min(xs) + 1
        bh = max(ys) - min(ys) + 1
        fill = len(pts) / max(1, bw * bh)
        # a pocket is roughly round/square and fairly solid
        if fill < 0.5 or not (0.5 < bw / max(1, bh) < 2.0):
            continue
        pockets.append(((min(xs) + max(xs)) // 2, (min(ys) + max(ys)) // 2,
                        bw, bh, len(pts)))

pockets.sort(key=lambda t: (t[1] // 100, t[0]))
print("\ncandidate pockets: %d" % len(pockets))
for cx, cy, bw, bh, n in pockets:
    fx = (cx - minx) / max(1, maxx - minx)
    fy = (cy - miny) / max(1, maxy - miny)
    print("  centre (%4d,%4d) size %dx%d n=%5d   rel (%.3f, %.3f)"
          % (cx, cy, bw, bh, n, fx, fy))
