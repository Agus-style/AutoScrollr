package com.suyashsrijan.autoscrollr;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Path;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.support.v4.app.NotificationCompat;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

public class ScrollrService extends AccessibilityService {

    private static final String TAG = "AutoScrollr-Service";
    private static final String CHANNEL_ID = "autoscrollr_channel";
    private static final String TIKTOK_PACKAGE = "com.zhiliaoapp.musically";
    private static final String TIKTOK_PACKAGE_ALT = "com.ss.android.ugc.trill";
    private static final long SCROLL_ANIMATION_DURATION = 400;
    private static final long CHECK_INTERVAL = 500;

    public static ScrollrService instance;

    private Handler handler = new Handler(Looper.getMainLooper());
    private Runnable checkProgressRunnable;
    private boolean isRunning = false;
    private boolean isPaused = false;
    private boolean isScrolling = false;
    private long lastScrollTime = 0;

    private BroadcastReceiver controlReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getStringExtra("action");
            if (action == null) return;
            switch (action) {
                case "start":  startAutoScroll(); break;
                case "stop":   stopAutoScroll();  break;
                case "pause":  pauseAutoScroll(); break;
                case "resume": resumeAutoScroll(); break;
            }
        }
    };

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.i(TAG, "Service connected");
        registerReceiver(controlReceiver,
            new IntentFilter("com.suyashsrijan.autoscrollr.CONTROL"));
        showNotification();
        sendStatusBroadcast("connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!isRunning || isPaused) return;
        String pkg = event.getPackageName() != null
            ? event.getPackageName().toString() : "";
        if (!pkg.equals(TIKTOK_PACKAGE) && !pkg.equals(TIKTOK_PACKAGE_ALT)) return;

        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            checkAndSkipLive();
        }
    }

    @Override
    public void onInterrupt() { stopAutoScroll(); }

    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;
        stopAutoScroll();
        try { unregisterReceiver(controlReceiver); } catch (Exception ignored) {}
    }

    public void startAutoScroll() {
        if (isRunning) return;
        isRunning = true;
        isPaused = false;
        Log.i(TAG, "Starting - monitor progress bar");
        sendStatusBroadcast("started");
        startProgressMonitor();
    }

    public void stopAutoScroll() {
        isRunning = false;
        isPaused = false;
        stopProgressMonitor();
        Log.i(TAG, "Stopped");
        sendStatusBroadcast("stopped");
    }

    public void pauseAutoScroll() {
        isPaused = true;
        stopProgressMonitor();
        sendStatusBroadcast("paused");
    }

    public void resumeAutoScroll() {
        if (!isRunning) return;
        isPaused = false;
        sendStatusBroadcast("started");
        startProgressMonitor();
    }

    public boolean isRunning() { return isRunning; }
    public boolean isPaused()  { return isPaused; }

    private void startProgressMonitor() {
        stopProgressMonitor();
        checkProgressRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isRunning || isPaused) return;

                if (isSkipLiveEnabled() && isCurrentlyLive()) {
                    Log.i(TAG, "LIVE - skipping");
                    sendStatusBroadcast("live_skipped");
                    doSwipeUp();
                    handler.postDelayed(checkProgressRunnable, 2000);
                    return;
                }

                float[] progress = getVideoProgress();

                if (progress != null && progress[1] > 0) {
                    float current = progress[0];
                    float max = progress[1];
                    float percent = (current / max) * 100f;

                    long durationMs = max <= 600 ? (long)(max * 1000) : (long) max;
                    long remainingMs = max <= 600
                        ? (long)((max - current) * 1000)
                        : (long)(max - current);
                    sendDurationBroadcast(durationMs);

                    Log.d(TAG, "Progress: " + String.format("%.1f", percent)
                        + "% remaining: " + remainingMs + "ms");

                    if (percent >= 99f || remainingMs <= 800) {
                        if (!isScrolling &&
                            System.currentTimeMillis() - lastScrollTime > 2000) {
                            Log.i(TAG, "Video selesai! Scrolling...");
                            isScrolling = true;
                            lastScrollTime = System.currentTimeMillis();
                            sendStatusBroadcast("scrolled");

                            long extraDelay = getExtraDelayFromPrefs();
                            handler.postDelayed(() -> {
                                doSwipeUp();
                                isScrolling = false;
                                handler.postDelayed(checkProgressRunnable, 1500);
                            }, extraDelay);
                            return;
                        }
                    }
                } else {
                    // Progress bar tidak terdeteksi
                    // Fallback: pakai durasi default dari settings
                    Log.w(TAG, "Progress bar not found - using default duration fallback");
                    long fallback = getDefaultDurationFromPrefs();
                    if (!isScrolling &&
                        System.currentTimeMillis() - lastScrollTime > fallback) {
                        isScrolling = true;
                        lastScrollTime = System.currentTimeMillis();
                        sendStatusBroadcast("scrolled");
                        handler.postDelayed(() -> {
                            doSwipeUp();
                            isScrolling = false;
                            handler.postDelayed(checkProgressRunnable, 1500);
                        }, getExtraDelayFromPrefs());
                        return;
                    }
                }

                handler.postDelayed(checkProgressRunnable, CHECK_INTERVAL);
            }
        };
        handler.post(checkProgressRunnable);
    }

    private void stopProgressMonitor() {
        if (checkProgressRunnable != null) {
            handler.removeCallbacks(checkProgressRunnable);
            checkProgressRunnable = null;
        }
    }

    private float[] getVideoProgress() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;

        try {
            String[] ids = {
                // TikTok regional (prioritas utama)
                TIKTOK_PACKAGE_ALT + ":id/progress_bar",
                TIKTOK_PACKAGE_ALT + ":id/video_progress",
                TIKTOK_PACKAGE_ALT + ":id/seek_bar",
                TIKTOK_PACKAGE_ALT + ":id/player_progress",
                TIKTOK_PACKAGE_ALT + ":id/video_seek_bar",
                TIKTOK_PACKAGE_ALT + ":id/tt_video_progress",
                TIKTOK_PACKAGE_ALT + ":id/slide_seekbar",
                TIKTOK_PACKAGE_ALT + ":id/video_controller_seekbar",
                // TikTok global
                TIKTOK_PACKAGE + ":id/progress_bar",
                TIKTOK_PACKAGE + ":id/video_progress",
                TIKTOK_PACKAGE + ":id/seek_bar",
                TIKTOK_PACKAGE + ":id/player_progress",
                TIKTOK_PACKAGE + ":id/video_seek_bar",
                TIKTOK_PACKAGE + ":id/tt_video_progress",
                TIKTOK_PACKAGE + ":id/slide_seekbar",
            };

            for (String resId : ids) {
                List<AccessibilityNodeInfo> bars =
                    root.findAccessibilityNodeInfosByViewId(resId);
                if (bars != null && !bars.isEmpty()) {
                    for (AccessibilityNodeInfo bar : bars) {
                        if (bar.getRangeInfo() != null) {
                            float current = bar.getRangeInfo().getCurrent();
                            float max = bar.getRangeInfo().getMax();
                            if (max > 0) {
                                Log.d(TAG, "Found progress bar: " + resId
                                    + " current=" + current + " max=" + max);
                                root.recycle();
                                return new float[]{current, max};
                            }
                        }
                    }
                }
            }

            // Fallback: scan semua node cari RangeInfo
            float[] result = scanAllNodesForProgress(root);
            if (result != null) {
                root.recycle();
                return result;
            }

        } catch (Exception e) {
            Log.e(TAG, "getVideoProgress error: " + e.getMessage());
        }

        root.recycle();
        return null;
    }

    // Scan semua node untuk cari progress bar apapun ID-nya
    private float[] scanAllNodesForProgress(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.getRangeInfo() != null) {
            float current = node.getRangeInfo().getCurrent();
            float max = node.getRangeInfo().getMax();
            if (max > 0 && current >= 0) {
                Log.d(TAG, "Found RangeInfo via scan: current="
                    + current + " max=" + max
                    + " viewId=" + node.getViewIdResourceName());
                return new float[]{current, max};
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            float[] result = scanAllNodesForProgress(child);
            if (child != null) child.recycle();
            if (result != null) return result;
        }
        return null;
    }

    private boolean isCurrentlyLive() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        try {
            String[] keywords = {"LIVE", "Live", "SIARAN LANGSUNG"};
            for (String kw : keywords) {
                List<AccessibilityNodeInfo> nodes =
                    root.findAccessibilityNodeInfosByText(kw);
                if (nodes != null) {
                    for (AccessibilityNodeInfo node : nodes) {
                        CharSequence text = node.getText();
                        if (text != null &&
                            text.toString().trim().equalsIgnoreCase("LIVE")) {
                            root.recycle();
                            return true;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "isLive error: " + e.getMessage());
        }
        root.recycle();
        return false;
    }

    private void checkAndSkipLive() {
        handler.postDelayed(() -> {
            if (!isRunning || isPaused) return;
            if (isCurrentlyLive()) {
                sendStatusBroadcast("live_skipped");
                doSwipeUp();
            }
        }, 600);
    }

    private void doSwipeUp() {
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        Path path = new Path();
        path.moveTo(w / 2f, h * 0.75f);
        path.lineTo(w / 2f, h * 0.25f);
        GestureDescription gesture = new GestureDescription.Builder()
            .addStroke(new GestureDescription.StrokeDescription(
                path, 0, SCROLL_ANIMATION_DURATION))
            .build();
        dispatchGesture(gesture, null, null);
    }

    private long getDefaultDurationFromPrefs() {
        try { return Long.parseLong(PreferenceManager
            .getDefaultSharedPreferences(this)
            .getString("defaultVideoDuration", "15000"));
        } catch (Exception e) { return 15000L; }
    }

    private long getExtraDelayFromPrefs() {
        try { return Long.parseLong(PreferenceManager
            .getDefaultSharedPreferences(this)
            .getString("extraDelay", "0"));
        } catch (Exception e) { return 0L; }
    }

    private boolean isSkipLiveEnabled() {
        return PreferenceManager.getDefaultSharedPreferences(this)
            .getBoolean("skipLive", true);
    }

    public void showNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "TikTok AutoScrollr",
                NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
        Intent notifIntent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, notifIntent,
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_IMMUTABLE : 0);
        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TikTok AutoScrollr")
            .setStyle(new NotificationCompat.BigTextStyle()
                .bigText("Memantau progress video • Skip LIVE otomatis"))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pi)
            .setOngoing(true)
            .build();
        startForeground(3107, n);
    }

    private void sendStatusBroadcast(String status) {
        Intent i = new Intent("com.suyashsrijan.autoscrollr.STATUS_UPDATE");
        i.putExtra("status", status);
        sendBroadcast(i);
    }

    private void sendDurationBroadcast(long ms) {
        Intent i = new Intent("com.suyashsrijan.autoscrollr.STATUS_UPDATE");
        i.putExtra("status", "duration");
        i.putExtra("duration_ms", ms);
        sendBroadcast(i);
    }
}
