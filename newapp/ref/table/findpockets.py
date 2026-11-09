#!/usr/bin/env python3
"""Find the six pockets as black blobs on the official table sprite."""
import sys
from PIL import Image

path = sys.argv[1] if len(sys.argv) > 1 else 'singletableLondon-ipadhd.png'
im = Image.open(path).convert('RGBA')
W, H = im.size
px = im.load()

def is_black(x, y):
    r, g, b, a = px[x, y]
    return a > 100 and r < 40 and g < 40 and b < 40

seen = bytearray(W * H)
blobs = []
for y in range(H):
    for x in range(W):
        i = y * W + x
        if seen[i] or not is_black(x, y):
            continue
        stack = [i]
        seen[i] = 1
        pts = []
        while stack:
            cur = stack.pop()
            pts.append(cur)
            cy, cx = divmod(cur, W)
            for nx, ny in ((cx-1,cy),(cx+1,cy),(cx,cy-1),(cx,cy+1)):
                if 0 <= nx < W and 0 <= ny < H:
                    ni = ny * W + nx
                    if not seen[ni] and is_black(nx, ny):
                        seen[ni] = 1
                        stack.append(ni)
        if len(pts) > 150:
            blobs.append(pts)

blobs.sort(key=len, reverse=True)
print("sprite %dx%d  black blobs: %d" % (W, H, len(blobs)))
info = []
for pts in blobs[:12]:
    xs = [p % W for p in pts]
    ys = [p // W for p in pts]
    x0, x1, y0, y1 = min(xs), max(xs), min(ys), max(ys)
    bw, bh = x1-x0+1, y1-y0+1
    fill = len(pts)/max(1, bw*bh)
    cx, cy = (x0+x1)//2, (y0+y1)//2
    info.append((cx, cy, bw, bh, len(pts), fill))
    print("  centre (%4d,%4d)  %3dx%-3d  n=%5d fill=%.2f" % (cx, cy, bw, bh, len(pts), fill))

# Normalised pocket layout relative to sprite extent
if len(info) >= 6:
    print("\nnormalised (x/W, y/H):")
    for cx, cy, bw, bh, n, fill in info[:8]:
        print("  %.3f, %.3f" % (cx / W, cy / H))
