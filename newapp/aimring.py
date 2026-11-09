#!/usr/bin/env python3
"""Radial probe around the cue ball to find the game's aim guideline."""
import sys
from PIL import Image

im = Image.open(sys.argv[1]).convert('RGB')
W, H = im.size
px = im.load()

cx, cy = int(sys.argv[2]), int(sys.argv[3])
print("image %dx%d  cue=%d,%d" % (W, H, cx, cy))

# Sample a ring of radii and report the brightest near-neutral pixels.
for rad in (40, 80, 120, 160, 200, 260, 320):
    found = []
    for deg in range(0, 360, 5):
        import math
        x = int(cx + rad * math.cos(math.radians(deg)))
        y = int(cy + rad * math.sin(math.radians(deg)))
        if not (0 <= x < W and 0 <= y < H):
            continue
        r, g, b = px[x, y]
        mx, mn = max(r, g, b), min(r, g, b)
        if mx > 150 and mx - mn < 70:
            found.append((deg, mx, mn))
    print("r=%3d bright:%2d %s" % (rad, len(found),
          ' '.join("%d(%d/%d)" % (d, a, b) for d, a, b in found[:14])))
