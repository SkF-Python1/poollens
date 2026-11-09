package com.poollens.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;

/**
 * Foreground service that owns the floating overlay and the capture pipeline.
 *
 * Lifecycle on modern Android:
 *   1. onCreate  -> immediately startForeground() (obligatory, else ANR)
 *   2. ACTION_GRANT -> build MediaProjection, attach capture engine
 *   3. ACTION_STOP / onDestroy -> tear everything down
 */
public class OverlayService extends Service implements CaptureEngine.Frames {

    private static final String TAG = "PoolLens";

    static final String ACTION_GRANT = "com.poollens.app.GRANT";
    static final String ACTION_DENIED = "com.poollens.app.DENIED";
    static final String ACTION_STOP = "com.poollens.app.STOP";

    private static final String CHANNEL_ID = "poollens_capture";
    private static final int NOTIF_ID = 4201;

    private static volatile boolean running = false;
    private static volatile boolean haveProjection = false;
    /** True only when the MediaProjection object itself is alive. */
    private static volatile boolean liveProjection = false;

    private MediaProjection projection;
    private CaptureEngine engine;
    private ControlPanel bubble;
    private GuideOverlay guide;
    private final Handler main = new Handler(Looper.getMainLooper());

    static boolean isRunning() {
        return running;
    }

    /** True only while a real, live projection exists (not just a stale flag). */
    static boolean hasProjection() {
        return haveProjection && liveProjection;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;

        // Must happen before any other work, no exceptions.
        promoteToForeground();

        // Overlay can be shown straight away, before capture is authorised.
        if (Settings.canDrawOverlays(this)) {
            try {
                bubble = new ControlPanel(this);
                bubble.show();
            } catch (Exception e) {
                Log.e(TAG, "overlay failed to attach", e);
            }
        } else {
            Log.w(TAG, "no overlay permission at service start");
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Every startForegroundService() call must be answered by a
        // startForeground() call within ~5s, even if the service is already
        // running and onCreate() will not fire again.
        promoteToForeground();

        String action = intent != null ? intent.getAction() : null;

        if (ACTION_STOP.equals(action)) {
            shutdown();
            return START_NOT_STICKY;
        }

        if (ACTION_DENIED.equals(action)) {
            toastOnMain("Screen capture was declined");
            return START_NOT_STICKY;
        }

        if (ACTION_GRANT.equals(action) && intent != null) {
            int code = intent.getIntExtra(CaptureConsentActivity.EXTRA_RESULT_CODE, 0);
            Intent data = intent.getParcelableExtra(CaptureConsentActivity.EXTRA_RESULT_DATA);
            startProjection(code, data);
            return START_NOT_STICKY;
        }

        // Plain start (from MainActivity): if we have no grant yet ask for one.
        if (!haveProjection) {
            CaptureConsentActivity.request(this);
        }
        return START_NOT_STICKY;
    }

    // ---------------------------------------------------------------- capture

    private void startProjection(int resultCode, Intent data) {
        if (resultCode == 0 || data == null) {
            Log.w(TAG, "grant without usable token");
            return;
        }
        if (projection != null) {
            Log.d(TAG, "projection already live");
            return;
        }

        MediaProjectionManager mpm = (MediaProjectionManager)
                getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mpm == null) {
            Log.e(TAG, "no MediaProjectionManager");
            return;
        }

        // Android 14+ ordering: the service must already be in the foreground
        // *with the mediaProjection type* before getMediaProjection() is called.
        haveProjection = true;
        promoteToForeground();

        try {
            projection = mpm.getMediaProjection(resultCode, data);
        } catch (Exception e) {
            Log.e(TAG, "getMediaProjection threw", e);
            haveProjection = false;
            promoteToForeground();
            return;
        }
        if (projection == null) {
            Log.e(TAG, "getMediaProjection returned null");
            haveProjection = false;
            promoteToForeground();
            return;
        }

        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                Log.i(TAG, "projection stopped by system/user");
                haveProjection = false;
                liveProjection = false;
                stopEngine();
                updateNotification("Capture stopped");
            }
        }, main);

        engine = new CaptureEngine(this, this);
        startDetectThread();
        engine.start(projection);
        liveProjection = true;
        lastRotation = engine.rotation();
        Log.i(TAG, "capture size " + engine.size() + " rotation " + lastRotation);
        updateNotification("Capturing the table");

        // Real screen size: capture pixels are whole-screen pixels, while the
        // overlay window is inset by the nav bar, so the two need explicit
        // mapping or the guide is drawn offset and slightly too large.
        int screenW = 0, screenH = 0;
        try {
            DisplayMetrics dm = new DisplayMetrics();
            WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            Display d = wm != null ? wm.getDefaultDisplay() : null;
            if (d != null) {
                d.getRealMetrics(dm);
                screenW = dm.widthPixels;
                screenH = dm.heightPixels;
            }
        } catch (Exception e) {
            Log.w(TAG, "screen metrics failed", e);
        }

        // The detector samples on an adaptive grid, and the ink mask must use the
        // exact same grid or the exclusion is off by up to a cell -- which is
        // enough for the aim scan to see our own white line again.
        InkMask.configure(engine.width(), engine.height(),
                Detector.stepFor(engine.width(), engine.height()));

        if (guide == null) {
            guide = new GuideOverlay(this);
            guide.setCaptureSize(engine.width(), engine.height());
            guide.setScreenSize(screenW, screenH);
            guide.show();
            if (bubble != null) bubble.setGuide(guide);
        } else {
            // Guide survived a rotation rebuild; keep everything in sync.
            guide.setCaptureSize(engine.width(), engine.height());
            guide.setScreenSize(screenW, screenH);
            if (bubble != null) bubble.setGuide(guide);
        }
    }

    private void stopEngine() {
        stopDetectThread();
        if (engine != null) {
            engine.stop();
            engine = null;
        }
        if (guide != null) {
            guide.hide();
            guide = null;
        }
        if (projection != null) {
            try {
                projection.stop();
            } catch (Exception ignored) {
            }
            projection = null;
        }
        haveProjection = false;
        liveProjection = false;
    }

    private long lastDetectMs;
    private long lastLogMs;
    /** Detections completed since the last log line, used to report real Hz. */
    private int detectCount;
    private volatile Detector.Result lastResult;

    /** Warn when a detection pass takes longer than one display frame. */
    private long lastSlowDetectLog;

    // Aim hold: the game's guideline pulses, so keep the last good angle for a
    // moment rather than turning the extension on and off every other frame.
    private float lastAimAngle = Float.NaN;
    private int lastAimScore;
    private long lastAimMs;
    private Detector.Ball lastCue;
    private float lastTargetAngle = Float.NaN;
    private Detector.Ball lastAimTarget = null;
    private float lastGhostX = Float.NaN, lastGhostY = Float.NaN;
    private float lastStubTipX = Float.NaN, lastStubTipY = Float.NaN;
    private float lastCueReboundAngle = Float.NaN;
    private int lastRotation = -1;

    @Override
    public void onFrame(CaptureEngine.Frame frame) {
        if (bubble != null) bubble.onFrame(frame);

        // A rotation change invalidates the virtual display size, so rebuild it
        // rather than mirroring a stretched portrait buffer into the game.
        if (engine != null) {
            int rot = engine.rotation();
            if (lastRotation == -1) {
                lastRotation = rot;
            } else if (rot != lastRotation) {
                lastRotation = rot;
                final MediaProjection p = projection;
                if (p != null) {
                    Log.i(TAG, "rotation changed, rebuilding capture");
                    if (frame.bitmap != null && !frame.bitmap.isRecycled()) {
                        frame.bitmap.recycle();
                    }
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (engine != null) engine.rebuild(p);
                        }
                    });
                    return;
                }
            }
        }

        // Hand the frame to the detection thread instead of analysing it here.
        // analyse() is the single most expensive thing we do, and running it on
        // the ImageReader callback meant the capture could only deliver a new
        // frame once the previous one had finished being processed: measured
        // 39 frames in 5.1 s (7.6 fps) purely because detection took ~130 ms.
        // The capture now runs free, and detection consumes whatever the latest
        // frame is, dropping any that arrive while it is still busy.
        submitFrame(frame.bitmap);
    }

    // ------------------------------------------------------- detection thread

    /** Only ever touched from the detection thread. */
    private android.os.HandlerThread detectThread;
    private Handler detectHandler;

    /** Single-slot handoff: newest frame wins, older ones are recycled. */
    private final Object slotLock = new Object();
    private Bitmap slotFrame;
    private boolean slotPending;

    private void startDetectThread() {
        if (detectHandler != null) return;
        detectThread = new android.os.HandlerThread("poollens-detect",
                android.os.Process.THREAD_PRIORITY_DISPLAY);
        detectThread.start();
        detectHandler = new Handler(detectThread.getLooper());
    }

    private void stopDetectThread() {
        if (detectThread != null) {
            detectThread.quitSafely();
            detectThread = null;
            detectHandler = null;
        }
        synchronized (slotLock) {
            if (slotFrame != null) {
                slotFrame.recycle();
                slotFrame = null;
            }
            slotPending = false;
        }
    }

    private void submitFrame(Bitmap bmp) {
        if (bmp == null) return;
        startDetectThread();
        Handler h = detectHandler;
        if (h == null) {
            bmp.recycle();
            return;
        }
        boolean post;
        synchronized (slotLock) {
            // If the detector has not picked up the previous frame yet, that
            // frame is already stale: bin it and keep only the newest.
            if (slotFrame != null) slotFrame.recycle();
            slotFrame = bmp;
            post = !slotPending;
            slotPending = true;
        }
        if (post) {
            h.post(detectRunnable);
        }
    }

    private final Runnable detectRunnable = new Runnable() {
        @Override
        public void run() {
            Bitmap bmp;
            synchronized (slotLock) {
                bmp = slotFrame;
                slotFrame = null;
                slotPending = false;
            }
            if (bmp == null) return;
            try {
                runDetect(bmp);
            } catch (Exception e) {
                Log.e(TAG, "detect failed", e);
            } finally {
                if (!bmp.isRecycled()) bmp.recycle();
            }
        }
    };

    private void runDetect(Bitmap bitmap) {
        Detector.Result r = Detector.analyse(bitmap);
        if (r == null) return;
        detectCount++;
        FrameDump.report(this, bitmap, r);

        {

            // The game pulses its guideline, so a single frame often misses it.
            // Hold the last good aim briefly instead of flickering off, and only
            // accept a new angle once it has settled.
            long t = System.currentTimeMillis();
            if (r.aimFound) {
                // Two near-white lines compete: the dashed guideline and the
                // wooden cue stick. When the guideline fades the detected angle
                // can jump tens of degrees between frames. At 14 Hz a real drag
                // never sweeps that far, so treat a big jump as a mis-detection
                // unless the new line scores clearly better.
                boolean prevRecent = lastAimMs != 0 && t - lastAimMs < 2000
                        && lastCue != null && r.cue != null
                        && Math.hypot(r.cue.x - lastCue.x, r.cue.y - lastCue.y) < 60;
                if (prevRecent) {
                    float delta = Math.abs(normDeg(
                            (float) Math.toDegrees(r.aimAngle - lastAimAngle)));
                    if (delta > 18f) {
                        r.aimAngle = lastAimAngle;
                        r.aimScore = lastAimScore;
                    }
                }
                lastAimAngle = r.aimAngle;
                lastAimScore = r.aimScore;
                lastAimMs = t;

                if (r.aimTarget != null) {
                    if (prevRecent && !Float.isNaN(lastTargetAngle) && !Float.isNaN(r.targetAngle)) {
                        float dTarget = Math.abs(normDeg(
                                (float) Math.toDegrees(r.targetAngle - lastTargetAngle)));
                        if (dTarget > 20f) {
                            // Wild target jump while cue is stationary: hold settled target!
                            r.targetAngle = lastTargetAngle;
                            r.stubTipX = lastStubTipX;
                            r.stubTipY = lastStubTipY;
                        }
                    }
                    lastAimTarget = r.aimTarget;
                    lastTargetAngle = r.targetAngle;
                    lastGhostX = r.ghostX;
                    lastGhostY = r.ghostY;
                    lastStubTipX = r.stubTipX;
                    lastStubTipY = r.stubTipY;
                    lastCueReboundAngle = r.cueReboundAngle;
                } else if (prevRecent && lastAimTarget != null) {
                    // Momentary pulse dip: restore previous target!
                    r.aimTarget = lastAimTarget;
                    r.targetAngle = lastTargetAngle;
                    r.ghostX = lastGhostX;
                    r.ghostY = lastGhostY;
                    r.stubTipX = lastStubTipX;
                    r.stubTipY = lastStubTipY;
                    r.cueReboundAngle = lastCueReboundAngle;
                }
            } else if (lastAimMs != 0 && t - lastAimMs < 1200 && lastCue != null) {
                // If cue was momentarily missed (e.g. shadowed or during cue animation), restore last cue
                if (r.cue == null) {
                    r.cue = lastCue;
                }
                if (Math.hypot(r.cue.x - lastCue.x, r.cue.y - lastCue.y) < 60) {
                    // Reuse the recent angle and target rather than blinking off
                    r.aimAngle = lastAimAngle;
                    r.aimScore = lastAimScore;
                    r.aimFound = true;
                    if (lastAimTarget != null) {
                        r.aimTarget = lastAimTarget;
                        r.targetAngle = lastTargetAngle;
                        r.ghostX = lastGhostX;
                        r.ghostY = lastGhostY;
                        r.stubTipX = lastStubTipX;
                        r.stubTipY = lastStubTipY;
                        r.cueReboundAngle = lastCueReboundAngle;
                    }
                }
            } else {
                // Aim session ended: clear stale target/ghost so they never leak into idle state
                lastAimTarget = null;
                lastTargetAngle = Float.NaN;
                lastGhostX = Float.NaN;
                lastGhostY = Float.NaN;
                lastStubTipX = Float.NaN;
                lastStubTipY = Float.NaN;
                lastCueReboundAngle = Float.NaN;
            }
            if (r.cue != null) lastCue = r.cue;

            lastResult = r;
            if (guide != null) guide.render(r);
            if (bubble != null) bubble.onDetect(r);

            if (t - lastLogMs >= 2000) {
                double hz = detectCount * 1000.0 / Math.max(1, t - lastLogMs);
                detectCount = 0;
                lastLogMs = t;
                StringBuilder sb = new StringBuilder();
                sb.append("detect ").append(String.format("%.1f", hz)).append("Hz")
                        .append(" table=").append(r.tableFound)
                        .append(' ').append(r.tableLeft).append(',').append(r.tableTop)
                        .append('-').append(r.tableRight).append(',').append(r.tableBottom)
                        .append(" felt=").append(r.feltCoverage).append('%');
                if (r.aimFound) {
                    sb.append(" aim=")
                            .append(Math.round(Math.toDegrees(r.aimAngle)))
                            .append("deg(").append(r.aimScore).append("%)");
                } else {
                    sb.append(" aim=--");
                }
                if (!Float.isNaN(r.stickAngle)) {
                    sb.append(" stick=")
                            .append(Math.round(Math.toDegrees(r.stickAngle))).append("deg");
                } else {
                    sb.append(" stick=--");
                }
                if (r.cue != null) {
                    sb.append(" cue@").append(r.cue.x).append(',').append(r.cue.y);
                } else {
                    sb.append(" cue=none");
                }
                if (r.aimTarget != null) {
                    sb.append(" ->@").append(r.aimTarget.x).append(',')
                            .append(r.aimTarget.y);
                } else {
                    sb.append(" ->none");
                }
                if (!Float.isNaN(r.ghostX)) {
                    sb.append(" ghost@").append(Math.round(r.ghostX)).append(',')
                            .append(Math.round(r.ghostY));
                }
                if (!Float.isNaN(r.targetAngle)) {
                    sb.append(" tgtAng=").append(Math.round(Math.toDegrees(r.targetAngle))).append("deg");
                }
                if (!Float.isNaN(r.cueReboundAngle)) {
                    sb.append(" cueAng=").append(Math.round(Math.toDegrees(r.cueReboundAngle))).append("deg");
                }
                Log.i(TAG, sb.toString());
            }
        }
    }

    Detector.Result lastResult() {
        return lastResult;
    }
    /** Wraps an angle in degrees into (-180, 180]. */
    private static float normDeg(float a) {
        a = a % 360f;
        if (a > 180f) a -= 360f;
        if (a <= -180f) a += 360f;
        return a;
    }

    // ------------------------------------------------------- foreground + ui

    private void promoteToForeground() {
        NotificationManager nm =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "PoolLens Capture", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }

        Intent open = new Intent(this, MainActivity.class);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) piFlags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, piFlags);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        b.setContentTitle(getString(R.string.notif_title))
                .setContentText(getString(R.string.notif_text))
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .setContentIntent(pi);

        Notification n = b.build();

        // The manifest declares foregroundServiceType="mediaProjection", so the
        // system validates the projection grant on every startForeground()
        // overload. Before consent exists that check always throws, so we simply
        // do not promote yet; startProjection() promotes once the grant lands.
        if (!haveProjection) {
            return;
        }

        try {
            startForeground(NOTIF_ID, n);
        } catch (Exception e) {
            Log.e(TAG, "startForeground failed", e);
        }
    }

    private void updateNotification(String text) {
        NotificationManager nm =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        Intent open = new Intent(this, MainActivity.class);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) piFlags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, piFlags);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        b.setContentTitle(getString(R.string.notif_title))
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .setContentIntent(pi);

        try {
            nm.notify(NOTIF_ID, b.build());
        } catch (Exception ignored) {
        }
    }

    private void toastOnMain(final String s) {
        main.post(new Runnable() {
            @Override
            public void run() {
                android.widget.Toast.makeText(OverlayService.this, s,
                        android.widget.Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ------------------------------------------------------------- teardown

    private void shutdown() {
        stopEngine();
        if (bubble != null) {
            bubble.hide();
            bubble = null;
        }
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        running = false;
        stopEngine();
        if (bubble != null) {
            bubble.hide();
            bubble = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
