#!/usr/bin/env python3
"""Full-image map showing ONLY overlay strokes, recovered by inverting the
0.79 alpha blend. The game underneath is discarded, so the overlay layout
is unambiguous.

Usage: overlaymap.py SHOT.png [block]
"""
import sys
import numpy as np
from PIL import Image, ImageFile

ImageFile.LOAD_TRUNCATED_IMAGES = True

STROKES = [
    ("T", (255, 0, 255)),      # target magenta
    ("G", (0, 255, 0)),        # ghost lime
    ("U", (0, 0, 255)),        # cue blue
    ("E", (0, 255, 255)),      # amber-end cyan
    ("A", (255, 192, 32)),     # amber line
    ("P", (255, 20, 147)),     # pink cue track
    ("D", (102, 224, 255)),    # cyan deflection dots
    ("Y", (255, 255, 0)),      # yellow ghost ring
    ("R", (61, 220, 151)),     # border green
    ("B", (127, 212, 255)),    # bank blue
    ("S", (153, 153, 153)),    # strike grey
    ("W", (255, 255, 255)),    # white
]

path = sys.argv[1]
block = int(sys.argv[2]) if len(sys.argv) > 2 else 20
alpha, beta = 0.79, 0.21

im = Image.open(path).convert("RGB")
P = np.asarray(im).astype(np.float32)
H, W = P.shape[:2]

print("%s %dx%d block=%d" % (path, W, H, block))
print("legend: T target G ghost U cue E amber-end A amber P pink-cuetrack "
      "D cyan-dots Y ghost-ring R border S strike B bank . bg")
hdr = "      "
for x in range(0, W, block):
    hdr += str((x // 100) % 10)
print(hdr)
for y in range(0, H, block):
    row = []
    for x in range(0, W, block):
        patch = P[y:y + block, x:x + block].reshape(-1, 3)
        bestch, besterr = ".", 1e9
        for ch, C in STROKES:
            Cv = np.array(C, np.float32)
            BG = (patch - alpha * Cv) / beta
            err = (np.maximum(0, -BG.min(axis=1) - 20)
                   + np.maximum(0, BG.max(axis=1) - 275))
            e = err.min()
            if e < besterr:
                besterr, bestch = e, ch
        row.append(bestch if besterr <= 10 else ".")
    print("%5d " % y + "".join(row))
