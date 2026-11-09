package com.poollens.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.util.Log;
import android.view.MotionEvent;
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

    private static final String PREF_NAME       = "poollens_prefs";
    private static final String KEY_USE_MANUAL  = "use_manual";
    private static final String KEY_MANUAL_L    = "manual_l";
    private static final String KEY_MANUAL_T    = "manual_t";
    private static final String KEY_MANUAL_R    = "manual_r";
    private static final String KEY_MANUAL_B    = "manual_b";
    private static final String KEY_SHOW_BORDER = "show_border";

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
    private volatile boolean showBorder = false;

    /** Table border calibration state and manual rectangle coordinates (view space). */
    private volatile boolean calibrating = false;
    private volatile boolean useManual   = false;
    private volatile float mL, mT, mR, mB;

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

        SharedPreferences p = this.ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        this.useManual  = p.getBoolean(KEY_USE_MANUAL, false);
        this.showBorder = p.getBoolean(KEY_SHOW_BORDER, false);
        if (useManual) {
            this.mL = p.getFloat(KEY_MANUAL_L, 0f);
            this.mT = p.getFloat(KEY_MANUAL_T, 0f);
            this.mR = p.getFloat(KEY_MANUAL_R, 0f);
            this.mB = p.getFloat(KEY_MANUAL_B, 0f);
        }
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

    boolean borderOn()      { return showBorder; }
    boolean toggleBorder()  {
        showBorder = !showBorder;
        SharedPreferences p = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        p.edit().putBoolean(KEY_SHOW_BORDER, showBorder).apply();
        invalidate();
        return showBorder;
    }

    boolean hasManualRect() { return useManual; }
    boolean isCalibrating() { return calibrating; }

    void setCalibrating(boolean val) {
        if (val) {
            if (mL <= 0 || mR <= mL) {
                Detector.Result raw = last;
                if (raw != null && raw.tableFound) {
                    mL = vx(raw.tableLeft);
                    mT = vy(raw.tableTop);
                    mR = vx(raw.tableRight);
                    mB = vy(raw.tableBottom);
                } else if (screenW > 0 && screenH > 0) {
                    mL = screenW * 0.12f;
                    mT = screenH * 0.18f;
                    mR = screenW * 0.88f;
                    mB = screenH * 0.82f;
                }
            }
        }
        calibrating = val;
        applyTouchable();
        invalidate();
    }

    void saveRect() {
        useManual = true;
        calibrating = false;
        applyTouchable();
        SharedPreferences p = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        p.edit()
                .putBoolean(KEY_USE_MANUAL, true)
                .putFloat(KEY_MANUAL_L, mL)
                .putFloat(KEY_MANUAL_T, mT)
                .putFloat(KEY_MANUAL_R, mR)
                .putFloat(KEY_MANUAL_B, mB)
                .apply();
        invalidate();
    }

    void resetRect() {
        useManual = false;
        calibrating = false;
        applyTouchable();
        SharedPreferences p = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        p.edit()
                .remove(KEY_USE_MANUAL)
                .remove(KEY_MANUAL_L)
                .remove(KEY_MANUAL_T)
                .remove(KEY_MANUAL_R)
                .remove(KEY_MANUAL_B)
                .apply();
        Detector.Result raw = last;
        if (raw != null && raw.tableFound) {
            mL = vx(raw.tableLeft);
            mT = vy(raw.tableTop);
            mR = vx(raw.tableRight);
            mB = vy(raw.tableBottom);
        } else {
            mL = 0; mT = 0; mR = 0; mB = 0;
        }
        invalidate();
    }

    void applyTouchable() {
        final CanvasView v = view;
        if (v == null || lp == null || wm == null) return;
        v.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (calibrating) {
                        lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                    } else {
                        lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                    }
                    wm.updateViewLayout(v, lp);
                } catch (Exception e) {
                    Log.w(TAG, "applyTouchable failed", e);
                }
            }
        });
    }

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

        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        if (!calibrating) {
            flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }

        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                flags,
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

        // Custom border & calibration paints
        private final Paint railPaint      = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint jawPaint       = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pocketPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pocketRimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint handlePaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint      = new Paint(Paint.ANTI_ALIAS_FLAG);

        // Drag calibration modes
        private static final int DRAG_NONE   = 0;
        private static final int DRAG_TL     = 1;
        private static final int DRAG_TR     = 2;
        private static final int DRAG_BL     = 3;
        private static final int DRAG_BR     = 4;
        private static final int DRAG_LEFT   = 5;
        private static final int DRAG_TOP    = 6;
        private static final int DRAG_RIGHT  = 7;
        private static final int DRAG_BOTTOM = 8;
        private static final int DRAG_MOVE   = 9;

        private int dragMode = DRAG_NONE;
        private float lastTouchX, lastTouchY;

        /** Smooth-hold for the aim and target angles. */
        private float smoothAimAngle = Float.NaN;
        private float smoothTargetAngle = Float.NaN;
        private float lastStubTipX = Float.NaN;
        private float lastStubTipY = Float.NaN;
        private Detector.Ball lastTarget = null;
        private static final int AIM_HOLD = 6;
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

            railPaint.setStyle(Paint.Style.STROKE);
            railPaint.setStrokeWidth(5f);
            railPaint.setColor(0xFF3DDC97);
            railPaint.setStrokeCap(Paint.Cap.ROUND);

            jawPaint.setStyle(Paint.Style.STROKE);
            jawPaint.setStrokeWidth(4f);
            jawPaint.setColor(0xCC3DDC97);
            jawPaint.setStrokeCap(Paint.Cap.ROUND);

            pocketPaint.setStyle(Paint.Style.FILL);
            pocketPaint.setColor(0xEE0A0F14);

            pocketRimPaint.setStyle(Paint.Style.STROKE);
            pocketRimPaint.setStrokeWidth(3.5f);
            pocketRimPaint.setColor(0xFF3DDC97);

            textPaint.setColor(0xFFFFFFFF);
            textPaint.setTextSize(26f);
            textPaint.setFakeBoldText(true);
            textPaint.setTextAlign(Paint.Align.CENTER);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (!calibrating) return false;

            float x = e.getX();
            float y = e.getY();

            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    float handleR = 75f;
                    float edgeThick = 55f;

                    if (Math.hypot(x - mL, y - mT) <= handleR) {
                        dragMode = DRAG_TL;
                    } else if (Math.hypot(x - mR, y - mT) <= handleR) {
                        dragMode = DRAG_TR;
                    } else if (Math.hypot(x - mL, y - mB) <= handleR) {
                        dragMode = DRAG_BL;
                    } else if (Math.hypot(x - mR, y - mB) <= handleR) {
                        dragMode = DRAG_BR;
                    } else if (Math.abs(x - mL) <= edgeThick && y >= mT && y <= mB) {
                        dragMode = DRAG_LEFT;
                    } else if (Math.abs(x - mR) <= edgeThick && y >= mT && y <= mB) {
                        dragMode = DRAG_RIGHT;
                    } else if (Math.abs(y - mT) <= edgeThick && x >= mL && x <= mR) {
                        dragMode = DRAG_TOP;
                    } else if (Math.abs(y - mB) <= edgeThick && x >= mL && x <= mR) {
                        dragMode = DRAG_BOTTOM;
                    } else if (x >= mL && x <= mR && y >= mT && y <= mB) {
                        dragMode = DRAG_MOVE;
                    } else {
                        dragMode = DRAG_NONE;
                        return false;
                    }
                    lastTouchX = x;
                    lastTouchY = y;
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    if (dragMode == DRAG_NONE) return false;
                    float dx = x - lastTouchX;
                    float dy = y - lastTouchY;
                    switch (dragMode) {
                        case DRAG_TL: mL += dx; mT += dy; break;
                        case DRAG_TR: mR += dx; mT += dy; break;
                        case DRAG_BL: mL += dx; mB += dy; break;
                        case DRAG_BR: mR += dx; mB += dy; break;
                        case DRAG_LEFT:   mL += dx; break;
                        case DRAG_TOP:    mT += dy; break;
                        case DRAG_RIGHT:  mR += dx; break;
                        case DRAG_BOTTOM: mB += dy; break;
                        case DRAG_MOVE:
                            mL += dx; mR += dx;
                            mT += dy; mB += dy;
                            break;
                    }
                    // Prevent inverted or too tiny rect
                    if (mR - mL < 120f) {
                        if (dragMode == DRAG_LEFT || dragMode == DRAG_TL || dragMode == DRAG_BL) mL = mR - 120f;
                        else mR = mL + 120f;
                    }
                    if (mB - mT < 80f) {
                        if (dragMode == DRAG_TOP || dragMode == DRAG_TL || dragMode == DRAG_TR) mT = mB - 80f;
                        else mB = mT + 80f;
                    }
                    lastTouchX = x;
                    lastTouchY = y;
                    postInvalidate();
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    dragMode = DRAG_NONE;
                    postInvalidate();
                    return true;
            }
            return super.onTouchEvent(e);
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

                    // Target angle: switch targets immediately, gentle deadband for micro-jitter
                    if (copy.aimTarget != null && !Float.isNaN(copy.targetAngle)) {
                        if (!Float.isNaN(smoothTargetAngle) && lastTarget != null
                                && Math.hypot(copy.aimTarget.x - lastTarget.x, copy.aimTarget.y - lastTarget.y) < 20) {
                            float dTarget = angleDelta(copy.targetAngle, smoothTargetAngle);
                            if (Math.abs(dTarget) < 0.003f) {
                                copy.targetAngle = smoothTargetAngle;
                            } else {
                                smoothTargetAngle += dTarget * 0.45f;
                                copy.targetAngle = smoothTargetAngle;
                            }
                        } else {
                            smoothTargetAngle = copy.targetAngle;
                            lastTarget = copy.aimTarget;
                        }
                        lastStubTipX = copy.stubTipX;
                        lastStubTipY = copy.stubTipY;
                    } else {
                        smoothTargetAngle = Float.NaN;
                        lastTarget = null;
                        lastStubTipX = Float.NaN;
                        lastStubTipY = Float.NaN;
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
            } else if (holdCounter > 0) {
                holdCounter--;
                r = heldResult;
            } else {
                smoothAimAngle = Float.NaN;
                smoothTargetAngle = Float.NaN;
                lastTarget = null;
                heldResult = null;
            }

            // Determine table bounds (custom calibrated or auto-detected)
            float tL, tT, tR, tB;
            boolean hasTable = false;
            if (useManual || calibrating) {
                tL = mL; tT = mT; tR = mR; tB = mB;
                hasTable = (tR > tL + 20f && tB > tT + 20f);
            } else if (r != null && r.tableFound) {
                tL = vx(r.tableLeft);
                tT = vy(r.tableTop);
                tR = vx(r.tableRight);
                tB = vy(r.tableBottom);
                hasTable = (tR > tL + 20f && tB > tT + 20f);
                mL = tL; mT = tT; mR = tR; mB = tB;
            } else if (raw != null && raw.tableFound) {
                tL = vx(raw.tableLeft);
                tT = vy(raw.tableTop);
                tR = vx(raw.tableRight);
                tB = vy(raw.tableBottom);
                hasTable = (tR > tL + 20f && tB > tT + 20f);
                mL = tL; mT = tT; mR = tR; mB = tB;
            } else if (mL > 0 && mR > mL + 20f) {
                tL = mL; tT = mT; tR = mR; tB = mB;
                hasTable = (tR > tL + 20f && tB > tT + 20f);
            } else {
                tL = 0; tT = 0; tR = 0; tB = 0;
            }

            // Draw custom border with 6 cushions and 6 pockets
            if (hasTable && (showBorder || calibrating)) {
                drawCustomBorder(c, tL, tT, tR, tB, calibrating);
            }

            if (r == null || !r.aimFound || !hasTable) return;

            InkMask.begin();
            if (showAim) drawAimLines(c, r, tL, tT, tR, tB);
            InkMask.commit();
        }

        private float angleDelta(float a, float b) {
            float d = (a - b) % (2f * (float) Math.PI);
            if (d >  (float) Math.PI) d -= 2f * (float) Math.PI;
            if (d < -(float) Math.PI) d += 2f * (float) Math.PI;
            return d;
        }

        // ---------------------------------------------------------------- main draw

        private void drawAimLines(Canvas c, Detector.Result r,
                                  float tL, float tT, float tR, float tB) {
            if (r.cue == null || Float.isNaN(r.aimAngle)) return;
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
                runAndPaint(c, linePaint, segs, cueX, cueY, r.aimAngle, 0f,
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

            int n = runAndPaint(c, linePaint, objSegs, startX, startY, ang, 0f,
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
                runAndPaint(c, linePaint, segs, ghostX, ghostY, qAng, 0f,
                        tL, tT, tR, tB, 0xCC66E0FF, thickAim ? OBJ_W_THICK : OBJ_W, bankCount);
            }
        }

        // ---------------------------------------------------------------- helpers

        private void drawArrowHead(Canvas c, int n) {
            if (n <= 0) return;
            AimLine.Segment last = objSegs[n - 1];
            if (last == null) return;

            if (last.potted) {
                // Ball sinks cleanly into the pocket: draw pocket pot bullseye!
                linePaint.setColor(0xFFFFC020);
                linePaint.setStyle(Paint.Style.STROKE);
                linePaint.setStrokeWidth(thickAim ? 5f : 3f);
                c.drawCircle(last.x2, last.y2, 18f, linePaint);
                linePaint.setStyle(Paint.Style.FILL);
                c.drawCircle(last.x2, last.y2, 6f, linePaint);
                linePaint.setStyle(Paint.Style.STROKE);
                return;
            }

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

        /**
         * Custom Table Border & 6 Pockets Layout:
         * - 6 cushions with pocket mouth gaps
         * - 4 corner pockets diagonally outside the cushion box
         * - 2 middle side pockets recessed slightly outside the top and bottom rails
         * - Chamfered pocket jaws
         * - Calibration drag handles (when cal == true)
         */
        private void drawCustomBorder(Canvas c, float l, float t, float r, float b, boolean cal) {
            final float tableW = r - l;
            final float tableH = b - t;
            if (tableW < 50f || tableH < 50f) return;

            final float ballR = Math.max(14f, tableW / 58f);
            final float cornerMouth = 1.9f * ballR;
            final float sideMouth = 1.4f * ballR;
            final float xMid = (l + r) * 0.5f;

            // 1. Draw 6 Pockets (corners diagonally OUTSIDE, middle recessed into rails)
            float pRad = ballR * 1.15f;

            // Pocket centers:
            // 4 corners: outside the cushion box
            float tlX = l - 0.6f * ballR, tlY = t - 0.6f * ballR;
            float trX = r + 0.6f * ballR, trY = t - 0.6f * ballR;
            float blX = l - 0.6f * ballR, blY = b + 0.6f * ballR;
            float brX = r + 0.6f * ballR, brY = b + 0.6f * ballR;
            // 2 middle side pockets
            float tmX = xMid, tmY = t - 0.5f * ballR;
            float bmX = xMid, bmY = b + 0.5f * ballR;

            float[][] pockets = {
                {tlX, tlY}, {trX, trY}, {blX, blY}, {brX, brY},
                {tmX, tmY}, {bmX, bmY}
            };

            for (float[] p : pockets) {
                c.drawCircle(p[0], p[1], pRad, pocketPaint);
                c.drawCircle(p[0], p[1], pRad, pocketRimPaint);
            }

            // 2. Draw 6 Cushion Rails
            // Top-Left Rail
            c.drawLine(l + cornerMouth, t, xMid - sideMouth, t, railPaint);
            // Top-Right Rail
            c.drawLine(xMid + sideMouth, t, r - cornerMouth, t, railPaint);
            // Bottom-Left Rail
            c.drawLine(l + cornerMouth, b, xMid - sideMouth, b, railPaint);
            // Bottom-Right Rail
            c.drawLine(xMid + sideMouth, b, r - cornerMouth, b, railPaint);
            // Left Rail
            c.drawLine(l, t + cornerMouth, l, b - cornerMouth, railPaint);
            // Right Rail
            c.drawLine(r, t + cornerMouth, r, b - cornerMouth, railPaint);

            // 3. Pocket Jaw Chamfers (connecting cushion endpoints towards pockets)
            // Top-Left corner jaws
            c.drawLine(l + cornerMouth, t, l + cornerMouth * 0.35f, t - 0.35f * cornerMouth, jawPaint);
            c.drawLine(l, t + cornerMouth, l - 0.35f * cornerMouth, t + cornerMouth * 0.35f, jawPaint);
            // Top-Right corner jaws
            c.drawLine(r - cornerMouth, t, r - cornerMouth * 0.35f, t - 0.35f * cornerMouth, jawPaint);
            c.drawLine(r, t + cornerMouth, r + 0.35f * cornerMouth, t + cornerMouth * 0.35f, jawPaint);
            // Bottom-Left corner jaws
            c.drawLine(l + cornerMouth, b, l + cornerMouth * 0.35f, b + 0.35f * cornerMouth, jawPaint);
            c.drawLine(l, b - cornerMouth, l - 0.35f * cornerMouth, b - cornerMouth * 0.35f, jawPaint);
            // Bottom-Right corner jaws
            c.drawLine(r - cornerMouth, b, r - cornerMouth * 0.35f, b + 0.35f * cornerMouth, jawPaint);
            c.drawLine(r, b - cornerMouth, r + 0.35f * cornerMouth, b - cornerMouth * 0.35f, jawPaint);
            // Top-Middle side pocket jaws
            c.drawLine(xMid - sideMouth, t, xMid - sideMouth * 0.6f, t - 0.45f * ballR, jawPaint);
            c.drawLine(xMid + sideMouth, t, xMid + sideMouth * 0.6f, t - 0.45f * ballR, jawPaint);
            // Bottom-Middle side pocket jaws
            c.drawLine(xMid - sideMouth, b, xMid - sideMouth * 0.6f, b + 0.45f * ballR, jawPaint);
            c.drawLine(xMid + sideMouth, b, xMid + sideMouth * 0.6f, b + 0.45f * ballR, jawPaint);

            // 4. Calibration Handles & Instruction Overlay
            if (cal) {
                // Dashed / faint box
                handlePaint.setStyle(Paint.Style.STROKE);
                handlePaint.setStrokeWidth(2f);
                handlePaint.setColor(0x803DDC97);
                c.drawRect(l, t, r, b, handlePaint);

                // 4 Corner Drag Handles
                float hR = 28f;
                float[][] corners = {{l, t}, {r, t}, {l, b}, {r, b}};
                for (float[] cn : corners) {
                    handlePaint.setStyle(Paint.Style.FILL);
                    handlePaint.setColor(0x993DDC97);
                    c.drawCircle(cn[0], cn[1], hR, handlePaint);
                    handlePaint.setStyle(Paint.Style.STROKE);
                    handlePaint.setStrokeWidth(3.5f);
                    handlePaint.setColor(0xFFFFFFFF);
                    c.drawCircle(cn[0], cn[1], hR, handlePaint);
                    handlePaint.setStyle(Paint.Style.FILL);
                    handlePaint.setColor(0xFF3DDC97);
                    c.drawCircle(cn[0], cn[1], 10f, handlePaint);
                }

                // 4 Edge Midpoint Handles
                float yMid = (t + b) * 0.5f;
                float[][] edges = {{l, yMid}, {r, yMid}, {xMid, t}, {xMid, b}};
                for (float[] ed : edges) {
                    handlePaint.setStyle(Paint.Style.FILL);
                    handlePaint.setColor(0x99FFC020);
                    c.drawCircle(ed[0], ed[1], 20f, handlePaint);
                    handlePaint.setStyle(Paint.Style.STROKE);
                    handlePaint.setStrokeWidth(3f);
                    handlePaint.setColor(0xFFFFFFFF);
                    c.drawCircle(ed[0], ed[1], 20f, handlePaint);
                    handlePaint.setStyle(Paint.Style.FILL);
                    handlePaint.setColor(0xFFFFC020);
                    c.drawCircle(ed[0], ed[1], 8f, handlePaint);
                }

                // Top calibration message banner
                handlePaint.setStyle(Paint.Style.FILL);
                handlePaint.setColor(0xD9101820);
                float msgY = Math.max(70f, t - 60f);
                c.drawRoundRect(xMid - 400f, msgY - 32f, xMid + 400f, msgY + 16f, 12f, 12f, handlePaint);
                handlePaint.setStyle(Paint.Style.STROKE);
                handlePaint.setColor(0xFF3DDC97);
                handlePaint.setStrokeWidth(2f);
                c.drawRoundRect(xMid - 400f, msgY - 32f, xMid + 400f, msgY + 16f, 12f, 12f, handlePaint);

                c.drawText("DRAG CORNERS / EDGES TO ALIGN TABLE | TAP SAVE", xMid, msgY, textPaint);
            }
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
