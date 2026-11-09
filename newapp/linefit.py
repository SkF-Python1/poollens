#!/usr/bin/env python3
"""Recover the drawn extent of each overlay line from a screenshot, by
inverting the 0.79 alpha blend, then report the extreme endpoints.

Usage: linefit.py SHOT.png
"""
import sys
import numpy as np
from PIL import Image, ImageFile

ImageFile.LOAD_TRUNCATED_IMAGES = True

ALPHA, BETA = 0.79, 0.21

LINES = {
    "AMBER": (255, 192, 32),
    "PINK ": (255, 20, 147),      # cue track in DEBUG_VIS
    "WHITE": (255, 255, 255),
    "CYAND": (102, 224, 255),     # deflection dots
    "BANK ": (127, 212, 255),
}

path = sys.argv[1]
im = Image.open(path).convert("RGB")
P = np.asarray(im).astype(np.float32)
H, W = P.shape[:2]
print("%s %dx%d" % (path, W, H))

for name, C in LINES.items():
    Cv = np.array(C, np.float32)
    BG = (P - ALPHA * Cv) / BETA
    err = (np.maximum(0, -BG.min(axis=2) - 18)
           + np.maximum(0, BG.max(axis=2) - 273))
    m = err <= 6
    n = int(m.sum())
    if n < 30:
        print(" %s : %d px" % (name, n))
        continue
    ys, xs = np.nonzero(m)
    pts = np.stack([xs - xs.mean(), ys - ys.mean()], 1).astype(float)
    ev, evec = np.linalg.eigh(pts.T @ pts / len(pts))
    v = evec[:, -1]
    t = pts @ v
    i0, i1 = t.argmin(), t.argmax()
    ang = np.degrees(np.arctan2(v[1], v[0])) % 180
    print(" %s : %6d px  ends (%4d,%4d)-(%4d,%4d)  len=%4.0f  axis=%.1fdeg"
          % (name, n, xs[i0], ys[i0], xs[i1], ys[i1],
             np.hypot(xs[i1] - xs[i0], ys[i1] - ys[i0]), ang))
