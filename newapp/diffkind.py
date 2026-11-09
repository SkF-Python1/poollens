#!/usr/bin/env python3
"""Classify a difference image: thin lines (our overlay) vs blobs (game content)."""
import sys
from PIL import Image, ImageChops

a = Image.open(sys.argv[1]).convert('RGB')
b = Image.open(sys.argv[2]).convert('RGB')
d = ImageChops.difference(a, b).convert('L')
W, H = d.size
px = d.load()

step = 3
gw, gh = W // step, H // step
mask = [[px[i * step, j * step] > 18 for i in range(gw)] for j in range(gh)]

seen = [[False] * gw for _ in range(gh)]
blobs = []
for j in range(gh):
    for i in range(gw):
        if not mask[j][i] or seen[j][i]:
            continue
        stack = [(i, j)]
        seen[j][i] = True
        pts = []
        while stack:
            x, y = stack.pop()
            pts.append((x, y))
            for dx, dy in ((1,0),(-1,0),(0,1),(0,-1)):
                nx, ny = x+dx, y+dy
                if 0 <= nx < gw and 0 <= ny < gh and mask[ny][nx] and not seen[ny][nx]:
                    seen[ny][nx] = True
                    stack.append((nx, ny))
        blobs.append(pts)

blobs.sort(key=len, reverse=True)
print("changed blobs: %d" % len(blobs))
for pts in blobs[:8]:
    xs = [p[0] * step for p in pts]
    ys = [p[1] * step for p in pts]
    w = max(xs) - min(xs) + step
    h = max(ys) - min(ys) + step
    fill = len(pts) / max(1, (w // step) * (h // step))
    kind = "LINE/overlay" if (fill < 0.22 or max(w, h) / max(1, min(w, h)) > 6) else "BLOB/game"
    print("  %4d px  bbox %4d,%4d %4dx%-4d fill=%.2f  %s"
          % (len(pts), min(xs), min(ys), w, h, fill, kind))
