package com.poollens.app;

import android.graphics.Bitmap;
import android.util.Log;

/**
 * Finds the table, the cue ball, the aim guideline and the target ball.
 *
 * Colour ball detection was REMOVED on purpose. The game paints its aim
 * guideline, ghost-ball ring, direction arrows and cue stick in the same
 * near-white it uses for ball highlights, and that ink physically TOUCHES the
 * object balls, so every blob pass fused line + stick + ring + ball into shapes
 * that are not balls. The resulting ball list was wrong AND it fed the aim
 * logic, which is what made the extension jump and flicker.
 *
 * The object ball is now found GEOMETRICALLY instead: the aim guideline ends
 * exactly on the ghost-ball contact point, so walking the detected aim ray to
 * where the guideline stops gives the contact point, and the one ball that both
 * touches that point and lies along the ray is the target. No palette needed.
 *
 * The felt is a desaturated cyan-blue measured on device at hue 192-202.
 */
final class Detector {

    private static final String TAG = "PoolLens";

    static final int STEP = 3;

    /**
     * The resolution every absolute threshold was measured at. All pixel-based
     * constants are scaled by (captureLong / REF_LONG) so the detector can run
     * on a downscaled capture without any behaviour change.
     */
    static final int REF_LONG = 3200;
    private static final float STEP_REF = 3f;

    // Scratch buffers, grown once and reused. Allocating these per frame was
    // ~27 MB of garbage every frame at 3200x2136, which alone is enough to
    // keep the collector busy and stall the capture.
    private static int[] rgbaBuf = new int[0];
    private static int[] sampleBuf = new int[0];
    private static boolean[] feltBuf = new boolean[0];
    private static boolean[] solidBuf = new boolean[0];
    private static boolean[] seenBuf = new boolean[0];
    private static int[] stackBuf = new int[0];
    private static int[] curPtsBuf = new int[0];
    private static int[] bestPtsBuf = new int[0];
    private static int[] colCovBuf = new int[0];
    private static int[] rowCovBuf = new int[0];
    private static int[] coreCovBuf = new int[0];
    private static boolean[] tmpBuf = new boolean[0];
    private static int[] thinBuf = new int[360];
    private static float[] stubPtsX = new float[512];
    private static float[] stubPtsY = new float[512];

    /** Returns {@code buf} when it is big enough, else a new one of that size. */
    private static int[] ensure(int[] buf, int n) {
        return buf.length >= n ? buf : new int[n];
    }

    private static boolean[] ensure(boolean[] buf, int n) {
        return buf.length >= n ? buf : new boolean[n];
    }

    /** Zeroes the first {@code n} entries (cheaper than reallocating). */
    private static void clear(boolean[] buf, int n) {
        java.util.Arrays.fill(buf, 0, n, false);
    }

    // ------------------------------------------------------------ references

    /**
     * The cue ball, or the object ball the aim points at. Position and radius
     * only: colour and number detection were removed (see the class comment).
     */
    static final class Ball {
        final int x;
        final int y;
        final int radius;

        Ball(int x, int y, int radius) {
            this.x = x;
            this.y = y;
            this.radius = radius;
        }
    }

    static final class Result {
        /** The cue ball, or null. The aim line starts here. */
        Ball cue;
        int tableLeft, tableTop, tableRight, tableBottom;
        boolean tableFound;
        int feltCoverage;
        /** Direction of the game's own aim guideline, radians. NaN = unknown. */
        float aimAngle = Float.NaN;
        boolean aimFound;
        /** Confidence 0..100 for the detected aim angle. */
        int aimScore;
        /** The single object ball the aim line points at, or null.
         *  Found geometrically from the guideline/ghost, not by colour. */
        Ball aimTarget;
        /** Predicted contact point on the target ball (ghost ball centre). */
        float ghostX = Float.NaN, ghostY = Float.NaN;
        /** Direction the cue stick points, radians. NaN = no stick visible. */
        float stickAngle = Float.NaN;
        /** Target ball outgoing aim trajectory angle, radians. */
        float targetAngle = Float.NaN;
        /** Outermost tip of the game's white aim stub, if detected. */
        float stubTipX = Float.NaN, stubTipY = Float.NaN;
        /** Cue ball rebound deflection trajectory angle, radians. */
        float cueReboundAngle = Float.NaN;

        Result copy() {
            Result o = new Result();
            o.cue = cue;
            o.tableLeft = tableLeft;
            o.tableTop = tableTop;
            o.tableRight = tableRight;
            o.tableBottom = tableBottom;
            o.tableFound = tableFound;
            o.feltCoverage = feltCoverage;
            o.aimAngle = aimAngle;
            o.aimFound = aimFound;
            o.aimScore = aimScore;
            o.aimTarget = aimTarget;
            o.ghostX = ghostX;
            o.ghostY = ghostY;
            o.stickAngle = stickAngle;
            o.targetAngle = targetAngle;
            o.stubTipX = stubTipX;
            o.stubTipY = stubTipY;
            o.cueReboundAngle = cueReboundAngle;
            return o;
        }
    }

    private Detector() {
    }

    // --------------------------------------------------------------- colour

    /** Felt: bright cyan-blue, clearly more blue than red. */
    private static boolean isFelt(int r, int g, int b) {
        return b > 100 && g > 90 && b > r + 40 && g > r + 20;
    }

    static boolean isFeltPixel(int p) {
        int r = (p >> 16) & 0xFF;
        int g = (p >> 8) & 0xFF;
        int b = p & 0xFF;
        return isFelt(r, g, b);
    }

    // ------------------------------------------------------------ profiling

    /** Set false to strip all timing work out of the hot path. */
    static final boolean PROFILE = true;

    private static long pT0, pT1;
    private static final long[] pPhase = new long[8];
    private static final String[] pName = {
            "pixels", "sample", "classify", "table", "cue", "aim", "other", "-"};

    private static void mark(int phase) {
        long t = System.nanoTime();
        if (pT0 != 0) pPhase[phase] += t - pT1;
        pT1 = t;
    }

    private static void reportProfile(int w, int h, long totalNs) {
        StringBuilder sb = new StringBuilder();
        sb.append("profile ").append(w).append('x').append(h)
                .append(" total=").append(totalNs / 1000000).append("ms");
        for (int i = 0; i < pPhase.length; i++) {
            if (pPhase[i] == 0) continue;
            sb.append(' ').append(pName[i]).append('=')
                    .append(pPhase[i] / 1000000).append("ms");
            pPhase[i] = 0;
        }
        sb.append(" ink=").append(InkMask.marked());
        sb.append(" aimInk=").append(aimInkSkips);
        sb.append(" top=").append(topDeg[0]).append('/').append(topHits[0])
                .append(',').append(topDeg[1]).append('/').append(topHits[1])
                .append(',').append(topDeg[2]).append('/').append(topHits[2]);
        topDeg[0] = topDeg[1] = topDeg[2] = -1;
        topHits[0] = topHits[1] = topHits[2] = 0;
        Log.i(TAG, sb.toString());
    }

    /** Rings skipped in the winning aim direction because our ink covered them. */
    private static volatile int aimInkSkips;
    /** Top coarse angles and their raw ring hits, for the profile log. */
    private static final int[] topDeg = new int[3];
    private static final int[] topHits = new int[3];

    // ------------------------------------------------------------ main entry

    /**
     * Scale factor from the 3200 px reference capture the thresholds were
     * tuned against. Everything expressed in absolute pixels is multiplied by
     * this so the detector behaves identically at any capture resolution,
     * which is what lets the capture run downscaled for speed.
     */
    private static float sc = 1f;

    /** Capture long side in the current frame (~spectator for diagnostics). */
    private static int capLong = 3200;

    static float scale() {
        return sc;
    }

    /**
     * The coarse grid step the detector will use for a capture of this size.
     * Exposed so the overlay records its ink on EXACTLY the same grid; a
     * mismatch of a single cell makes the self-exclusion leak and the scan
     * lock onto our own line again.
     */
    static int stepFor(int w, int h) {
        float k = (float) Math.max(w, h) / REF_LONG;
        return Math.max(2, Math.round(STEP_REF * k));
    }

    static Result analyse(Bitmap src) {
        Result out = new Result();
        if (src == null || src.isRecycled()) return out;

        int w = src.getWidth();
        int h = src.getHeight();
        if (w < 64 || h < 64) return out;

        // Adaptive grid step: a fixed 3 px step on a half-size capture would
        // sample the same physical points but with a coarser angular resolution
        // per ball, so keep the step proportional to the capture instead.
        capLong = Math.max(w, h);
        // MUST be a float divide: integer division here returned 0 for any
        // downscaled capture (1600/3200 == 0), which collapsed every scaled
        // length in the detector to zero -- the aim scan walked a ray of length
        // 0 and locked onto whatever happened to sit next to the cue ball.
        sc = (float) capLong / REF_LONG;
        int step = Math.max(2, Math.round(STEP_REF * sc));

        int gw = w / step;
        int gh = h / step;
        int total = gw * gh;

        int[] rgba = ensure(rgbaBuf, w * h);
        rgbaBuf = rgba;

        if (PROFILE) pT0 = pT1 = System.nanoTime();
        try {
            src.getPixels(rgba, 0, w, 0, 0, w, h);
        } catch (Exception e) {
            return out;
        }
        if (PROFILE) mark(0);

        int[] sample = ensure(sampleBuf, total);
        sampleBuf = sample;
        // The long-side loop order matters: walking rows lets the destination
        // stride be a running counter instead of a multiply per cell.
        for (int gy = 0, srcRow = 0, dst = 0; gy < gh; gy++, srcRow += step * w) {
            int s = srcRow;
            for (int gx = 0; gx < gw; gx++, s += step) {
                sample[dst++] = rgba[s];
            }
        }
        if (PROFILE) mark(1);

        boolean[] felt = ensure(feltBuf, total);
        feltBuf = felt;
        clear(felt, total);
        int feltCount = 0;

        // The overlay window is mirrored into the capture too, so our own
        // drawings are visible here: the aim line reads as a bright thin line,
        // the border as felt and the shot lines as ball material. Every cell the
        // overlay painted is treated as unknown and excluded from both masks,
        // otherwise the detector locks onto its own output.
        InkMask.configure(w, h, step);

        for (int gy = 0; gy < gh; gy++) {
            int row = gy * gw;
            int pixY = gy * step;
            for (int gx = 0; gx < gw; gx++) {
                int i = row + gx;
                // The control panel is a separate window, so only its rectangle
                // is known; its buttons and text would otherwise read as balls.
                if (InkMask.isPanel(gx * step, pixY)) continue;

                int p = sample[i];
                int r = (p >> 16) & 0xFF;
                int g = (p >> 8) & 0xFF;
                int b = p & 0xFF;
                if (isFelt(r, g, b)) {
                    felt[i] = true;
                    feltCount++;
                }
            }
        }
        if (PROFILE) mark(2);

        out.feltCoverage = (int) (100L * feltCount / Math.max(1, total));
        if (out.feltCoverage < 10) {
            if (PROFILE) reportProfile(w, h, System.nanoTime() - pT0);
            return out; // table not on screen
        }

        findTable(out, felt, gw, gh, step);
        if (PROFILE) mark(3);
        findCueBall(out, rgba, w, h, step);
        if (PROFILE) mark(4);
        findAim(out, rgba, w, h, felt, gw, gh);
        if (PROFILE) mark(5);
        findGhostTarget(out, rgba, w, h, felt, gw, gh, step);
        if (PROFILE) mark(6);
        reportProfile(w, h, System.nanoTime() - pT0);
        return out;
    }

    // ----------------------------------------------------------------- aim

    /**
     * Locate the game's own aim guideline.
     *
     * Angular voting over every near-white pixel does not work reliably: pocket
     * highlights, the cue stick and ball specular spots all cast votes, so the
     * scene rarely has one direction that dominates by 2x and the detector kept
     * giving up (aim=--).
     *
     * A radial ray scan is far more robust. For every candidate angle we march
     * outward from the cue ball and count how many samples along that ray look
     * like the thin near-white guideline. Off-axis clutter contributes nothing
     * because it is never sampled, and the score is naturally normalised by how
     * far the line survives. The winning ray is then refined to sub-degree
     * accuracy so the extension still lands on a whole ball at the far cushion.
     */
    private static void findAim(Result out, int[] rgba, int w, int h,
                                boolean[] felt, int gw, int gh) {
        Ball cue = out.cue;
        if (cue == null) return;

        final float cx = cue.x;
        final float cy = cue.y;

        final int rings = 120;
        final float ringStep = Math.max(2f, 7f * sc);
        final float r0 = cue.radius + Math.max(1f, 4f * sc);
        final int tang = Math.max(2, Math.round(3 * Math.max(0.6f, sc)));
        final float tangStep = Math.max(1f, 1f * Math.max(0.6f, sc));

        // Find the cue stick FIRST. In 8 Ball Pool, the cue stick is rigidly
        // positioned opposite the shot line, making it a reliable orientation anchor.
        float stickAngle = findCueStick(out, rgba, w, h, cue);
        out.stickAngle = stickAngle;
        boolean hasStick = !Float.isNaN(stickAngle);

        float bestAngle = Float.NaN;
        int bestHits = 0;

        if (hasStick) {
            // Anchor search around stickAngle (+/- 12 degrees).
            // A fine search with 0.2-degree steps finds the game guideline
            // without being distracted by reflections, pocket rims, or balls on other parts of table.
            float stickDeg = (float) Math.toDegrees(stickAngle);
            float minDeg = stickDeg - 12f;
            float maxDeg = stickDeg + 12f;
            int numSteps = Math.round((maxDeg - minDeg) / 0.2f) + 1;
            int[] hitCounts = new int[numSteps];
            float[] angleValues = new float[numSteps];
            int maxH = 0;
            int idx = 0;
            for (float d = minDeg; d <= maxDeg + 0.01f && idx < numSteps; d += 0.2f, idx++) {
                angleValues[idx] = d;
                double th = Math.toRadians(d);
                float dx = (float) Math.cos(th);
                float dy = (float) Math.sin(th);
                float pxv = -dy;
                float pyv = dx;

                int hits = 0;
                int misses = 0;
                for (int i = 1; i <= rings; i++) {
                    float dist = r0 + i * ringStep;
                    int rx = (int) (cx + dx * dist);
                    int ry = (int) (cy + dy * dist);
                    if (rx < 1 || ry < 1 || rx >= w - 1 || ry >= h - 1) break;

                    boolean hit = false;
                    for (int k = -tang; k <= tang; k++) {
                        float off = k * tangStep;
                        int x = (int) (cx + dx * dist + pxv * off);
                        int y = (int) (cy + dy * dist + pyv * off);
                        if (x < 1 || y < 1 || x >= w - 1 || y >= h - 1) continue;
                        if (InkMask.isInk(x, y)) continue;
                        if (isGuidePixel(rgba[y * w + x])) { hit = true; break; }
                    }
                    if (hit) {
                        hits++;
                        misses = 0;
                    } else {
                        misses++;
                        // Guidelines stop at the ghost ball contact point; consecutive misses
                        // indicate the end of the line rather than a low score.
                        if (misses > 10 && i > 12) break;
                    }
                }
                hitCounts[idx] = hits;
                if (hits > maxH) maxH = hits;
            }

            if (maxH >= 5) {
                // Average angles that reached >= 90% of max hits to find exact center
                float sumA = 0f;
                int countA = 0;
                int thresh = (int) (maxH * 0.90f);
                for (int i = 0; i < idx; i++) {
                    if (hitCounts[i] >= thresh) {
                        sumA += angleValues[i];
                        countA++;
                    }
                }
                if (countA > 0) {
                    bestAngle = (float) Math.toRadians(sumA / countA);
                    bestHits = maxH;
                }
            }
        }

        // Fallback: if cue stick is visible, stick reliably anchors aim direction.
        // If no stick visible, perform full 360-degree coarse scan.
        if (bestHits < 5) {
            if (hasStick) {
                bestAngle = stickAngle;
                bestHits = 5;
            } else {
                int coarseHits = 0;
                float coarseBest = Float.NaN;
                for (int a = 0; a < 360; a += 2) {
                    double th = Math.toRadians(a);
                    float dx = (float) Math.cos(th);
                    float dy = (float) Math.sin(th);
                    float pxv = -dy;
                    float pyv = dx;

                    int hits = 0;
                    int misses = 0;
                    for (int i = 1; i <= rings; i++) {
                        float dist = r0 + i * ringStep;
                        int rx = (int) (cx + dx * dist);
                        int ry = (int) (cy + dy * dist);
                        if (rx < 1 || ry < 1 || rx >= w - 1 || ry >= h - 1) break;

                        boolean hit = false;
                        for (int k = -tang; k <= tang; k++) {
                            float off = k * tangStep;
                            int x = (int) (cx + dx * dist + pxv * off);
                            int y = (int) (cy + dy * dist + pyv * off);
                            if (x < 1 || y < 1 || x >= w - 1 || y >= h - 1) continue;
                            if (InkMask.isInk(x, y)) continue;
                            if (isGuidePixel(rgba[y * w + x])) { hit = true; break; }
                        }
                        if (hit) {
                            hits++;
                            misses = 0;
                        } else {
                            misses++;
                            if (misses > 10 && i > 12) break;
                        }
                    }
                    if (hits > coarseHits) {
                        coarseHits = hits;
                        coarseBest = (float) th;
                    }
                }
                if (coarseHits >= 10) {
                    bestHits = coarseHits;
                    bestAngle = coarseBest;
                }
            }
        }

        if (bestHits >= 10 && !Float.isNaN(bestAngle)) {
            out.aimAngle = bestAngle;
            out.aimFound = true;
            out.aimScore = Math.min(100, bestHits * 100 / 30);
        } else if (hasStick) {
            out.aimAngle = stickAngle;
            out.aimFound = true;
            out.aimScore = 40;
        }
    }

    /**
     * Direction the cue stick points, in radians, oriented the way the ball will
     * travel (i.e. away from the stick butt), or NaN when no stick is visible.
     *
     * The stick is the only long, thin, solid object touching the cue ball from
     * behind, so thinness is what identifies it. Each ring is tested at the ray
     * AND 18 px to either side: a stick lights the centre while the flanks stay
     * felt, whereas a rail, a ball or a table graphic is broad and lights all
     * three. Colour is used to exclude pure white, which is what separates the
     * stick from the game's own guideline.
     */
    private static float findCueStick(Result out, int[] rgba, int w, int h, Ball cue) {
        final float cx = cue.x;
        final float cy = cue.y;
        final float r0 = cue.radius + 2f * sc;
        final int rings = 44;
        final float ringStep = 5f * sc;
        final int flank = Math.max(6, Math.round(18 * sc));

        int[] thin = ensure(thinBuf, 360);
        thinBuf = thin;
        java.util.Arrays.fill(thin, 0, 360, 0);
        int bestDeg = -1;

        for (int a = 0; a < 360; a++) {
            double th = Math.toRadians(a);
            float dx = (float) Math.cos(th);
            float dy = (float) Math.sin(th);
            float pxv = -dy;
            float pyv = dx;

            int hits = 0;
            for (int i = 1; i <= rings; i++) {
                float d = r0 + i * ringStep;
                int x = (int) (cx + dx * d);
                int y = (int) (cy + dy * d);
                if (x < 1 || y < 1 || x >= w - 1 || y >= h - 1) break;
                // The dashed strike line we draw behind the cue ball is a
                // saturated-white hybrid (0x99FFFFFF) that isSolidPixel would
                // accept; skip our own ink so the scan cannot see it.
                if (InkMask.isInk(x, y)) continue;
                if (!isSolidPixel(rgba[y * w + x])) continue;

                // Centre is solid; both flanks must be felt for this to be a
                // thin object rather than a rail or a graphic.
                boolean narrow = true;
                for (int s = -1; s <= 1 && narrow; s += 2) {
                    float off = s * flank;
                    int fx = (int) (cx + dx * d + pxv * off);
                    int fy = (int) (cy + dy * d + pyv * off);
                    if (fx < 1 || fy < 1 || fx >= w - 1 || fy >= h - 1) continue;
                    if (InkMask.isInk(fx, fy)) continue;
                    if (isSolidPixel(rgba[fy * w + fx])) narrow = false;
                }
                if (narrow) hits++;
            }
            thin[a] = hits;
            if (bestDeg < 0 || hits > thin[bestDeg]) bestDeg = a;
        }

        // The stick is the only long, thin, COLOURED object touching the cue
        // ball from behind. White is deliberately not accepted: the game's own
        // aim guideline also leaves the cue ball as a thin bright line, and it
        // is collinear with the stick, so brightness alone cannot tell them
        // apart. Wood is saturated, the guideline is not.
        final int minThin = Math.max(8, (int) (rings * 0.28f));
        final int bestThin = thin[bestDeg];
        if (bestThin < minThin) return Float.NaN;

        // Must also clearly beat every direction more than 20 degrees away,
        // otherwise the "stick" is table clutter. Angles within 20 degrees are
        // the same line seen slightly off, so they are not competitors.
        int rival = 0;
        for (int a = 0; a < 360; a++) {
            int d = Math.abs(a - bestDeg);
            if (d > 180) d = 360 - d;
            if (d > 20 && thin[a] > rival) rival = thin[a];
        }
        if (bestThin < rival * 1.2f) return Float.NaN;

        // The stick points AT the ball, so the shot travels the other way.
        return (float) Math.toRadians((bestDeg + 180) % 360);
    }

    /**
     * Saturated, non-felt pixel. Used for the cue stick, whose defining trait is
     * colour: unlike the aim guideline it is never a flat bright white.
     */
    private static boolean isSolidPixel(int p) {
        int r = (p >> 16) & 0xFF;
        int g = (p >> 8) & 0xFF;
        int b = p & 0xFF;
        if (isFelt(r, g, b)) return false;
        int mx = Math.max(r, Math.max(g, b));
        int mn = Math.min(r, Math.min(g, b));
        if (mx < 50) return false;            // shadow / black
        // Cue stick is wooden/colored: must have distinct saturation and warm tone
        if (mx - mn < 25) return false;       // rejects grey/white guideline outlines
        if (r < b) return false;              // warm wood tone (r >= b)
        return true;
    }

    /** Shortest signed distance between two radian angles, in degrees. */
    private static float angleDeltaDeg(float a, float b) {
        float d = a - b;
        while (d > 180f) d -= 360f;
        while (d < -180f) d += 360f;
        return d;
    }

    /**
     * The guideline is a bright, low-saturation line. Felt is excluded so a
     * bright cloth highlight cannot be mistaken for it.
     */
    private static boolean isGuidePixel(int p) {
        int r = (p >> 16) & 0xFF;
        int g = (p >> 8) & 0xFF;
        int b = p & 0xFF;
        int mn = Math.min(r, Math.min(g, b));
        int mx = Math.max(r, Math.max(g, b));
        if (mn < 150) return false;
        if (mx - mn > 50) return false;
        return true;
    }

    /**
     * Ghost ball: where the cue ball's centre is at the moment of contact.
     *
     * This must be the point on the aim RAY exactly one combined radius from the
     * target centre - a ray/circle intersection, not a fixed step back along the
     * aim direction. Stepping back a fixed distance puts the ghost, the cue and
     * the target all on one line, which makes the object ball's predicted path
     * identical to the aim line and hides the cut angle entirely. Solving the
     * intersection instead puts the gap off to one side on a cut shot, so the
     * object ball visibly leaves at an angle.
     */
    static void ghostFor(Result r) {
        r.ghostX = Float.NaN;
        r.ghostY = Float.NaN;
        if (r.cue == null || r.aimTarget == null || Float.isNaN(r.aimAngle)) return;

        float dx = (float) Math.cos(r.aimAngle);
        float dy = (float) Math.sin(r.aimAngle);
        float ox = r.aimTarget.x - r.cue.x;
        float oy = r.aimTarget.y - r.cue.y;

        float reach = r.cue.radius + r.aimTarget.radius;
        float along = ox * dx + oy * dy;              // projection of target on ray
        if (along <= 0) return;

        float perp2 = ox * ox + oy * oy - along * along;
        if (perp2 < 0f) perp2 = 0f;                      // float rounding guard
        float h2 = reach * reach - perp2;             // negative => the ray misses
        if (h2 <= 0f) return;                         // grazing or no contact

        float dist = along - (float) Math.sqrt(h2);    // first contact point
        if (dist < 0f) return;                         // contact is behind the cue

        r.ghostX = r.cue.x + dx * dist;
        r.ghostY = r.cue.y + dy * dist;
    }

    /**
     * Find the object ball the aim points at, WITHOUT a colour palette.
     *
     * The guideline's own length is NOT trusted: the capture mirrors our own
     * overlay, so the white line we drew ourselves would fake the contact
     * point. Instead the ball is found directly - walk out along the aim ray
     * and look for a run of material that only a ball can make: a patch that
     * is not felt, not our ink, not near-white, and is still there a ball
     * radius to either side of the ray. That rejects every thin stroke and
     * accepts any coloured ball. The middle of the run is the contact-side
     * centre and a perpendicular pass re-centres it, because the ray usually
     * passes near rather than exactly through the centre.
     *
     * Every ball in pool shares the cue ball's radius, so the ghost ball is
     * then a plain ray/circle intersection and no second radius estimate is
     * needed.
     */
    private static void findGhostTarget(Result out, int[] rgba, int w, int h,
                                        boolean[] felt,
                                        int gw, int gh, int step) {
        if (out.cue == null || !out.aimFound || Float.isNaN(out.aimAngle)) return;

        final float cx = out.cue.x;
        final float cy = out.cue.y;
        final float r = Math.max(8f, out.cue.radius);
        final float dirx = (float) Math.cos(out.aimAngle);
        final float diry = (float) Math.sin(out.aimAngle);
        final float pxv = -diry, pyv = dirx;

        final boolean inTable = out.tableFound;
        final float tL = inTable ? out.tableLeft + r : r;
        final float tT = inTable ? out.tableTop + r : r;
        final float tR = inTable ? out.tableRight - r : w - r;
        final float tB = inTable ? out.tableBottom - r : h - r;

        final float maxD = r * 50f;
        final int tang = Math.max(2, Math.round(2 * sc));

        // Step 1: Trace the guideline along dirx, diry to find its termination.
        // In 8 Ball Pool, the incoming cue line is a continuous solid line.
        // A gap of > 10 pixels means we have hit the end of the incoming cue track.
        float lastWhiteD = -1f;
        int consecMisses = 0;
        final float maxDashGap = Math.max(8f, r * 0.7f);

        for (float d = r * 1.5f; d <= maxD; d += 2f) {
            float rx = cx + dirx * d;
            float ry = cy + diry * d;
            if (rx < tL || rx > tR || ry < tT || ry > tB) break;

            boolean hit = false;
            for (int k = -tang; k <= tang; k += tang) {
                int px = (int) (rx + pxv * k);
                int py = (int) (ry + pyv * k);
                if (px < 1 || py < 1 || px >= w - 1 || py >= h - 1) continue;
                if (InkMask.isInk(px, py)) continue;
                if (isGuidePixel(rgba[py * w + px])) {
                    hit = true;
                    break;
                }
            }

            if (hit) {
                lastWhiteD = d;
                consecMisses = 0;
            } else if (lastWhiteD > 0) {
                consecMisses += 2;
                if (consecMisses > maxDashGap) break;
            }
        }

        if (lastWhiteD < r * 1.8f) return;

        // Step 2: Pinpoint the game-drawn ghost ball ring center
        float bestGhostD = lastWhiteD - r * 0.7f;
        int bestRingScore = -1;
        float searchMin = lastWhiteD - r * 1.5f;
        float searchMax = lastWhiteD + r * 0.2f;

        for (float testD = searchMin; testD <= searchMax; testD += 1.0f) {
            float gx0 = cx + dirx * testD;
            float gy0 = cy + diry * testD;
            int ringHits = 0;
            for (int deg = 0; deg < 360; deg += 15) {
                double th = Math.toRadians(deg);
                int px = (int) (gx0 + (r * 0.85f) * Math.cos(th));
                int py = (int) (gy0 + (r * 0.85f) * Math.sin(th));
                if (px < 1 || py < 1 || px >= w - 1 || py >= h - 1) continue;
                if (InkMask.isInk(px, py)) continue;
                if (isGuidePixel(rgba[py * w + px])) {
                    ringHits++;
                }
            }
            if (ringHits > bestRingScore) {
                bestRingScore = ringHits;
                bestGhostD = testD;
            }
        }

        float gx = cx + dirx * bestGhostD;
        float gy = cy + diry * bestGhostD;
        out.ghostX = gx;
        out.ghostY = gy;

        // Step 3: Radial scan from ghost ball center for outgoing stubs
        // Direction of incoming cue line (to exclude from candidates):
        final float aimDeg = (float) Math.toDegrees(out.aimAngle);
        final float incDeg = (aimDeg + 180f) % 360f;

        int[] rayHits = new int[360];
        int[] rayInner = new int[360];
        float[] rayMaxD = new float[360];

        final int innerLimit = Math.max(3, Math.round(r * 0.75f));
        final int outerMin = Math.max(innerLimit + 1, Math.round(r * 1.2f));
        final int outerMax = Math.round(r * 4.5f);

        for (int a = 0; a < 360; a++) {
            float dInc = Math.abs((a - incDeg + 180f) % 360f - 180f);
            if (dInc < 42f) continue; // Skip incoming cue line and cue stick cone

            double th = Math.toRadians(a);
            float cdx = (float) Math.cos(th);
            float cdy = (float) Math.sin(th);

            int hits = 0;
            int inner = 0;
            float maxHitD = 0f;

            for (int d = Math.max(3, Math.round(r * 0.2f)); d <= outerMax; d++) {
                int px = (int) (gx + cdx * d);
                int py = (int) (gy + cdy * d);
                if (px < 1 || py < 1 || px >= w - 1 || py >= h - 1) break;
                if (InkMask.isInk(px, py)) continue;
                if (isGuidePixel(rgba[py * w + px])) {
                    if (d <= innerLimit) inner++;
                    if (d >= outerMin) {
                        hits++;
                        if (d > maxHitD) maxHitD = d;
                    }
                }
            }
            rayHits[a] = hits;
            rayInner[a] = inner;
            rayMaxD[a] = maxHitD;
        }

        // Smooth rayHits to remove plateaus and noise
        float[] smoothHits = new float[360];
        for (int a = 0; a < 360; a++) {
            int prev = (a + 359) % 360;
            int next = (a + 1) % 360;
            smoothHits[a] = (rayHits[prev] + rayHits[a] * 2f + rayHits[next]) * 0.25f;
        }

        // Find candidate peaks
        int bestTargetA = -1;
        float bestTargetHits = 0f;
        float bestTargetMaxD = 0f;

        int bestReboundA = -1;
        float bestReboundHits = 0f;
        float bestReboundMaxD = 0f;

        final float minTargetReach = r * 2.3f;

        for (int a = 0; a < 360; a++) {
            int prev = (a + 359) % 360;
            int next = (a + 1) % 360;
            float hCur = smoothHits[a];
            if (hCur < 6f || hCur < smoothHits[prev] || hCur <= smoothHits[next]) continue;

            if (rayInner[a] >= 4) {
                // Cue rebound line starts inside / at ghost ring
                if (hCur > bestReboundHits) {
                    bestReboundHits = hCur;
                    bestReboundA = a;
                    bestReboundMaxD = rayMaxD[a];
                }
            } else if (rayMaxD[a] >= minTargetReach) {
                // Target stub line must extend past the target ball surface
                if (hCur > bestTargetHits) {
                    bestTargetHits = hCur;
                    bestTargetA = a;
                    bestTargetMaxD = rayMaxD[a];
                }
            }
        }

        // If target stub was found:
        if (bestTargetA >= 0 && bestTargetHits >= 7f) {
            float th = (float) Math.toRadians(bestTargetA);
            float dx = (float) Math.cos(th);
            float dy = (float) Math.sin(th);

            out.targetAngle = th;
            out.stubTipX = gx + dx * bestTargetMaxD;
            out.stubTipY = gy + dy * bestTargetMaxD;

            // Target ball center lies along targetAngle at distance 2*r from ghost
            float tx = gx + dx * (2.0f * r);
            float ty = gy + dy * (2.0f * r);
            out.aimTarget = new Ball(Math.round(tx), Math.round(ty), Math.round(r));
        }

        // If cue rebound line was found:
        if (bestReboundA >= 0 && bestReboundHits >= 7f) {
            out.cueReboundAngle = (float) Math.toRadians(bestReboundA);
        } else if (bestTargetA >= 0) {
            // Geometric fallback for cue rebound if game stub is short/hidden
            float nx = (float) Math.cos(out.targetAngle);
            float ny = (float) Math.sin(out.targetAngle);
            float dot = dirx * nx + diry * ny;
            float qx = dirx - dot * nx;
            float qy = diry - dot * ny;
            float qd = (float) Math.hypot(qx, qy);
            if (qd > 0.05f) {
                out.cueReboundAngle = (float) Math.atan2(qy / qd, qx / qd);
            }
        }
    }

    /**
     * True when (x,y) sits on a ball rather than on a line: ball material here
     * and still material a ball radius to one side of the ray. A stroke is only
     * a few pixels wide, so both flanks fall on cloth and it is rejected.
     */
    private static boolean isBallPatch(int[] rgba, boolean[] felt, int w, int h,
                                       int x, int y, float dirx, float diry,
                                       float r, int gw, int gh, int step) {
        if (!ballPixel(rgba, felt, w, h, x, y, gw, gh, step)) return false;
        final int arm = Math.max(2, Math.round(0.55f * r));
        final int ox = Math.round(-diry * arm);
        final int oy = Math.round(dirx * arm);
        return ballPixel(rgba, felt, w, h, x + ox, y + oy, gw, gh, step)
                || ballPixel(rgba, felt, w, h, x - ox, y - oy, gw, gh, step);
    }

    /** Ball material: on the table, not felt, not our ink, not near-white. */
    private static boolean ballPixel(int[] rgba, boolean[] felt, int w, int h,
                                     int x, int y, int gw, int gh, int step) {
        if (x < 1 || y < 1 || x >= w - 1 || y >= h - 1) return false;
        // felt[] holds one flag per SAMPLED cell, not per pixel.
        int mx = x / step, my = y / step;
        if (mx < 0 || my < 0 || mx >= gw || my >= gh) return false;
        if (felt[my * gw + mx]) return false;
        if (InkMask.isInkCell(mx, my)) return false;
        return isBallish(rgba[y * w + x], false);
    }

    /**
     * Signed perpendicular distance from (bx,by) to the middle of the ball it
     * sits in, found by walking both ways until the material stops.
     */
    private static float perpOffset(int[] rgba, boolean[] felt, int w, int h,
                                    float bx, float by, float pxv, float pyv,
                                    float r, int gw, int gh, int step) {
        float pos = 0f, neg = 0f;
        final float lim = 1.3f * r;
        for (float d = 1f; d <= lim; d += 1f) {
            if (ballPixel(rgba, felt, w, h, (int) (bx + pxv * d),
                    (int) (by + pyv * d), gw, gh, step)) pos = d;
            if (ballPixel(rgba, felt, w, h, (int) (bx - pxv * d),
                    (int) (by - pyv * d), gw, gh, step)) neg = d;
        }
        return (pos - neg) * 0.5f;
    }

    /** Local disk clamp. */
    private static float clampF(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** A pixel that is plausibly a ball surface: neither felt nor near-white. */
    private static boolean isBallish(int p, boolean feltPix) {
        if (feltPix) return false;
        int r = (p >> 16) & 0xFF;
        int g = (p >> 8) & 0xFF;
        int b = p & 0xFF;
        int mx = Math.max(r, Math.max(g, b));
        int mn = Math.min(r, Math.min(g, b));
        if (mx > 185 && mx - mn < 70) return false;   // near-white: cue ball/line
        if (mx < 25 && mx - mn < 20) return false;    // deep shadow
        return true;
    }

    /** Shortest signed distance between two angles in degrees. */
    private static int angleDelta(int a, int b) {
        int d = (a - b) % 360;
        if (d > 180) d -= 360;
        if (d < -180) d += 360;
        return d;
    }

    /** True when the pixel is brighter than the pixels across the line. */
    private static boolean isRidge(int[] rgba, int w, int h, int x, int y) {
        int step = 6;
        if (x - step < 0 || x + step >= w || y - step < 0 || y + step >= h) {
            return false;
        }
        double l = lum(rgba[y * w + x]);
        double left = lum(rgba[y * w + x - step]);
        double right = lum(rgba[y * w + x + step]);
        double up = lum(rgba[(y - step) * w + x]);
        double down = lum(rgba[(y + step) * w + x]);

        boolean thinH = (l - (left + right) / 2) > 22;
        boolean thinV = (l - (up + down) / 2) > 22;
        return thinH || thinV;
    }

    private static double lum(int p) {
        int r = (p >> 16) & 0xFF;
        int g = (p >> 8) & 0xFF;
        int b = p & 0xFF;
        return 0.299 * r + 0.587 * g + 0.114 * b;
    }

    // --------------------------------------------------------------- table

    /**
     * Find the playable surface.
     *
     * A plain bounding box of every felt pixel is wrong: pockets, rail
     * highlights and the animated background all register as felt and drag the
     * box outward. Instead take the largest connected felt region and then keep
     * only the rows/columns that are mostly covered by it, which cuts off the
     * pockets and any stray pixels.
     */
    private static void findTable(Result out, boolean[] felt, int gw, int gh,
                                  int step) {
        int total = gw * gh;

        // The game draws thin dark aiming/target decorations ON the cloth. They
        // are not felt-coloured, so they slice the play surface into pieces and
        // the largest piece ends up short of the real cushion. A morphological
        // closing (dilate then erode) with a small radius bridges any line up to
        // 2*R_C*STEP pixels wide without moving the outer edges, because the
        // following erosion restores the original boundary.
        final int R_C = 2;
        boolean[] solid = ensure(solidBuf, total);
        solidBuf = solid;
        close(felt, solid, gw, gh, R_C);

        boolean[] seen = ensure(seenBuf, total);
        seenBuf = seen;
        clear(seen, total);
        int[] stack = ensure(stackBuf, total);
        stackBuf = stack;

        // Single flood fill at a time, keeping the largest component's pixels.
        int[] bestPts = ensure(bestPtsBuf, total);
        bestPtsBuf = bestPts;
        int bestCount = 0;
        int[] curPts = ensure(curPtsBuf, total);
        curPtsBuf = curPts;

        for (int start = 0; start < total; start++) {
            if (!solid[start] || seen[start]) continue;

            int sp = 0;
            int count = 0;
            stack[sp++] = start;
            seen[start] = true;

            while (sp > 0) {
                int cur = stack[--sp];
                curPts[count++] = cur;
                int cy = cur / gw;
                int cx = cur - cy * gw;

                if (cx > 0) { int n = cur - 1; if (solid[n] && !seen[n]) { seen[n] = true; stack[sp++] = n; } }
                if (cx < gw - 1) { int n = cur + 1; if (solid[n] && !seen[n]) { seen[n] = true; stack[sp++] = n; } }
                if (cy > 0) { int n = cur - gw; if (solid[n] && !seen[n]) { seen[n] = true; stack[sp++] = n; } }
                if (cy < gh - 1) { int n = cur + gw; if (solid[n] && !seen[n]) { seen[n] = true; stack[sp++] = n; } }
            }

            if (count > bestCount) {
                bestCount = count;
                System.arraycopy(curPts, 0, bestPts, 0, count);
            }
        }

        if (bestCount < total / 50) return; // no credible table on screen

        // The largest connected felt region IS the play surface: pockets and
        // rails are not felt-coloured, so they never join it. But its plain
        // bounding box is still wrong at the corners, where the pocket mouths
        // and shadow graphics bridge a few felt cells diagonally and drag the
        // box out to the rail posts (measured: bbox 372,629-2824,1906 versus a
        // true cloth of 373,630-2825,1906 plus spurious corner spikes).
        //
        // So the box is derived from the COMPONENT's coverage instead of from
        // extreme pixels: a row or column only counts as part of the table when
        // the component fills most of it. Corner spikes are a few cells wide and
        // never pass that test, while the real cushions do.
        int minY = gh, maxY = -1;
        for (int i = 0; i < bestCount; i++) {
            int cy = bestPts[i] / gw;
            if (cy < minY) minY = cy;
            if (cy > maxY) maxY = cy;
        }
        if (minY > maxY) return;

        // Rows/columns covered by the component, as a per-cell count, built in
        // one pass so this stays cheap.
        int[] colCov = ensure(colCovBuf, gw);
        colCovBuf = colCov;
        int[] rowCov = ensure(rowCovBuf, gh);
        rowCovBuf = rowCov;
        java.util.Arrays.fill(colCov, 0, gw, 0);
        java.util.Arrays.fill(rowCov, 0, gh, 0);
        for (int i = 0; i < bestCount; i++) {
            int idx = bestPts[i];
            int cy = idx / gw;
            int cx = idx - cy * gw;
            colCov[cx]++;
            rowCov[cy]++;
        }

        // Work inside the vertical core of the component only, so a corner spike
        // cannot inflate the row band it is measured against.
        int bandTop = minY + (maxY - minY) / 4;
        int bandBot = maxY - (maxY - minY) / 4;
        if (bandBot <= bandTop) {
            bandTop = minY;
            bandBot = maxY;
        }
        int bandHeight = bandBot - bandTop + 1;

        // Column coverage is only counted inside the vertical core.
        int[] coreCov = ensure(coreCovBuf, gw);
        coreCovBuf = coreCov;
        java.util.Arrays.fill(coreCov, 0, gw, 0);
        for (int i = 0; i < bestCount; i++) {
            int idx = bestPts[i];
            int cy = idx / gw;
            if (cy < bandTop || cy > bandBot) continue;
            coreCov[idx - cy * gw]++;
        }

        int left = -1, right = -1;
        for (int x = 0; x < gw; x++) {
            if (coreCov[x] >= bandHeight * 0.35f) {
                if (left < 0) left = x;
                right = x;
            }
        }
        if (left < 0) return;

        // Same again horizontally: within the accepted column range, a row is
        // table only when the component covers most of that width.
        int bandWidth = right - left + 1;
        int top = -1, bottom = -1;
        // Reset rowCov: the earlier pass counted ALL component cells, but now
        // we want only cells inside the accepted column range.
        java.util.Arrays.fill(rowCov, 0, gh, 0);
        for (int i = 0; i < bestCount; i++) {
            int idx = bestPts[i];
            int cx = idx - (idx / gw) * gw;
            if (cx < left || cx > right) continue;
            rowCov[idx / gw]++;
        }
        for (int y = 0; y < gh; y++) {
            if (rowCov[y] >= bandWidth * 0.35f) {
                if (top < 0) top = y;
                bottom = y;
            }
        }
        if (top < 0) return;

        // Reject implausible aspect ratios (a HUD strip is not a pool table).
        int bw = right - left + 1;
        int bh = bottom - top + 1;
        if (bw < 8 || bh < 8) return;
        float aspect = (float) Math.max(bw, bh) / Math.max(1, Math.min(bw, bh));
        if (aspect < 1.35f || aspect > 2.6f) return;

        // Inset table boundaries to match playable cushion face
        int cushionInset = Math.max(1, Math.round(22 * sc));
        out.tableLeft = (left * step) + cushionInset;
        out.tableTop = (top * step) + cushionInset;
        out.tableRight = ((right + 1) * step - 1) - cushionInset;
        out.tableBottom = ((bottom + 1) * step - 1) - cushionInset;
        out.tableFound = true;
    }

    // ------------------------------------------------------------- helpers

    /**
     * Morphological closing with a square structuring element of radius
     * {@code rad} (in grid cells): dilate, then erode by the same amount.
     * The erosion restores the outer boundary, so this only fills the thin
     * gaps that decorative lines cut through the cloth.
     *
     * Separable: a square window's max is a horizontal max followed by a
     * vertical max, which turns O(rad^2) work per cell into O(rad) and is what
     * keeps this off the critical path at 60 Hz.
     */
    private static void close(boolean[] src, boolean[] out, int gw, int gh, int rad) {
        if (rad <= 0) {
            System.arraycopy(src, 0, out, 0, Math.min(src.length, out.length));
            return;
        }

        boolean[] tmp = ensure(tmpBuf, src.length);
        tmpBuf = tmp;

        // Horizontal dilate: tmp = max over x in [x-rad, x+rad].
        // Running "count of true in the window" makes each step O(1).
        for (int y = 0; y < gh; y++) {
            int row = y * gw;
            int count = 0;
            for (int x = 0; x < Math.min(gw, rad + 1); x++) if (src[row + x]) count++;
            for (int x = 0; x < gw; x++) {
                int add = x + rad;
                if (add < gw && src[row + add]) count++;
                int rem = x - rad - 1;
                if (rem >= 0 && src[row + rem]) count--;
                tmp[row + x] = count > 0;
            }
        }

        // Vertical dilate: out = max over y in [y-rad, y+rad].
        for (int x = 0; x < gw; x++) {
            int count = 0;
            for (int y = 0; y < Math.min(gh, rad + 1); y++) if (tmp[y * gw + x]) count++;
            for (int y = 0; y < gh; y++) {
                int add = y + rad;
                if (add < gh && tmp[add * gw + x]) count++;
                int rem = y - rad - 1;
                if (rem >= 0 && tmp[rem * gw + x]) count--;
                out[y * gw + x] = count > 0;
            }
        }

        // Erode the dilated mask by the same rule, using "count of true in the
        // window == window size" which is the definition of an erosion.
        // Horizontal erode: tmp = min over x.
        for (int y = 0; y < gh; y++) {
            int row = y * gw;
            int count = 0;
            for (int x = 0; x < Math.min(gw, rad + 1); x++) if (out[row + x]) count++;
            for (int x = 0; x < gw; x++) {
                int add = x + rad;
                if (add < gw && out[row + add]) count++;
                int rem = x - rad - 1;
                if (rem >= 0 && out[row + rem]) count--;
                int lo = Math.max(0, x - rad), hi = Math.min(gw - 1, x + rad);
                tmp[row + x] = count == (hi - lo + 1);
            }
        }
        // Vertical erode: out = min over y.
        for (int x = 0; x < gw; x++) {
            int count = 0;
            for (int y = 0; y < Math.min(gh, rad + 1); y++) if (tmp[y * gw + x]) count++;
            for (int y = 0; y < gh; y++) {
                int add = y + rad;
                if (add < gh && tmp[add * gw + x]) count++;
                int rem = y - rad - 1;
                if (rem >= 0 && tmp[rem * gw + x]) count--;
                int lo = Math.max(0, y - rad), hi = Math.min(gh - 1, y + rad);
                out[y * gw + x] = count == (hi - lo + 1);
            }
        }
    }

    // --------------------------------------------------------------- balls

    /**
     * Locate the cue ball by scanning a disc over the felt.
     *
     * This cannot use the blob pass: the game draws its white aim guideline
     * starting at the cue ball, so the ball and the line fuse into one long
     * thin blob that fails every roundness test. Instead, slide a disc the size
     * of a real ball over the table and keep the centre that is whitest and
     * least saturated. A stripe is also largely white but always carries a
     * coloured band, so subtracting saturation separates them cleanly; on a
     * measured frame the cue ball scored 0.85 against 0.11 for the best stripe.
     */
    private static void findCueBall(Result out, int[] rgba, int w, int h, int step) {
        if (!out.tableFound) return;

        // Ball size is derived from the TABLE, not from a ball list: a ball is
        // roughly 1/60 of the long cushion. (The old code averaged detected blob
        // radii, which is no longer possible and was circular besides.)
        int tableLong = Math.max(1, out.tableRight - out.tableLeft);
        int radiusRaw = Math.max(18, tableLong / 60);

        // Clamp against the table, not an absolute pixel count, so the scan
        // adapts to any screen: a ball is roughly 1/60 of the long cushion.
        final int rMin = Math.max((int) (14 * sc), tableLong / 90);
        final int rMaxD = Math.max((int) (30 * sc), tableLong / 40);
        final int radius = Math.max(rMin, Math.min(rMaxD, radiusRaw));

        final int inset = radius + 4;
        final int left = out.tableLeft + inset;
        final int right = out.tableRight - inset;
        final int top = out.tableTop + inset;
        final int bottom = out.tableBottom - inset;
        if (right <= left || bottom <= top) return;

        // Evaluating the disc per candidate costs a few thousand pixel reads,
        // and the table has thousands of candidates, which used to drop the
        // detection rate below 1 Hz. So each ring of radius r around a centre
        // is reduced to four summed-area lookups: the whole disc is the union
        // of concentric bands, and a band is one big box minus one small box /
        // one small disc. Summing the bands therefore costs O(radius) lookups
        // instead of O(radius^2) reads.
        //
        // Integrate only over the playable rect. The full frame is three times
        // the area and the extra work was enough to halve the frame rate.
        final int ix0 = Math.max(0, out.tableLeft);
        final int iy0 = Math.max(0, out.tableTop);
        final int ix1 = Math.min(w - 1, out.tableRight);
        final int iy1 = Math.min(h - 1, out.tableBottom);
        if (ix1 - ix0 < radius * 2 || iy1 - iy0 < radius * 2) return;

        buildIntegrals(rgba, w, ix0, iy0, ix1, iy1);
        CueScan scan = new CueScan(igWhite, igSat, ix1 - ix0 + 1, iy1 - iy0 + 1,
                radius);

        // Coarse sweep, then a fine one around the winner. The coarse step must
        // stay well under one ball diameter or a gap between sample centres can
        // hide the cue ball entirely; a quarter of the radius is a good trade
        // between coverage and the O(radius) cost per candidate.
        final int coarseStep = Math.max(6, radius / 4);
        float[] coarseBest = new float[3];   // score, x, y
        scan.run(left - ix0, right - ix0, top - iy0, bottom - iy0,
                coarseStep, coarseBest);
        if (coarseBest[0] < 0f) return;

        final int rx = (int) coarseBest[1];
        final int ry = (int) coarseBest[2];
        float[] fine = new float[3];
        scan.run(Math.max(left - ix0, rx - coarseStep),
                Math.min(right - ix0, rx + coarseStep),
                Math.max(top - iy0, ry - coarseStep),
                Math.min(bottom - iy0, ry + coarseStep),
                1, fine);

        float[] best = fine[0] > coarseBest[0] ? fine : coarseBest;
        // Threshold lowered from 0.40 to 0.28: prevents dropping the cue ball during
        // stick pullback, shadow, or animation pulse.
        if (best[0] < 0.28f) return;   // no cue ball on this frame
        out.cue = new Ball((int) best[1] + ix0, (int) best[2] + iy0, radius);
    }

/** Reused summed-area buffers. Allocating these per frame is tens of MB of
     *  garbage, which alone is enough to stall the detection thread. */
    private static int[] igWhite = new int[0];
    private static int[] igSat = new int[0];


    /** Builds the summed-area tables for "white" and "saturated" pixels inside
     *  the inclusive rect (x0,y0)-(x1,y1). */
    private static void buildIntegrals(int[] rgba, int w,
                                       int x0, int y0, int x1, int y1) {
        final int iw = x1 - x0 + 1;
        final int ih = y1 - y0 + 1;
        final int stride = iw + 1;
        final int need = stride * (ih + 1);
        if (igWhite.length < need) {
            igWhite = new int[need];
            igSat = new int[need];
        }
        for (int y = 0; y < ih; y++) {
            int rowW = 0, rowS = 0;
            int src = (y0 + y) * w + x0;
            int dst = (y + 1) * stride + 1;
            int up = y * stride + 1;
            for (int x = 0; x < iw; x++) {
                int p = rgba[src + x];
                int r = (p >> 16) & 0xFF;
                int g = (p >> 8) & 0xFF;
                int b = p & 0xFF;
                int mx = Math.max(r, Math.max(g, b));
                int mn = Math.min(r, Math.min(g, b));
                if (mx > 140 && mx - mn < 70) rowW++;
                else if (mx - mn > 80 && mx > 70) rowS++;
                igWhite[dst + x] = igWhite[up + x] + rowW;
                igSat[dst + x] = igSat[up + x] + rowS;
            }
        }
    }

    /** Disc scorer backed by summed-area tables. Coordinates here are relative
     *  to the integrated table rect, not the full frame. */
    private static final class CueScan {
        private final int[] white, sat;
        private final int w, h, stride, radius;
        private final int discArea;

        CueScan(int[] white, int[] sat, int w, int h, int radius) {
            this.white = white;
            this.sat = sat;
            this.w = w;
            this.h = h;
            this.stride = w + 1;
            this.radius = radius;
            this.discArea = Math.max(1, (int) Math.round(Math.PI * radius * radius));
        }

        void run(int left, int right, int top, int bottom, int centerStep,
                 float[] best) {
            best[0] = -1f;
            best[1] = 0f;
            best[2] = 0f;
            // The step is a target, but always walk the full range: a step that
            // does not divide the span evenly can otherwise skip the very last
            // strip of the table, which is exactly where the cue ball sits after
            // a break shot with no balls remaining.
            if (centerStep < 1) centerStep = 1;
            for (int cy = top; cy <= bottom; cy += centerStep) {
                for (int cx = left; cx <= right; cx += centerStep) {
                    float score = score(cx, cy);
                    if (score > best[0]) {
                        best[0] = score;
                        best[1] = cx;
                        best[2] = cy;
                    }
                }
            }
            // Make sure the far edges are sampled too.
            if (right > left) {
                for (int cy = top; cy <= bottom; cy += centerStep) {
                    float score = score(right, cy);
                    if (score > best[0]) {
                        best[0] = score;
                        best[1] = right;
                        best[2] = cy;
                    }
                }
            }
            if (bottom > top) {
                for (int cx = left; cx <= right; cx += centerStep) {
                    float score = score(cx, bottom);
                    if (score > best[0]) {
                        best[0] = score;
                        best[1] = cx;
                        best[2] = bottom;
                    }
                }
            }
        }

        /** White minus saturated fraction inside the disc centred at (cx, cy). */
        private float score(int cx, int cy) {
            // A disc costs one box query per row (~2*radius lookups) instead of
            // one read per pixel. Off-table candidates are rejected outright.
            if (cx - radius < 0 || cx + radius >= w) return -1f;
            if (cy - radius < 0 || cy + radius >= h) return -1f;

            int wsum = 0, ssum = 0;
            final int rr = radius * radius;

            // Cheap rejection: the ball must be broadly white. One box over the
            // whole disc costs four lookups and throws out most of the felt
            // before any row walking happens.
            int boxArea = (2 * radius + 1) * (2 * radius + 1);
            int boxWhite = box(white, cx - radius, cy - radius,
                    cx + radius, cy + radius);
            if (boxWhite < boxArea * 0.30f) return -1f;

            for (int dy = -radius; dy <= radius; dy++) {
                int half = (int) Math.sqrt(rr - dy * dy);
                if (half <= 0) continue;
                int y = cy + dy;
                wsum += box(white, cx - half, y, cx + half, y);
                ssum += box(sat, cx - half, y, cx + half, y);
            }

            float whiteFrac = (float) wsum / discArea;
            float satFrac = (float) ssum / discArea;
            // The cue ball is a white disc sitting on felt.
            if (whiteFrac < 0.45f) return -1f;
            return whiteFrac - satFrac * 1.5f;
        }

        private int box(int[] pre, int x0, int y0, int x1, int y1) {
            int a = y0 * stride + x0;
            int b = y0 * stride + (x1 + 1);
            int c = (y1 + 1) * stride + x0;
            int d = (y1 + 1) * stride + (x1 + 1);
            return pre[d] - pre[b] - pre[c] + pre[a];
        }
    }
}
