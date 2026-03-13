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
    private static final long CHECK_INTERVAL = 800;

    public static ScrollrService instance;

    private Handler handler = new Handler(Looper.getMainLooper());
    private Runnable checkRunnable;
    private Runnable timerRunnable;
    private boolean isRunning = false;
    private boolean isPaused = false;
    private boolean isScrolling = false;
    private long lastScrollTime = 0;
    private long currentTimerDuration = -1;

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

        // Video baru dibuka → reset timer
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (!isScrolling) {
                handler.postDelayed(() -> resetTimerForNewVideo(), 600);
            }
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

    // ===================== KONTROL =====================

    public void startAutoScroll() {
        if (isRunning) return;
        isRunning = true;
        isPaused = false;
        Log.i(TAG, "Starting");
        sendStatusBroadcast("started");
        resetTimerForNewVideo();
    }

    public void stopAutoScroll() {
        isRunning = false;
        isPaused = false;
        cancelAllCallbacks();
        Log.i(TAG, "Stopped");
        sendStatusBroadcast("stopped");
    }

    public void pauseAutoScroll() {
        isPaused = true;
        cancelAllCallbacks();
        sendStatusBroadcast("paused");
    }

    public void resumeAutoScroll() {
        if (!isRunning) return;
        isPaused = false;
        sendStatusBroadcast("started");
        resetTimerForNewVideo();
    }

    public boolean isRunning() { return isRunning; }
    public boolean isPaused()  { return isPaused; }

    private void cancelAllCallbacks() {
        if (checkRunnable != null) handler.removeCallbacks(checkRunnable);
        if (timerRunnable != null) handler.removeCallbacks(timerRunnable);
        checkRunnable = null;
        timerRunnable = null;
    }

    // ===================== INTI UTAMA =====================

    private void resetTimerForNewVideo() {
        if (!isRunning || isPaused) return;
        cancelAllCallbacks();

        // Cek LIVE dulu
        if (isSkipLiveEnabled() && isStrictlyLive()) {
            Log.i(TAG, "LIVE badge detected - skipping");
            sendStatusBroadcast("live_skipped");
            doScroll();
            return;
        }

        // Coba baca timestamp dari layar
        long[] times = readTimestamp();

        if (times != null && times[1] > 0) {
            // Berhasil baca timestamp!
            long currentSec = times[0];
            long totalSec = times[1];
            long remainingMs = (totalSec - currentSec) * 1000L;

            // Minimal 1 detik, maksimal 3 menit
            remainingMs = Math.max(1000, Math.min(remainingMs, 180000));

            Log.i(TAG, "Timestamp: " + currentSec + "s / " + totalSec
                + "s → scroll in " + remainingMs + "ms");
            sendDurationBroadcast(totalSec * 1000L);

            scheduleScrollAfter(remainingMs);

        } else {
            // Tidak bisa baca timestamp → pakai timer default
            long defaultMs = getDefaultDurationFromPrefs();
            Log.i(TAG, "No timestamp → timer " + defaultMs + "ms");
            sendDurationBroadcast(defaultMs);
            scheduleScrollAfter(defaultMs);

            // Sambil nunggu, terus coba deteksi timestamp setiap CHECK_INTERVAL
            startTimestampChecker();
        }
    }

    private void scheduleScrollAfter(long delayMs) {
        if (timerRunnable != null) handler.removeCallbacks(timerRunnable);
        timerRunnable = () -> {
            if (!isRunning || isPaused || isScrolling) return;
            if (System.currentTimeMillis() - lastScrollTime < 1500) return;

            Log.i(TAG, "Timer done → scrolling!");
            isScrolling = true;
            lastScrollTime = System.currentTimeMillis();
            sendStatusBroadcast("scrolled");

            long extra = getExtraDelayFromPrefs();
            handler.postDelayed(() -> {
                doScroll();
                isScrolling = false;
                // Tunggu video baru load lalu reset timer
                handler.postDelayed(() -> resetTimerForNewVideo(), 1000);
            }, extra);
        };
        handler.postDelayed(timerRunnable, delayMs);
    }

    // Cek timestamp setiap interval — kalau ketemu, cancel timer lama & jadwalkan ulang
    private void startTimestampChecker() {
        if (checkRunnable != null) handler.removeCallbacks(checkRunnable);
        checkRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isRunning || isPaused || isScrolling) return;

                long[] times = readTimestamp();
                if (times != null && times[1] > 0) {
                    long remainingMs = (times[1] - times[0]) * 1000L;
                    remainingMs = Math.max(1000, Math.min(remainingMs, 180000));
                    Log.i(TAG, "Timestamp found by checker: " + times[0]
                        + "s / " + times[1] + "s");
                    sendDurationBroadcast(times[1] * 1000L);

                    // Cancel timer lama, jadwalkan dengan sisa durasi
                    if (timerRunnable != null) handler.removeCallbacks(timerRunnable);
                    scheduleScrollAfter(remainingMs);
                    // Stop checker
                    checkRunnable = null;
                    return;
                }

                handler.postDelayed(this, CHECK_INTERVAL);
            }
        };
        handler.postDelayed(checkRunnable, CHECK_INTERVAL);
    }

    // ===================== BACA TIMESTAMP =====================
    // Baca teks "0:23 / 1:00" atau "23 / 60" dari layar

    private long[] readTimestamp() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;

        List<String> texts = new ArrayList<>();
        collectTexts(root, texts);
        root.recycle();

        for (String text : texts) {
            text = text.trim();

            // Format: "0:23 / 1:00" atau "0:23/1:00"
            if (text.contains("/")) {
                String[] parts = text.split("/");
                if (parts.length == 2) {
                    long cur = parseTime(parts[0].trim());
                    long tot = parseTime(parts[1].trim());
                    if (cur >= 0 && tot > 0 && tot <= 600) {
                        Log.d(TAG, "Timestamp: " + text);
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
                return Long.parseLong(p[0].trim()) * 60 + Long.parseLong(p[1].trim());
            }
            return Long.parseLong(s);
        } catch (Exception e) { return -1; }
    }

    // ===================== LIVE CHECK (STRICT) =====================
    // Hanya skip kalau ada badge LIVE merah — bukan profil live!

    private boolean isStrictlyLive() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        try {
            // Cari teks LIVE yang pendek (badge) bukan deskripsi panjang
            List<AccessibilityNodeInfo> nodes =
                root.findAccessibilityNodeInfosByText("LIVE");
            if (nodes != null) {
                for (AccessibilityNodeInfo node : nodes) {
                    CharSequence text = node.getText();
                    CharSequence desc = node.getContentDescription();
                    // Badge LIVE biasanya teks pendek persis "LIVE"
                    // Bukan "Go LIVE", "Watch LIVE", "is live" dll
                    if (text != null && text.toString().trim().equals("LIVE")) {
                        // Pastikan ini bukan tombol/profil
                        // Badge LIVE tidak bisa diklik seperti profil
                        String className = node.getClassName() != null
                            ? node.getClassName().toString() : "";
                        if (!className.contains("Button")
                            && !className.contains("Image")) {
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

    // ===================== SCROLL =====================

    private void doScroll() {
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
        Log.i(TAG, "Swipe performed");
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
                .bigText("Auto scroll aktif • Skip LIVE otomatis"))
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
