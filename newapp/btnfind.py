#!/usr/bin/env python3
"""Locate dialog buttons by finding rows of near-uniform bright/coloured pixels."""
import sys
from PIL import Image

im = Image.open(sys.argv[1]).convert('RGB')
W, H = im.size
px = im.load()

# Find large horizontal bands of a saturated accent colour (buttons are usually
# a solid fill spanning a wide run in the dialog).
rows = {}
for y in range(0, H, 4):
    run = 0
    best = 0
    start = 0
    bs = 0
    for x in range(0, W, 4):
        r, g, b = px[x, y]
        mx, mn = max(r, g, b), min(r, g, b)
        # solid button fill: bright and either saturated or very light
        ok = (mx > 90 and mx - mn > 45) or (mx > 200 and mx - mn < 30)
        if ok:
            if run == 0:
                start = x
            run += 1
            if run > best:
                best = run
                bs = start
        else:
            run = 0
    if best >= 25:
        rows[y] = (bs, bs + best * 4, best * 4)

# Group consecutive rows with similar spans.
groups = []
for y in sorted(rows):
    s, e, w = rows[y]
    if groups and abs(groups[-1][1] - y) <= 8 and abs(groups[-1][2] - s) < 90:
        groups[-1][1] = y
        groups[-1][3] = e
    else:
        groups.append([y, y, s, e])

print("candidate button bands (y0,y1,x0,x1):")
for g in groups:
    y0, y1, x0, x1 = g
    if y1 - y0 >= 8:
        cy = (y0 + y1) // 2
        cx = (x0 + x1) // 2
        print("  y %4d..%4d  x %4d..%4d  centre=(%d,%d)  rgb=%s"
              % (y0, y1, x0, x1, cx, cy, px[cx, cy]))
