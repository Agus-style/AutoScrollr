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
    private static final long SCROLL_ANIMATION_DURATION = 400;
    private static final long CHECK_INTERVAL = 1000;

    public static ScrollrService instance;

    private Handler handler = new Handler(Looper.getMainLooper());
    private Runnable checkProgressRunnable;
    private boolean isRunning = false;
    private boolean isPaused = false;
    private boolean isScrolling = false;
    private long lastScrollTime = 0;
    private long scheduledDuration = -1;

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
            // Video baru dibuka - reset timer
            if (!isScrolling) {
                handler.postDelayed(() -> {
                    scheduleScrollFromTimestamp();
                }, 800);
            }
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
        Log.i(TAG, "Starting");
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

    // ===================== MONITOR =====================

    private void startProgressMonitor() {
        stopProgressMonitor();
        checkProgressRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isRunning || isPaused) return;

                // Cek LIVE
                if (isSkipLiveEnabled() && isCurrentlyLive()) {
                    Log.i(TAG, "LIVE - skipping");
                    sendStatusBroadcast("live_skipped");
                    doSwipeUp();
                    handler.postDelayed(checkProgressRunnable, 2000);
                    return;
                }

                // Coba baca timestamp dari layar
                long[] times = readTimestampFromScreen();
                // times[0] = current detik, times[1] = total detik

                if (times != null && times[1] > 0) {
                    long currentSec = times[0];
                    long totalSec = times[1];
                    long remainingSec = totalSec - currentSec;
                    long remainingMs = remainingSec * 1000L;

                    Log.d(TAG, "Timestamp: " + currentSec + "s / " + totalSec
                        + "s - remaining: " + remainingSec + "s");

                    sendDurationBroadcast(totalSec * 1000L);

                    // Kalau sisa <= 1 detik → scroll!
                    if (remainingSec <= 1 && !isScrolling &&
                        System.currentTimeMillis() - lastScrollTime > 2000) {
                        Log.i(TAG, "Video hampir selesai! Scrolling...");
                        isScrolling = true;
                        lastScrollTime = System.currentTimeMillis();
                        sendStatusBroadcast("scrolled");

                        handler.postDelayed(() -> {
                            doSwipeUp();
                            isScrolling = false;
                            // Tunggu video baru load
                            handler.postDelayed(checkProgressRunnable, 1500);
                        }, getExtraDelayFromPrefs());
                        return;
                    }

                    // Jadwalkan check lebih cepat kalau hampir selesai
                    long nextCheck = remainingSec <= 3 ? 300 : CHECK_INTERVAL;
                    handler.postDelayed(checkProgressRunnable, nextCheck);

                } else {
                    // Timestamp tidak terdeteksi
                    // Coba progress bar biasa
                    float[] progress = getVideoProgressFromSeekBar();
                    if (progress != null && progress[1] > 0) {
                        float percent = (progress[0] / progress[1]) * 100f;
                        Log.d(TAG, "SeekBar progress: " + String.format("%.1f", percent) + "%");
                        sendDurationBroadcast(progress[1] <= 600
                            ? (long)(progress[1] * 1000) : (long) progress[1]);

                        if (percent >= 99f && !isScrolling &&
                            System.currentTimeMillis() - lastScrollTime > 2000) {
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
                    } else {
                        // Fallback durasi default
                        Log.w(TAG, "No progress detected - fallback");
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

    private void scheduleScrollFromTimestamp() {
        long[] times = readTimestampFromScreen();
        if (times != null && times[1] > 0) {
            long remainingMs = (times[1] - times[0]) * 1000L;
            Log.i(TAG, "Schedule scroll in: " + remainingMs + "ms");
            sendDurationBroadcast(times[1] * 1000L);
        }
    }

    // ===================== BACA TIMESTAMP TEKS =====================
    // Baca teks seperti "0:23 / 1:00" atau "23 / 60" dari layar TikTok

    private long[] readTimestampFromScreen() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;

        List<String> allTexts = new ArrayList<>();
        collectAllTexts(root, allTexts);
        root.recycle();

        for (String text : allTexts) {
            // Format: "0:23 / 1:00"
            if (text.contains("/")) {
                String[] parts = text.split("/");
                if (parts.length == 2) {
                    long current = parseTimeToSeconds(parts[0].trim());
                    long total = parseTimeToSeconds(parts[1].trim());
                    if (current >= 0 && total > 0) {
                        Log.d(TAG, "Timestamp found: " + text
                            + " -> " + current + "s / " + total + "s");
                        return new long[]{current, total};
                    }
                }
            }
        }
        return null;
    }

    private void collectAllTexts(AccessibilityNodeInfo node, List<String> texts) {
        if (node == null) return;
        CharSequence text = node.getText();
        if (text != null && text.length() > 0) {
            texts.add(text.toString());
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            collectAllTexts(child, texts);
            if (child != null) child.recycle();
        }
    }

    // Parse "1:23" atau "23" jadi detik
    private long parseTimeToSeconds(String s) {
        s = s.trim();
        try {
            if (s.contains(":")) {
                String[] parts = s.split(":");
                if (parts.length == 2) {
                    return Long.parseLong(parts[0].trim()) * 60
                        + Long.parseLong(parts[1].trim());
                }
            } else {
                return Long.parseLong(s);
            }
        } catch (NumberFormatException ignored) {}
        return -1;
    }

    // ===================== SEEKBAR FALLBACK =====================

    private float[] getVideoProgressFromSeekBar() {
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
                TIKTOK_PACKAGE + ":id/progress_bar",
                TIKTOK_PACKAGE + ":id/video_progress",
                TIKTOK_PACKAGE + ":id/seek_bar",
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
                                root.recycle();
                                return new float[]{current, max};
                            }
                        }
                    }
                }
            }
            // Scan semua node
            float[] result = scanAllNodesForProgress(root);
            if (result != null) {
                root.recycle();
                return result;
            }
        } catch (Exception e) {
            Log.e(TAG, "seekbar error: " + e.getMessage());
        }
        root.recycle();
        return null;
    }

    private float[] scanAllNodesForProgress(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.getRangeInfo() != null) {
            float current = node.getRangeInfo().getCurrent();
            float max = node.getRangeInfo().getMax();
            if (max > 0 && current >= 0) {
                Log.d(TAG, "RangeInfo found: id="
                    + node.getViewIdResourceName()
                    + " current=" + current + " max=" + max);
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

    // ===================== LIVE CHECK =====================

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
                .bigText("Memantau progress video • Skip LIVE otomatis"))
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
