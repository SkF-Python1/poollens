#!/usr/bin/env python3
"""Crop view of the overlay strokes after inverting the 0.79 alpha blend.

Each cell picks the overlay stroke whose implied background is most plausible,
so the map shows what the overlay (not the game) painted.

Usage: blendcrop.py SHOT.png x0 y0 x1 y1 [block]

Legend
  T = target marker (magenta 255,0,255)
  G = ghost marker  (lime 0,255,0)
  U = cue marker    (blue 0,0,255)
  E = amber endpoint(cyan 0,255,255)
  A = amber line    (255,192,32)
  W = white line    (255,255,255)
  P = pink line     (255,20,147)   <- cue track in DEBUG_VIS
  B = bank blue     (127,212,255)
  D = cyan dots     (102,224,255)
  R = border green  (61,220,151)
  S = strike grey   (153,153,153)
  . = background (game)
"""
import sys
import numpy as np
from PIL import Image, ImageFile

ImageFile.LOAD_TRUNCATED_IMAGES = True

STROKES = [
    ("T", (255, 0, 255)),
    ("G", (0, 255, 0)),
    ("U", (0, 0, 255)),
    ("E", (0, 255, 255)),
    ("A", (255, 192, 32)),
    ("W", (255, 255, 255)),
    ("P", (255, 20, 147)),
    ("D", (102, 224, 255)),
    ("B", (127, 212, 255)),
    ("R", (61, 220, 151)),
    ("S", (153, 153, 153)),
]

path = sys.argv[1]
x0, y0, x1, y1 = (int(v) for v in sys.argv[2:6])
block = int(sys.argv[6]) if len(sys.argv) > 6 else 8
alpha = 0.79
beta = 1.0 - alpha

im = Image.open(path).convert("RGB")
P = np.asarray(im).astype(np.float32)

xs = np.arange(x0, min(x1, P.shape[1]), block)
ys = np.arange(y0, min(y1, P.shape[0]), block)

print("%s  crop x%d..%d y%d..%d block=%d" % (path, x0, y0, x1, y1, block))
hdr = "      " + "".join(str((x // 100) % 10) for x in xs)
print(hdr)
for y in ys:
    row = []
    for x in xs:
        patch = P[y:y + block, x:x + block].reshape(-1, 3)
        bestch, besterr = ".", 1e9
        for ch, C in STROKES:
            Cv = np.array(C, np.float32)
            BG = (patch - alpha * Cv) / beta
            lo = BG.min(axis=1)
            hi = BG.max(axis=1)
            err = np.maximum(0, -lo - 20) + np.maximum(0, hi - 275)
            e = err.min()
            if e < besterr:
                besterr = e
                bestch = ch
        if besterr > 12:
            bestch = "."
        row.append(bestch)
    print("%5d " % y + "".join(row))
