#!/usr/bin/env python3
"""Hue-based search for PoolLens overlay strokes in a screenshot.

The overlay window is composited at alpha 0.79, so the "pure" debug colours
are blended with the game beneath: magenta (255,0,255) arrives as roughly
0.79*c + 0.21*bg. Match by hue/dominance instead of exact RGB.

Usage: huesearch.py SHOT.png [...]
"""
import sys
import math
import numpy as np
from PIL import Image

ImageFile = None


def load(path):
    from PIL import ImageFile as IF
    IF.LOAD_TRUNCATED_IMAGES = True
    return np.asarray(Image.open(path).convert("RGB")).astype(np.int16)


def bbox(mask, name, minpx=20):
    ys, xs = np.nonzero(mask)
    n = len(xs)
    if n < minpx:
        print("  %-10s: %d px" % (name, n))
        return None
    print("  %-10s: %5d px  x %d..%d  y %d..%d  centroid (%.0f,%.0f)"
          % (name, n, xs.min(), xs.max(), ys.min(), ys.max(), xs.mean(), ys.mean()))
    return xs, ys


def report(path):
    print("=" * 74)
    im = Image.open(path).convert("RGB")
    print("%s  %s" % (path, im.size))
    a = np.asarray(im).astype(np.int16)
    R, G, B = a[:, :, 0], a[:, :, 1], a[:, :, 2]
    MX = np.max(a, axis=2)
    MN = np.min(a, axis=2)

    # MAGENTA: R and B high, G much lower.
    mag = (R > 110) & (B > 110) & (G < MX * 0.55)
    # LIME: G high, R/B low.
    lime = (G > 120) & (R < MX * 0.72) & (B < MX * 0.72)
    # BLUE: B high, R/G low.
    blue = (B > 130) & (R < MX * 0.6) & (G < MX * 0.7)
    # AMBER: R high, G mid, B low.
    amber = (R > 150) & (G > 90) & (G < R * 0.9) & (B < R * 0.45)
    # CYAN: G and B high, R low.
    cyan = (G > 150) & (B > 150) & (R < MX * 0.7)

    bbox(mag, "magenta")
    bbox(lime, "lime")
    bbox(blue, "blue")
    bbox(amber, "amber")
    bbox(cyan, "cyan")


for p in sys.argv[1:]:
    report(p)
