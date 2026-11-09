package com.poollens.app;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Compact control toolbar.
 *
 * A flat toolbar of small buttons in two short rows, parked in the HUD strip
 * above the cushion line, where it covers no cloth. Nothing redraws unless a
 * label actually changes.
 *
 * The border / ball / pocket buttons and the manual border editor were removed
 * along with those overlay layers, so the rows now hold only the shot-line
 * controls.
 */
class ControlPanel {

    private static final String TAG = "PoolLens";

    private final Context ctx;
    private final WindowManager wm;
    private final Handler main = new Handler(Looper.getMainLooper());

    private LinearLayout panel;
    private LinearLayout rowA;
    private LinearLayout rowB;
    private WindowManager.LayoutParams lp;

    private TextView guideButton;
    private TextView borderButton;
    private TextView yellowButton;
    private TextView cyanButton;
    private TextView whiteButton;
    private TextView bankButton;
    private TextView widthButton;
    private TextView editButton;
    private TextView resetButton;
    private TextView statusText;
    private TextView title;
    private TextView minButton;

    private boolean collapsed;
    private boolean shown;
    private GuideOverlay guide;

    /** Drag state for the panel header. */
    private int startX, startY;
    private float touchX, touchY;
    private boolean moved;

    private int frameCount;
    private long lastFpsStamp;

    ControlPanel(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.wm = (WindowManager) this.ctx.getSystemService(Context.WINDOW_SERVICE);
    }

    void setGuide(GuideOverlay g) {
        guide = g;
        refreshLabels();
    }

    void show() {
        if (shown || wm == null) return;

        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        // FLAG_NOT_TOUCH_MODAL is mandatory: without it an overlay window
        // consumes every touch outside its own bounds, which makes the game
        // underneath completely unresponsive.
        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = dp(8);
        lp.y = dp(8);

        panel = buildPanel();

        try {
            wm.addView(panel, lp);
            shown = true;
            Log.i(TAG, "control panel shown");
            publishRect();
        } catch (Exception e) {
            Log.e(TAG, "addView panel failed", e);
            panel = null;
        }
    }

    void hide() {
        if (panel != null && shown) {
            try {
                wm.removeView(panel);
            } catch (Exception ignored) {
            }
        }
        panel = null;
        rowA = null;
        rowB = null;
        shown = false;
        InkMask.setPanelRect(0, 0, -1, -1);
    }

    /** Puts the panel back on top of the overlay stack. */
    void raise() {
        if (!shown || panel == null || lp == null) return;
        try {
            wm.removeView(panel);
            wm.addView(panel, lp);
            publishRect();
        } catch (Exception e) {
            Log.w(TAG, "panel raise failed", e);
        }
    }

    /**
     * Reports the panel's real footprint so the detector can ignore it: its
     * buttons and text would otherwise read as objects.
     */
    private void publishRect() {
        LinearLayout p = panel;
        if (p == null) return;
        int[] loc = new int[2];
        p.getLocationOnScreen(loc);
        int w = p.getWidth();
        int h = p.getHeight();
        if (w <= 0 || h <= 0) {
            p.post(new Runnable() {
                @Override
                public void run() {
                    publishRect();
                }
            });
            return;
        }
        float sx = guide != null && guide.screenW > 0 ? (float) guide.capW / guide.screenW : 0.5f;
        float sy = guide != null && guide.screenH > 0 ? (float) guide.capH / guide.screenH : 0.5f;
        int pl = Math.round(loc[0] * sx);
        int pt = Math.round(loc[1] * sy);
        int pr = Math.round((loc[0] + w) * sx);
        int pb = Math.round((loc[1] + h) * sy);
        InkMask.setPanelRect(pl, pt, pr, pb);
    }

    // ------------------------------------------------------------ panel build

    private LinearLayout buildPanel() {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(8), dp(5), dp(8), dp(7));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE6141A20);
        bg.setCornerRadius(dp(10));
        bg.setStroke(dp(1), 0xFF3DDC97);
        box.setBackground(bg);

        // Header: drag handle, status, collapse toggle.
        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        title = new TextView(ctx);
        title.setText("POOLLENS");
        title.setTextColor(0xFF3DDC97);
        title.setTextSize(11);
        title.setPadding(0, dp(2), dp(8), dp(2));
        header.addView(title);

        statusText = new TextView(ctx);
        statusText.setText("--");
        statusText.setTextColor(0xFF8FA3B5);
        statusText.setTextSize(10);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        header.addView(statusText, sLp);

        minButton = new TextView(ctx);
        minButton.setText("\u2212");
        minButton.setTextColor(0xFF3DDC97);
        minButton.setTextSize(15);
        minButton.setPadding(dp(12), 0, dp(4), 0);
        minButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setCollapsed(!collapsed);
            }
        });
        header.addView(minButton);

        box.addView(header);
        installDrag(title);
        installDrag(statusText);

        rowA = new LinearLayout(ctx);
        rowA.setOrientation(LinearLayout.HORIZONTAL);
        box.addView(rowA);

        rowB = new LinearLayout(ctx);
        rowB.setOrientation(LinearLayout.HORIZONTAL);
        box.addView(rowB);

        guideButton = addButton(rowA, new Runnable() {
            @Override
            public void run() {
                if (guide != null) guide.toggleEnabled();
                refreshLabels();
            }
        });

        yellowButton = addButton(rowA, new Runnable() {
            @Override
            public void run() {
                if (guide != null) guide.toggleYellow();
                refreshLabels();
            }
        });

        cyanButton = addButton(rowA, new Runnable() {
            @Override
            public void run() {
                if (guide != null) guide.toggleCyan();
                refreshLabels();
            }
        });

        whiteButton = addButton(rowA, new Runnable() {
            @Override
            public void run() {
                if (guide != null) guide.toggleWhite();
                refreshLabels();
            }
        });

        borderButton = addButton(rowA, new Runnable() {
            @Override
            public void run() {
                if (guide != null) guide.toggleBorder();
                refreshLabels();
            }
        });

        bankButton = addButton(rowB, new Runnable() {
            @Override
            public void run() {
                if (guide == null) return;
                guide.setBankCount((guide.bankCount() + 1) % 7);
                refreshLabels();
            }
        });

        widthButton = addButton(rowB, new Runnable() {
            @Override
            public void run() {
                if (guide != null) guide.toggleThickAim();
                refreshLabels();
            }
        });

        editButton = addButton(rowB, new Runnable() {
            @Override
            public void run() {
                if (guide == null) return;
                if (guide.isCalibrating()) {
                    guide.saveRect();
                } else {
                    guide.setCalibrating(true);
                    raise();
                }
                refreshLabels();
            }
        });

        resetButton = addButton(rowB, new Runnable() {
            @Override
            public void run() {
                if (guide != null) guide.resetRect();
                refreshLabels();
            }
        });

        TextView stop = addButton(rowB, new Runnable() {
            @Override
            public void run() {
                ctx.stopService(new android.content.Intent(ctx, OverlayService.class));
            }
        });
        stop.setText("STOP");

        return box;
    }

    /** Collapses the panel to just its header, or expands it again. */
    private void setCollapsed(boolean on) {
        collapsed = on;
        if (minButton != null) minButton.setText(on ? "+" : "\u2212");
        if (rowA != null) rowA.setVisibility(on ? View.GONE : View.VISIBLE);
        if (rowB != null) rowB.setVisibility(on ? View.GONE : View.VISIBLE);
        updateStatus();
        LinearLayout p = panel;
        if (p != null) {
            p.post(new Runnable() {
                @Override
                public void run() {
                    publishRect();
                }
            });
        }
    }

    /** Creates a compact button in the given row; refreshLabels() sets the text. */
    private TextView addButton(LinearLayout row, final Runnable action) {
        final TextView t = new TextView(ctx);
        t.setTextSize(10);
        t.setPadding(dp(9), dp(6), dp(9), dp(6));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x263DDC97);
        bg.setCornerRadius(dp(6));
        t.setBackground(bg);
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
            }
        });
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        p.rightMargin = dp(5);
        p.topMargin = dp(4);
        row.addView(t, p);
        return t;
    }

    /** Rebuilds every label from the guide's real state. */
    private void refreshLabels() {
        main.post(new Runnable() {
            @Override
            public void run() {
                GuideOverlay g = guide;
                if (g == null) {
                    if (statusText != null) statusText.setText("no guide");
                    return;
                }
                set(guideButton, "GUIDE", g.isEnabled() ? "ON" : "OFF",
                        g.isEnabled() ? 0xFF3DDC97 : 0xFF7C8B99);
                set(borderButton, "BORDER", g.borderOn() ? "ON" : "OFF",
                        g.borderOn() ? 0xFF3DDC97 : 0xFF7C8B99);
                set(yellowButton, "YELLOW", g.yellowOn() ? "ON" : "OFF",
                        g.yellowOn() ? 0xFFFFC020 : 0xFF7C8B99);
                set(cyanButton, "CYAN", g.cyanOn() ? "ON" : "OFF",
                        g.cyanOn() ? 0xFF66E0FF : 0xFF7C8B99);
                set(whiteButton, "WHITE", g.whiteOn() ? "ON" : "OFF",
                        g.whiteOn() ? 0xFFFFFFFF : 0xFF7C8B99);
                set(bankButton, "BANKS", String.valueOf(g.bankCount()), 0xFFBFD3E3);
                set(widthButton, "WIDTH", g.thickAimOn() ? "THICK" : "THIN",
                        0xFFBFD3E3);

                if (editButton != null) {
                    if (g.isCalibrating()) {
                        editButton.setText("SAVE");
                        editButton.setTextColor(0xFFFFC020);
                    } else {
                        editButton.setText(g.hasManualRect() ? "EDIT (SAVED)" : "EDIT BORDER");
                        editButton.setTextColor(g.hasManualRect() ? 0xFF3DDC97 : 0xFFBFD3E3);
                    }
                }
                if (resetButton != null) {
                    resetButton.setText("RESET");
                    resetButton.setTextColor(0xFFBFD3E3);
                }
            }
        });
    }

    private void set(TextView t, String name, String value, int color) {
        if (t == null) return;
        t.setText(name + ": " + value);
        t.setTextColor(color);
    }

    // ---------------------------------------------------------------- dragging

    /** Lets the header move the whole panel. */
    private void installDrag(View handle) {
        if (handle == null) return;
        handle.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = lp.x;
                        startY = lp.y;
                        touchX = e.getRawX();
                        touchY = e.getRawY();
                        moved = false;
                        return true;

                    case MotionEvent.ACTION_MOVE: {
                        int dx = (int) (e.getRawX() - touchX);
                        int dy = (int) (e.getRawY() - touchY);
                        if (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6)) moved = true;
                        lp.x = startX + dx;
                        lp.y = startY + dy;
                        if (shown && panel != null) {
                            try {
                                wm.updateViewLayout(panel, lp);
                            } catch (Exception ignored) {
                            }
                        }
                        return true;
                    }

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        publishRect();
                        return moved;
                }
                return false;
            }
        });
    }

    // ------------------------------------------------------------------ status

    private volatile int fpsValue;
    private volatile boolean aimValue;
    private volatile int aimScoreValue = -1;

    void onFrame(CaptureEngine.Frame frame) {
        frameCount++;
        long now = System.currentTimeMillis();
        if (lastFpsStamp == 0) lastFpsStamp = now;
        if (now - lastFpsStamp < 1000) return;

        final int fps = (int) (frameCount * 1000L / (now - lastFpsStamp));
        frameCount = 0;
        lastFpsStamp = now;

        main.post(new Runnable() {
            @Override
            public void run() {
                fpsValue = fps;
                updateStatus();
            }
        });
    }

    void onDetect(final Detector.Result r) {
        if (r == null) return;
        final boolean aim = r.aimFound;
        final int score = r.aimScore;
        main.post(new Runnable() {
            @Override
            public void run() {
                aimValue = aim;
                aimScoreValue = score;
                updateStatus();
            }
        });
    }

    private void updateStatus() {
        if (statusText == null) return;
        if (collapsed) {
            statusText.setText(aimValue ? "aim " + aimScoreValue + "%" : "--");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(fpsValue).append("fps");
        sb.append(aimValue ? "  aim " + aimScoreValue + "%" : "  no aim");
        statusText.setText(sb.toString());
    }

    private int dp(int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density);
    }
}
