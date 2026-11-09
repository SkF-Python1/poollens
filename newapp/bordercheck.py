#!/usr/bin/env python3
"""Compare the detected table rect against the actual felt extent on a frame."""
import sys
from PIL import Image

im = Image.open(sys.argv[1]).convert('RGB')
W, H = im.size
px = im.load()
DL, DT, DR, DB = map(int, sys.argv[2:6])

def felt(r, g, b):
    return b > 100 and g > 90 and b > r + 40 and g > r + 20

# For each row, how far does felt extend? Build a per-column felt count and
# per-row felt count, then find where coverage collapses to zero.
colC = [0] * W
rowC = [0] * H
for y in range(0, H, 2):
    for x in range(0, W, 2):
        if felt(*px[x, y]):
            colC[x] += 1
            rowC[y] += 1

def span(counts, n, thresh):
    lo = hi = -1
    for i in range(n):
        if counts[i] > thresh:
            if lo < 0:
                lo = i
            hi = i
    return lo, hi

# Use a high threshold so isolated background pixels are ignored.
thrC = max(colC) * 0.25
thrR = max(rowC) * 0.25
fl, fr = span(colC, W, thrC)
ft, fb = span(rowC, H, thrR)

print("detected rect : x %d..%d  y %d..%d  (w=%d h=%d)"
      % (DL, DR, DT, DB, DR - DL, DB - DT))
print("actual felt   : x %d..%d  y %d..%d  (w=%d h=%d)"
      % (fl, fr, ft, fb, fr - fl, fb - ft))
print("delta         : left %+d  right %+d  top %+d  bottom %+d"
      % (DL - fl, DR - fr, DT - ft, DB - fb))
print("aspect detected %.3f  actual %.3f"
      % ((DR - DL) / max(1, DB - DT), (fr - fl) / max(1, fb - ft)))
