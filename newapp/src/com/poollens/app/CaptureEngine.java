package com.poollens.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.WindowManager;

import java.nio.ByteBuffer;

/**
 * Pulls frames out of a MediaProjection via a VirtualDisplay backed by an
 * ImageReader, converts them to RGB bitmaps, and hands them to a listener.
 *
 * Deliberately simple: one acquisition at a time on a private thread, no
 * re-entrancy, no retry storms.
 */
class CaptureEngine {

    interface Frames {
        void onFrame(Frame frame);
    }

    static final class Frame {
        final Bitmap bitmap;
        final long timestampMs;
        final int rotation;

        Frame(Bitmap b, long t, int r) {
            bitmap = b;
            timestampMs = t;
            rotation = r;
        }
    }

    private static final String TAG = "PoolLens";

    private final Context ctx;
    private final Frames listener;

    private HandlerThread thread;
    private Handler handler;
    private ImageReader reader;
    private VirtualDisplay display;
    private volatile boolean active;

    private int width;
    private int height;
    private int density;
    private int rotation;

    /**
     * Long-side cap for the virtual display.
     *
     * The detector reads full-frame pixels every pass, and cost scales with
     * area, so mirroring 3200x2136 costs 4x what 1600x1068 does for no accuracy
     * we can use: a ball is still ~21 px across at half size. The overlay maps
     * capture pixels back to the real screen, so nothing visual changes.
     */
    private static final int CAPTURE_LONG = 1600;

    CaptureEngine(Context ctx, Frames listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
    }

    void start(MediaProjection projection) {
        if (active) {
            Log.d(TAG, "engine already active");
            return;
        }
        if (projection == null) {
            Log.w(TAG, "start with null projection");
            return;
        }

        readDisplayMetrics();

        thread = new HandlerThread("poollens-capture");
        thread.start();
        handler = new Handler(thread.getLooper());

        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        final ImageReader thisReader = reader;

        reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            @Override
            public void onImageAvailable(ImageReader r) {
                // During a rotation rebuild the old reader can still fire once
                // after being closed. Its buffer is already invalid, so ignore
                // anything from a reader that is no longer current.
                if (r != thisReader || !active) {
                    return;
                }
                Image img = null;
                try {
                    img = r.acquireLatestImage();
                    if (img == null) return;
                    Bitmap bmp = toBitmap(img);
                    if (bmp != null && listener != null && active) {
                        long n = noteFrame();
                        if (n == 1) {
                            Log.i(TAG, "first frame image=" + img.getWidth() + "x"
                                    + img.getHeight() + " bitmap=" + bmp.getWidth()
                                    + "x" + bmp.getHeight());
                        }
                        listener.onFrame(new Frame(bmp, System.currentTimeMillis(), 0));
                    }
                } catch (IllegalStateException e) {
                    // Expected during teardown; not worth logging.
                } catch (Exception e) {
                    Log.e(TAG, "image acquire failed", e);
                } finally {
                    if (img != null) {
                        try {
                            img.close();
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        }, handler);

        try {
            display = projection.createVirtualDisplay(
                    "PoolLensCapture",
                    width, height, density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.getSurface(),
                    null, handler);
        } catch (Exception e) {
            Log.e(TAG, "createVirtualDisplay failed", e);
            release();
            return;
        }

        if (display == null) {
            Log.e(TAG, "virtual display null");
            release();
            return;
        }

        active = true;
        Log.i(TAG, "capture started " + width + "x" + height + "@" + density);
    }

    private long framesSeen;
    private long lastLog;

    /** Rate-limited frame counter so we can prove the pipeline is delivering. */
    long noteFrame() {
        framesSeen++;
        long now = System.currentTimeMillis();
        if (lastLog == 0) lastLog = now;
        if (now - lastLog >= 5000) {
            Log.i(TAG, "frames=" + framesSeen + " in " + (now - lastLog) + "ms");
            lastLog = now;
        }
        return framesSeen;
    }

    long frameCount() {
        return framesSeen;
    }

    void stop() {
        active = false;
        release();
        Log.i(TAG, "capture stopped");
    }

    private void release() {
        if (display != null) {
            try {
                display.release();
            } catch (Exception ignored) {
            }
            display = null;
        }
        if (reader != null) {
            try {
                reader.close();
            } catch (Exception ignored) {
            }
            reader = null;
        }
        if (thread != null) {
            try {
                thread.quitSafely();
            } catch (Exception ignored) {
            }
            thread = null;
            handler = null;
        }
    }

    private void readDisplayMetrics() {
        WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics dm = new DisplayMetrics();
        Display d = null;
        if (wm != null) {
            d = wm.getDefaultDisplay();
            if (d != null) d.getRealMetrics(dm);
        }
        if (dm.widthPixels <= 0 || dm.heightPixels <= 0) {
            dm = ctx.getResources().getDisplayMetrics();
        }

        // The virtual display must match the display's CURRENT orientation.
        // Creating it portrait while the game runs landscape scales the mirror
        // and wastes most of the buffer.
        rotation = 0;
        if (d != null) {
            try {
                rotation = d.getRotation();
            } catch (Exception ignored) {
            }
        }

        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            width = Math.max(dm.widthPixels, dm.heightPixels);
            height = Math.min(dm.widthPixels, dm.heightPixels);
        } else {
            width = dm.widthPixels;
            height = dm.heightPixels;
        }
        density = dm.densityDpi;

        // Downscale the mirror to keep the per-frame pixel work sane. The virtual
        // display scales the Surface for us, so this costs nothing extra.
        int longSide = Math.max(width, height);
        if (longSide > CAPTURE_LONG) {
            float k = (float) CAPTURE_LONG / longSide;
            width = Math.max(64, Math.round(width * k));
            height = Math.max(64, Math.round(height * k));
        }
    }

    /** Live display rotation, so a change can trigger a rebuild. */
    int rotation() {
        WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) return rotation;
        Display d = wm.getDefaultDisplay();
        if (d == null) return rotation;
        try {
            return d.getRotation();
        } catch (Exception e) {
            return rotation;
        }
    }

    /** Current virtual display size, for diagnostics. */
    String size() {
        return width + "x" + height;
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    boolean running() {
        return active;
    }

    /** Rebuild the virtual display at the new orientation, reusing the projection. */
    void rebuild(MediaProjection projection) {
        if (projection == null) return;
        Log.i(TAG, "rebuilding capture for rotation change");
        // release() tears the pipeline down but leaves the active flag set, so
        // it must be cleared here or start() will think a capture is already
        // running and bail out, leaving us with no frames at all.
        active = false;
        release();
        start(projection);
    }

    /** RGBA_8888 Image -> tightly packed ARGB_8888 Bitmap, honouring row stride. */
    private Bitmap toBitmap(Image img) {
        Image.Plane[] planes = img.getPlanes();
        if (planes.length == 0) return null;

        ByteBuffer buf = planes[0].getBuffer();
        int pixelStride = planes[0].getPixelStride();
        int rowStride = planes[0].getRowStride();

        int w = img.getWidth();
        int h = img.getHeight();

        buf.rewind();

        if (pixelStride == 4 && rowStride == w * 4) {
            // Tightly packed; the common case.
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            bmp.copyPixelsFromBuffer(buf);
            return bmp;
        }

        // Padded rows: copy row by row.
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        int rowBytes = w * pixelStride;
        byte[] row = new byte[rowBytes];
        ByteBuffer out = ByteBuffer.allocateDirect(w * h * 4);
        for (int y = 0; y < h; y++) {
            buf.position(y * rowStride);
            buf.get(row, 0, Math.min(rowBytes, buf.remaining()));
            out.put(row);
        }
        out.rewind();
        bmp.copyPixelsFromBuffer(out);
        return bmp;
    }
}
