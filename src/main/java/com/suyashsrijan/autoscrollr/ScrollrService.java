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
    private Runnable pollDurationRunnable;

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

        int type = event.getEventType();

        // Window state changed = halaman baru / video baru
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            handler.postDelayed(() -> checkAndSkipLiveOrAd(), 600);
        }

        // Content changed = UI update (progress bar bergerak, dll)
        // Debounce: hanya cancel pollDurationRunnable, JANGAN cancel timerRunnable
        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            if (pollDurationRunnable != null)
                handler.removeCallbacks(pollDurationRunnable);
            pollDurationRunnable = () -> {
                checkAndSkipLiveOrAd();
                trySmartDurationCorrect();
            };
            handler.postDelayed(pollDurationRunnable, 1000);
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
        if (pollDurationRunnable != null) handler.removeCallbacks(pollDurationRunnable);
        timerRunnable = null;
        countdownRunnable = null;
        pollDurationRunnable = null;
    }

    private void resetStats() {
        videoCount = 0; likeCount = 0; followCount = 0; commentCount = 0;
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
                if (countdownRemaining > 0) handler.postDelayed(this, 1000);
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

            // Auto aksi — pakai delay bertahap agar tidak bentrok
            long delay = 0;
            if (isAutoLikeEnabled()) {
                long d = delay; handler.postDelayed(() -> doAutoLike(), d); delay += 500; }
            if (isAutoFollowEnabled()) {
                long d = delay; handler.postDelayed(() -> doAutoFollow(), d); delay += 500; }
            if (isAutoSaveEnabled()) {
                long d = delay; handler.postDelayed(() -> doAutoSave(), d); delay += 2500;
            } else if (isAutoShareEnabled()) {
                long d = delay; handler.postDelayed(() -> doAutoShare(), d); delay += 2000; }
            if (isAutoCommentEnabled()) {
                long d = delay; handler.postDelayed(() -> doAutoComment(), d); delay += 3000; }
            final long totalDelay = delay;

            // Scroll setelah semua aksi
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
            }, totalDelay + 500);
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
                String t = text.trim().toLowerCase();

                if (isSkipLiveEnabled() && (
                        t.equals("live") ||
                        t.contains("sedang live") ||
                        t.contains("is live") ||
                        t.startsWith("live ") ||
                        t.endsWith(" live"))) {
                    Log.i(TAG, "LIVE detected: [" + text + "]");
                    sendStatusBroadcast("live_skipped");
                    root.recycle();
                    cancelAll();
                    doScroll();
                    handler.postDelayed(() -> startTimer(), 1500);
                    return true;
                }

                if (isSkipAdsEnabled() && (
                        t.equals("iklan") || t.equals("ad") ||
                        t.equals("sponsored") || t.equals("berbayar") ||
                        t.equals("promoted") || t.equals("bersponsor") ||
                        t.contains("konten berbayar") ||
                        t.contains("paid partnership") ||
                        t.contains("sponsored content"))) {
                    Log.i(TAG, "Ad detected: [" + text + "]");
                    sendStatusBroadcast("ad_skipped");
                    root.recycle();
                    cancelAll();
                    doScroll();
                    handler.postDelayed(() -> startTimer(), 1500);
                    return true;
                }
            }
        } catch (Exception e) { Log.e(TAG, "checkLiveAd: " + e.getMessage()); }
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
            for (String kw : new String[]{"Sukai video", "Like video", "Suka"}) {
                AccessibilityNodeInfo node = findNodeByTextOrDesc(root, kw, null);
                if (node != null) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    likeCount++; sendStatsBroadcast();
                    Log.i(TAG, "Auto liked! total: " + likeCount); break;
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
            AccessibilityNodeInfo node = findNodeByTextOrDesc(root, "Ikuti", "Mengikuti");
            if (node != null) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                followCount++; sendStatsBroadcast();
                Log.i(TAG, "Auto followed! total: " + followCount);
            }
        } catch (Exception e) { Log.e(TAG, "autoFollow: " + e.getMessage()); }
        root.recycle();
    }

    // ===================== AUTO COMMENT =====================

    private void doAutoComment() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        try {
            for (String kw : new String[]{"Baca atau tambahkan komentar", "Tambahkan komentar", "Comment"}) {
                AccessibilityNodeInfo node = findNodeByTextOrDesc(root, kw, null);
                if (node != null) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    handler.postDelayed(() -> typeComment(), 1200);
                    Log.i(TAG, "Comment button tapped"); break;
                }
            }
        } catch (Exception e) { Log.e(TAG, "autoComment: " + e.getMessage()); }
        root.recycle();
    }

    private void typeComment() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        try {
            String[] inputIds = {
                TIKTOK_PACKAGE_ALT + ":id/comment_edit_text",
                TIKTOK_PACKAGE_ALT + ":id/et_comment",
                TIKTOK_PACKAGE_ALT + ":id/input",
                TIKTOK_PACKAGE + ":id/comment_edit_text",
                TIKTOK_PACKAGE + ":id/et_comment",
            };
            String comment = getRandomComment();
            boolean typed = false;
            for (String id : inputIds) {
                List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(id);
                if (nodes != null && !nodes.isEmpty()) {
                    AccessibilityNodeInfo input = nodes.get(0);
                    input.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                    Bundle args = new Bundle();
                    args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, comment);
                    input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                    typed = true; break;
                }
            }
            if (!typed) typed = findAndTypeInEditText(root, comment);
            if (typed) {
                handler.postDelayed(() -> tapSend(), 600);
                commentCount++; sendStatsBroadcast();
                Log.i(TAG, "Comment typed: " + comment);
            }
        } catch (Exception e) { Log.e(TAG, "typeComment: " + e.getMessage()); }
        root.recycle();
    }

    private boolean findAndTypeInEditText(AccessibilityNodeInfo node, String text) {
        if (node == null) return false;
        if ("android.widget.EditText".equals(node.getClassName() != null ? node.getClassName().toString() : "")) {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            return true;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            boolean result = findAndTypeInEditText(child, text);
            if (child != null) child.recycle();
            if (result) return true;
        }
        return false;
    }

    private void tapSend() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        try {
            for (String kw : new String[]{"Kirim", "Send", "Posting"}) {
                AccessibilityNodeInfo node = findNodeByTextOrDesc(root, kw, null);
                if (node != null) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    Log.i(TAG, "Comment sent!");
                    handler.postDelayed(() -> performGlobalAction(GLOBAL_ACTION_BACK), 500);
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
            for (String kw : new String[]{"Bagikan video", "Share video", "Bagikan"}) {
                AccessibilityNodeInfo node = findNodeByTextOrDesc(root, kw, null);
                if (node != null) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    Log.i(TAG, "Auto shared!");
                    handler.postDelayed(() -> performGlobalAction(GLOBAL_ACTION_BACK), 1500);
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
            for (String kw : new String[]{"Bagikan video", "Share video", "Bagikan"}) {
                AccessibilityNodeInfo node = findNodeByTextOrDesc(root, kw, null);
                if (node != null) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    handler.postDelayed(() -> tapSaveButton(), 1500);
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
            for (String kw : new String[]{"Simpan video", "Save video", "Unduh", "Download"}) {
                AccessibilityNodeInfo node = findNodeByTextOrDesc(root, kw, null);
                if (node != null) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    Log.i(TAG, "Auto saved!");
                    handler.postDelayed(() -> performGlobalAction(GLOBAL_ACTION_BACK), 800);
                    break;
                }
            }
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
            } else { v.vibrate(50); }
        } catch (Exception e) { Log.e(TAG, "haptic: " + e.getMessage()); }
    }

    // ===================== HELPER: CARI NODE BY TEXT ATAU CONTENT-DESC =====================

    private AccessibilityNodeInfo findNodeByTextOrDesc(
            AccessibilityNodeInfo node, String keyword, String excludeKeyword) {
        if (node == null) return null;
        String kw = keyword.toLowerCase();
        String ex = excludeKeyword != null ? excludeKeyword.toLowerCase() : null;
        String textStr = node.getText() != null ? node.getText().toString().toLowerCase() : "";
        String descStr = node.getContentDescription() != null ? node.getContentDescription().toString().toLowerCase() : "";
        boolean matches = textStr.contains(kw) || descStr.contains(kw);
        boolean excluded = ex != null && (textStr.contains(ex) || descStr.contains(ex));
        if (matches && !excluded) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            AccessibilityNodeInfo result = findNodeByTextOrDesc(child, keyword, excludeKeyword);
            if (result != null) {
                if (result != child && child != null) child.recycle();
                return result;
            }
            if (child != null) child.recycle();
        }
        return null;
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

    private boolean isWithinActiveHours() {
        if (!isNightModeEnabled()) return true;
        try {
            int start = Integer.parseInt(PreferenceManager
                .getDefaultSharedPreferences(this).getString("activeHourStart", "8"));
            int end = Integer.parseInt(PreferenceManager
                .getDefaultSharedPreferences(this).getString("activeHourEnd", "22"));
            int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
            return hour >= start && hour < end;
        } catch (Exception e) { return true; }
    }

    // ===================== SMART DURATION =====================

    /**
     * Dipanggil dari pollDurationRunnable saat UI TikTok update.
     * Baca posisi & total dari SeekBar, koreksi timer jika selisih > 3 detik.
     * TIDAK cancel timerRunnable yang sudah jalan kecuali memang perlu koreksi.
     */
    private void trySmartDurationCorrect() {
        if (!isRunning || isPaused || isScrolling) return;
        if (countdownRemaining < 3000) return; // sudah dekat scroll, jangan ganggu

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        try {
            long[] info = findRangeCurrentAndMax(root);
            if (info != null && info[1] > 2000) {
                long remaining = info[1] - info[0];
                if (remaining < 1000 || remaining > 600000) { root.recycle(); return; }
                if (Math.abs(remaining - countdownRemaining) > 3000) {
                    Log.i(TAG, "Smart duration koreksi: " + remaining + "ms");
                    if (timerRunnable != null) handler.removeCallbacks(timerRunnable);
                    if (countdownRunnable != null) handler.removeCallbacks(countdownRunnable);
                    timerRunnable = null; countdownRunnable = null;
                    countdownRemaining = remaining;
                    sendDurationBroadcast(countdownRemaining);
                    startCountdown();
                    scheduleScroll(remaining);
                }
            }
        } catch (Exception e) { Log.e(TAG, "smartDuration: " + e.getMessage()); }
        root.recycle();
    }

    private long[] findRangeCurrentAndMax(AccessibilityNodeInfo node) {
        if (node == null) return null;
        AccessibilityNodeInfo.RangeInfo range = node.getRangeInfo();
        if (range != null) {
            float max = range.getMax();
            float cur = range.getCurrent();
            if (max > 0 && max <= 600000 && cur >= 0 && cur <= max) {
                long maxMs = max > 1000 ? (long) max : (long)(max * 1000);
                long curMs = max > 1000 ? (long) cur : (long)(cur * 1000);
                if (maxMs >= 2000) return new long[]{curMs, maxMs};
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            long[] result = findRangeCurrentAndMax(child);
            if (child != null) child.recycle();
            if (result != null) return result;
        }
        return null;
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
            .getDefaultSharedPreferences(this).getString("scrollSpeed", "15000"));
        } catch (Exception e) { return 15000L; } }
    private long getSwipeSpeedFromPrefs() {
        try { return Long.parseLong(PreferenceManager
            .getDefaultSharedPreferences(this).getString("swipeSpeed", "300"));
        } catch (Exception e) { return 300L; } }
    private long getExtraDelayFromPrefs() {
        try { return Long.parseLong(PreferenceManager
            .getDefaultSharedPreferences(this).getString("extraDelay", "0"));
        } catch (Exception e) { return 0L; } }
    private boolean isSkipLiveEnabled() {
        return PreferenceManager.getDefaultSharedPreferences(this).getBoolean("skipLive", true); }
    private boolean isSkipAdsEnabled() {
        return PreferenceManager.getDefaultSharedPreferences(this).getBoolean("skipAds", true); }
    private boolean isAutoLikeEnabled() {
        return PreferenceManager.getDefaultSharedPreferences(this).getBoolean("autoLike", false); }
    private boolean isAutoFollowEnabled() {
        return PreferenceManager.getDefaultSharedPreferences(this).getBoolean("autoFollow", false); }
    private boolean isAutoCommentEnabled() {
        return PreferenceManager.getDefaultSharedPreferences(this).getBoolean("autoComment", false); }
    private boolean isAutoShareEnabled() {
        return PreferenceManager.getDefaultSharedPreferences(this).getBoolean("autoShare", false); }
    private boolean isAutoSaveEnabled() {
        return PreferenceManager.getDefaultSharedPreferences(this).getBoolean("autoSave", false); }
    private boolean isHapticEnabled() {
        return PreferenceManager.getDefaultSharedPreferences(this).getBoolean("hapticFeedback", false); }
    private boolean isNightModeEnabled() {
        return PreferenceManager.getDefaultSharedPreferences(this).getBoolean("nightMode", false); }
    private boolean isLimitVideosEnabled() {
        return PreferenceManager.getDefaultSharedPreferences(this).getBoolean("limitVideos", false); }
    private int getMaxVideos() {
        try { return Integer.parseInt(PreferenceManager
            .getDefaultSharedPreferences(this).getString("maxVideos", "50"));
        } catch (Exception e) { return 50; } }
    private String getBlacklistWords() {
        return PreferenceManager.getDefaultSharedPreferences(this).getString("blacklistWords", ""); }
    private String getWhitelistWords() {
        return PreferenceManager.getDefaultSharedPreferences(this).getString("whitelistWords", ""); }

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
        i.putExtra("status", status); sendBroadcast(i);
    }
    private void sendDurationBroadcast(long ms) {
        Intent i = new Intent("com.suyashsrijan.autoscrollr.STATUS_UPDATE");
        i.putExtra("status", "duration");
        i.putExtra("duration_ms", ms); sendBroadcast(i);
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
