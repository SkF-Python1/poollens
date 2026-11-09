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
 * Click-through overlay canvas that draws the three shot lines over the game.
 *
 * A plain View is used (not a SurfaceView): a SurfaceView is composited on its
 * own hardware layer, which on Android 12+ counts as an opaque obscuring
 * surface and makes taps unreliable. A normal View shares the window's
 * translucent surface instead.
 *
 * The table border, the manual border calibration and the per-ball rings were
 * all REMOVED. The border was never aligned with the game's cloth and only got
 * in the way, and the ball rings needed a colour blob pass that flickered. What
 * remains is the shot geometry:
 *
 *   WHITE  cue ball aim line, cue centre -> ghost ball (stops at contact)
 *   AMBER  target ball path, from the target centre along the line of centres
 *   CYAN   cue ball path AFTER the contact (its velocity minus the part spent
 *          on the target), from the ghost centre
 *
 * All three fold off the cushions (BANKS) and can be thickened (WIDTH).
 */
class GuideOverlay {

    private static final String TAG = "PoolLens";

    /** Visual debug markers (target/ghost/cue rings + readout). Off for release. */
    static final boolean DEBUG_VIS = false;

    private final Context ctx;
    private final WindowManager wm;

    private CanvasView view;
    private WindowManager.LayoutParams lp;
    private boolean shown;

    private volatile Detector.Result last;

    /** When false the guide keeps running but draws nothing (paused). */
    private volatile boolean enabled = true;

    /** True while a redraw is wanted on every display frame (dash animation). */
    private volatile boolean animating;

    /** Layers, toggled from the panel. Yellow is ON by default; cyan & white are OFF by default. */
    private volatile boolean showAim = true;
    private volatile boolean showYellow = true;
    private volatile boolean showCyan = false;
    private volatile boolean showWhite = false;
    /** When on, every aim line is drawn wider so it reads over a busy table. */
    private volatile boolean thickAim;

    /** How many cushions every line folds off. User adjustable (BANKS). */
    private int bankCount = 2;

    private final AimLine.Segment[] segs = new AimLine.Segment[8];
    /**
     * Scratch for the AMBER (target ball) path. Kept apart from {@link #segs}
     * so the amber line and the cue-ball lines can never overwrite each other's
     * segments - the three lines are computed and drawn independently.
     */
    private final AimLine.Segment[] objSegs = new AimLine.Segment[8];

    /** Stroke widths, normal vs thickened, in view pixels. */
    private static final float AIM_W = 6f;
    private static final float AIM_W_THICK = 12f;
    private static final float BANK_W = 7f;
    private static final float BANK_W_THICK = 13f;
    private static final float OBJ_W = 9f;
    private static final float OBJ_W_THICK = 15f;

    /** Capture buffer size, so drawn geometry can be mapped into view space. */
    volatile int capW;
    volatile int capH;
    /** Real screen size in pixels, which capture pixels map onto. */
    volatile int screenW;
    volatile int screenH;

    void setCaptureSize(int w, int h) {
        capW = w;
        capH = h;
        invalidate();
    }

    void setScreenSize(int w, int h) {
        screenW = w;
        screenH = h;
        invalidate();
    }

    /** Capture->screen scale factors. */
    private float capToScreenX() {
        return (capW > 0 && screenW > 0) ? (float) screenW / capW : 1f;
    }

    private float capToScreenY() {
        return (capH > 0 && screenH > 0) ? (float) screenH / capH : 1f;
    }

    /**
     * Capture -> view mapping, cached once per draw pass. viewOrigin() does a
     * layout query, so the whole pass shares one snapshot.
     */
    private float tfScaleX = 1f, tfScaleY = 1f, tfOrgX, tfOrgY;

    private void refreshTransform() {
        tfScaleX = capToScreenX();
        tfScaleY = capToScreenY();
        CanvasView v = view;
        if (v == null) {
            tfOrgX = tfOrgY = 0f;
            return;
        }
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        tfOrgX = loc[0];
        tfOrgY = loc[1];
    }

    /** Capture-space X/Y mapped into the overlay view's own coordinates. */
    private float viewX(float capX) {
        return capX * tfScaleX - tfOrgX;
    }

    private float viewY(float capY) {
        return capY * tfScaleY - tfOrgY;
    }

    /** Capture-space LENGTH scaled into view space (uniform, for radii). */
    private float viewLen(float capLen) {
        return capLen * Math.min(tfScaleX, tfScaleY);
    }

    GuideOverlay(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.wm = (WindowManager) this.ctx.getSystemService(Context.WINDOW_SERVICE);
    }

    /**
     * Detected table rect mapped into view space.
     *
     * Detection works in CAPTURE pixels (whole screen). The overlay View is not
     * laid out over the whole screen - with a landscape nav bar its frame is
     * inset - so drawing capture coordinates straight into it shifts and
     * stretches everything. Mapping through capture->screen scale and the
     * view's screen origin fixes that.
     */
    private void tableRect(Detector.Result r, float[] out) {
        out[0] = r.tableLeft;
        out[1] = r.tableTop;
        out[2] = r.tableRight;
        out[3] = r.tableBottom;
        CanvasView v = view;
        if (v == null) return;
        final int capW2 = capW, capH2 = capH;
        if (capW2 <= 0 || capH2 <= 0 || screenW <= 0 || screenH <= 0) return;
        final float csx = (float) screenW / capW2;
        final float csy = (float) screenH / capH2;
        out[0] = out[0] * csx - tfOrgX;
        out[2] = out[2] * csx - tfOrgX;
        out[1] = out[1] * csy - tfOrgY;
        out[3] = out[3] * csy - tfOrgY;
    }

    boolean isEnabled() {
        return enabled;
    }

    void setEnabled(boolean on) {
        enabled = on;
        invalidate();
    }

    /** Flips the guide on/off and returns the new state. */
    boolean toggleEnabled() {
        enabled = !enabled;
        invalidate();
        return enabled;
    }

    boolean toggleAim() {
        showAim = !showAim;
        invalidate();
        return showAim;
    }

    boolean aimOn() {
        return showAim;
    }

    boolean yellowOn() {
        return showYellow;
    }

    boolean toggleYellow() {
        showYellow = !showYellow;
        invalidate();
        return showYellow;
    }

    boolean cyanOn() {
        return showCyan;
    }

    boolean toggleCyan() {
        showCyan = !showCyan;
        invalidate();
        return showCyan;
    }

    boolean whiteOn() {
        return showWhite;
    }

    boolean toggleWhite() {
        showWhite = !showWhite;
        invalidate();
        return showWhite;
    }

    boolean thickAimOn() {
        return thickAim;
    }

    boolean toggleThickAim() {
        thickAim = !thickAim;
        invalidate();
        return thickAim;
    }

    void setBankCount(int n) {
        bankCount = Math.max(0, Math.min(6, n));
        invalidate();
    }

    int bankCount() {
        return bankCount;
    }

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

        // Android 12+ treats a touch as "obscured" when an overlay above it is
        // more opaque than settings:maximum_obscuring_opacity_for_touch
        // (default 0.8). Unity games drop obscured touches, so a fully opaque
        // full-screen window silently kills all input. Stay just under it.
        lp.alpha = 0.79f;

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
            try {
                wm.removeView(view);
            } catch (Exception ignored) {
            }
        }
        view = null;
        shown = false;
        animating = false;
    }

    /** Called from the capture thread; drawing happens on the UI thread. */
    void render(Detector.Result result) {
        last = result;
        CanvasView v = view;
        if (v != null) v.postInvalidate();
    }

    /** Plain View so the overlay never claims an independent opaque layer. */
    private final class CanvasView extends View {

        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint aim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        /** Animation phase of the dashed strike line behind the cue ball. */
        private float dashPhase;

        private Detector.Result displayed;
        private int aimHoldFrames = 0;
        private static final int MAX_HOLD_FRAMES = 6;

        CanvasView(Context c) {
            super(c);
            setWillNotDraw(false);

            text.setColor(Color.WHITE);
            text.setTextSize(32f);
            text.setShadowLayer(3f, 1f, 1f, 0xFF000000);

            aim.setStyle(Paint.Style.STROKE);
            dot.setStyle(Paint.Style.FILL);
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);
            c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
            if (!enabled) {
                displayed = null;
                aimHoldFrames = 0;
                return;
            }

            Detector.Result r = last;
            if (r == null || !r.tableFound) {
                displayed = null;
                aimHoldFrames = 0;
                return;
            }

            dashPhase = (android.os.SystemClock.uptimeMillis() * 0.06f) % 26f;

            if (r.aimFound && r.cue != null && !Float.isNaN(r.aimAngle)) {
                if (displayed == null || displayed.cue == null
                        || Math.hypot(r.cue.x - displayed.cue.x, r.cue.y - displayed.cue.y) > 25f) {
                    displayed = r.copy();
                } else {
                    displayed.tableFound = r.tableFound;
                    displayed.tableLeft = r.tableLeft;
                    displayed.tableTop = r.tableTop;
                    displayed.tableRight = r.tableRight;
                    displayed.tableBottom = r.tableBottom;
                    displayed.cue = r.cue;
                    displayed.stickAngle = r.stickAngle;

                    float dAng = angleDeltaRad(r.aimAngle, displayed.aimAngle);
                    if (Math.abs(dAng) < 0.09f) {
                        displayed.aimAngle += dAng * 0.60f;
                    } else {
                        displayed.aimAngle = r.aimAngle;
                    }

                    if (r.aimTarget != null) {
                        displayed.aimTarget = r.aimTarget;
                        displayed.ghostX = r.ghostX;
                        displayed.ghostY = r.ghostY;
                        displayed.targetAngle = r.targetAngle;
                        displayed.cueReboundAngle = r.cueReboundAngle;
                    } else {
                        displayed.aimTarget = null;
                        displayed.ghostX = Float.NaN;
                        displayed.ghostY = Float.NaN;
                        displayed.targetAngle = Float.NaN;
                        displayed.cueReboundAngle = Float.NaN;
                    }
                }
                aimHoldFrames = MAX_HOLD_FRAMES;
            } else if (aimHoldFrames > 0) {
                aimHoldFrames--;
            } else {
                displayed = null;
            }

            if (displayed == null || !displayed.aimFound) return;

            InkMask.begin();
            refreshTransform();

            if (showAim) {
                drawAim(c, displayed);
            }

            InkMask.commit();
        }

        private float angleDeltaRad(float target, float current) {
            float d = (target - current) % (2f * (float) Math.PI);
            if (d > (float) Math.PI) d -= 2f * (float) Math.PI;
            if (d < -(float) Math.PI) d += 2f * (float) Math.PI;
            return d;
        }

        /** Inverse of the draw-time mapping, back into capture pixels. */
        private void toCapture(float vx, float vy, float[] out) {
            float sx = capToScreenX();
            float sy = capToScreenY();
            out[0] = (vx + tfOrgX) / (sx == 0f ? 1f : sx);
            out[1] = (vy + tfOrgY) / (sy == 0f ? 1f : sy);
        }

        /**
         * Draws in VIEW space and records the same stroke in CAPTURE space.
         *
         * The two spaces differ when the capture is downscaled and when the
         * view is inset, so the conversion happens in exactly one place. Mixing
         * the two double-transforms every stroke.
         */
        private void drawLineV(Canvas c, Paint p,
                               float x1, float y1, float x2, float y2) {
            c.drawLine(x1, y1, x2, y2, p);
            final float kx = tfScaleX == 0f ? 1f : tfScaleX;
            final float ky = tfScaleY == 0f ? 1f : tfScaleY;
            InkMask.line((x1 + tfOrgX) / kx, (y1 + tfOrgY) / ky,
                    (x2 + tfOrgX) / kx, (y2 + tfOrgY) / ky, p.getStrokeWidth());
        }

        private void drawCircleV(Canvas c, Paint p, float x, float y, float r) {
            c.drawCircle(x, y, r, p);
            final float kx = tfScaleX == 0f ? 1f : tfScaleX;
            final float ky = tfScaleY == 0f ? 1f : tfScaleY;
            InkMask.circle((x + tfOrgX) / kx, (y + tfOrgY) / ky,
                    r / Math.min(kx, ky), p.getStrokeWidth());
        }

        /**
         * Draws the three independent shot lines.
         *
         * Geometry (all in view space, so it shares the cushion rect with every
         * other stroke):
         *   WHITE cue ball: cue centre -> ghost ball, stopping at contact.
         *   AMBER target  : from the target centre along the line of centres
         *                   (the object ball's direction for a frictionless
         *                   collision), NOT the aim angle.
         *   CYAN  cue     : from the ghost centre, the cue velocity minus its
         *                   line-of-centres component.
         */
        private void drawAim(Canvas c, Detector.Result r) {
            if (r.cue == null || !r.aimFound || Float.isNaN(r.aimAngle)) return;

            float[] t = new float[4];
            tableRect(r, t);
            final float L = t[0], TP = t[1], R = t[2], B = t[3];
            if (R - L < 4f || B - TP < 4f) return;

            final float cueX = viewX(r.cue.x);
            final float cueY = viewY(r.cue.y);
            final float cueR = Math.max(6f, viewLen(r.cue.radius));

            final float dx = (float) Math.cos(r.aimAngle);
            final float dy = (float) Math.sin(r.aimAngle);

            // Cue track: straight cue -> ghost, or cue -> cushion when no ball
            // is on the line. rayToCushion is only the fallback length.
            float dirx = dx, diry = dy;
            float len = AimLine.rayToCushion(cueX, cueY, r.aimAngle, cueR,
                    L, TP, R, B, 100000f);

            boolean hasTarget = r.aimTarget != null && !Float.isNaN(r.ghostX);
            final float gx, gy;
            if (hasTarget) {
                gx = viewX(r.ghostX);
                gy = viewY(r.ghostY);
                float gd = (float) Math.hypot(gx - cueX, gy - cueY);
                if (gd > 0.5f) {
                    len = gd;
                    dirx = (gx - cueX) / gd;
                    diry = (gy - cueY) / gd;
                }
            } else {
                gx = gy = 0f;
            }

            // Strike line & WHITE cue aim line:
            if (showWhite) {
                float bx, by;
                if (!Float.isNaN(r.stickAngle)) {
                    bx = -(float) Math.cos(r.stickAngle);
                    by = -(float) Math.sin(r.stickAngle);
                } else {
                    bx = -dx;
                    by = -dy;
                }
                float backLen = AimLine.rayToCushion(cueX, cueY,
                        (float) Math.atan2(by, bx), cueR, L, TP, R, B, 100000f);
                if (Float.isNaN(r.stickAngle)) backLen = Math.min(backLen, 260f);

                aim.setColor(0x99FFFFFF);
                aim.setStrokeWidth(thickAim ? 6f : 4f);
                final float sx0 = cueX + bx * (cueR + 3f);
                final float sy0 = cueY + by * (cueR + 3f);
                final float segLen = 13f;
                for (float d = 0; d < backLen; d += 26f) {
                    float a0 = d + dashPhase;
                    float a1 = Math.min(a0 + segLen, backLen);
                    if (a1 <= a0) break;
                    drawLineV(c, aim, sx0 + bx * a0, sy0 + by * a0,
                            sx0 + bx * a1, sy0 + by * a1);
                }

                // WHITE cue aim line, cue -> ghost (or cushion).
                aim.setColor(0xFFFFFFFF);
                aim.setStrokeWidth(thickAim ? AIM_W_THICK : AIM_W);
                drawLineV(c, aim, cueX, cueY, cueX + dirx * len, cueY + diry * len);
            }

            // Ghost ball ring
            if (hasTarget && (showWhite || showCyan || showYellow)) {
                aim.setColor(0xB4FFFFFF);
                aim.setStrokeWidth(4f);
                drawCircleV(c, aim, gx, gy, cueR);
            }

            // Cue ball's path AFTER the contact (CYAN), from the ghost centre.
            if (showCyan && hasTarget) {
                float qAng = Float.NaN;
                if (!Float.isNaN(r.cueReboundAngle)) {
                    qAng = r.cueReboundAngle;
                } else {
                    Detector.Ball tgt = r.aimTarget;
                    float ox = viewX(tgt.x) - gx;
                    float oy = viewY(tgt.y) - gy;
                    float od = (float) Math.hypot(ox, oy);
                    if (od > 0.5f) {
                        float oxu = ox / od, oyu = oy / od;
                        float ax = (float) Math.cos(r.aimAngle);
                        float ay = (float) Math.sin(r.aimAngle);
                        float dotNA = ax * oxu + ay * oyu;
                        float qx = ax - dotNA * oxu;
                        float qy = ay - dotNA * oyu;
                        float qd = (float) Math.hypot(qx, qy);
                        if (qd > 0.05f) {
                            qAng = (float) Math.atan2(qy / qd, qx / qd);
                        }
                    }
                }
                if (!Float.isNaN(qAng)) {
                    final float qw = thickAim ? OBJ_W_THICK : OBJ_W;
                    runShot(c, aim, segs, gx, gy, qAng,
                            Math.max(3f, cueR), L, TP, R, B,
                            0xCC66E0FF, qw, bankCount);
                }
            }

            // Cue-ball rail path when NO object ball is on the line (a kick).
            if (showCyan && bankCount > 0 && !hasTarget) {
                runShot(c, aim, segs, cueX, cueY, r.aimAngle, cueR,
                        L, TP, R, B, 0xEE7FD4FF,
                        thickAim ? BANK_W_THICK : BANK_W, bankCount);
            }

            // AMBER / YELLOW target-ball path (the main focus), painted LAST.
            if (showYellow && hasTarget) {
                drawTargetPath(c, r, gx, gy, L, TP, R, B);
            }

            aim.setStyle(Paint.Style.STROKE);
        }

        /**
         * AMBER: the target ball's outgoing path, from its centre along the
         * line of centres (ghost -> target), folded off the cushions.
         */
        private void drawTargetPath(Canvas c, Detector.Result r,
                                    float gx, float gy,
                                    float L, float TP, float R, float B) {
            Detector.Ball tgt = r.aimTarget;
            if (tgt == null || Float.isNaN(r.ghostX)) return;

            final float tx = viewX(tgt.x);
            final float ty = viewY(tgt.y);
            final float tR = Math.max(6f, viewLen(tgt.radius));

            float ang;
            if (!Float.isNaN(r.targetAngle)) {
                ang = r.targetAngle;
            } else {
                float ox = tx - gx;
                float oy = ty - gy;
                float od = (float) Math.hypot(ox, oy);
                if (od < 0.5f) return;
                ang = (float) Math.atan2(oy, ox);
            }

            final int n = runShot(c, aim, objSegs, tx, ty, ang, tR,
                    L, TP, R, B, 0xFFFFC020,
                    thickAim ? OBJ_W_THICK : OBJ_W, bankCount);
            if (n <= 0 || objSegs[0] == null) return;
            AimLine.Segment s0 = objSegs[Math.min(n, objSegs.length) - 1];
            float angEnd = (float) Math.atan2(s0.y2 - s0.y1, s0.x2 - s0.x1);

            aim.setColor(0xFFFFC020);
            aim.setStrokeWidth(thickAim ? OBJ_W_THICK : OBJ_W);
            final float head = 34f, spread = 0.40f;
            drawLineV(c, aim, s0.x2, s0.y2,
                    s0.x2 - head * (float) Math.cos(angEnd - spread),
                    s0.y2 - head * (float) Math.sin(angEnd - spread));
            drawLineV(c, aim, s0.x2, s0.y2,
                    s0.x2 - head * (float) Math.cos(angEnd + spread),
                    s0.y2 - head * (float) Math.sin(angEnd + spread));

            if (DEBUG_VIS) {
                dot.setStyle(Paint.Style.STROKE);
                dot.setStrokeWidth(5f);
                dot.setColor(0xFFFF2222);
                drawCircleV(c, dot, tx, ty, 30f);      // target centre
                dot.setColor(0xFF00E5FF);
                drawCircleV(c, dot, s0.x2, s0.y2, 28f); // amber endpoint
            }
        }

        /**
         * Traces a shot from (x,y) in VIEW space, folds it off the drawn
         * cushions and paints every flight. The same rect feeds both the fold
         * and the drawing, so the result can never leave the table.
         *
         * @return the number of flights painted
         */
        private int runShot(Canvas c, Paint p, AimLine.Segment[] out,
                            float x, float y, float angle, float ballR,
                            float L, float TP, float R, float B,
                            int color, float width, int bounces) {
            final float inset = Math.max(3f, ballR);
            int n = AimLine.trace(x, y, angle, inset, L, TP, R, B, bounces, out);
            for (int i = 0; i < n && i < out.length; i++) {
                AimLine.Segment s = out[i];
                if (s == null) break;
                p.setColor(color);
                p.setStrokeWidth(width);
                drawLineV(c, p, s.x1, s.y1, s.x2, s.y2);
                dot.setColor(color);
                dot.setStyle(Paint.Style.FILL);
                drawCircleV(c, dot, s.x1, s.y1, 10f);
            }
            return n;
        }
    }
}
