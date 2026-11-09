#!/usr/bin/env python3
"""Pockets in the official sprite are fully transparent cut-outs inside the
felt. Find them and print the six-pocket layout normalised to the felt rect."""
import sys
from PIL import Image

path = sys.argv[1] if len(sys.argv) > 1 else 'singletableLondon-ipadhd.png'
im = Image.open(path).convert('RGBA')
W, H = im.size
px = im.load()

def felt(r, g, b):
    return b > 90 and g > 80 and b > r + 35 and g > r + 15

# Felt bbox (opaque felt only)
minx, miny, maxx, maxy = W, H, -1, -1
for y in range(H):
    for x in range(W):
        r, g, b, a = px[x, y]
        if a > 128 and felt(r, g, b):
            minx = min(minx, x); maxx = max(maxx, x)
            miny = min(miny, y); maxy = max(maxy, y)
print("felt bbox %d,%d - %d,%d  (%dx%d)" % (minx, miny, maxx, maxy,
                                             maxx-minx+1, maxy-miny+1))

# Transparent blobs fully inside the felt bbox.
inv = [[False]*W for _ in range(H)]
for y in range(miny, maxy+1):
    for x in range(minx, maxx+1):
        inv[y][x] = px[x, y][3] < 60

seen = [[False]*W for _ in range(H)]
holes = []
for y in range(miny, maxy+1):
    for x in range(minx, maxx+1):
        if not inv[y][x] or seen[y][x]:
            continue
        stack = [(x, y)]; seen[y][x] = True; pts = []
        touches = False
        while stack:
            cx, cy = stack.pop()
            pts.append((cx, cy))
            if cx <= minx+1 or cx >= maxx-1 or cy <= miny+1 or cy >= maxy-1:
                touches = True
            for nx, ny in ((cx-1,cy),(cx+1,cy),(cx,cy-1),(cx,cy+1)):
                if minx <= nx <= maxx and miny <= ny <= maxy \
                        and inv[ny][nx] and not seen[ny][nx]:
                    seen[ny][nx] = True; stack.append((nx, ny))
        if touches or len(pts) < 100:
            continue
        xs = [p[0] for p in pts]; ys = [p[1] for p in pts]
        holes.append((sum(xs)//len(xs), sum(ys)//len(ys),
                      max(xs)-min(xs)+1, max(ys)-min(ys)+1, len(pts)))

holes.sort(key=lambda t: -t[4])
print("\nholes: %d" % len(holes))
for cx, cy, bw, bh, n in holes:
    print("  centre (%4d,%4d) %3dx%-3d n=%5d  rel(%.3f, %.3f)"
          % (cx, cy, bw, bh, n,
             (cx-minx)/(maxx-minx), (cy-miny)/(maxy-miny)))
