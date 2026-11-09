#!/usr/bin/env python3
"""Measure HSV of genuine felt vs rails so the felt test can be widened."""
import sys, colorsys
from PIL import Image

im = Image.open(sys.argv[1]).convert('RGB')
W, H = im.size
px = im.load()

# Sample a grid across the frame and bucket by hue.
import collections
hues = collections.Counter()
sats = []
vals = []
for y in range(200, 1020, 6):
    for x in range(200, 2100, 6):
        r, g, b = px[x, y]
        h, s, v = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
        hues[int(h * 360) // 10 * 10] += 1

print("hue histogram (deg bucket: count), top 12:")
for k, c in hues.most_common(12):
    print("   %3d-%3d : %d" % (k, k + 9, c))

def probe(x, y):
    r, g, b = px[x, y]
    h, s, v = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
    print("   (%4d,%4d) rgb=%-16s h=%3d s=%.2f v=%.2f"
          % (x, y, str((r, g, b)), int(h * 360), s, v))

print("\ncentre felt samples:")
for y in (300, 500, 700, 900):
    for x in (700, 1200, 1700):
        probe(x, y)

print("\nold is_felt acceptance across the felt area:")
def old_felt(r, g, b):
    return b > 100 and g > 90 and b > r + 40 and g > r + 20
ok = tot = 0
for y in range(240, 1000, 6):
    for x in range(500, 1900, 6):
        r, g, b = px[x, y]
        tot += 1
        if old_felt(r, g, b):
            ok += 1
print("   %d / %d accepted (%.0f%%)" % (ok, tot, 100.0 * ok / max(1, tot)))

print("\nhue-based acceptance (150<h<250, s>0.35, v>0.18):")
def new_felt(r, g, b):
    h, s, v = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
    deg = h * 360
    return 150 < deg < 250 and s > 0.35 and v > 0.18
ok = tot = 0
for y in range(240, 1000, 6):
    for x in range(500, 1900, 6):
        r, g, b = px[x, y]
        tot += 1
        if new_felt(r, g, b):
            ok += 1
print("   %d / %d accepted (%.0f%%)" % (ok, tot, 100.0 * ok / max(1, tot)))
