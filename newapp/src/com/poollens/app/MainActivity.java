package com.poollens.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Launcher screen. Collects the two permissions we need, then starts the
 * capture service. Everything here is built in code so we have no XML layout
 * dependency issues on newer platform versions.
 */
public class MainActivity extends Activity {

    private static final int REQ_NOTIF = 1001;
    private static final int REQ_OVERLAY = 1002;

    private TextView statusView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF10151B);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        root.setPadding(pad, dp(48), pad, pad);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("PoolLens");
        title.setTextColor(0xFF3DDC97);
        title.setTextSize(30);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Table & ball detection overlay for 8 Ball Pool");
        sub.setTextColor(0xFF8A97A5);
        sub.setTextSize(13);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(4);
        subLp.bottomMargin = dp(28);
        root.addView(sub, subLp);

        statusView = new TextView(this);
        statusView.setTextColor(0xFFD7E0E8);
        statusView.setTextSize(14);
        statusView.setPadding(dp(16), dp(16), dp(16), dp(16));
        statusView.setBackgroundColor(0xFF1B232C);
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        stLp.bottomMargin = dp(24);
        root.addView(statusView, stLp);

        Button start = new Button(this);
        start.setText("START OVERLAY");
        start.setAllCaps(true);
        start.setTextColor(0xFF10151B);
        start.setBackgroundColor(0xFF3DDC97);
        start.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                begin();
            }
        });
        root.addView(start, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));

        Button stop = new Button(this);
        stop.setText("STOP");
        stop.setAllCaps(true);
        stop.setTextColor(0xFF8A97A5);
        stop.setBackgroundColor(0xFF1B232C);
        stop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopService(new Intent(MainActivity.this, OverlayService.class));
                refresh();
                toast("Stopped");
            }
        });
        LinearLayout.LayoutParams stopLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        stopLp.topMargin = dp(12);
        root.addView(stop, stopLp);

        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();

        // Once the grant lands and the service is live, get this activity off
        // the screen. It is a full-screen opaque window: if it stays on top it
        // covers the game completely, so every touch lands on our buttons and
        // the capture mirrors this settings page instead of the table.
        if (OverlayService.isRunning() && OverlayService.hasProjection()) {
            moveTaskToBack(true);
        }
    }

    private void refresh() {
        boolean overlay = canDrawOverlays();
        boolean running = OverlayService.isRunning();

        StringBuilder sb = new StringBuilder();
        sb.append(overlay ? "[ok]  " : "[--]  ").append("Draw over other apps\n");
        sb.append(notifGranted() ? "[ok]  " : "[--]  ").append("Notifications\n");
        sb.append(running ? "[ok]  " : "[--]  ").append("Capture service running");
        statusView.setText(sb.toString());
    }

    /** Kicks off the whole chain, asking only for what is still missing. */
    private void begin() {
        if (!canDrawOverlays()) {
            toast("Enable 'Display over other apps', then press Start again");
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            try {
                startActivityForResult(i, REQ_OVERLAY);
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
            }
            return;
        }

        if (!notifGranted()) {
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
            } else {
                launchService();
            }
            return;
        }

        launchService();
    }

    private void launchService() {
        // If we already hold a live grant, just (re)start the running service.
        if (OverlayService.hasProjection()) {
            Intent svc = new Intent(this, OverlayService.class);
            try {
                startService(svc);
                toast("Overlay running");
                // Get out of the way: leaving this activity on top would cover
                // the game with a full-screen, fully opaque window and every
                // touch would land here instead of on the table.
                moveTaskToBack(true);
            } catch (Exception e) {
                toast("Could not start service: " + e.getClass().getSimpleName());
            }
            return;
        }

        // Otherwise ask for screen capture first. The service is created only
        // once consent exists, because Android refuses to promote a
        // mediaProjection foreground service before the grant is recorded.
        try {
            CaptureConsentActivity.request(this);
            toast("Approve screen capture");
        } catch (Exception e) {
            toast("Could not request capture: " + e.getClass().getSimpleName());
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        // Whatever the outcome we try to continue; the notification is optional.
        launchService();
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == REQ_OVERLAY) {
            refresh();
            if (canDrawOverlays()) begin();
        }
    }

    private boolean canDrawOverlays() {
        if (Build.VERSION.SDK_INT < 23) return true;
        return Settings.canDrawOverlays(this);
    }

    private boolean notifGranted() {
        if (Build.VERSION.SDK_INT < 33) return true;
        return checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
