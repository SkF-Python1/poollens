#!/usr/bin/env python3
"""Locate the PoolLens overlay bubble on screen by colour, without vision.

Reads a raw RGBA screenshot and reports the bounding box of pixels matching
the accent green (#3DDC97), which only the bubble draws.
"""
import struct
import sys
import zlib


def read_png(path):
    with open(path, "rb") as fh:
        data = fh.read()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise SystemExit("not a png")

    pos = 8
    width = height = None
    bitdepth = colortype = None
    idat = bytearray()

    while pos < len(data):
        (length,) = struct.unpack(">I", data[pos:pos + 4])
        ctype = data[pos + 4:pos + 8]
        body = data[pos + 8:pos + 8 + length]
        pos += 12 + length

        if ctype == b"IHDR":
            width, height, bitdepth, colortype = struct.unpack(">IIBB", body[:10])
        elif ctype == b"IDAT":
            idat += body
        elif ctype == b"IEND":
            break

    if bitdepth != 8:
        raise SystemExit("unsupported bit depth %s" % bitdepth)

    channels = {0: 1, 2: 3, 4: 2, 6: 4}[colortype]
    raw = zlib.decompress(bytes(idat))
    stride = width * channels

    out = bytearray(height * stride)
    prev = bytearray(stride)
    src = 0
    for y in range(height):
        f = raw[src]
        src += 1
        line = bytearray(raw[src:src + stride])
        src += stride
        if f == 1:
            for i in range(channels, stride):
                line[i] = (line[i] + line[i - channels]) & 0xFF
        elif f == 2:
            for i in range(stride):
                line[i] = (line[i] + prev[i]) & 0xFF
        elif f == 3:
            for i in range(stride):
                left = line[i - channels] if i >= channels else 0
                line[i] = (line[i] + ((left + prev[i]) >> 1)) & 0xFF
        elif f == 4:
            for i in range(stride):
                a = line[i - channels] if i >= channels else 0
                b = prev[i]
                c = prev[i - channels] if i >= channels else 0
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[i] = (line[i] + pr) & 0xFF
        out[y * stride:(y + 1) * stride] = line
        prev = line

    return width, height, channels, out


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else "/tmp/shot.png"
    width, height, ch, px = read_png(path)

    target = (0x3D, 0xDC, 0x97)
    tol = 40

    minx, miny, maxx, maxy = width, height, -1, -1
    count = 0

    for y in range(0, height, 2):
        row = y * width * ch
        for x in range(0, width, 2):
            i = row + x * ch
            r = px[i]
            g = px[i + 1] if ch >= 3 else r
            b = px[i + 2] if ch >= 3 else r
            if abs(r - target[0]) <= tol and abs(g - target[1]) <= tol \
                    and abs(b - target[2]) <= tol:
                count += 1
                if x < minx:
                    minx = x
                if x > maxx:
                    maxx = x
                if y < miny:
                    miny = y
                if y > maxy:
                    maxy = y

    print("image %dx%d channels=%d" % (width, height, ch))
    if count == 0:
        print("bubble: NOT FOUND")
        return 1

    print("accent pixels: %d" % count)
    print("bbox: (%d,%d)-(%d,%d)  center=(%d,%d)"
          % (minx, miny, maxx, maxy, (minx + maxx) // 2, (miny + maxy) // 2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
