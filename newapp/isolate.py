#!/usr/bin/env python3
"""Isolate overlay strokes in a screenshot by inverting the 0.79 alpha blend.

The PoolLens overlay window is composited over the game at alpha ~0.79, so a
stroke colour C arrives as P = a*C + (1-a)*BG with 0 <= BG <= 255. For a given
C, the implied background BG = (P - a*C)/(1-a) must be a valid RGB triple.
Testing every known stroke colour against that constraint recovers the stroke
that was actually drawn, independent of what is underneath.

Usage: isolate.py SHOT.png [alpha]
"""
import sys
import numpy as np
from PIL import Image, ImageFile

ImageFile.LOAD_TRUNCATED_IMAGES = True

# Every colour the overlay can paint (see GuideOverlay), with a label.
STROKES = {
    "TARGET-magenta": (255, 0, 255),       # debug ring/square at target
    "GHOST-lime": (0, 255, 0),             # debug ring/square at ghost
    "CUE-blue": (0, 0, 255),               # debug ring/square at cue
    "AEND-cyan": (0, 255, 255),            # debug ring/square at amber end
    "AMBER-line": (255, 192, 32),          # target trajectory 0xFFFFC020
    "WHITE-line": (255, 255, 255),         # cue track (when DEBUG_VIS off)
    "PINK-line": (255, 20, 147),           # cue track 0xFFFF1493 (DEBUG_VIS on)
    "BORDER-green": (61, 220, 151),        # 0xFF3DDC97
    "BANK-blue": (127, 212, 255),          # 0xEE7FD4FF
    "CYAN-dot": (102, 224, 255),           # 0xCC66E0FF deflection dots
    "STRIKE-grey": (153, 153, 153),        # 0x99FFFFFF strike dashes
}

path = sys.argv[1]
alpha = float(sys.argv[2]) if len(sys.argv) > 2 else 0.79
beta = 1.0 - alpha

im = Image.open(path).convert("RGB")
P = np.asarray(im).astype(np.float32)
H, W = P.shape[:2]
print("%s %dx%d alpha=%.2f" % (path, W, H, alpha))

best = np.full((H, W), -1, np.int16)
bestfit = np.full((H, W), 1e9, np.float32)
names = list(STROKES)

for i, name in enumerate(names):
    C = np.array(STROKES[name], np.float32)
    BG = (P - alpha * C) / beta
    lo = BG.min(axis=2)
    hi = BG.max(axis=2)
    # Valid when every implied background channel is inside [0,255].
    ok = (lo > -24) & (hi < 279)
    # Fit error: how far outside the valid range the background is.
    err = np.maximum(0, -lo - 24) + np.maximum(0, hi - 279)
    upd = ok & (err < bestfit)
    best[upd] = i
    bestfit[upd] = err[upd]

total_ok = (best >= 0).sum()
print("explained pixels: %d / %d (%.1f%%)" % (total_ok, H * W, 100.0 * total_ok / (H * W)))
for i, name in enumerate(names):
    n = int((best == i).sum())
    if n == 0:
        continue
    ys, xs = np.nonzero(best == i)
    print("  %-16s %7d px  x %4d..%4d  y %4d..%4d"
          % (name, n, xs.min(), xs.max(), ys.min(), ys.max()))
