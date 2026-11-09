#!/usr/bin/env python3
"""For each direction from the cue ball, measure how far a sustained
bright/structured object extends. The cue stick is the long one behind."""
import sys, math
from PIL import Image

im = Image.open(sys.argv[1]).convert('RGB')
W, H = im.size
px = im.load()
cx, cy = int(sys.argv[2]), int(sys.argv[3])
reach = int(sys.argv[4]) if len(sys.argv) > 4 else 600

def felt(r, g, b):
    return b > 90 and g > 80 and b > r + 35 and g > r + 15

print("cue=%d,%d reach=%d" % (cx, cy, reach))
best = []
for deg in range(0, 360, 3):
    a = math.radians(deg)
    dx, dy = math.cos(a), math.sin(a)
    run = 0
    best_run = 0
    hits = 0
    for t in range(12, reach, 2):
        x = int(cx + dx * t); y = int(cy + dy * t)
        if not (0 <= x < W and 0 <= y < H):
            break
        r, g, b = px[x, y]
        if felt(r, g, b):
            run = 0
        else:
            run += 1
            hits += 1
            if run > best_run:
                best_run = run
    best.append((best_run, hits, deg))

best.sort(reverse=True)
print("top sustained runs (len, total-hits, deg):")
for br, hh, d in best[:14]:
    print("  run=%3d hits=%3d deg=%3d (%6.1f)" % (br, hh, d, math.radians(d)))
