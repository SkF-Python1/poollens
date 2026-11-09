#!/usr/bin/env python3
"""Find the real playable table rect: largest connected felt component,
then expand outward to the cushion's inner edge."""
import sys
from PIL import Image

im = Image.open(sys.argv[1]).convert('RGB')
W, H = im.size
px = im.load()

def is_felt(r, g, b):
    return b > 100 and g > 90 and b > r + 40 and g > r + 20

# Downsample 4x for speed.
S = 4
gw, gh = W // S, H // S
mask = bytearray(gw * gh)
for gy in range(gh):
    for gx in range(gw):
        r, g, b = px[gx * S, gy * S]
        if is_felt(r, g, b):
            mask[gy * gw + gx] = 1

# Largest connected component (BFS with an explicit stack).
seen = bytearray(gw * gh)
best = None
for start in range(gw * gh):
    if not mask[start] or seen[start]:
        continue
    stack = [start]
    seen[start] = 1
    pts = []
    while stack:
        cur = stack.pop()
        pts.append(cur)
        cy, cx = divmod(cur, gw)
        if cx > 0 and mask[cur - 1] and not seen[cur - 1]:
            seen[cur - 1] = 1; stack.append(cur - 1)
        if cx < gw - 1 and mask[cur + 1] and not seen[cur + 1]:
            seen[cur + 1] = 1; stack.append(cur + 1)
        if cy > 0 and mask[cur - gw] and not seen[cur - gw]:
            seen[cur - gw] = 1; stack.append(cur - gw)
        if cy < gh - 1 and mask[cur + gw] and not seen[cur + gw]:
            seen[cur + gw] = 1; stack.append(cur + gw)
    if best is None or len(pts) > len(best):
        best = pts

xs = [p % gw for p in best]
ys = [p // gw for p in best]
x0, x1 = min(xs), max(xs)
y0, y1 = min(ys), max(ys)
print("felt component: %d px" % len(best))
print("rect(4x coords) x %d..%d  y %d..%d" % (x0, x1, y0, y1))
print("rect(full res)  x %d..%d  y %d..%d" % (x0*S, x1*S, y0*S, y1*S))
print("size %d x %d   aspect %.2f" % ((x1-x0)*S, (y1-y0)*S, (x1-x0)/max(1,(y1-y0))))

# Extend along each axis to the cushion inner edge: walk outward while the
# column/row is still mostly dark rail OR felt (pockets interrupt it).
def col_felt_frac(x):
    n = 0; t = 0
    for y in range(y0*S, y1*S, 6):
        t += 1
        r, g, b = px[x, y]
        if is_felt(r, g, b):
            n += 1
    return n / max(1, t)

print("\ncolumn felt fraction near edges:")
for x in range(max(0, x0*S - 40), min(W, x0*S + 60), 10):
    print("  x=%4d  felt=%.2f" % (x, col_felt_frac(x)))
print("  ...")
for x in range(max(0, x1*S - 60), min(W, x1*S + 40), 10):
    print("  x=%4d  felt=%.2f" % (x, col_felt_frac(x)))
