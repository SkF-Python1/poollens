#!/usr/bin/env python3
"""Locate the game's own white aim guideline on the felt."""
import sys, zlib, struct
from collections import Counter

def load(path):
    d = open(path, 'rb').read()
    assert d[:8] == b'\x89PNG\r\n\x1a\n'
    p = 8; w = h = 0; idat = b''; bd = ct = 0
    while p < len(d):
        ln = struct.unpack('>I', d[p:p+4])[0]
        typ = d[p+4:p+8]; body = d[p+8:p+8+ln]
        if typ == b'IHDR':
            w, h, bd, ct = struct.unpack('>IIBB', body[:10])
        elif typ == b'IDAT':
            idat += body
        elif typ == b'IEND':
            break
        p += 12 + ln
    ch = {0:1, 2:3, 3:1, 4:2, 6:4}[ct]
    raw = zlib.decompress(idat)
    stride = w * ch
    out = bytearray(stride * h)
    prev = bytearray(stride)
    q = 0
    for y in range(h):
        f = raw[q]; q += 1
        line = bytearray(raw[q:q+stride]); q += stride
        if f == 1:
            for i in range(ch, stride):
                line[i] = (line[i] + line[i-ch]) & 255
        elif f == 2:
            for i in range(stride):
                line[i] = (line[i] + prev[i]) & 255
        elif f == 3:
            for i in range(stride):
                a = line[i-ch] if i >= ch else 0
                line[i] = (line[i] + ((a + prev[i]) >> 1)) & 255
        elif f == 4:
            for i in range(stride):
                a = line[i-ch] if i >= ch else 0
                b = prev[i]
                c = prev[i-ch] if i >= ch else 0
                pa, pb, pc = abs(b-c), abs(a-c), abs(a+b-2*c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[i] = (line[i] + pr) & 255
        out[y*stride:(y+1)*stride] = line
        prev = line
    return w, h, ch, out

w, h, ch, px = load(sys.argv[1])
print("image %dx%d ch=%d" % (w, h, ch))

# The aim guideline is a bright near-white line drawn over the blue felt.
# Felt is blue (b much greater than r). Guideline is r~g~b high.
hits = []
step = 2
for y in range(0, h, step):
    row = y * w * ch
    for x in range(0, w, step):
        i = row + x * ch
        r, g, b = px[i], px[i+1], px[i+2]
        mx, mn = max(r, g, b), min(r, g, b)
        if mx > 185 and mx - mn < 34:
            hits.append((x, y))
print("white-ish pixels: %d" % len(hits))
if not hits:
    sys.exit(0)

xs = [p[0] for p in hits]; ys = [p[1] for p in hits]
print("bbox x %d..%d  y %d..%d" % (min(xs), max(xs), min(ys), max(ys)))

# ASCII density map of white pixels
gw, gh = 78, 17
grid = [[0]*gw for _ in range(gh)]
for x, y in hits:
    grid[y*gh//h][x*gw//w] += 1
mx = max(max(r) for r in grid) or 1
for j, r in enumerate(grid):
    line = ''.join('#' if v > mx*0.5 else ('+' if v > mx*0.2 else ('.' if v else ' ')) for v in r)
    print("%2d %s" % (j, line))
