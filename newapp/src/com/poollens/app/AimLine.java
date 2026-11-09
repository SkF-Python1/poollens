package com.poollens.app;

/**
 * Ray-cast helper that traces the cue ball's aim line across the table and
 * folds it off the cushions to produce bank-shot predictions.
 *
 * Pure geometry, no Android types, so it is easy to reason about and reuse.
 */
final class AimLine {

    /** One straight flight of the ball between two cushion contacts. */
    static final class Segment {
        float x1, y1, x2, y2;
        /** 0 = first flight, 1 = after one cushion, ... */
        int bounce;
    }

    private AimLine() {
    }

    /**
     * Trace a shot.
     *
     * @param cueX,cueY   cue ball centre in screen pixels
     * @param angle       aim direction in radians (screen space, +y down)
     * @param ballRadius  radius of the cue ball, used to inset the cushions
     * @param left,top,right,bottom  playable table rectangle
     * @param maxBounces  how many cushions to fold off (1 = bank, 2 = double)
     * @param out         receives the segments; length <= maxBounces + 1
     * @return number of segments written
     */
    static int trace(float cueX, float cueY, float angle, float ballRadius,
                     float left, float top, float right, float bottom,
                     int maxBounces, Segment[] out) {
        if (out == null || out.length == 0) return 0;

        // Keep the centre of the ball this far from each cushion.
        float l = left + ballRadius;
        float t = top + ballRadius;
        float r = right - ballRadius;
        float b = bottom - ballRadius;
        if (r <= l || b <= t) return 0;

        float x = clamp(cueX, l, r);
        float y = clamp(cueY, t, b);

        double dx = Math.cos(angle);
        double dy = Math.sin(angle);
        if (Math.abs(dx) < 1e-6 && Math.abs(dy) < 1e-6) return 0;

        int count = 0;
        int bounces = 0;

        while (count < out.length) {
            // Distance to each cushion along the current direction.
            double tx = Double.MAX_VALUE;
            double ty = Double.MAX_VALUE;
            int wall = 0;

            if (dx > 1e-9) {
                tx = (r - x) / dx;
                if (tx <= 0.05) tx = Double.MAX_VALUE; // ignore wall we are already touching
                else wall = 1; // right
            } else if (dx < -1e-9) {
                tx = (l - x) / dx;
                if (tx <= 0.05) tx = Double.MAX_VALUE;
                else wall = 2; // left
            }
            if (dy > 1e-9) {
                ty = (b - y) / dy;
                if (ty <= 0.05) ty = Double.MAX_VALUE;
                else if (ty < tx) wall = 3; // bottom
            } else if (dy < -1e-9) {
                ty = (t - y) / dy;
                if (ty <= 0.05) ty = Double.MAX_VALUE;
                else if (ty < tx) wall = 4; // top
            }

            double dist;
            if (tx < ty) {
                dist = tx;
                wall = (dx > 0) ? 1 : 2;
            } else {
                dist = ty;
                wall = (dy > 0) ? 3 : 4;
            }
            if (!(dist > 1e-6) || dist == Double.MAX_VALUE) break;

            float hx = (float) (x + dx * dist);
            float hy = (float) (y + dy * dist);
            hx = clamp(hx, l, r);
            hy = clamp(hy, t, b);

            Segment s = out[count];
            if (s == null) {
                s = new Segment();
                out[count] = s;
            }
            s.x1 = x;
            s.y1 = y;
            s.x2 = hx;
            s.y2 = hy;
            s.bounce = bounces;
            count++;

            if (bounces >= maxBounces) break;

            // Fold off the cushion we just hit.
            if (wall == 1 || wall == 2) {
                dx = -dx;
            } else {
                dy = -dy;
            }
            x = clamp(hx, l, r);
            y = clamp(hy, t, b);
            bounces++;
        }

        return count;
    }

    /**
     * Walk a straight ray until it reaches a ball, the cushions, or the ray
     * length limit. Returns the travelled distance.
     */
    static float rayToCushion(float x, float y, float angle, float ballRadius,
                              float left, float top, float right, float bottom,
                              float maxLen) {
        float l = left + ballRadius;
        float t = top + ballRadius;
        float r = right - ballRadius;
        float b = bottom - ballRadius;

        double dx = Math.cos(angle);
        double dy = Math.sin(angle);

        double tx = Double.MAX_VALUE;
        double ty = Double.MAX_VALUE;
        if (dx > 1e-9) tx = (r - x) / dx;
        else if (dx < -1e-9) tx = (l - x) / dx;
        if (dy > 1e-9) ty = (b - y) / dy;
        else if (dy < -1e-9) ty = (t - y) / dy;

        double dist = Math.min(tx, ty);
        if (dist == Double.MAX_VALUE) dist = maxLen;
        if (dist > maxLen) dist = maxLen;
        if (dist < 0) dist = 0;
        return (float) dist;
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
