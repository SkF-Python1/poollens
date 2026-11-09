#!/usr/bin/env python3
"""Detect thin bright ridges (the aim guideline) over the felt.

A guideline pixel is brighter than the felt on BOTH perpendicular sides.
That rejects solid balls, whose interior is uniformly bright.
"""
import sys, math
from PIL import Image

im = Image.open(sys.argv[1]).convert('RGB')
W, H = im.size
px = im.load()

def lum(p):
    return 0.299 * p[0] + 0.587 * p[1] + 0.114 * p[2]

def feltish(p):
    r, g, b = p
    return b > 90 and g > 80 and b > r + 35 and g > r + 15

# Collect ridge points.
ridges = set()
step = 2
for y in range(0, H, step):
    for x in range(6, W - 6, step):
        p = px[x, y]
        if feltish(p):
            continue
        l = lum(p)
        # must be much brighter than the median felt around it
        if l < 130:
            continue
        lx = lum(px[x - 5, y]); rx = lum(px[x + 5, y])
        uy = lum(px[x, y - 5]); dy = lum(px[x, y + 5])
        across = (l - (lx + rx) / 2) > 28
        down = (l - (uy + dy) / 2) > 28
        if across or down:
            ridges.add((x, y))

print("ridge pixels: %d" % len(ridges))

# Hough-style angle histogram relative to the table centre.
tf = {}
for (x, y) in ridges:
    for other in list(ridges)[:0]:
        pass

# Instead: cluster ridge pixels by simple grid and print a coarse map.
cols, rows = 96, 40
grid = [[0] * cols for _ in range(rows)]
for x, y in ridges:
    grid[y * rows // H][x * cols // W] += 1
mx = max(max(r) for r in grid) or 1
print("ridge density map:")
for j, row in enumerate(grid):
    print("%3d %s" % (j * H // rows,
          ''.join('#' if v > mx*0.34 else ('+' if v > mx*0.12 else ('.' if v else ' ')) for v in row)))

# Angle histogram using pairs from a coarse sample of ridge points.
pts = list(ridges)
import random
random.seed(1)
sample = pts if len(pts) < 900 else random.sample(pts, 900)
hist = [0] * 180
for i in range(len(sample)):
    x1, y1 = sample[i]
    for j in range(i + 1, len(sample)):
        x2, y2 = sample[j]
        d = math.hypot(x2 - x1, y2 - y1)
        if d < 60 or d > 500:
            continue
        a = int(math.degrees(math.atan2(y2 - y1, x2 - x1))) % 180
        hist[a] += 1
peak = sorted(range(180), key=lambda k: -hist[k])[:8]
print("strongest line angles (deg mod 180):")
for k in peak:
    print("  %3d : %d" % (k, hist[k]))
