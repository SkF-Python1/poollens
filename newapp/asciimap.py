#!/usr/bin/env python3
"""Render a coarse ASCII map of a screenshot so the overlay layout can be
inspected without an image viewer.

Each output cell covers a BLOCK x BLOCK block of pixels; the character is
chosen from the most distinctive (highest saturation / most unusual hue)
pixel in that block:

  M = magenta (target ring/square)   G = lime  (ghost ring/square)
  B = blue    (cue ring/square)      C = cyan  (amber endpoint / deflection)
  A = amber   (target trajectory)    P = pink   (white cue aim line / strike)
  W = white                          . = dark      _ = green/felt
  w = wood / brown

Usage: asciimap.py SHOT.png [block]
"""
import sys
import numpy as np
from PIL import Image, ImageFile

ImageFile.LOAD_TRUNCATED_IMAGES = True

path = sys.argv[1]
block = int(sys.argv[2]) if len(sys.argv) > 2 else 24

im = Image.open(path).convert("RGB")
a = np.asarray(im).astype(np.int16)
H, W = a.shape[:2]


def classify(r, g, b):
    mx = max(r, g, b)
    mn = min(r, g, b)
    sat = mx - mn
    if mx < 40:
        return "."
    if sat < 30:
        return "W" if mx > 200 else "w"
    # magenta: R,B high, G low
    if r > 120 and b > 110 and g < mx * 0.55:
        return "M"
    # lime: G high, R,B low
    if g > 150 and r < mx * 0.72 and b < mx * 0.72:
        return "G"
    # blue: B dominant
    if b > 150 and r < mx * 0.6 and g < mx * 0.8:
        return "B"
    # cyan: G,B high, R low
    if g > 150 and b > 170 and r < mx * 0.75:
        return "C"
    # amber: R high, G mid, B low
    if r > 150 and 0.55 * r < g < 0.95 * r and b < 0.5 * r:
        return "A"
    # pink: R high, B mid, G low
    if r > 150 and 0.35 * r < b < 0.8 * r and g < 0.55 * r:
        return "P"
    # green felt: G dominant, B > R
    if g >= r and g >= b:
        return "_"
    if r > g and g > b:
        return "w"
    return "?"


print("%s  %dx%d  block=%d" % (path, W, H, block))
# priority: show special colours even if rare
PRIO = "MGB CAPW_.w?"
for by in range(0, H, block):
    row = []
    for bx in range(0, W, block):
        cell = a[by:by + block, bx:bx + block].reshape(-1, 3)
        best = "?"
        bestscore = -1
        for p in cell[::max(1, len(cell) // 64)]:
            ch = classify(int(p[0]), int(p[1]), int(p[2]))
            # priority index: lower is more interesting
            pi = PRIO.find(ch)
            if pi < 0:
                pi = 99
            score = 100 - pi
            mx = int(max(p))
            mn = int(min(p))
            score += (mx - mn) * 0.4
            if score > bestscore:
                bestscore = score
                best = ch
        row.append(best)
    print("".join(row))
