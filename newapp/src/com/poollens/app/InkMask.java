package com.poollens.app;

import java.util.Arrays;

/**
 * Records where the overlay window has drawn its own ink, in CAPTURE pixel
 * space, on the same coarse grid the detector samples.
 *
 * Why this exists: a MediaProjection virtual display mirrors the WHOLE screen,
 * including our own TYPE_APPLICATION_OVERLAY window, so the detector was seeing
 * its own output:
 *
 *   - the white aim line is thin, bright and low-saturation, so it passed
 *     {@code isGuidePixel} and the aim scan locked onto it (a stable, false
 *     100% confidence),
 *   - the green border rectangle passes the felt test and inflates the cloth,
 *   - the amber and cyan shot lines pass the ball-material test.
 *
 * Clearing every painted cell out of the felt/material masks fixes all three.
 * Two buffers alternate so a drawing pass never mutates the mask the detector
 * is concurrently reading: marks accumulate in the inactive buffer and become
 * visible only on {@link #commit()}.
 */
final class InkMask {

    private static volatile int step = 3;
    private static volatile int gw = 0;
    private static volatile int gh = 0;

    private static byte[] bufA = new byte[0];
    private static byte[] bufB = new byte[0];

    private static volatile byte[] live = new byte[0];
    private static byte[] draw = new byte[0];

    /** Control panel footprint, in capture pixels. */
    private static volatile int panL, panT, panR = -1, panB = -1;

    private InkMask() {
    }

    /** (Re)sizes the grid. Call on every capture (re)start. */
    static void configure(int captureW, int captureH, int gridStep) {
        step = Math.max(1, gridStep);
        int nw = Math.max(1, captureW / step);
        int nh = Math.max(1, captureH / step);
        if (nw != gw || nh != gh) {
            bufA = new byte[nw * nh];
            bufB = new byte[nw * nh];
            live = new byte[0];
            gw = nw;
            gh = nh;
        }
    }

    /** Forgets every mark (used when the capture geometry changes). */
    static void reset() {
        live = new byte[0];
        panL = panT = 0;
        panR = panB = -1;
    }

    static int gridW() {
        return gw;
    }

    static int gridH() {
        return gh;
    }

    static int gridStep() {
        return step;
    }

    /** Starts an ink pass; marks go to the buffer the detector is not using. */
    static void begin() {
        if (bufA.length == 0) return;
        draw = (live == bufA) ? bufB : bufA;
        if (draw.length != bufA.length) draw = new byte[bufA.length];
        Arrays.fill(draw, (byte) 0);
    }

    /** Publishes the pass; the detector picks it up on the next frame. */
    static void commit() {
        if (draw.length != 0) live = draw;
    }

    /**
     * The control panel is a separate window, so its footprint cannot be
     * recorded stroke by stroke; just remember its rectangle.
     */
    static void setPanelRect(int left, int top, int right, int bottom) {
        panL = left;
        panT = top;
        panR = right;
        panB = bottom;
    }

    // ------------------------------------------------------------ diagnostics

    /** Count of marked cells in the live buffer, for the profile log. */
    static int marked() {
        byte[] m = live;
        int n = 0;
        for (int i = 0; i < m.length; i++) if (m[i] != 0) n++;
        return n;
    }

    // -------------------------------------------------------------- queries

    /** True when the capture pixel (x,y) was painted by the overlay or falls in the panel. */
    static boolean isInk(int x, int y) {
        return isPanel(x, y);
    }

    /** Cell-space query, for callers already working on the sampled grid. */
    static boolean isInkCell(int cx, int cy) {
        return isPanel(cx * step, cy * step);
    }

    /** True when a capture-space point falls inside the panel window. */
    static boolean isPanel(int x, int y) {        return panR >= panL && x >= panL && x <= panR && y >= panT && y <= panB;
    }

    // ---------------------------------------------------------------- marks

    /** Stroke half-thickness in cells, including a safety margin. */
    private static int radiusCells(float widthPx) {
        return Math.max(1, (int) Math.ceil((widthPx * 0.5f + 1f) / step));
    }

    private static void blob(int cx, int cy, int r) {
        if (draw.length == 0) return;
        for (int dy = -r; dy <= r; dy++) {
            int yy = cy + dy;
            if (yy < 0 || yy >= gh) continue;
            int row = yy * gw;
            for (int dx = -r; dx <= r; dx++) {
                int xx = cx + dx;
                if (xx < 0 || xx >= gw) continue;
                int i = row + xx;
                if (i >= 0 && i < draw.length) draw[i] = 1;
            }
        }
    }

    /** Mark a straight stroke; endpoints in capture pixels. */
    static void line(float x1, float y1, float x2, float y2, float widthPx) {
        if (draw.length == 0) return;
        if (Float.isNaN(x1) || Float.isNaN(y1) || Float.isNaN(x2) || Float.isNaN(y2)) return;
        int r = radiusCells(widthPx);
        float dx = x2 - x1, dy = y2 - y1;
        float len = (float) Math.hypot(dx, dy);
        int s = step;
        int n = Math.max(1, Math.min(4000, (int) (len / s) + 1));
        for (int i = 0; i <= n; i++) {
            float t = (float) i / n;
            blob((int) ((x1 + dx * t) / s), (int) ((y1 + dy * t) / s), r);
        }
    }

    /** Mark the outline of a rectangle, in capture pixels. */
    static void rect(float l, float t, float r, float b, float widthPx) {
        line(l, t, r, t, widthPx);
        line(l, b, r, b, widthPx);
        line(l, t, l, b, widthPx);
        line(r, t, r, b, widthPx);
    }

    /** Mark a circle outline, in capture pixels. */
    static void circle(float cx, float cy, float rad, float widthPx) {
        if (draw.length == 0) return;
        if (Float.isNaN(cx) || Float.isNaN(cy) || Float.isNaN(rad)) return;
        int r = radiusCells(widthPx);
        int s = step;
        int n = Math.max(16, Math.min(2000, (int) (2 * Math.PI * rad / s)));
        for (int i = 0; i < n; i++) {
            double a = 2 * Math.PI * i / n;
            blob((int) ((cx + Math.cos(a) * rad) / s), (int) ((cy + Math.sin(a) * rad) / s), r);
        }
    }

    /** Mark a filled disc, in capture pixels. */
    static void disc(float cx, float cy, float rad) {
        if (draw.length == 0) return;
        if (Float.isNaN(cx) || Float.isNaN(cy) || Float.isNaN(rad)) return;
        int s = step;
        blob((int) (cx / s), (int) (cy / s), Math.max(1, (int) Math.ceil(rad / s) + 1));
    }

    /** Mark a filled rectangle (the panel), in capture pixels. */
    static void fillRect(float l, float t, float r, float b) {
        if (draw.length == 0) return;
        int x0 = (int) (l / step), x1 = (int) (r / step);
        int y0 = (int) (t / step), y1 = (int) (b / step);
        for (int y = y0; y <= y1; y++) {
            if (y < 0 || y >= gh) continue;
            int row = y * gw;
            for (int x = x0; x <= x1; x++) {
                if (x < 0 || x >= gw) continue;
                int i = row + x;
                if (i >= 0 && i < draw.length) draw[i] = 1;
            }
        }
    }
}
