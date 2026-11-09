#!/usr/bin/env python3
"""Report HSV statistics for the table region so thresholds can be chosen."""
import sys

sys.path.insert(0, "/mnt/data/Code/8BP/newapp")
from findbubble import read_png


def rgb_to_hsv(r, g, b):
    rf, gf, bf = r / 255.0, g / 255.0, b / 255.0
    mx, mn = max(rf, gf, bf), min(rf, gf, bf)
    d = mx - mn
    if d == 0:
        hh = 0.0
    elif mx == rf:
        hh = (60 * ((gf - bf) / d)) % 360
    elif mx == gf:
        hh = 60 * ((bf - rf) / d) + 120
    else:
        hh = 60 * ((rf - gf) / d) + 240
    v = mx
    s = 0.0 if mx == 0 else d / mx
    return hh, s, v


def main():
    path = sys.argv[1]
    w, h, ch, px = read_png(path)
    print("image %dx%d" % (w, h))

    samples = []
    for y in range(0, h, 6):
        for x in range(0, w, 6):
            i = (y * w + x) * ch
            samples.append((px[i], px[i + 1], px[i + 2]))

    # Cluster the bright blue-cyan family (the felt).
    hs, ss, vs = [], [], []
    for r, g, b in samples:
        hh, s, v = rgb_to_hsv(r, g, b)
        if 160 <= hh <= 230 and s > 0.25 and v > 0.35:
            hs.append(hh)
            ss.append(s)
            vs.append(v)

    print("felt candidate pixels: %d / %d" % (len(hs), len(samples)))
    if not hs:
        return

    hs.sort()
    ss.sort()
    vs.sort()

    def pct(a, p):
        return a[min(len(a) - 1, int(len(a) * p))]

    print("hue  : p05=%.0f p50=%.0f p95=%.0f" % (pct(hs, .05), pct(hs, .5), pct(hs, .95)))
    print("sat  : p05=%.2f p50=%.2f p95=%.2f" % (pct(ss, .05), pct(ss, .5), pct(ss, .95)))
    print("val  : p05=%.2f p50=%.2f p95=%.2f" % (pct(vs, .05), pct(vs, .5), pct(vs, .95)))

    # How much of the frame is felt?
    print("felt coverage: %.1f%%" % (100.0 * len(hs) / len(samples)))


main()
