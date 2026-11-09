#!/usr/bin/env python3
"""Print a coarse ASCII map of a screenshot so layout is readable without vision."""
import sys

sys.path.insert(0, "/mnt/data/Code/8BP/newapp")
from findbubble import read_png

# character per colour family
LEGEND = [
    ("felt",  (0x5A, 0xB0, 0xC8), "~"),
    ("dark",  (0x18, 0x20, 0x30), "."),
    ("panel", (0x2A, 0x32, 0x40), "#"),
    ("white", (0xE0, 0xE8, 0xF0), "O"),
    ("black", (0x00, 0x00, 0x00), "@"),
    ("red",   (0xD0, 0x30, 0x30), "R"),
    ("yellow",(0xE0, 0xC0, 0x30), "Y"),
    ("green", (0x3D, 0xDC, 0x97), "G"),
]


def classify(r, g, b):
    best, bestd = "?", 1 << 30
    for name, (tr, tg, tb), ch in LEGEND:
        d = (r - tr) ** 2 + (g - tg) ** 2 + (b - tb) ** 2
        if d < bestd:
            bestd, best = d, ch
    return best


def main():
    path = sys.argv[1]
    cols = int(sys.argv[2]) if len(sys.argv) > 2 else 78
    w, h, ch, px = read_png(path)
    rows = max(1, int(cols * h / w / 2.1))

    print("image %dx%d -> map %dx%d" % (w, h, cols, rows))
    for ry in range(rows):
        line = []
        for rx in range(cols):
            x = int((rx + 0.5) * w / cols)
            y = int((ry + 0.5) * h / rows)
            i = (y * w + x) * ch
            line.append(classify(px[i], px[i + 1], px[i + 2]))
        print("%3d %s" % (ry, "".join(line)))


main()
