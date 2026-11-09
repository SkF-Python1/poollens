#!/usr/bin/env python3
"""Angle-voting aim detection: thin near-white ridges vote on a direction."""
import sys, math
from PIL import Image

im = Image.open(sys.argv[1]).convert('RGB')
W, H = im.size
px = im.load()
cue_x, cue_y = int(sys.argv[2]), int(sys.argv[3])
L, T, R, B = map(int, sys.argv[4:8])

def lum(p):  return 0.299*p[0] + 0.587*p[1] + 0.114*p[2]
def felt(p):
    r, g, b = p
    return b > 100 and g > 90 and b > r + 40 and g > r + 20

# Thin, near-white ridge pixels inside the table.
ridge = []
for y in range(max(T, 0), min(B, H), 2):
    for x in range(max(L, 0), min(R, W), 2):
        p = px[x, y]
        r, g, b = p
        mx, mn = max(p), min(p)
        if mx < 190 or (mx - mn) > 55:      # must be near-white
            continue
        if felt(p):
            continue
        l = lum(p)
        # Thin: brighter than both perpendicular neighbours.
        thin_h = (l - (lum(px[x-6, y]) + lum(px[x+6, y])) / 2) > 22
        thin_v = (l - (lum(px[x, y-6]) + lum(px[x, y+6])) / 2) > 22
        if thin_h or thin_v:
            ridge.append((x, y))

print("near-white thin ridge px: %d" % len(ridge))

# Vote on direction from the cue ball.
votes = [0] * 360
for (x, y) in ridge:
    dx, dy = x - cue_x, y - cue_y
    d = math.hypot(dx, dy)
    if d < 35 or d > 700:
        continue
    a = int(math.degrees(math.atan2(dy, dx))) % 360
    votes[a] += 1

peak = sorted(range(360), key=lambda k: -votes[k])[:12]
print("top vote bins:")
for k in peak:
    print("  %3d deg : %4d votes" % (k, votes[k]))

# Smooth-peak refine using +/- 4 deg window
best, bestv = 0, -1
for k in range(360):
    s = sum(votes[(k + o) % 360] for o in range(-4, 5))
    if s > bestv:
        bestv, best = s, k
print("refined peak: %d deg  window=%d" % (best, bestv))
