package com.poollens.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;

/**
 * Click-through overlay canvas that draws the shot lines over the game.
 *
 * COORDINATE SPACE:
 *   - Detector works in CAPTURE pixels (e.g. 1600x1068).
 *   - Overlay view is MATCH_PARENT at screen resolution (e.g. 3200x2136).
 *   - vx(capX) = capX * (screenW / capW)  [overlay starts at screen origin 0,0]
 *
 * LINES DRAWN:
 *   YELLOW  target ball path: from just past target ball along line-of-centers.
 *           This is the MAIN feature. ON by default.
 *   CYAN    cue ball path after contact. OFF by default.
 *   WHITE   cue ball aim line to ghost ball. OFF by default.
 */
class GuideOverlay {

    private static final String TAG = "PoolLens";

    /** Visual debug: draw rings at detected positions. */
    static final boolean DEBUG_VIS = false;

    private final Context ctx;
    private final WindowManager wm;

    private CanvasView view;
    private WindowManager.LayoutParams lp;
    private boolean shown;

    private volatile Detector.Result last;

    /** When false the guide keeps running but draws nothing (paused). */
    private volatile boolean enabled = true;

    /** Layers, toggled from the panel. Yellow ON; cyan & white OFF by default. */
    private volatile boolean showAim    = true;
    private volatile boolean showYellow = true;
    private volatile boolean showCyan   = false;
    private volatile boolean showWhite  = false;
    /** When on, every aim line is drawn wider. */
    private volatile boolean thickAim;

    /** How many cushions every line folds off. */
    private int bankCount = 2;

    private final AimLine.Segment[] segs    = new AimLine.Segment[8];
    private final AimLine.Segment[] objSegs = new AimLine.Segment[8];

    // Stroke widths
    private static final float AIM_W        = 6f;
    private static final float AIM_W_THICK  = 12f;
    private static final float BANK_W       = 7f;
    private static final float BANK_W_THICK = 13f;
    private static final float OBJ_W        = 10f;
    private static final float OBJ_W_THICK  = 18f;

    /** Capture size. */
    volatile int capW;
    volatile int capH;
    /** Real screen size. */
    volatile int screenW;
    volatile int screenH;

    void setCaptureSize(int w, int h) { capW = w; capH = h; invalidate(); }
    void setScreenSize (int w, int h) { screenW = w; screenH = h; invalidate(); }

    // ---------------------------------------------------------------- transforms

    /** Capture-space X → view (screen) pixels. */
    private float vx(float capX) {
        return (capW > 0 && screenW > 0) ? capX * (float) screenW / capW : capX;
    }

    private float vy(float capY) {
        return (capH > 0 && screenH > 0) ? capY * (float) screenH / capH : capY;
    }

    /** Scale a capture-space length to view length. */
    private float vLen(float capLen) {
        float sx = (capW > 0 && screenW > 0) ? (float) screenW / capW : 1f;
        float sy = (capH > 0 && screenH > 0) ? (float) screenH / capH : 1f;
        return capLen * Math.min(sx, sy);
    }

    // ---------------------------------------------------------------- lifecycle

    GuideOverlay(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.wm  = (WindowManager) this.ctx.getSystemService(Context.WINDOW_SERVICE);
    }

    boolean isEnabled()          { return enabled; }
    void    setEnabled(boolean v){ enabled = v; invalidate(); }
    boolean toggleEnabled()      { enabled = !enabled; invalidate(); return enabled; }

    boolean aimOn()      { return showAim; }
    boolean toggleAim()  { showAim = !showAim; invalidate(); return showAim; }

    boolean yellowOn()     { return showYellow; }
    boolean toggleYellow() { showYellow = !showYellow; invalidate(); return showYellow; }

    boolean cyanOn()     { return showCyan; }
    boolean toggleCyan() { showCyan = !showCyan; invalidate(); return showCyan; }

    boolean whiteOn()     { return showWhite; }
    boolean toggleWhite() { showWhite = !showWhite; invalidate(); return showWhite; }

    boolean thickAimOn()     { return thickAim; }
    boolean toggleThickAim() { thickAim = !thickAim; invalidate(); return thickAim; }

    void setBankCount(int n) { bankCount = Math.max(0, Math.min(6, n)); invalidate(); }
    int  bankCount()         { return bankCount; }

    private void invalidate() {
        CanvasView v = view;
        if (v != null) v.postInvalidate();
    }

    void show() {
        if (shown || wm == null) return;

        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT);
        lp.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
        lp.alpha   = 0.99f;

        view = new CanvasView(ctx);
        try {
            wm.addView(view, lp);
            shown = true;
            Log.i(TAG, "guide overlay shown");
        } catch (Exception e) {
            Log.e(TAG, "guide overlay failed", e);
            view = null;
        }
    }

    void hide() {
        if (view != null && shown) {
            try { wm.removeView(view); } catch (Exception ignored) {}
        }
        view  = null;
        shown = false;
    }

    /** Called from the capture thread; drawing happens on the UI thread. */
    void render(Detector.Result result) {
        last = result;
        CanvasView v = view;
        if (v != null) v.postInvalidate();
    }

    // ---------------------------------------------------------------- canvas view

    private final class CanvasView extends View {

        private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dotPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dbgPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);

        /** Smooth-hold for the aim and target angles. */
        private float smoothAimAngle = Float.NaN;
        private float smoothTargetAngle = Float.NaN;
        private float lastStubTipX = Float.NaN;
        private float lastStubTipY = Float.NaN;
        private Detector.Ball lastTarget = null;
        private static final int AIM_HOLD = 60;
        private int holdCounter;
        private Detector.Result heldResult;

        CanvasView(Context c) {
            super(c);
            setWillNotDraw(false);

            linePaint.setStyle(Paint.Style.STROKE);
            linePaint.setStrokeCap(Paint.Cap.ROUND);
            linePaint.setStrokeJoin(Paint.Join.ROUND);
            linePaint.setAntiAlias(true);

            dotPaint.setStyle(Paint.Style.FILL);
            dotPaint.setAntiAlias(true);

            dbgPaint.setStyle(Paint.Style.STROKE);
            dbgPaint.setStrokeWidth(5f);
            dbgPaint.setAntiAlias(true);
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);
            c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);

            if (!enabled) {
                heldResult  = null;
                holdCounter = 0;
                smoothAimAngle = Float.NaN;
                smoothTargetAngle = Float.NaN;
                lastTarget = null;
                return;
            }

            Detector.Result r = null;
            Detector.Result raw = last;
            if (raw == null || !raw.tableFound) {
                if (holdCounter > 0) {
                    holdCounter--;
                    r = heldResult;
                } else {
                    heldResult  = null;
                    smoothAimAngle = Float.NaN;
                    smoothTargetAngle = Float.NaN;
                    lastTarget = null;
                    return;
                }
            }

            // --- Aim smoothing + hold ---
            if (raw != null && raw.tableFound) {
                if (raw.aimFound && raw.cue != null && !Float.isNaN(raw.aimAngle)) {
                    if (!Float.isNaN(smoothAimAngle)) {
                        float delta = angleDelta(raw.aimAngle, smoothAimAngle);
                        // Micro deadband (< 0.17 deg) prevents line jitter when cue is stationary
                        if (Math.abs(delta) < 0.003f) {
                            // perfectly static
                        } else if (Math.abs(delta) < 0.12f) {
                            smoothAimAngle += delta * 0.35f;
                        } else {
                            smoothAimAngle = raw.aimAngle;
                        }
                    } else {
                        smoothAimAngle = raw.aimAngle;
                    }
                    Detector.Result copy = raw.copy();
                    copy.aimAngle = smoothAimAngle;

                    // Target angle smoothing & stability filter:
                    if (copy.aimTarget != null && !Float.isNaN(copy.targetAngle)) {
                        if (!Float.isNaN(smoothTargetAngle)) {
                            float dTarget = angleDelta(copy.targetAngle, smoothTargetAngle);
                            // If target jumps wildly (> 20 deg) while cue is stationary, reject the jitter
                            if (Math.abs(dTarget) > 0.35f) {
                                copy.targetAngle = smoothTargetAngle;
                                copy.stubTipX = lastStubTipX;
                                copy.stubTipY = lastStubTipY;
                            } else {
                                smoothTargetAngle += dTarget * 0.40f;
                                copy.targetAngle = smoothTargetAngle;
                                lastStubTipX = copy.stubTipX;
                                lastStubTipY = copy.stubTipY;
                            }
                        } else {
                            smoothTargetAngle = copy.targetAngle;
                            lastStubTipX = copy.stubTipX;
                            lastStubTipY = copy.stubTipY;
                        }
                        lastTarget = copy.aimTarget;
                    } else if (lastTarget != null && !Float.isNaN(smoothTargetAngle)) {
                        // Momentary pulse: hold the last good target ball rather than dropping it
                        copy.aimTarget = lastTarget;
                        copy.targetAngle = smoothTargetAngle;
                        copy.stubTipX = lastStubTipX;
                        copy.stubTipY = lastStubTipY;
                    }

                    heldResult  = copy;
                    holdCounter = AIM_HOLD;
                    r = heldResult;
                } else if (holdCounter > 0) {
                    holdCounter--;
                    r = heldResult;
                } else {
                    smoothAimAngle = Float.NaN;
                    smoothTargetAngle = Float.NaN;
                    lastTarget = null;
                    heldResult  = null;
                }
            }

            if (r == null || !r.aimFound) return;

            InkMask.begin();
            if (showAim) drawAimLines(c, r);
            InkMask.commit();
        }

        private float angleDelta(float a, float b) {
            float d = (a - b) % (2f * (float) Math.PI);
            if (d >  (float) Math.PI) d -= 2f * (float) Math.PI;
            if (d < -(float) Math.PI) d += 2f * (float) Math.PI;
            return d;
        }

        // ---------------------------------------------------------------- main draw

        private void drawAimLines(Canvas c, Detector.Result r) {
            if (r.cue == null || Float.isNaN(r.aimAngle)) return;

            // Table rect
            final float tL = vx(r.tableLeft);
            final float tT = vy(r.tableTop);
            final float tR = vx(r.tableRight);
            final float tB = vy(r.tableBottom);
            if (tR - tL < 4f || tB - tT < 4f) return;

            // Cue ball
            final float cueX = vx(r.cue.x);
            final float cueY = vy(r.cue.y);
            final float cueR = Math.max(10f, vLen(r.cue.radius));

            // Aim direction
            final float aimDx = (float) Math.cos(r.aimAngle);
            final float aimDy = (float) Math.sin(r.aimAngle);

            // Ghost / target
            final boolean hasGhost  = !Float.isNaN(r.ghostX) && !Float.isNaN(r.ghostY);
            final boolean hasTarget = r.aimTarget != null && hasGhost;

            final float ghostX = hasGhost ? vx(r.ghostX) : cueX + aimDx * 300f;
            final float ghostY = hasGhost ? vy(r.ghostY) : cueY + aimDy * 300f;

            // WHITE line
            if (showWhite) {
                drawWhiteLine(c, cueX, cueY, cueR, ghostX, ghostY, r, tL, tT, tR, tB);
            }

            // Ghost ring (only if WHITE guideline is toggled ON)
            if (showWhite && hasTarget) {
                linePaint.setColor(0xB0FFFFFF);
                linePaint.setStrokeWidth(thickAim ? 5f : 3f);
                drawAndMaskCircle(c, linePaint, ghostX, ghostY, cueR * 0.8f);
            }

            // YELLOW - primary aim line, always present across all parts of table
            if (showYellow) {
                drawYellowLine(c, r, cueX, cueY, ghostX, ghostY, cueR, aimDx, aimDy,
                        hasTarget, tL, tT, tR, tB);
            }

            // CYAN after-contact
            if (showCyan && hasTarget) {
                drawCyanLine(c, r, ghostX, ghostY, cueR, aimDx, aimDy, tL, tT, tR, tB);
            }

            // CYAN kick (no target)
            if (showCyan && !hasTarget && bankCount > 0) {
                runAndPaint(c, linePaint, segs, cueX, cueY, r.aimAngle, cueR,
                        tL, tT, tR, tB, 0xEE7FD4FF, thickAim ? BANK_W_THICK : BANK_W, bankCount);
            }

            // Debug
            if (DEBUG_VIS) {
                dbgPaint.setColor(0xFFFF0000);
                drawAndMaskCircle(c, dbgPaint, cueX, cueY, cueR + 4f);
                if (hasGhost) {
                    dbgPaint.setColor(0xFF00FF00);
                    drawAndMaskCircle(c, dbgPaint, ghostX, ghostY, cueR + 4f);
                }
                if (hasTarget) {
                    dbgPaint.setColor(0xFFFF8800);
                    drawAndMaskCircle(c, dbgPaint, vx(r.aimTarget.x), vy(r.aimTarget.y), cueR + 4f);
                }
            }
        }

        // ---------------------------------------------------------------- WHITE

        private void drawWhiteLine(Canvas c,
                                   float cueX, float cueY, float cueR,
                                   float ghostX, float ghostY,
                                   Detector.Result r,
                                   float tL, float tT, float tR, float tB) {
            // Back-track dashes behind cue ball
            if (!Float.isNaN(r.stickAngle)) {
                float bx = -(float) Math.cos(r.stickAngle);
                float by = -(float) Math.sin(r.stickAngle);
                float ang = (float) Math.atan2(by, bx);
                float backLen = AimLine.rayToCushion(cueX, cueY, ang, cueR,
                        tL, tT, tR, tB, 100000f);
                linePaint.setColor(0x80FFFFFF);
                linePaint.setStrokeWidth(thickAim ? 5f : 3f);
                drawAndMaskLine(c, linePaint,
                        cueX + bx * cueR, cueY + by * cueR,
                        cueX + bx * (cueR + backLen), cueY + by * (cueR + backLen));
            }

            // Cue → ghost solid line
            float gd = (float) Math.hypot(ghostX - cueX, ghostY - cueY);
            if (gd < 2f) return;
            float dx = (ghostX - cueX) / gd;
            float dy = (ghostY - cueY) / gd;
            linePaint.setColor(0xFFFFFFFF);
            linePaint.setStrokeWidth(thickAim ? AIM_W_THICK : AIM_W);
            drawAndMaskLine(c, linePaint,
                    cueX + dx * cueR, cueY + dy * cueR,
                    ghostX, ghostY);
        }

        // ---------------------------------------------------------------- YELLOW

        /**
         * YELLOW = primary aim & trajectory line.
         *
         * Consistently displays across all parts of the table:
         * 1) Target ball hit: extends the target ball trajectory from the game stub tip
         *    (or target ball edge) bouncing off cushions with ball radius cushion inset.
         * 2) Ghost ball hit (faint stub): extends along the aim direction ahead of ghost ball.
         * 3) Kick shot (no object ball): traces the cue ball path folding off cushions.
         */
        private void drawYellowLine(Canvas c, Detector.Result r,
                                    float cueX, float cueY,
                                    float ghostX, float ghostY, float cueR,
                                    float aimDx, float aimDy,
                                    boolean hasTarget,
                                    float tL, float tT, float tR, float tB) {
            final float startX;
            final float startY;
            final float ang;

            if (hasTarget && r.aimTarget != null) {
                final float tx   = vx(r.aimTarget.x);
                final float ty   = vy(r.aimTarget.y);
                final float tRad = Math.max(cueR * 0.8f, vLen(r.aimTarget.radius));

                if (!Float.isNaN(r.stubTipX) && !Float.isNaN(r.stubTipY) && !Float.isNaN(r.targetAngle)) {
                    // Seamless extension: start directly at the tip of the game's own white stub!
                    startX = vx(r.stubTipX);
                    startY = vy(r.stubTipY);
                    ang = r.targetAngle;
                } else {
                    float dx = tx - ghostX;
                    float dy = ty - ghostY;
                    float d  = (float) Math.hypot(dx, dy);
                    if (d < 2f) {
                        dx = aimDx;
                        dy = aimDy;
                    } else {
                        dx /= d;
                        dy /= d;
                    }
                    ang = !Float.isNaN(r.targetAngle) ? r.targetAngle : (float) Math.atan2(dy, dx);
                    startX = tx + dx * tRad;
                    startY = ty + dy * tRad;
                }
            } else {
                // Kick / cushion shot: start at cue ball and fold off cushions!
                startX = cueX;
                startY = cueY;
                ang = r.aimAngle;
            }

            int n = runAndPaint(c, linePaint, objSegs, startX, startY, ang, cueR,
                    tL, tT, tR, tB, 0xFFFFC020, thickAim ? OBJ_W_THICK : OBJ_W, bankCount);

            drawArrowHead(c, n);
        }

        // ---------------------------------------------------------------- CYAN

        private void drawCyanLine(Canvas c, Detector.Result r,
                                  float ghostX, float ghostY, float cueR,
                                  float aimDx, float aimDy,
                                  float tL, float tT, float tR, float tB) {
            float qAng = Float.NaN;
            if (!Float.isNaN(r.cueReboundAngle)) {
                qAng = r.cueReboundAngle;
            } else if (r.aimTarget != null) {
                float tx   = vx(r.aimTarget.x);
                float ty   = vy(r.aimTarget.y);
                float ox   = tx - ghostX;
                float oy   = ty - ghostY;
                float od   = (float) Math.hypot(ox, oy);
                if (od > 0.5f) {
                    float oxu = ox / od;
                    float oyu = oy / od;
                    float dot = aimDx * oxu + aimDy * oyu;
                    float qx  = aimDx - dot * oxu;
                    float qy  = aimDy - dot * oyu;
                    float qd  = (float) Math.hypot(qx, qy);
                    if (qd > 0.05f) qAng = (float) Math.atan2(qy / qd, qx / qd);
                }
            }
            if (!Float.isNaN(qAng)) {
                runAndPaint(c, linePaint, segs, ghostX, ghostY, qAng, cueR,
                        tL, tT, tR, tB, 0xCC66E0FF, thickAim ? OBJ_W_THICK : OBJ_W, bankCount);
            }
        }

        // ---------------------------------------------------------------- helpers

        private void drawArrowHead(Canvas c, int n) {
            if (n <= 0) return;
            AimLine.Segment last = objSegs[n - 1];
            if (last == null) return;
            float ang  = (float) Math.atan2(last.y2 - last.y1, last.x2 - last.x1);
            float head = 44f;
            float spr  = 0.42f;
            linePaint.setColor(0xFFFFC020);
            linePaint.setStrokeWidth(thickAim ? OBJ_W_THICK : OBJ_W);
            drawAndMaskLine(c, linePaint, last.x2, last.y2,
                    last.x2 - head * (float) Math.cos(ang - spr),
                    last.y2 - head * (float) Math.sin(ang - spr));
            drawAndMaskLine(c, linePaint, last.x2, last.y2,
                    last.x2 - head * (float) Math.cos(ang + spr),
                    last.y2 - head * (float) Math.sin(ang + spr));
        }

        private int runAndPaint(Canvas c, Paint p, AimLine.Segment[] out,
                                float x, float y, float angle, float ballR,
                                float tL, float tT, float tR, float tB,
                                int color, float width, int bounces) {
            int n = AimLine.trace(x, y, angle, ballR, tL, tT, tR, tB, bounces, out);
            p.setColor(color);
            p.setStrokeWidth(width);
            for (int i = 0; i < n && i < out.length; i++) {
                AimLine.Segment s = out[i];
                if (s == null) break;
                drawAndMaskLine(c, p, s.x1, s.y1, s.x2, s.y2);
                dotPaint.setColor(color);
                c.drawCircle(s.x1, s.y1, Math.max(5f, width * 0.4f), dotPaint);
            }
            return n;
        }

        // ---------------------------------------------------------------- ink mask

        /** Scale factor view → capture (inverse of vx/vy). */
        private float capSx() { return (screenW > 0 && capW > 0) ? (float) capW / screenW : 1f; }
        private float capSy() { return (screenH > 0 && capH > 0) ? (float) capH / screenH : 1f; }

        private void drawAndMaskLine(Canvas c, Paint p, float x1, float y1, float x2, float y2) {
            c.drawLine(x1, y1, x2, y2, p);
            float sx = capSx(), sy = capSy();
            InkMask.line(x1 * sx, y1 * sy, x2 * sx, y2 * sy,
                    p.getStrokeWidth() * Math.min(sx, sy));
        }

        private void drawAndMaskCircle(Canvas c, Paint p, float cx, float cy, float rad) {
            c.drawCircle(cx, cy, rad, p);
            float sx = capSx(), sy = capSy();
            InkMask.circle(cx * sx, cy * sy, rad * Math.min(sx, sy),
                    p.getStrokeWidth() * Math.min(sx, sy));
        }
    }
}
