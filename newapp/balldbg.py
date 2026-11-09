#!/usr/bin/env python3
"""Replica of Detector.findBalls classification, to see how each blob is read."""
import sys
import math
from PIL import Image

STEP = 3
im = Image.open(sys.argv[1]).convert('RGB')
W, H = im.size
px = im.load()

BALL_COLORS = [
    0xF8F0D8, 0xF8A800, 0x2860C0, 0xF00010, 0x501890, 0xF05800,
    0x109020, 0x601000, 0x000000, 0xF8A800, 0x2860C0, 0xF00010,
    0x501890, 0xF05800, 0x109020, 0x601000,
]

def is_felt(r, g, b):
    return b > 100 and g > 90 and b > r + 40 and g > r + 20

def is_material(r, g, b):
    mx = max(r, g, b); mn = min(r, g, b); sat = mx - mn
    if is_felt(r, g, b):
        return False
    if mx > 150 and sat < 60:
        return True
    if mx < 60:
        return True
    if sat > 60 and mx > 60:
        return True
    return False

def match_ball(r, g, b):
    mx = max(r, g, b); mn = min(r, g, b)
    if mx > 200 and (mx - mn) < 40:
        return 0, False
    best, bestd = -1, 1 << 60
    for i in range(1, 16):
        c = BALL_COLORS[i]
        cr, cg, cb = (c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF
        d = (r-cr)**2 + (g-cg)**2 + (b-cb)**2
        if d < bestd:
            bestd, best = d, i
    if best == 8 and bestd > 3 * 55 * 55:
        return -1, False
    return best, True

gw, gh = W // STEP, H // STEP
sample = [[im.getpixel((gx*STEP, gy*STEP)) for gx in range(gw)] for gy in range(gh)]
mat = [[is_material(*sample[gy][gx]) for gx in range(gw)] for gy in range(gh)]

seen = [[False]*gw for _ in range(gh)]
blobs = []
for sy in range(gh):
    for sx in range(gw):
        if seen[sy][sx] or not mat[sy][sx]:
            continue
        st = [(sx, sy)]; seen[sy][sx] = True; pts = []
        while st:
            x, y = st.pop(); pts.append((x, y))
            for nx, ny in ((x-1,y),(x+1,y),(x,y-1),(x,y+1)):
                if 0 <= nx < gw and 0 <= ny < gh and mat[ny][nx] and not seen[ny][nx]:
                    seen[ny][nx] = True; st.append((nx, ny))
        blobs.append(pts)

total = gw * gh
min_blob = max(8, total // 12000)
print("%-5s %-8s %-6s %-6s %-22s %s" % ("n", "centre", "whitef", "satf", "mean", "match"))
for pts in sorted(blobs, key=len, reverse=True)[:16]:
    if len(pts) < min_blob:
        continue
    sr = sg = sb = 0
    white = sat = 0
    xs = [p[0] for p in pts]; ys = [p[1] for p in pts]
    for (x, y) in pts:
        r, g, b = sample[y][x]
        sr += r; sg += g; sb += b
        mx, mn = max(r, g, b), min(r, g, b)
        if mx > 150 and (mx - mn) < 60:
            white += 1
        if (mx - mn) > 80 and mx > 70:
            sat += 1
    cnt = len(pts)
    mr, mg, mb = sr//cnt, sg//cnt, sb//cnt
    cx = (min(xs)+max(xs))//2*STEP; cy = (min(ys)+max(ys))//2*STEP
    num, ok = match_ball(mr, mg, mb)
    print("%-5d %-8s %-6.2f %-6.2f %-22s %s" % (
        cnt, "%d,%d" % (cx, cy), white/cnt, sat/cnt,
        "(%d,%d,%d)" % (mr, mg, mb), num if ok else "reject"))
