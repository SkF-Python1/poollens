#!/usr/bin/env python3
"""Print pixel colours along a segment, to see what the overlay actually drew.

Usage:
  probeline.py SHOT.png x1 y1 x2 y2 [steps]
"""
import sys
import numpy as np
from PIL import Image, ImageFile

ImageFile.LOAD_TRUNCATED_IMAGES = True

path = sys.argv[1]
x1, y1, x2, y2 = (float(v) for v in sys.argv[2:6])
steps = int(sys.argv[6]) if len(sys.argv) > 6 else 30

im = Image.open(path).convert("RGB")
a = np.asarray(im).astype(np.int16)
H, W = a.shape[:2]
print("%s  %dx%d" % (path, W, H))
print("from (%.0f,%.0f) to (%.0f,%.0f)" % (x1, y1, x2, y2))
for i in range(steps + 1):
    t = i / steps
    x = int(round(x1 + (x2 - x1) * t))
    y = int(round(y1 + (y2 - y1) * t))
    tag = ""
    if 0 <= x < W and 0 <= y < H:
        n = a[max(0, y - 2):y + 3, max(0, x - 2):x + 3].reshape(-1, 3)
        # pick the pixel furthest from felt-ish teal among the 5x5 window
        best = None
        for p in n:
            r, g, bl = int(p[0]), int(p[1]), int(p[2])
            sat = max(r, g, bl) - min(r, g, bl)
            if best is None or sat > best[3]:
                best = (r, g, bl, sat)
        tag = "centre=%s best5x5=%s" % (tuple(a[y, x]), best[:3])
    else:
        tag = "OUT OF BOUNDS"
    print("  t=%.2f (%4d,%4d) %s" % (t, x, y, tag))
