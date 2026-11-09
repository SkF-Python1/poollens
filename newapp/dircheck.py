#!/usr/bin/env python3
"""Compare colours along two opposite directions to tell the guideline
(near-white, dashed) from the cue stick (solid, wood/metal)."""
import sys, math
from PIL import Image

im = Image.open(sys.argv[1]).convert('RGB')
W, H = im.size
px = im.load()
cx, cy = int(sys.argv[2]), int(sys.argv[3])

def walk(deg, reach=520):
    a = math.radians(deg)
    dx, dy = math.cos(a), math.sin(a)
    white = wood = felt = other = 0
    runs = []
    cur = None
    for t in range(30, reach, 4):
        x = int(cx + dx * t); y = int(cy + dy * t)
        if not (0 <= x < W and 0 <= y < H):
            break
        r, g, b = px[x, y]
        mx, mn = max(r, g, b), min(r, g, b)
        if b > 100 and g > 90 and b > r + 40 and g > r + 20:
            k = 'F'; felt += 1
        elif mx > 185 and mx - mn < 55:
            k = 'W'; white += 1
        elif r > 110 and r > b + 25 and mx - mn > 30:
            k = 'w'; wood += 1       # wood/tan cue stick
        else:
            k = '.'; other += 1
        runs.append(k)
    return white, wood, felt, other, ''.join(runs)

for deg in (174, 354, 174 - 180, 177, 357):
    w, wd, f, o, s = walk(deg)
    print("deg %3d  white=%3d wood=%3d felt=%3d other=%3d" % (deg, w, wd, f, o))
    print("   %s" % s)
