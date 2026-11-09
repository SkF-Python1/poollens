#!/usr/bin/env python3
"""Find every debug marker blob (blended for window alpha 0.79) and report
its position, plus trace the amber / pink-white / cyan-cyan lines.

The overlay is composited at alpha 0.79, so a pure colour c arrives as
0.79*c + 0.21*bg over the game. We therefore classify by hue dominance with
generous tolerance and report blob centres.

Usage: dumpshots.py SHOT.png [...]
"""
import sys
import numpy as np
from PIL import Image, ImageFile

ImageFile.LOAD_TRUNCATED_IMAGES = True


def blobs(mask, minpx=6):
    h, w = mask.shape
    seen = np.zeros_like(mask, bool)
    out = []
    for sy, sx in np.argwhere(mask):
        if seen[sy, sx]:
            continue
        stack = [(sy, sx)]
        seen[sy, sx] = True
        pts = []
        while stack:
            y, x = stack.pop()
            pts.append((y, x))
            for dy in (-1, 0, 1):
                for dx in (-1, 0, 1):
                    ny, nx = y + dy, x + dx
                    if 0 <= ny < h and 0 <= nx < w and mask[ny, nx] and not seen[ny, nx]:
                        seen[ny, nx] = True
                        stack.append((ny, nx))
        if len(pts) >= minpx:
            arr = np.array(pts)
            out.append((float(arr[:, 1].mean()), float(arr[:, 0].mean()),
                        int(arr[:, 1].min()), int(arr[:, 1].max()),
                        int(arr[:, 0].min()), int(arr[:, 0].max()), len(pts)))
    return sorted(out, key=lambda b: -b[6])


def report(path):
    print("=" * 78)
    im = Image.open(path).convert("RGB")
    a = np.asarray(im).astype(np.int16)
    H, W = a.shape[:2]
    R, G, B = a[:, :, 0], a[:, :, 1], a[:, :, 2]
    MX = np.max(a, axis=2)
    MN = np.min(a, axis=2)
    print("%s  %dx%d" % (path, W, H))

    tests = {
        "magenta(target)": (R > 140) & (B > 120) & (G < 70) & (np.abs(R - B) < 70),
        "lime(ghost)":     (G > 170) & (R < 150) & (B < 150),
        "blue(cue)":       (B > 170) & (R < 90) & (G < 90),
        "cyan(amber-end)": (G > 180) & (B > 190) & (R < 140),
        "amber(line)":     (R > 150) & (G > 110) & (G < R * 0.94) & (B < 90),
        "pink(white-line)": (R > 170) & (B > 90) & (B < 170) & (G < 80),
    }
    for name, m in tests.items():
        bl = blobs(m)
        print("  %-18s %6d px  %d blobs" % (name, m.sum(), len(bl)))
        for cx, cy, x0, x1, y0, y1, n in bl[:5]:
            print("      (%4.0f,%4.0f) n=%-6d bbox x%d..%d y%d..%d"
                  % (cx, cy, n, x0, x1, y0, y1))


for p in sys.argv[1:]:
    report(p)
