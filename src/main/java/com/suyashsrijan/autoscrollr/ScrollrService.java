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

import java.util.ArrayList;
import java.util.List;

public class ScrollrService extends AccessibilityService {

    private static final String TAG = "AutoScrollr-Service";
    private static final String CHANNEL_ID = "autoscrollr_channel";
    private static final String TIKTOK_PACKAGE = "com.zhiliaoapp.musically";
    private static final String TIKTOK_PACKAGE_ALT = "com.ss.android.ugc.trill";
    private static final long SCROLL_ANIMATION_DURATION = 300;
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
            checkAndSkipLiveOrAd();
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
        Log.i(TAG, "Starting - monitor progress");
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

    // ===================== MONITOR PROGRESS =====================

    private void startProgressMonitor() {
        stopProgressMonitor();
        checkProgressRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isRunning || isPaused) return;

                // Cek LIVE dan iklan
                if (checkAndSkipLiveOrAd()) {
                    handler.postDelayed(checkProgressRunnable, 2000);
                    return;
                }

                // Coba baca progress dari SeekBar
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

                    // Scroll kalau sisa <= 800ms atau progress >= 99%
                    if ((percent >= 99f || remainingMs <= 800) && !isScrolling
                        && System.currentTimeMillis() - lastScrollTime > 2000) {
                        triggerScroll();
                        return;
                    }

                    // Cek lebih cepat kalau hampir selesai
                    long nextCheck = remainingMs <= 2000 ? 200 : CHECK_INTERVAL;
                    handler.postDelayed(checkProgressRunnable, nextCheck);

                } else {
                    // SeekBar tidak terdeteksi → coba timestamp teks
                    long[] times = readTimestamp();
                    if (times != null && times[1] > 0) {
                        long remainingSec = times[1] - times[0];
                        long remainingMs = remainingSec * 1000L;
                        sendDurationBroadcast(times[1] * 1000L);
                        Log.d(TAG, "Timestamp: " + times[0] + "s / "
                            + times[1] + "s remaining: " + remainingSec + "s");

                        if (remainingSec <= 1 && !isScrolling
                            && System.currentTimeMillis() - lastScrollTime > 2000) {
                            triggerScroll();
                            return;
                        }

                        long nextCheck = remainingSec <= 3 ? 300 : CHECK_INTERVAL;
                        handler.postDelayed(checkProgressRunnable, nextCheck);

                    } else {
                        // Fallback timer default
                        long fallback = getDefaultDurationFromPrefs();
                        if (!isScrolling
                            && System.currentTimeMillis() - lastScrollTime >= fallback) {
                            Log.w(TAG, "Fallback timer triggered");
                            triggerScroll();
                            return;
                        }
                        handler.postDelayed(checkProgressRunnable, CHECK_INTERVAL);
                    }
                }
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

    private void triggerScroll() {
        isScrolling = true;
        lastScrollTime = System.currentTimeMillis();
        sendStatusBroadcast("scrolled");
        long extra = getExtraDelayFromPrefs();
        handler.postDelayed(() -> {
            doSwipeUp();
            isScrolling = false;
            handler.postDelayed(checkProgressRunnable, 1200);
        }, extra);
    }

    // ===================== LIVE & IKLAN CHECK =====================

    private boolean checkAndSkipLiveOrAd() {
        if (!isSkipLiveEnabled()) return false;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;

        try {
            List<String> allTexts = new ArrayList<>();
            collectTexts(root, allTexts);

            for (String text : allTexts) {
                String t = text.trim();

                // Deteksi LIVE badge — hanya teks persis "LIVE"
                // bukan "Go LIVE", "Watch LIVE", "is live", "LIVE followers" dll
                if (t.equals("LIVE")) {
                    Log.i(TAG, "LIVE badge detected - skipping");
                    sendStatusBroadcast("live_skipped");
                    root.recycle();
                    doSwipeUp();
                    return true;
                }

                // Deteksi iklan — teks yang biasa muncul di iklan TikTok
                if (t.equals("Sponsored") || t.equals("Ad")
                    || t.equals("Iklan") || t.equals("Berbayar")
                    || t.equals("Promoted")) {
                    Log.i(TAG, "Ad detected - skipping");
                    sendStatusBroadcast("ad_skipped");
                    root.recycle();
                    doSwipeUp();
                    return true;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "checkLiveAd error: " + e.getMessage());
        }

        root.recycle();
        return false;
    }

    // ===================== BACA PROGRESS SEEKBAR =====================

    private float[] getVideoProgress() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;
        try {
            String[] ids = {
                TIKTOK_PACKAGE_ALT + ":id/progress_bar",
                TIKTOK_PACKAGE_ALT + ":id/video_progress",
                TIKTOK_PACKAGE_ALT + ":id/seek_bar",
                TIKTOK_PACKAGE_ALT + ":id/player_progress",
                TIKTOK_PACKAGE_ALT + ":id/tt_video_progress",
                TIKTOK_PACKAGE_ALT + ":id/slide_seekbar",
                TIKTOK_PACKAGE_ALT + ":id/video_controller_seekbar",
                TIKTOK_PACKAGE + ":id/progress_bar",
                TIKTOK_PACKAGE + ":id/video_progress",
                TIKTOK_PACKAGE + ":id/seek_bar",
                TIKTOK_PACKAGE + ":id/player_progress",
            };
            for (String resId : ids) {
                List<AccessibilityNodeInfo> bars =
                    root.findAccessibilityNodeInfosByViewId(resId);
                if (bars != null && !bars.isEmpty()) {
                    for (AccessibilityNodeInfo bar : bars) {
                        if (bar.getRangeInfo() != null) {
                            float cur = bar.getRangeInfo().getCurrent();
                            float max = bar.getRangeInfo().getMax();
                            if (max > 0) {
                                root.recycle();
                                return new float[]{cur, max};
                            }
                        }
                    }
                }
            }
            // Scan semua node
            float[] result = scanNodes(root);
            if (result != null) { root.recycle(); return result; }
        } catch (Exception e) {
            Log.e(TAG, "getProgress error: " + e.getMessage());
        }
        root.recycle();
        return null;
    }

    private float[] scanNodes(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.getRangeInfo() != null) {
            float cur = node.getRangeInfo().getCurrent();
            float max = node.getRangeInfo().getMax();
            if (max > 0 && cur >= 0) return new float[]{cur, max};
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            float[] r = scanNodes(child);
            if (child != null) child.recycle();
            if (r != null) return r;
        }
        return null;
    }

    // ===================== BACA TIMESTAMP TEKS =====================

    private long[] readTimestamp() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;
        List<String> texts = new ArrayList<>();
        collectTexts(root, texts);
        root.recycle();

        for (String text : texts) {
            text = text.trim();
            if (text.contains("/")) {
                String[] parts = text.split("/");
                if (parts.length == 2) {
                    long cur = parseTime(parts[0].trim());
                    long tot = parseTime(parts[1].trim());
                    if (cur >= 0 && tot > 0 && tot <= 600) {
                        return new long[]{cur, tot};
                    }
                }
            }
        }
        return null;
    }

    private void collectTexts(AccessibilityNodeInfo node, List<String> out) {
        if (node == null) return;
        CharSequence t = node.getText();
        if (t != null && t.length() > 0) out.add(t.toString());
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            collectTexts(child, out);
            if (child != null) child.recycle();
        }
    }

    private long parseTime(String s) {
        try {
            if (s.contains(":")) {
                String[] p = s.split(":");
                return Long.parseLong(p[0].trim()) * 60
                    + Long.parseLong(p[1].trim());
            }
            return Long.parseLong(s);
        } catch (Exception e) { return -1; }
    }

    // ===================== SWIPE =====================

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

    // ===================== PREFERENCES =====================

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

    // ===================== NOTIFICATION =====================

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
                .bigText("Auto scroll aktif • Skip LIVE & iklan otomatis"))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pi)
            .setOngoing(true)
            .build();
        startForeground(3107, n);
    }

    // ===================== BROADCAST =====================

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
