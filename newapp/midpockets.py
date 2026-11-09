#!/usr/bin/env python3
"""Locate middle pockets (left/right edge centres) to pin the true felt rect."""
import sys
from PIL import Image

im = Image.open(sys.argv[1]).convert('RGB')
W, H = im.size
px = im.load()
L, T, R, B = map(int, sys.argv[2:6])

def dark(x, y):
    r, g, b = px[x, y]
    return max(r, g, b) < 70

# Scan a horizontal strip at the vertical centre for a dark run at the sides.
midY = (T + B) // 2
print("scanning row y=%d from x=%d..%d" % (midY, L - 60, L + 200))
run = 0; edges = []
for x in range(max(0, L - 60), min(W, L + 220)):
    if dark(x, midY):
        run += 1
    else:
        if run >= 5:
            edges.append((x - run, x - 1))
        run = 0
print("  dark runs on left:", edges[:6])

run = 0; edges = []
for x in range(min(W - 1, R + 60), max(0, R - 220), -1):
    if dark(x, midY):
        run += 1
    else:
        if run >= 5:
            edges.append((x + 1, x + run))
        run = 0
print("  dark runs on right:", edges[:6])

# Vertical scan at horizontal centre for top/bottom middle pockets.
midX = (L + R) // 2
run = 0; edges = []
for y in range(max(0, T - 60), min(H, T + 160)):
    if dark(midX, y):
        run += 1
    else:
        if run >= 5:
            edges.append((y - run, y - 1))
        run = 0
print("  dark runs on top (x=%d):" % midX, edges[:6])

run = 0; edges = []
for y in range(min(H - 1, B + 60), max(0, B - 160), -1):
    if dark(midX, y):
        run += 1
    else:
        if run >= 5:
            edges.append((y + 1, y + run))
        run = 0
print("  dark runs on bottom:", edges[:6])
