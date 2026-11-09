#!/usr/bin/env python3
"""Probe small windows around predicted anchor points and print the most
saturated ("impossible") colour in each, so drawn debug markers can be
verified against the logged coordinates.

Usage: probepts.py SHOT.png "name:x:y" ["name:x:y" ...]
"""
import sys
import numpy as np
from PIL import Image, ImageFile

ImageFile.LOAD_TRUNCATED_IMAGES = True

path = sys.argv[1]
pts = []
for spec in sys.argv[2:]:
    n, x, y = spec.rsplit(":", 2)
    pts.append((n, int(x), int(y)))

im = Image.open(path).convert("RGB")
a = np.asarray(im).astype(np.int16)
H, W = a.shape[:2]
print("%s  %dx%d" % (path, W, H))

for name, x, y in pts:
    r = 14
    win = a[max(0, y - r):y + r + 1, max(0, x - r):x + r + 1].reshape(-1, 3)
    sat = win.max(axis=1) - win.min(axis=1)
    i = int(np.argmax(sat))
    print(" %-10s (%4d,%4d)  centre=%s  maxsat_in_%dx%d=%s (sat=%d)"
          % (name, x, y, tuple(a[y, x]), 2 * r + 1, 2 * r + 1,
             tuple(win[i]), int(sat[i])))
