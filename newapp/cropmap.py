#!/usr/bin/env python3
"""Fine ASCII crop map of a region, so the overlay layout can be inspected
without an image viewer.

Usage: cropmap.py SHOT.png x0 y0 x1 y1 [block]

Chars:  M magenta(target)  G lime(ghost)  B blue(cue)  C cyan  A amber
        P pink(our white line at 0.79 alpha)  W white  _ green  w wood  . dark
"""
import sys
import numpy as np
from PIL import Image, ImageFile

ImageFile.LOAD_TRUNCATED_IMAGES = True

path = sys.argv[1]
x0, y0, x1, y1 = (int(v) for v in sys.argv[2:6])
block = int(sys.argv[6]) if len(sys.argv) > 6 else 6

im = Image.open(path).convert("RGB")
a = np.asarray(im).astype(np.int16)

# Reference overlay colours (0xFFFFFFFF white, 0xFFFF1493 pink for the cue
# track in DEBUG_VIS, 0xFFFFC020 amber, 0xCC66E0FF cyan dots) blended at 0.79.
REF = {
    "M": (255, 0, 255),
    "G": (0, 255, 0),
    "B": (0, 0, 255),
    "C": (102, 224, 255),
    "A": (255, 192, 32),
    "P": (255, 20, 147),
    "W": (255, 255, 255),
    "_": (30, 150, 90),
    "w": (150, 110, 60),
    ".": (10, 10, 10),
}


def classify(p):
    r, g, b = int(p[0]), int(p[1]), int(p[2])
    mx, mn = max(r, g, b), min(r, g, b)
    sat = mx - mn
    if mx < 42:
        return "."
    # magenta
    if r > 120 and b > 110 and g < mx * 0.55:
        return "M"
    # pink (255,20,147)*0.79 -> ~(202,16,116); R high, B mid, G very low
    if r > 150 and g < 90 and 0.3 * r < b < 0.85 * r:
        return "P"
    # lime
    if g > 150 and r < mx * 0.72 and b < mx * 0.72:
        return "G"
    # cyan
    if g > 150 and b > 170 and r < mx * 0.78:
        return "C"
    # amber
    if r > 150 and 0.55 * r < g < 0.97 * r and b < 0.55 * r:
        return "A"
    # blue
    if b > 140 and r < mx * 0.6 and g < mx * 0.85:
        return "B"
    if sat < 34:
        return "W" if mx > 200 else (w_low(r, g, b))
    if g >= r and g >= b:
        return "_"
    if r > g and g > b:
        return "w"
    return "?"


def w_low(r, g, b):
    return "w" if (r > 80 and g > 60 and b < r) else "."


print("%s  crop x%d..%d y%d..%d block=%d" % (path, x0, y0, x1, y1, block))
hdr = "     " + "".join(str((x0 + i * block) // 100 % 10) for i in range(0, (x1 - x0) // block))
print(hdr)
for by in range(y0, y1, block):
    row = []
    for bx in range(x0, x1, block):
        cell = a[by:by + block, bx:bx + block].reshape(-1, 3)
        best, bs = "?", -1
        for p in cell:
            ch = classify(p)
            if ch == "?":
                continue
            # prefer rarer/overlay classes
            pr = "MGPBCAW_.w?".find(ch)
            sc = 40 - pr + (int(max(p)) - int(min(p))) * 0.5
            if sc > bs:
                bs = sc
                best = ch
        row.append(best)
    print("%4d " % by + "".join(row))
