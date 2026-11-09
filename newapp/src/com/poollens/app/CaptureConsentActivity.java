package com.poollens.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;

/**
 * Invisible activity whose only job is to show the system screen-capture
 * consent dialog and hand the resulting grant token to OverlayService.
 *
 * MediaProjection grants are single-use, so the token must be passed to the
 * service as an Intent extra rather than cached anywhere global.
 */
public class CaptureConsentActivity extends Activity {

    static final String EXTRA_RESULT_CODE = "result_code";
    static final String EXTRA_RESULT_DATA = "result_data";

    private static final int REQ_CONSENT = 2001;

    /** Convenience launcher used by the service/notification. */
    static void request(Context ctx) {
        Intent i = new Intent(ctx, CaptureConsentActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        ctx.startActivity(i);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Never re-prompt if we already hold a valid grant for this launch.
        if (OverlayService.hasProjection()) {
            finish();
            return;
        }

        MediaProjectionManager mpm = (MediaProjectionManager)
                getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mpm == null) {
            finish();
            return;
        }

        try {
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CONSENT);
        } catch (Exception e) {
            finish();
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);

        if (request != REQ_CONSENT) {
            finish();
            return;
        }

        if (result == RESULT_OK && data != null) {
            Intent toService = new Intent(this, OverlayService.class);
            toService.setAction(OverlayService.ACTION_GRANT);
            toService.putExtra(EXTRA_RESULT_CODE, result);
            toService.putExtra(EXTRA_RESULT_DATA, data);

            // If the service is already alive this is a plain start; otherwise
            // startForegroundService so the 5s clock starts and onStartCommand
            // answers it immediately.
            if (Build.VERSION.SDK_INT >= 26 && !OverlayService.isRunning()) {
                startForegroundService(toService);
            } else {
                startService(toService);
            }
        } else {
            // User declined - tell the service so it can stand down cleanly.
            Intent deny = new Intent(this, OverlayService.class);
            deny.setAction(OverlayService.ACTION_DENIED);
            try {
                startService(deny);
            } catch (Exception ignored) {
            }
        }

        finish();
        overridePendingTransition(0, 0);
    }
}
