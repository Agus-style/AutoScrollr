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
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.preference.PreferenceManager;
import android.support.v4.app.NotificationCompat;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Random;

public class ScrollrService extends AccessibilityService {

    private static final String TAG = "AutoScrollr-Service";
    private static final String CHANNEL_ID = "autoscrollr_channel";
    private static final String TIKTOK_PACKAGE = "com.zhiliaoapp.musically";
    private static final String TIKTOK_PACKAGE_ALT = "com.ss.android.ugc.trill";

    public static ScrollrService instance;

    private Handler handler = new Handler(Looper.getMainLooper());
    private Runnable timerRunnable;
    private Runnable countdownRunnable;
    private boolean isRunning = false;
    private boolean isPaused = false;
    private boolean isScrolling = false;
    private long lastScrollTime = 0;
    private long countdownRemaining = 0;
    private int videoCount = 0;
    private int likeCount = 0;
    private int followCount = 0;
    private int commentCount = 0;
    private Random random = new Random();

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
                case "reset_stats": resetStats(); break;
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
            handler.postDelayed(() -> checkAndSkipLiveOrAd(), 500);
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
        if (!isWithinActiveHours()) {
            sendStatusBroadcast("outside_hours");
            return;
        }
        isRunning = true;
        isPaused = false;
        videoCount = 0;
        Log.i(TAG, "Starting");
        sendStatusBroadcast("started");
        startTimer();
    }

    public void stopAutoScroll() {
        isRunning = false;
        isPaused = false;
        cancelAll();
        Log.i(TAG, "Stopped");
        sendStatusBroadcast("stopped");
    }

    public void pauseAutoScroll() {
        isPaused = true;
        cancelAll();
        sendStatusBroadcast("paused");
    }

    public void resumeAutoScroll() {
        if (!isRunning) return;
        isPaused = false;
        sendStatusBroadcast("started");
        startTimer();
    }

    public boolean isRunning() { return isRunning; }
    public boolean isPaused()  { return isPaused; }

    private void cancelAll() {
        if (timerRunnable != null) handler.removeCallbacks(timerRunnable);
        if (countdownRunnable != null) handler.removeCallbacks(countdownRunnable);
        timerRunnable = null;
        countdownRunnable = null;
    }

    private void resetStats() {
        videoCount = 0;
        likeCount = 0;
        followCount = 0;
        commentCount = 0;
        sendStatsBroadcast();
    }

    // ===================== TIMER =====================

    private void startTimer() {
        cancelAll();

        if (isLimitVideosEnabled()) {
            int maxVideos = getMaxVideos();
            if (maxVideos > 0 && videoCount >= maxVideos) {
                sendStatusBroadcast("limit_reached");
                stopAutoScroll();
                return;
            }
        }

        if (!isWithinActiveHours()) {
            sendStatusBroadcast("outside_hours");
            stopAutoScroll();
            return;
        }

        long duration = getScrollSpeedFromPrefs();
        countdownRemaining = duration;
        sendDurationBroadcast(countdownRemaining);
        startCountdown();
        scheduleScroll(duration);
    }

    private void startCountdown() {
        countdownRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isRunning || isPaused) return;
                countdownRemaining -= 1000;
                if (countdownRemaining < 0) countdownRemaining = 0;
                sendDurationBroadcast(countdownRemaining);
                if (countdownRemaining > 0) {
                    handler.postDelayed(this, 1000);
                }
            }
        };
        handler.postDelayed(countdownRunnable, 1000);
    }

    private void scheduleScroll(long delayMs) {
        timerRunnable = () -> {
            if (!isRunning || isPaused || isScrolling) return;
            if (System.currentTimeMillis() - lastScrollTime < 1500) return;

            // Cek whitelist
            if (!getWhitelistWords().isEmpty() && !isWhitelisted()) {
                sendStatusBroadcast("not_whitelisted");
                doScroll();
                handler.postDelayed(() -> startTimer(), 1000);
                return;
            }

            // Cek blacklist
            if (!getBlacklistWords().isEmpty() && isBlacklisted()) {
                sendStatusBroadcast("blacklisted");
                doScroll();
                handler.postDelayed(() -> startTimer(), 1000);
                return;
            }

            // Haptic
            if (isHapticEnabled()) doHaptic();

            // Hitung video
            videoCount++;
            sendStatsBroadcast();

            // Auto aksi
            if (isAutoLikeEnabled()) doAutoLike();
            if (isAutoFollowEnabled()) doAutoFollow();
            if (isAutoShareEnabled()) doAutoShare();
            if (isAutoSaveEnabled()) doAutoSave();
            if (isAutoCommentEnabled()) handler.postDelayed(() -> doAutoComment(), 400);

            // Scroll setelah aksi selesai
            handler.postDelayed(() -> {
                if (!isRunning || isPaused) return;
                isScrolling = true;
                lastScrollTime = System.currentTimeMillis();
                sendStatusBroadcast("scrolled");
                handler.postDelayed(() -> {
                    doScroll();
                    isScrolling = false;
                    handler.postDelayed(() -> startTimer(), 1000);
                }, getExtraDelayFromPrefs());
            }, getActionDelay());
        };
        handler.postDelayed(timerRunnable, delayMs);
    }

    // ===================== LIVE & IKLAN =====================

    private boolean checkAndSkipLiveOrAd() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        try {
            List<String> texts = new ArrayList<>();
            collectTexts(root, texts);
            for (String text : texts) {
                String t = text.trim();
                if (isSkipLiveEnabled() && t.equals("LIVE")) {
                    Log.i(TAG, "LIVE - skipping");
                    sendStatusBroadcast("live_skipped");
                    root.recycle();
                    cancelAll();
                    doScroll();
                    handler.postDelayed(() -> startTimer(), 1500);
                    return true;
                }
                if (isSkipAdsEnabled() && (
                    t.equals("Sponsored") || t.equals("Ad") ||
                    t.equals("Iklan") || t.equals("Berbayar") ||
                    t.equals("Promoted") || t.equals("Bersponsor"))) {
                    Log.i(TAG, "Ad - skipping");
                    sendStatusBroadcast("ad_skipped");
                    root.recycle();
                    cancelAll();
                    doScroll();
                    handler.postDelayed(() -> startTimer(), 1500);
                    return true;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "checkLiveAd: " + e.getMessage());
        }
        root.recycle();
        return false;
    }

    // ===================== BLACKLIST & WHITELIST =====================

    private boolean isBlacklisted() {
        String[] words = getBlacklistWords().split(",");
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        try {
            List<String> texts = new ArrayList<>();
            collectTexts(root, texts);
            String all = texts.toString().toLowerCase();
            for (String word : words) {
                String w = word.trim().toLowerCase();
                if (!w.isEmpty() && all.contains(w)) { root.recycle(); return true; }
            }
        } catch (Exception e) { Log.e(TAG, "blacklist: " + e.getMessage()); }
        root.recycle();
        return false;
    }

    private boolean isWhitelisted() {
        String[] words = getWhitelistWords().split(",");
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        try {
            List<String> texts = new ArrayList<>();
            collectTexts(root, texts);
            String all = texts.toString().toLowerCase();
            for (String word : words) {
                String w = word.trim().toLowerCase();
                if (!w.isEmpty() && all.contains(w)) { root.recycle(); return true; }
            }
        } catch (Exception e) { Log.e(TAG, "whitelist: " + e.getMessage()); }
        root.recycle();
        return false;
    }

    // ===================== AUTO LIKE =====================

    private void doAutoLike() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        try {
            String[] ids = {
                TIKTOK_PACKAGE_ALT + ":id/like_btn",
                TIKTOK_PACKAGE_ALT + ":id/iv_digg",
                TIKTOK_PACKAGE_ALT + ":id/btn_like",
                TIKTOK_PACKAGE + ":id/like_btn",
                TIKTOK_PACKAGE + ":id/iv_digg",
            };
            for (String id : ids) {
                List<AccessibilityNodeInfo> nodes =
                    root.findAccessibilityNodeInfosByViewId(id);
                if (nodes != null && !nodes.isEmpty()) {
                    nodes.get(0).performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    likeCount++;
                    sendStatsBroadcast();
                    break;
                }
            }
        } catch (Exception e) { Log.e(TAG, "autoLike: " + e.getMessage()); }
        root.recycle();
    }

    // ===================== AUTO FOLLOW =====================

    private void doAutoFollow() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        try {
            String[] ids = {
                TIKTOK_PACKAGE_ALT + ":id/follow_btn",
                TIKTOK_PACKAGE_ALT + ":id/btn_follow",
                TIKTOK_PACKAGE + ":id/follow_btn",
                TIKTOK_PACKAGE + ":id/btn_follow",
            };
            for (String id : ids) {
                List<AccessibilityNodeInfo> nodes =
                    root.findAccessibilityNodeInfosByViewId(id);
                if (nodes != null && !nodes.isEmpty()) {
                    AccessibilityNodeInfo node = nodes.get(0);
                    CharSequence desc = node.getContentDescription();
                    if (desc != null && !desc.toString().toLowerCase().contains("following")) {
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                        followCount++;
                        sendStatsBroadcast();
                        break;
                    }
                }
            }
        } catch (Exception e) { Log.e(TAG, "autoFollow: " + e.getMessage()); }
        root.recycle();
    }

    // ===================== AUTO COMMENT =====================

    private void doAutoComment() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        try {
            String[] ids = {
                TIKTOK_PACKAGE_ALT + ":id/comment_btn",
                TIKTOK_PACKAGE_ALT + ":id/btn_comment",
                TIKTOK_PACKAGE_ALT + ":id/iv_comment",
                TIKTOK_PACKAGE + ":id/comment_btn",
                TIKTOK_PACKAGE + ":id/btn_comment",
            };
            for (String id : ids) {
                List<AccessibilityNodeInfo> nodes =
                    root.findAccessibilityNodeInfosByViewId(id);
                if (nodes != null && !nodes.isEmpty()) {
                    nodes.get(0).performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    handler.postDelayed(() -> typeComment(), 1000);
                    break;
                }
            }
        } catch (Exception e) { Log.e(TAG, "autoComment: " + e.getMessage()); }
        root.recycle();
    }

    private void typeComment() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        try {
            String[] ids = {
                TIKTOK_PACKAGE_ALT + ":id/comment_edit_text",
                TIKTOK_PACKAGE_ALT + ":id/et_comment",
                TIKTOK_PACKAGE + ":id/comment_edit_text",
                TIKTOK_PACKAGE + ":id/et_comment",
            };
            String comment = getRandomComment();
            for (String id : ids) {
                List<AccessibilityNodeInfo> nodes =
                    root.findAccessibilityNodeInfosByViewId(id);
                if (nodes != null && !nodes.isEmpty()) {
                    AccessibilityNodeInfo input = nodes.get(0);
                    input.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                    Bundle args = new Bundle();
                    args.putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        comment);
                    input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                    handler.postDelayed(() -> tapSend(), 500);
                    commentCount++;
                    sendStatsBroadcast();
                    break;
                }
            }
        } catch (Exception e) { Log.e(TAG, "typeComment: " + e.getMessage()); }
        root.recycle();
    }

    private void tapSend() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        try {
            String[] ids = {
                TIKTOK_PACKAGE_ALT + ":id/btn_send",
                TIKTOK_PACKAGE_ALT + ":id/send_btn",
                TIKTOK_PACKAGE + ":id/btn_send",
                TIKTOK_PACKAGE + ":id/send_btn",
            };
            for (String id : ids) {
                List<AccessibilityNodeInfo> nodes =
                    root.findAccessibilityNodeInfosByViewId(id);
                if (nodes != null && !nodes.isEmpty()) {
                    nodes.get(0).performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    break;
                }
            }
        } catch (Exception e) { Log.e(TAG, "tapSend: " + e.getMessage()); }
        root.recycle();
    }

    // ===================== AUTO SHARE =====================

    private void doAutoShare() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        try {
            String[] ids = {
                TIKTOK_PACKAGE_ALT + ":id/share_btn",
                TIKTOK_PACKAGE_ALT + ":id/btn_share",
                TIKTOK_PACKAGE_ALT + ":id/iv_share",
                TIKTOK_PACKAGE + ":id/share_btn",
                TIKTOK_PACKAGE + ":id/btn_share",
            };
            for (String id : ids) {
                List<AccessibilityNodeInfo> nodes =
                    root.findAccessibilityNodeInfosByViewId(id);
                if (nodes != null && !nodes.isEmpty()) {
                    nodes.get(0).performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    handler.postDelayed(() -> performGlobalAction(GLOBAL_ACTION_BACK), 1000);
                    break;
                }
            }
        } catch (Exception e) { Log.e(TAG, "autoShare: " + e.getMessage()); }
        root.recycle();
    }

    // ===================== AUTO SAVE =====================

    private void doAutoSave() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        try {
            String[] ids = {
                TIKTOK_PACKAGE_ALT + ":id/share_btn",
                TIKTOK_PACKAGE_ALT + ":id/btn_share",
                TIKTOK_PACKAGE + ":id/share_btn",
            };
            for (String id : ids) {
                List<AccessibilityNodeInfo> nodes =
                    root.findAccessibilityNodeInfosByViewId(id);
                if (nodes != null && !nodes.isEmpty()) {
                    nodes.get(0).performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    handler.postDelayed(() -> tapSaveButton(), 1000);
                    break;
                }
            }
        } catch (Exception e) { Log.e(TAG, "autoSave: " + e.getMessage()); }
        root.recycle();
    }

    private void tapSaveButton() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        try {
            List<AccessibilityNodeInfo> nodes =
                root.findAccessibilityNodeInfosByText("Save video");
            if (nodes == null || nodes.isEmpty())
                nodes = root.findAccessibilityNodeInfosByText("Simpan video");
            if (nodes != null && !nodes.isEmpty())
                nodes.get(0).performAction(AccessibilityNodeInfo.ACTION_CLICK);
            handler.postDelayed(() -> performGlobalAction(GLOBAL_ACTION_BACK), 500);
        } catch (Exception e) { Log.e(TAG, "tapSave: " + e.getMessage()); }
        root.recycle();
    }

    // ===================== HAPTIC =====================

    private void doHaptic() {
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                v.vibrate(50);
            }
        } catch (Exception e) { Log.e(TAG, "haptic: " + e.getMessage()); }
    }

    // ===================== HELPER =====================

    private void collectTexts(AccessibilityNodeInfo node, List<String> out) {
        if (node == null) return;
        CharSequence t = node.getText();
        if (t != null && t.length() > 0) out.add(t.toString());
        CharSequence d = node.getContentDescription();
        if (d != null && d.length() > 0) out.add(d.toString());
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            collectTexts(child, out);
            if (child != null) child.recycle();
        }
    }

    private String getRandomComment() {
        String pref = PreferenceManager.getDefaultSharedPreferences(this)
            .getString("commentList", "Keren,Bagus,Mantap,Lucu,🔥");
        String[] comments = pref.split(",");
        if (comments.length == 0) return "Keren";
        return comments[random.nextInt(comments.length)].trim();
    }

    private long getActionDelay() {
        long d = 0;
        if (isAutoLikeEnabled()) d += 300;
        if (isAutoFollowEnabled()) d += 300;
        if (isAutoShareEnabled()) d += 1500;
        if (isAutoSaveEnabled()) d += 2000;
        if (isAutoCommentEnabled()) d += 2500;
        return d;
    }

    private boolean isWithinActiveHours() {
        if (!isNightModeEnabled()) return true;
        try {
            int start = Integer.parseInt(PreferenceManager
                .getDefaultSharedPreferences(this)
                .getString("activeHourStart", "8"));
            int end = Integer.parseInt(PreferenceManager
                .getDefaultSharedPreferences(this)
                .getString("activeHourEnd", "22"));
            int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
            return hour >= start && hour < end;
        } catch (Exception e) { return true; }
    }

    // ===================== SWIPE =====================

    private void doScroll() {
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        Path path = new Path();
        path.moveTo(w / 2f, h * 0.75f);
        path.lineTo(w / 2f, h * 0.25f);
        long swipeMs = getSwipeSpeedFromPrefs();
        GestureDescription gesture = new GestureDescription.Builder()
            .addStroke(new GestureDescription.StrokeDescription(path, 0, swipeMs))
            .build();
        dispatchGesture(gesture, null, null);
    }

    // ===================== PREFERENCES =====================

    private long getScrollSpeedFromPrefs() {
        try { return Long.parseLong(PreferenceManager
            .getDefaultSharedPreferences(this)
            .getString("scrollSpeed", "15000"));
        } catch (Exception e) { return 15000L; }
    }

    private long getSwipeSpeedFromPrefs() {
        try { return Long.parseLong(PreferenceManager
            .getDefaultSharedPreferences(this)
            .getString("swipeSpeed", "300"));
        } catch (Exception e) { return 300L; }
    }

    private long getExtraDelayFromPrefs() {
        try { return Long.parseLong(PreferenceManager
            .getDefaultSharedPreferences(this)
            .getString("extraDelay", "0"));
        } catch (Exception e) { return 0L; }
    }

    private boolean isSkipLiveEnabled() {
        return PreferenceManager.getDefaultSharedPreferences(this).getBoolean("skipLive", true);
    }
    private boolean isSkipAdsEnabled() {
        return PreferenceManager.getDefaultSharedPreferences(this).getBoolean("skipAds", true);
    }
    private boolean isAutoLikeEnabled() {
        return PreferenceManager.getDefaultSharedPreferences(this).getBoolean("autoLike", false);
    }
    private boolean isAutoFollowEnabled() {
        return PreferenceManager.getDefaultSharedPreferences(this).getBoolean("autoFollow", false);
    }
    private boolean isAutoCommentEnabled() {
        return PreferenceManager.getDefaultSharedPreferences(this).getBoolean("autoComment", false);
    }
    private boolean isAutoShareEnabled() {
        return PreferenceManager.getDefaultSharedPreferences(this).getBoolean("autoShare", false);
    }
    private boolean isAutoSaveEnabled() {
        return PreferenceManager.getDefaultSharedPreferences(this).getBoolean("autoSave", false);
    }
    private boolean isHapticEnabled() {
        return PreferenceManager.getDefaultSharedPreferences(this).getBoolean("hapticFeedback", false);
    }
    private boolean isNightModeEnabled() {
        return PreferenceManager.getDefaultSharedPreferences(this).getBoolean("nightMode", false);
    }
    private boolean isLimitVideosEnabled() {
        return PreferenceManager.getDefaultSharedPreferences(this).getBoolean("limitVideos", false);
    }
    private int getMaxVideos() {
        try { return Integer.parseInt(PreferenceManager
            .getDefaultSharedPreferences(this).getString("maxVideos", "50"));
        } catch (Exception e) { return 50; }
    }
    private String getBlacklistWords() {
        return PreferenceManager.getDefaultSharedPreferences(this).getString("blacklistWords", "");
    }
    private String getWhitelistWords() {
        return PreferenceManager.getDefaultSharedPreferences(this).getString("whitelistWords", "");
    }

    // ===================== NOTIFICATION =====================

    public void showNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "TikTok AutoScrollr", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
        Intent notifIntent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, notifIntent,
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TikTok AutoScrollr")
            .setStyle(new NotificationCompat.BigTextStyle()
                .bigText("Auto scroll • Skip LIVE/Iklan • Like/Follow/Comment/Share/Save"))
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

    private void sendStatsBroadcast() {
        Intent i = new Intent("com.suyashsrijan.autoscrollr.STATUS_UPDATE");
        i.putExtra("status", "stats");
        i.putExtra("video_count", videoCount);
        i.putExtra("like_count", likeCount);
        i.putExtra("follow_count", followCount);
        i.putExtra("comment_count", commentCount);
        sendBroadcast(i);
    }
}
