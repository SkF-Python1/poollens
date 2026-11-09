#!/usr/bin/env python3
"""Dump felt-coverage profile per column/row so edges can be located exactly."""
import sys
from PIL import Image

im = Image.open(sys.argv[1]).convert('RGB')
W, H = im.size
px = im.load()

def felt(r, g, b):
    return b > 100 and g > 90 and b > r + 40 and g > r + 20

print("size %dx%d" % (W, H))

colC = [0]*W
for x in range(W):
    c = 0
    for y in range(0, H, 3):
        if felt(*px[x, y]):
            c += 1
    colC[x] = c

maxc = max(colC) or 1
print("\ncolumn coverage (every 8 px from 1860 to 1930):")
for x in range(1860, min(1930, W), 2):
    print("  x=%d  %5.1f%%  %s" % (x, 100.0*colC[x]/maxc, '#'*int(40*colC[x]/maxc)))

print("\nleft edge 380..420:")
for x in range(380, 420, 2):
    print("  x=%d  %5.1f%%  %s" % (x, 100.0*colC[x]/maxc, '#'*int(40*colC[x]/maxc)))

rowC = [0]*H
for y in range(H):
    c = 0
    for x in range(0, W, 3):
        if felt(*px[x, y]):
            c += 1
    rowC[y] = c
maxr = max(rowC) or 1
print("\ntop edge 200..240:")
for y in range(200, 240, 2):
    print("  y=%d  %5.1f%%  %s" % (y, 100.0*rowC[y]/maxr, '#'*int(40*rowC[y]/maxr)))
print("\nbottom edge 990..1030:")
for y in range(990, min(1030, H), 2):
    print("  y=%d  %5.1f%%  %s" % (y, 100.0*rowC[y]/maxr, '#'*int(40*rowC[y]/maxr)))
