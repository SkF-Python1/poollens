#!/usr/bin/env python3
"""Inspect a PoolLens screenshot for the trajectory debug markers.

Finds the exact-colour debug anchors and traces the amber / white / cyan
lines, so the rendered geometry can be compared against the logged numbers
without vision.

Usage: trajinspect.py SHOT.png [SHOT2.png ...]
"""
import sys
import numpy as np
from PIL import Image

# Exact debug anchor colours drawn by GuideOverlay.drawTargetTrajectory().
TARGET = (255, 0, 255)      # magenta
GHOST = (0, 255, 0)         # lime
CUE = (0, 0, 255)           # blue
AEND = (0, 255, 255)        # cyan (amber endpoint)
AMBER = (255, 192, 32)      # 0xFFFFC020
WHITE = (255, 255, 255)


def near(img, col, tol=26):
    d = np.abs(img.astype(np.int16) - np.array(col, np.int16))
    return (d.sum(axis=2) <= tol)


def centroid(mask):
    ys, xs = np.nonzero(mask)
    if len(xs) == 0:
        return None
    return float(xs.mean()), float(ys.mean()), len(xs)


def blobs(mask, minpx=4):
    """Plain 4-connected labelling, returns list of (cx,cy,n)."""
    h, w = mask.shape
    seen = np.zeros_like(mask, bool)
    out = []
    idx = np.argwhere(mask)
    for sy, sx in idx:
        if seen[sy, sx]:
            continue
        stack = [(sy, sx)]
        seen[sy, sx] = True
        pts = []
        while stack:
            y, x = stack.pop()
            pts.append((y, x))
            for dy, dx in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                ny, nx = y + dy, x + dx
                if 0 <= ny < h and 0 <= nx < w and mask[ny, nx] and not seen[ny, nx]:
                    seen[ny, nx] = True
                    stack.append((ny, nx))
        if len(pts) >= minpx:
            arr = np.array(pts)
            out.append((float(arr[:, 1].mean()), float(arr[:, 0].mean()), len(pts)))
    return sorted(out, key=lambda b: -b[2])


def describe(name, mask, img):
    bl = blobs(mask)
    if not bl:
        print("  %-7s : none" % name)
        return
    for cx, cy, n in bl[:6]:
        # median colour inside the blob (pick one point)
        print("  %-7s : (%4d,%4d) n=%-6d" % (name, cx, cy, n))


def report(path):
    im = Image.open(path).convert("RGB")
    img = np.asarray(im)
    h, w = img.shape[:2]
    print("=" * 72)
    print("%s  %dx%d" % (path, w, h))

    m_t = near(img, TARGET)
    m_g = near(img, GHOST, tol=20)
    m_c = near(img, CUE)
    m_a = near(img, AEND, tol=20)
    m_amb = near(img, AMBER, tol=30)

    print(" debug anchors:")
    describe("MAGENTA", m_t, img)
    describe("LIME", m_g, img)
    describe("BLUE", m_c, img)
    describe("CYAN-a", m_a, img)

    # Amber line: fit it, report endpoints + angle.
    ys, xs = np.nonzero(m_amb)
    if len(xs) > 40:
        print("  amber px=%d  x %d..%d  y %d..%d" %
              (len(xs), xs.min(), xs.max(), ys.min(), ys.max()))
        c = centroid(m_amb)
        print("  amber centroid (%.0f,%.0f)" % (c[0], c[1]))
        # PCA direction
        P = np.stack([xs - c[0], ys - c[1]], 1).astype(float)
        cov = P.T @ P / len(P)
        evals, evecs = np.linalg.eigh(cov)
        v = evecs[:, -1]
        import math
        ang = math.degrees(math.atan2(v[1], v[0])) % 180
        print("  amber principal axis %.1f deg (mod 180)" % ang)
        # Extremes along the principal axis
        t = P @ v
        lo, hi = t.argmin(), t.argmax()
        print("  amber ends: (%d,%d) .. (%d,%d)" %
              (xs[lo], ys[lo], xs[hi], ys[hi]))
    else:
        print("  AMBER   : none (%d px)" % len(xs))

    # White line: white pixels that are NOT part of big UI regions. Just count.
    m_wh = (img[:, :, 0] > 235) & (img[:, :, 1] > 235) & (img[:, :, 2] > 235)
    print("  pure-white px=%d" % m_wh.sum())

    # Cyan dotted (0xCC66E0FF) ~ (102,224,255)
    d = np.abs(img.astype(np.int16) - np.array((102, 224, 255), np.int16))
    m_cy = (d.sum(axis=2) <= 40)
    print("  cyan-dot px=%d" % m_cy.sum())
    if m_cy.sum() > 10:
        ys2, xs2 = np.nonzero(m_cy)
        print("    cyan-dot bbox x %d..%d y %d..%d" %
              (xs2.min(), xs2.max(), ys2.min(), ys2.max()))


for p in sys.argv[1:]:
    report(p)
