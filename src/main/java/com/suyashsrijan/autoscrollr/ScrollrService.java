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
    private static final long DEFAULT_VIDEO_DURATION = 15000;
    private static final long LIVE_CHECK_DELAY = 1500;
    private static final long SCROLL_ANIMATION_DURATION = 400;

    public static ScrollrService instance;

    private Handler handler = new Handler(Looper.getMainLooper());
    private Runnable scrollRunnable;
    private boolean isRunning = false;
    private boolean isPaused = false;
    private long lastAutoScrollTime = 0;

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
        Log.i(TAG, "AccessibilityService connected");
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

        // Deteksi user seek/scroll manual
        if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_SCROLLED ||
            event.getEventType() == AccessibilityEvent.TYPE_VIEW_SELECTED) {

            long now = System.currentTimeMillis();
            // Pastikan bukan dari auto scroll kita sendiri
            if (now - lastAutoScrollTime > 1500) {
                // Hitung sisa durasi video setelah di-seek
                long remaining = getRemainingDuration();
                if (remaining > 0 && remaining < 180000) {
                    Log.i(TAG, "User seeked - remaining: " + remaining + "ms");
                    sendStatusBroadcast("user_seeked");
                    // Reset timer dengan sisa durasi
                    scheduleNextScroll(remaining + getExtraDelayFromPrefs());
                }
            }
        }

        // Deteksi scroll manual (swipe ke video baru)
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            long now = System.currentTimeMillis();
            if (now - lastAutoScrollTime > 1500) {
                Log.i(TAG, "User manually scrolled to new video");
                // Reset timer untuk video baru
                handler.postDelayed(() -> {
                    long duration = detectVideoDuration();
                    scheduleNextScroll(duration + getExtraDelayFromPrefs());
                    sendDurationBroadcast(duration);
                }, 500);
            }
            checkAndSkipLive();
        }

        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
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
        Log.i(TAG, "Starting Scrollr");
        sendStatusBroadcast("started");
        scheduleNextScroll(LIVE_CHECK_DELAY);
    }

    public void stopAutoScroll() {
        isRunning = false;
        isPaused = false;
        if (scrollRunnable != null) handler.removeCallbacks(scrollRunnable);
        Log.i(TAG, "Stopping Scrollr");
        sendStatusBroadcast("stopped");
    }

    public void pauseAutoScroll() {
        isPaused = true;
        if (scrollRunnable != null) handler.removeCallbacks(scrollRunnable);
        sendStatusBroadcast("paused");
    }

    public void resumeAutoScroll() {
        if (!isRunning) return;
        isPaused = false;
        sendStatusBroadcast("started");
        scheduleNextScroll(500);
    }

    public boolean isRunning() { return isRunning; }
    public boolean isPaused()  { return isPaused; }

    private void scheduleNextScroll(long delayMs) {
        if (scrollRunnable != null) handler.removeCallbacks(scrollRunnable);
        scrollRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isRunning || isPaused) return;

                if (isSkipLiveEnabled() && isCurrentlyLive()) {
                    Log.i(TAG, "LIVE detected - skipping");
                    sendStatusBroadcast("live_skipped");
                    performSwipeUp();
                    scheduleNextScroll(LIVE_CHECK_DELAY + SCROLL_ANIMATION_DURATION);
                    return;
                }

                long duration = detectVideoDuration();
                Log.i(TAG, "Video duration: " + duration + "ms");
                sendDurationBroadcast(duration);

                long extraDelay = getExtraDelayFromPrefs();
                scheduleNextScroll(duration + SCROLL_ANIMATION_DURATION + extraDelay);

                handler.postDelayed(() -> {
                    if (!isRunning || isPaused) return;
                    lastAutoScrollTime = System.currentTimeMillis();
                    performSwipeUp();
                    sendStatusBroadcast("scrolled");
                }, duration);
            }
        };
        handler.postDelayed(scrollRunnable, delayMs);
    }

    // Hitung sisa durasi video berdasarkan posisi progress bar sekarang
    private long getRemainingDuration() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return -1;

        try {
            String[] ids = {
                TIKTOK_PACKAGE + ":id/progress_bar",
                TIKTOK_PACKAGE + ":id/video_progress",
                TIKTOK_PACKAGE + ":id/seek_bar",
            };
            for (String resId : ids) {
                List<AccessibilityNodeInfo> bars =
                    root.findAccessibilityNodeInfosByViewId(resId);
                if (bars != null && !bars.isEmpty()) {
                    AccessibilityNodeInfo bar = bars.get(0);
                    if (bar.getRangeInfo() != null) {
                        float current = bar.getRangeInfo().getCurrent();
                        float max     = bar.getRangeInfo().getMax();
                        if (max > 0) {
                            // Hitung sisa dalam ms
                            float remaining = max - current;
                            long ms = remaining <= 600
                                ? (long)(remaining * 1000)
                                : (long) remaining;
                            root.recycle();
                            return Math.max(ms, 500); // minimal 0.5 detik
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "getRemainingDuration error: " + e.getMessage());
        }

        root.recycle();
        return -1;
    }

    private boolean isCurrentlyLive() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        try {
            String[] liveKeywords = {"LIVE", "Live", "SIARAN LANGSUNG"};
            for (String keyword : liveKeywords) {
                List<AccessibilityNodeInfo> nodes =
                    root.findAccessibilityNodeInfosByText(keyword);
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
            Log.e(TAG, "Error checking LIVE: " + e.getMessage());
        }
        root.recycle();
        return false;
    }

    private void checkAndSkipLive() {
        handler.postDelayed(() -> {
            if (!isRunning || isPaused) return;
            if (isCurrentlyLive()) {
                if (scrollRunnable != null) handler.removeCallbacks(scrollRunnable);
                sendStatusBroadcast("live_skipped");
                performSwipeUp();
                scheduleNextScroll(LIVE_CHECK_DELAY);
            }
        }, 600);
    }

    private long detectVideoDuration() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return getDefaultDurationFromPrefs();
        long duration = getDefaultDurationFromPrefs();
        try {
            duration = readFromSeekBar(root);
            if (duration == getDefaultDurationFromPrefs()) {
                long fromText = searchDurationInNode(root);
                if (fromText > 0) duration = fromText;
            }
        } catch (Exception e) {
            Log.e(TAG, "Duration detect error: " + e.getMessage());
        }
        root.recycle();
        return duration;
    }

    private long readFromSeekBar(AccessibilityNodeInfo root) {
        String[] ids = {
            TIKTOK_PACKAGE + ":id/progress_bar",
            TIKTOK_PACKAGE + ":id/video_progress",
            TIKTOK_PACKAGE + ":id/seek_bar",
        };
        for (String resId : ids) {
            List<AccessibilityNodeInfo> bars =
                root.findAccessibilityNodeInfosByViewId(resId);
            if (bars != null && !bars.isEmpty()) {
                AccessibilityNodeInfo bar = bars.get(0);
                if (bar.getRangeInfo() != null) {
                    int max = (int) bar.getRangeInfo().getMax();
                    if (max > 0) {
                        long ms = max <= 600 ? max * 1000L : max;
                        return Math.max(3000, Math.min(ms, 180000));
                    }
                }
            }
        }
        return getDefaultDurationFromPrefs();
    }

    private long searchDurationInNode(AccessibilityNodeInfo node) {
        if (node == null) return -1;
        CharSequence text = node.getText();
        if (text != null) {
            String s = text.toString().trim();
            if (s.matches("\\d+:\\d{2}")) {
                try {
                    String[] parts = s.split(":");
                    long ms = (Long.parseLong(parts[0]) * 60
                        + Long.parseLong(parts[1])) * 1000L;
                    if (ms >= 3000 && ms <= 180000) return ms;
                } catch (NumberFormatException ignored) {}
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            long result = searchDurationInNode(child);
            if (child != null) child.recycle();
            if (result > 0) return result;
        }
        return -1;
    }

    private void performSwipeUp() {
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        Path path = new Path();
        path.moveTo(w / 2, (int)(h * 0.75));
        path.lineTo(w / 2, (int)(h * 0.25));
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
        } catch (Exception e) { return DEFAULT_VIDEO_DURATION; }
    }

    private long getExtraDelayFromPrefs() {
        try { return Long.parseLong(PreferenceManager
            .getDefaultSharedPreferences(this)
            .getString("extraDelay", "500"));
        } catch (Exception e) { return 500L; }
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
                .bigText("Auto scroll aktif • Ikuti durasi video • Skip LIVE otomatis"))
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
