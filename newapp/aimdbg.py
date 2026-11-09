#!/usr/bin/env python3
"""Replica of Detector.findAim so the guideline detection can be debugged offline."""
import sys
import math
from PIL import Image

STEP = 3
im = Image.open(sys.argv[1]).convert('RGB')
W, H = im.size
px = im.load()

def is_felt(r, g, b):
    return b > 100 and g > 90 and b > r + 40 and g > r + 20

def is_material(r, g, b):
    mx = max(r, g, b)
    mn = min(r, g, b)
    if mx < 45:
        return False
    if mx - mn < 26 and mx > 150:
        return False
    if is_felt(r, g, b):
        return False
    return True

gw, gh = W // STEP, H // STEP
felt = [[False] * gw for _ in range(gh)]
mat = [[False] * gw for _ in range(gh)]
for gy in range(gh):
    for gx in range(gw):
        r, g, b = px[gx * STEP, gy * STEP]
        if is_felt(r, g, b):
            felt[gy][gx] = True
        elif is_material(r, g, b):
            mat[gy][gx] = True

# largest felt component bbox (after closing is skipped here; approximate)
seen = [[False] * gw for _ in range(gh)]
best = []
for sy in range(gh):
    for sx in range(gw):
        if not felt[sy][sx] or seen[sy][sx]:
            continue
        st = [(sx, sy)]
        seen[sy][sx] = True
        pts = []
        while st:
            x, y = st.pop()
            pts.append((x, y))
            for nx, ny in ((x-1,y),(x+1,y),(x,y-1),(x,y+1)):
                if 0 <= nx < gw and 0 <= ny < gh and felt[ny][nx] and not seen[ny][nx]:
                    seen[ny][nx] = True
                    st.append((nx, ny))
        if len(pts) > len(best):
            best = pts

xs = [p[0] for p in best]
ys = [p[1] for p in best]
L, R = min(xs) * STEP, max(xs) * STEP
T, B = min(ys) * STEP, max(ys) * STEP
print("table %d,%d-%d,%d" % (L, T, R, B))

# cue ball = first blob with number 0; approximate by finding white round blob.
# For debugging just report counts of near-white ridge pixels over the table.
n_white = 0
for y in range(T, B, 3):
    for x in range(L, R, 3):
        r, g, b = px[x, y]
        mx, mn = max(r, g, b), min(r, g, b)
        if mx >= 185 and (mx - mn) <= 60 and not is_felt(r, g, b):
            n_white += 1
print("near-white non-felt px in table:", n_white)
