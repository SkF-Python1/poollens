#!/usr/bin/env python3
"""Find pockets on a real game frame: dark circular blobs near the felt edge."""
import sys
from PIL import Image

im = Image.open(sys.argv[1]).convert('RGB')
W, H = im.size
px = im.load()
L, T, R, B = map(int, sys.argv[2:6])

def dark(x, y):
    r, g, b = px[x, y]
    return max(r, g, b) < 62

# Only look in a band inside the felt rect.
band = 90
x0, x1 = max(0, L - 30), min(W - 1, R + 30)
y0, y1 = max(0, T - 30), min(H - 1, B + 30)

inband = [[False] * (x1 - x0 + 1) for _ in range(y1 - y0 + 1)]
for y in range(y0, y1 + 1):
    for x in range(x0, x1 + 1):
        # near an edge only
        near = (x - L < band or R - x < band or y - T < band or B - y < band)
        if near and dark(x, y):
            inband[y - y0][x - x0] = True

gw = x1 - x0 + 1
gh = y1 - y0 + 1
seen = [[False] * gw for _ in range(gh)]
found = []
for j in range(gh):
    for i in range(gw):
        if not inband[j][i] or seen[j][i]:
            continue
        stack = [(i, j)]; seen[j][i] = True; pts = []
        while stack:
            cx, cy = stack.pop(); pts.append((cx, cy))
            for nx, ny in ((cx-1,cy),(cx+1,cy),(cx,cy-1),(cx,cy+1)):
                if 0 <= nx < gw and 0 <= ny < gh and inband[ny][nx] and not seen[ny][nx]:
                    seen[ny][nx] = True; stack.append((nx, ny))
        if len(pts) < 260:
            continue
        xs = [p[0] for p in pts]; ys = [p[1] for p in pts]
        bw = max(xs)-min(xs)+1; bh = max(ys)-min(ys)+1
        fill = len(pts) / max(1, bw*bh)
        asp = max(bw, bh) / max(1, min(bw, bh))
        if fill < 0.42 or asp > 2.4:
            continue
        found.append((x0 + (min(xs)+max(xs))//2, y0 + (min(ys)+max(ys))//2,
                      bw, bh, len(pts)))

found.sort(key=lambda t: -t[2])
print("table %d,%d - %d,%d" % (L, T, R, B))
print("pocket candidates: %d" % len(found))
for cx, cy, bw, bh, n in found:
    print("  (%4d,%4d) %3dx%-3d n=%4d  rel(%.3f, %.3f)"
          % (cx, cy, bw, bh, n, (cx-L)/max(1,R-L), (cy-T)/max(1,B-T)))
