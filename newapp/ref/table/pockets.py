#!/usr/bin/env python3
"""Probe the corners of the official table sprite for pocket colour/size."""
import sys
from PIL import Image

path = sys.argv[1] if len(sys.argv) > 1 else 'singletableLondon-ipadhd.png'
im = Image.open(path).convert('RGBA')
W, H = im.size
px = im.load()

def felt(r, g, b):
    return b > 90 and g > 80 and b > r + 35 and g > r + 15

# felt bbox
minx, miny, maxx, maxy = W, H, -1, -1
for y in range(0, H, 1):
    for x in range(0, W, 1):
        r, g, b, a = px[x, y]
        if a > 128 and felt(r, g, b):
            minx = min(minx, x); maxx = max(maxx, x)
            miny = min(miny, y); maxy = max(maxy, y)

print("felt bbox %d,%d - %d,%d" % (minx, miny, maxx, maxy))

# Print min/max luminance inside each corner region (100x100)
def probe(name, x0, y0, x1, y1):
    lo = 999; hi = -1; darkn = 0; tot = 0
    for y in range(y0, y1):
        for x in range(x0, x1):
            r, g, b, a = px[x, y]
            if a < 100:
                continue
            l = (r + g + b) / 3
            lo = min(lo, l); hi = max(hi, l)
            if l < 60:
                darkn += 1
            tot += 1
    print("  %-10s lum %.0f..%.0f  dark(<60)=%d/%d" % (name, lo, hi, darkn, tot))

print("\ncorner probes:")
probe("top-left", minx, miny, minx + 120, miny + 120)
probe("top-right", maxx - 120, miny, maxx, miny + 120)
probe("bot-left", minx, maxy - 120, minx + 120, maxy)
probe("bot-right", maxx - 120, maxy - 120, maxx, maxy)
probe("mid-left", minx, (miny + maxy) // 2 - 60, minx + 120, (miny + maxy) // 2 + 60)
probe("mid-right", maxx - 120, (miny + maxy) // 2 - 60, maxx, (miny + maxy) // 2 + 60)
probe("centre", W // 2 - 60, H // 2 - 60, W // 2 + 60, H // 2 + 60)

# Specific pixel dumps at the corners
print("\ncorner pixel samples (step 8):")
for tag, (ax, ay) in (("TL", (minx, miny)), ("TR", (maxx - 40, miny)),
                      ("BL", (minx, maxy - 40)), ("BR", (maxx - 40, maxy - 40))):
    row = []
    for d in range(0, 44, 8):
        r, g, b, a = px[min(W-1, ax + d), min(H-1, ay + d)]
        row.append("%3d,%3d,%3d" % (r, g, b))
    print("  %s %s" % (tag, ' | '.join(row)))
