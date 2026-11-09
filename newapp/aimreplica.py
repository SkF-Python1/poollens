#!/usr/bin/env python3
"""Offline replica of Detector.findAim so it can be tuned against real frames."""
import sys, math
from PIL import Image

im = Image.open(sys.argv[1]).convert('RGB')
W, H = im.size
px = im.load()

cue_x, cue_y, cue_r = int(sys.argv[2]), int(sys.argv[3]), int(sys.argv[4])
LEFT, TOP, RIGHT, BOTTOM = map(int, sys.argv[5:9])

STEP = 3
gw, gh = W // STEP, H // STEP

def is_felt(r, g, b):
    return b > 100 and g > 90 and b > r + 40 and g > r + 20

sample = [[px[gx * STEP, gy * STEP] for gx in range(gw)] for gy in range(gh)]
felt = [[is_felt(*sample[gy][gx]) for gx in range(gw)] for gy in range(gh)]

cx = cue_x // STEP
cy = cue_y // STEP
reach = max(24, gw // 4)
start = max(4, cue_r // STEP)
print("grid %dx%d cue=(%d,%d) reach=%d start=%d" % (gw, gh, cx, cy, reach, start))

results = []
for deg in range(0, 360, 2):
    a = math.radians(deg)
    dx, dy = math.cos(a), math.sin(a)
    score = gap = 0
    steps = 0
    for t in range(start, reach):
        x = int(round(cx + dx * t)); y = int(round(cy + dy * t))
        if x < 0 or y < 0 or x >= gw or y >= gh:
            break
        r, g, b = sample[y][x]
        mx, mn = max(r, g, b), min(r, g, b)
        bright = mx > 150 and (mx - mn) < 70
        if bright:
            score += 2; gap = 0; steps += 1
        elif felt[y][x]:
            gap += 1
            if gap > 3:
                break
        else:
            break
    results.append((score, deg, steps))

results.sort(reverse=True)
min_score = max(8, reach // 3)
print("min_score=%d" % min_score)
print("top angles:")
for s, d, st in results[:10]:
    print("  deg=%3d score=%4d brightsteps=%3d %s" % (d, s, st, "PASS" if s >= min_score else ""))
