#!/usr/bin/env python3
"""Coarse visual fingerprint of a screenshot, for when image viewing is unavailable."""
import sys

sys.path.insert(0, "/mnt/data/Code/8BP/newapp")
from findbubble import read_png


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else "/tmp/shot.png"
    w, h, ch, px = read_png(path)
    print("size %dx%d" % (w, h))

    for y in range(0, h, 180):
        cells = []
        for x in range(0, w, 220):
            i = (y * w + x) * ch
            cells.append("%02X%02X%02X" % (px[i], px[i + 1], px[i + 2]))
        print("%4d  %s" % (y, " ".join(cells)))

    # accent pixels tell us whether our overlay is present
    target = (0x3D, 0xDC, 0x97)
    n = 0
    for y in range(0, h, 2):
        for x in range(0, w, 2):
            i = (y * w + x) * ch
            if abs(px[i] - target[0]) < 40 and abs(px[i + 1] - target[1]) < 40 \
                    and abs(px[i + 2] - target[2]) < 40:
                n += 1
    print("accent pixels: %d" % n)


main()
