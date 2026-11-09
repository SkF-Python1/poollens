#!/usr/bin/env python3
"""Measure dominant body colour of each 8BP ball sprite (WebP/PNG aware)."""
import colorsys
import glob
import os
import sys
from collections import Counter

from PIL import Image


def body_colour(path):
    im = Image.open(path).convert("RGBA")
    w, h = im.size
    px = im.load()

    counts = Counter()
    opaque = 0
    for y in range(h):
        for x in range(w):
            r, g, b, a = px[x, y]
            if a < 200:
                continue
            opaque += 1
            counts[(r >> 3 << 3, g >> 3 << 3, b >> 3 << 3)] += 1

    if not opaque:
        return w, h, 0, None, None, 0.0

    (tr, tg, tb), tn = counts.most_common(1)[0]

    tot = sr = sg = sb = 0
    for (cr, cg, cb), cn in counts.most_common(5):
        sr += cr * cn
        sg += cg * cn
        sb += cb * cn
        tot += cn

    return (w, h, opaque, (tr, tg, tb),
            (sr // tot, sg // tot, sb // tot), 100.0 * tn / opaque)


def main():
    pattern = sys.argv[1] if len(sys.argv) > 1 else \
        "/mnt/data/Code/8BP/newapp/ref/ball*-ipadhd.png"

    print("%-22s %-9s %6s  %-8s %-8s %5s  hue" %
          ("sprite", "size", "opaque", "top", "mean", "pct"))

    for path in sorted(glob.glob(pattern)):
        name = os.path.basename(path)
        try:
            w, h, opaque, top, mean, pct = body_colour(path)
        except Exception as e:
            print("%-22s ERROR %s" % (name, e))
            continue
        if top is None:
            print("%-22s %4dx%-4d %6d  (no opaque)" % (name, w, h, opaque))
            continue

        hh, ss, vv = colorsys.rgb_to_hsv(
            mean[0] / 255, mean[1] / 255, mean[2] / 255)
        print("%-22s %4dx%-4d %6d  #%02X%02X%02X  #%02X%02X%02X  %5.1f  %3.0f" %
              (name, w, h, opaque, top[0], top[1], top[2],
               mean[0], mean[1], mean[2], pct, hh * 360))


main()
