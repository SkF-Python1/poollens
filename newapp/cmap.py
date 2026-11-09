#!/usr/bin/env python3
"""Coarse colour-category map of a screenshot, for eyeballing layout."""
import sys
from PIL import Image

im = Image.open(sys.argv[1]).convert('RGB')
W, H = im.size
cols, rows = 96, 40
sw, sh = W / cols, H / rows
px = im.load()

def cat(r, g, b):
    mx, mn = max(r, g, b), min(r, g, b)
    if mx > 200 and mx - mn < 45:
        return 'O'          # white ball / bright text
    if mx < 45:
        return ' '          # black
    if b > 100 and g > 90 and b > r + 40 and g > r + 20:
        return '~'          # blue felt
    if g > r + 30 and g > b + 10:
        return 'G'          # green
    if r > g + 40 and r > b + 20:
        return 'R'          # red
    if r > 150 and g > 110 and b < 100:
        return 'Y'          # yellow/orange
    if mx - mn < 55:
        return '.'          # grey
    return '?'

print("image %dx%d" % (W, H))
for j in range(rows):
    line = []
    for i in range(cols):
        x = int(i * sw + sw / 2)
        y = int(j * sh + sh / 2)
        line.append(cat(*px[x, y]))
    print("%3d %s" % (int(j * sh), ''.join(line)))
