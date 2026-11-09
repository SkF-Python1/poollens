package com.poollens.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Engineering aid: writes the exact Bitmap the detector is handed to a PNG,
 * together with the detector's own result for that same frame.
 *
 * This exists because a device screenshot and a MediaProjection capture are NOT
 * the same image: they are produced by different scaling paths, and it is not
 * obvious whether a screenshot even includes our overlay window. When a live
 * bug has to be pinned down, this is the only way to be certain what the
 * detector actually saw.
 *
 * Pull the files with:
 *   adb pull /sdcard/Android/data/com.poollens.app/files/frame1.png
 */
final class FrameDump {

    private static final String TAG = "PoolLens";

    /** Flip to true to capture; remember to flip back for release builds. */
    static final boolean ENABLED = false;

    /** Stop after this many frames so the app does not fill the card. */
    private static final int MAX = 3;

    private static int written;

    private FrameDump() {
    }

    /**
     * Saves a frame plus the result computed FROM THAT FRAME, so the two can
     * never drift apart. The numbers are logged before the (slow) PNG write.
     */
    static void report(Context ctx, Bitmap bmp, Detector.Result r) {
        if (!ENABLED || bmp == null || bmp.isRecycled() || r == null) return;
        if (written >= MAX) return;
        int n = ++written;
        Log.i(TAG, "frame" + n
                + " aim=" + (r.aimFound
                        ? Math.round(Math.toDegrees(r.aimAngle)) + "deg" : "none")
                + " score=" + r.aimScore
                + " stick=" + (Float.isNaN(r.stickAngle)
                        ? "none" : Math.round(Math.toDegrees(r.stickAngle)) + "deg")
                + " cue=" + (r.cue != null ? r.cue.x + "," + r.cue.y : "none")
                + " table=" + r.tableLeft + "," + r.tableTop + "-"
                + r.tableRight + "," + r.tableBottom);
        try {
            File dir = ctx.getExternalFilesDir(null);
            if (dir == null) return;
            File f = new File(dir, "frame" + n + ".png");
            FileOutputStream out = new FileOutputStream(f);
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
            out.close();
            Log.i(TAG, "dumped " + f.getAbsolutePath() + " " + bmp.getWidth()
                    + "x" + bmp.getHeight());
        } catch (Exception e) {
            Log.e(TAG, "frame dump failed", e);
        }
    }
}
