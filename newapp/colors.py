#!/usr/bin/env python3
"""Report dominant colours in named regions of a screenshot.

Used to pick thresholds for the on-device ball/table detector.
"""
import sys
from collections import Counter

sys.path.insert(0, "/mnt/data/Code/8BP/newapp")
from findbubble import read_png


def main():
    path = sys.argv[1]
    w, h, ch, px = read_png(path)
    print("image %dx%d ch=%d" % (w, h, ch))

    # Bucket colours across the whole frame to find the felt colour.
    counts = Counter()
    for y in range(0, h, 8):
        for x in range(0, w, 8):
            i = (y * w + x) * ch
            r, g, b = px[i], px[i + 1], px[i + 2]
            # quantise to 16 levels
            counts[(r >> 4 << 4, g >> 4 << 4, b >> 4 << 4)] += 1

    total = sum(counts.values())
    print("\ntop colours:")
    for (r, g, b), n in counts.most_common(12):
        print("  #%02X%02X%02X  %5.1f%%" % (r, g, b, 100.0 * n / total))

    # Sample a horizontal and vertical strip to see the table area.
    print("\nhorizontal strip at y=%d:" % (h // 2))
    y = h // 2
    for x in range(0, w, w // 16):
        i = (y * w + x) * ch
        print("  x=%4d  #%02X%02X%02X" % (x, px[i], px[i + 1], px[i + 2]))


main()
