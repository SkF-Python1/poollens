#!/usr/bin/env python3
"""Report whether two screenshots differ, ignoring tiny noise."""
import sys
from PIL import Image, ImageChops

a = Image.open(sys.argv[1]).convert('RGB')
b = Image.open(sys.argv[2]).convert('RGB')
if a.size != b.size:
    print("size mismatch")
    sys.exit(0)

d = ImageChops.difference(a, b)
bbox = d.getbbox()
px = d.load()
W, H = d.size
changed = 0
for y in range(0, H, 4):
    for x in range(0, W, 4):
        r, g, bl = px[x, y]
        if r + g + bl > 40:
            changed += 1
total = (W // 4) * (H // 4)
print("changed %d / %d samples (%.2f%%)" % (changed, total, 100.0 * changed / total))
print("diff bbox:", bbox)
