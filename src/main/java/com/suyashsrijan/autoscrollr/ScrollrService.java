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
    private long videoDurationMs = 0;   // durasi total video saat ini (ms)
    private long videoStartTime = 0;    // System.currentTimeMillis() saat video mulai

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

        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // Video baru / halaman baru — cek live/iklan
            handler.postDelayed(() -> checkAndSkipLiveOrAd(), 600);
        }

        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            // Debounce: cancel hanya pollDurationRunnable, JANGAN cancel timerRunnable
            if (pollDurationRunnable != null)
                handler.removeCallbacks(pollDurationRunnable);

            pollDurationRunnable = () -> {
                checkAndSkipLiveOrAd();
                if (isSmartDurationEnabled()) tryDetectAndReschedule();
            };
            handler.postDelayed(pollDurationRunnable, 1000);
        }
    }
    }

    /**
     * Dipanggil saat konten UI TikTok berubah.
     * Coba baca RangeInfo SeekBar untuk dapat posisi & total durasi,
     * lalu reschedule scroll tepat saat video habis.
     */
    private void tryDetectAndReschedule() {
        if (!isRunning || isPaused || isScrolling) return;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        try {
            long[] info = findRangeCurrentAndMax(root);
            if (info != null && info[1] > 2000) {
                long remaining = info[1] - info[0];
                if (remaining < 800) remaining = 800;
                if (remaining > 600000) { root.recycle(); return; }

                // Jika timer belum jalan (timerRunnable null) → schedule baru
                if (timerRunnable == null) {
                    Log.i(TAG, "Smart duration (fresh): " + remaining + "ms");
                    countdownRemaining = remaining;
                    sendDurationBroadcast(countdownRemaining);
                    startCountdown();
                    scheduleScroll(remaining);
                }
                // Jika timer sudah jalan tapi selisih > 3 detik → koreksi
                else if (Math.abs(remaining - countdownRemaining) > 3000) {
                    Log.i(TAG, "Smart duration (correct): " + remaining + "ms");
                    // Cancel countdown lama, start yang baru — tapi JANGAN cancel timerRunnable
                    if (countdownRunnable != null) handler.removeCallbacks(countdownRunnable);
                    countdownRunnable = null;
                    countdownRemaining = remaining;
                    sendDurationBroadcast(countdownRemaining);
                    startCountdown();
                    // Reschedule timerRunnable juga
                    if (timerRunnable != null) handler.removeCallbacks(timerRunnable);
                    timerRunnable = null;
                    scheduleScroll(remaining);
                }
            }
        } catch (Exception e) { Log.e(TAG, "tryDetect: " + e.getMessage()); }
        root.recycle();
    }

    /**
     * Traverse node tree, cari SeekBar/ProgressBar dengan RangeInfo valid.
     * Return [current_ms, max_ms] atau null jika tidak ketemu.
     */
    private long[] findRangeCurrentAndMax(AccessibilityNodeInfo node) {
        if (node == null) return null;
        AccessibilityNodeInfo.RangeInfo range = node.getRangeInfo();
        if (range != null) {
            float max = range.getMax();
            float cur = range.getCurrent();
            if (max > 0 && max <= 600000 && cur >= 0 && cur <= max) {
                long maxMs, curMs;
                if (max > 1000) {
                    // Sudah dalam ms
                    maxMs = (long) max;
                    curMs = (long) cur;
                } else {
                    // Dalam detik
                    maxMs = (long)(max * 1000);
                    curMs = (long)(cur * 1000);
                }
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

        // Coba deteksi durasi video dari UI dulu
        if (isSmartDurationEnabled()) {
            long detected = detectVideoDurationMs();
            if (detected > 0) {
                Log.i(TAG, "Smart duration detected: " + detected + "ms");
                countdownRemaining = detected;
                sendDurationBroadcast(countdownRemaining);
                startCountdown();
                scheduleScroll(detected);
                return;
            }
        }

        // Fallback: pakai durasi manual dari settings
        long duration = getScrollSpeedFromPrefs();
        countdownRemaining = duration;
        sendDurationBroadcast(countdownRemaining);
        startCountdown();
        scheduleScroll(duration);
    }

    /**
     * Deteksi durasi video dari AccessibilityNodeInfo.
     * TikTok merender SeekBar/ProgressBar dengan rangeInfo (max = durasi ms atau detik).
     * Juga coba baca label teks durasi seperti "0:15 / 0:30".
     * Return durasi dalam ms, atau 0 jika tidak ketemu.
     */
    private long detectVideoDurationMs() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return 0;
        try {
            // Cara 1: cari ProgressBar/SeekBar dengan RangeInfo
            long fromRange = findDurationFromRangeInfo(root);
            if (fromRange > 0) { root.recycle(); return fromRange; }

            // Cara 2: cari teks durasi format "M:SS / M:SS" atau "MM:SS"
            long fromText = findDurationFromText(root);
            if (fromText > 0) { root.recycle(); return fromText; }

        } catch (Exception e) { Log.e(TAG, "detectDuration: " + e.getMessage()); }
        root.recycle();
        return 0;
    }

    private long findDurationFromRangeInfo(AccessibilityNodeInfo node) {
        if (node == null) return 0;
        AccessibilityNodeInfo.RangeInfo range = node.getRangeInfo();
        if (range != null) {
            float max = range.getMax();
            float current = range.getCurrent();
            // TikTok SeekBar: max bisa dalam ms (>1000) atau detik (<300)
            if (max > 0 && max <= 600000) {
                long remaining;
                if (max > 1000) {
                    // Dalam ms
                    remaining = (long)(max - current);
                } else {
                    // Dalam detik
                    remaining = (long)((max - current) * 1000);
                }
                // Minimal 2 detik, maksimal 10 menit
                if (remaining >= 2000 && remaining <= 600000) {
                    return remaining;
                }
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            long result = findDurationFromRangeInfo(child);
            if (child != null) child.recycle();
            if (result > 0) return result;
        }
        return 0;
    }

    private long findDurationFromText(AccessibilityNodeInfo node) {
        if (node == null) return 0;
        CharSequence text = node.getText();
        if (text != null) {
            long parsed = parseDurationText(text.toString());
            if (parsed > 0) return parsed;
        }
        CharSequence desc = node.getContentDescription();
        if (desc != null) {
            long parsed = parseDurationText(desc.toString());
            if (parsed > 0) return parsed;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            long result = findDurationFromText(child);
            if (child != null) child.recycle();
            if (result > 0) return result;
        }
        return 0;
    }

    /**
     * Parse teks "0:15 / 0:30" ambil sisa (total - current).
     * Atau "0:30" saja ambil langsung.
     */
    private long parseDurationText(String text) {
        if (text == null || text.isEmpty()) return 0;
        // Format: "M:SS / M:SS" atau "MM:SS / MM:SS"
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
            "(\\d{1,2}):(\\d{2})\\s*/\\s*(\\d{1,2}):(\\d{2})");
        java.util.regex.Matcher m = p.matcher(text);
        if (m.find()) {
            int totalMin = Integer.parseInt(m.group(3));
            int totalSec = Integer.parseInt(m.group(4));
            int curMin   = Integer.parseInt(m.group(1));
            int curSec   = Integer.parseInt(m.group(2));
            long totalMs = (totalMin * 60L + totalSec) * 1000L;
            long curMs   = (curMin * 60L + curSec) * 1000L;
            long remaining = totalMs - curMs;
            if (remaining >= 2000 && remaining <= 600000) return remaining;
        }
        // Format tunggal: "M:SS"
        java.util.regex.Pattern p2 = java.util.regex.Pattern.compile("^(\\d{1,2}):(\\d{2})$");
        java.util.regex.Matcher m2 = p2.matcher(text.trim());
        if (m2.find()) {
            int min = Integer.parseInt(m2.group(1));
            int sec = Integer.parseInt(m2.group(2));
            long ms = (min * 60L + sec) * 1000L;
            if (ms >= 2000 && ms <= 600000) return ms;
        }
        return 0;
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
                long d = delay;
                handler.postDelayed(() -> doAutoLike(), d);
                delay += 500;
            }
            if (isAutoFollowEnabled()) {
                long d = delay;
                handler.postDelayed(() -> doAutoFollow(), d);
                delay += 500;
            }
            // Share & Save TIDAK boleh bersamaan (keduanya buka share sheet)
            // Prioritaskan Save jika keduanya aktif
            if (isAutoSaveEnabled()) {
                long d = delay;
                handler.postDelayed(() -> doAutoSave(), d);
                delay += 2500;
            } else if (isAutoShareEnabled()) {
                long d = delay;
                handler.postDelayed(() -> doAutoShare(), d);
                delay += 2000;
            }
            if (isAutoCommentEnabled()) {
                long d = delay;
                handler.postDelayed(() -> doAutoComment(), d);
                delay += 3000;
            }

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

                // Deteksi LIVE — contains agar tidak miss "LIVE •" atau "🔴 LIVE"
                if (isSkipLiveEnabled() && (
                        t.equals("live") ||
                        t.contains("sedang live") ||
                        t.contains("is live") ||
                        t.startsWith("live ") ||
                        t.endsWith(" live"))) {
                    Log.i(TAG, "LIVE detected: [" + text + "] - skipping");
                    sendStatusBroadcast("live_skipped");
                    root.recycle();
                    cancelAll();
                    doScroll();
                    handler.postDelayed(() -> startTimer(), 1500);
                    return true;
                }

                // Deteksi Iklan — contains agar tidak miss variasi teks
                if (isSkipAdsEnabled() && (
                        t.equals("iklan") ||
                        t.equals("ad") ||
                        t.equals("sponsored") ||
                        t.equals("berbayar") ||
                        t.equals("promoted") ||
                        t.equals("bersponsor") ||
                        t.contains("konten berbayar") ||
                        t.contains("paid partnership") ||
                        t.contains("sponsored content") ||
                        t.contains("iklan •") ||
                        t.contains("• iklan"))) {
                    Log.i(TAG, "Ad detected: [" + text + "] - skipping");
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

    // ===================== HELPER: CARI NODE BY TEXT ATAU CONTENT-DESC =====================

    /**
     * Cari node yang text ATAU content-description-nya mengandung keyword.
     * excludeKeyword: jika desc/text mengandung ini, skip (contoh: "Mengikuti").
     */
    private AccessibilityNodeInfo findNodeByTextOrDesc(
            AccessibilityNodeInfo node, String keyword, String excludeKeyword) {
        if (node == null) return null;

        String kw = keyword.toLowerCase();
        String ex = excludeKeyword != null ? excludeKeyword.toLowerCase() : null;

        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        String textStr = text != null ? text.toString().toLowerCase() : "";
        String descStr = desc != null ? desc.toString().toLowerCase() : "";

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

    // ===================== AUTO LIKE =====================
    // content-desc: "Sukai video. X suka"

    private void doAutoLike() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        try {
            // Cari tombol like — bisa "Sukai video" atau "Like"
            String[] likeKeywords = {"Sukai video", "Like video", "Suka"};
            for (String kw : likeKeywords) {
                AccessibilityNodeInfo node = findNodeByTextOrDesc(root, kw, null);
                if (node != null) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    likeCount++;
                    sendStatsBroadcast();
                    Log.i(TAG, "Auto liked! total: " + likeCount);
                    break;
                }
            }
        } catch (Exception e) { Log.e(TAG, "autoLike: " + e.getMessage()); }
        root.recycle();
    }

    // ===================== AUTO FOLLOW =====================
    // content-desc: "Ikuti [nama]"

    private void doAutoFollow() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        try {
            // Cari "Ikuti", exclude "Mengikuti" (sudah diikuti)
            AccessibilityNodeInfo node = findNodeByTextOrDesc(root, "Ikuti", "Mengikuti");
            if (node != null) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                followCount++;
                sendStatsBroadcast();
                Log.i(TAG, "Auto followed! total: " + followCount);
            }
        } catch (Exception e) { Log.e(TAG, "autoFollow: " + e.getMessage()); }
        root.recycle();
    }

    // ===================== AUTO COMMENT =====================
    // content-desc: "Baca atau tambahkan komentar. X komentar"

    private void doAutoComment() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        try {
            // Cari tombol komentar by text atau content-desc
            String[] commentKeywords = {"Baca atau tambahkan komentar", "Tambahkan komentar", "Comment"};
            for (String kw : commentKeywords) {
                AccessibilityNodeInfo node = findNodeByTextOrDesc(root, kw, null);
                if (node != null) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    handler.postDelayed(() -> typeComment(), 1200);
                    Log.i(TAG, "Comment button tapped");
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
            // Cari input field komentar
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
                List<AccessibilityNodeInfo> nodes =
                    root.findAccessibilityNodeInfosByViewId(id);
                if (nodes != null && !nodes.isEmpty()) {
                    AccessibilityNodeInfo input = nodes.get(0);
                    input.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                    Bundle args = new Bundle();
                    args.putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, comment);
                    input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                    typed = true;
                    break;
                }
            }

            // Fallback: cari EditText by class
            if (!typed) {
                typed = findAndTypeInEditText(root, comment);
            }

            if (typed) {
                handler.postDelayed(() -> tapSend(), 600);
                commentCount++;
                sendStatsBroadcast();
                Log.i(TAG, "Comment typed: " + comment);
            }
        } catch (Exception e) { Log.e(TAG, "typeComment: " + e.getMessage()); }
        root.recycle();
    }

    private boolean findAndTypeInEditText(AccessibilityNodeInfo node, String text) {
        if (node == null) return false;
        if ("android.widget.EditText".equals(node.getClassName() != null
            ? node.getClassName().toString() : "")) {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            Bundle args = new Bundle();
            args.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
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
            // Cari tombol send
            String[] sendTexts = {"Kirim", "Send", "Posting"};
            for (String sendText : sendTexts) {
                List<AccessibilityNodeInfo> nodes =
                    root.findAccessibilityNodeInfosByText(sendText);
                if (nodes != null && !nodes.isEmpty()) {
                    nodes.get(0).performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    Log.i(TAG, "Comment sent!");
                    // Tutup komentar
                    handler.postDelayed(() -> performGlobalAction(GLOBAL_ACTION_BACK), 500);
                    break;
                }
            }
        } catch (Exception e) { Log.e(TAG, "tapSend: " + e.getMessage()); }
        root.recycle();
    }

    // ===================== AUTO SHARE =====================
    // content-desc: "Bagikan video. X kali dibagikan"

    private void doAutoShare() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        try {
            // Cari tombol share by text atau content-desc
            String[] shareKeywords = {"Bagikan video", "Share video", "Bagikan"};
            for (String kw : shareKeywords) {
                AccessibilityNodeInfo node = findNodeByTextOrDesc(root, kw, null);
                if (node != null) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    Log.i(TAG, "Auto shared!");
                    // Tutup share sheet setelah terbuka (jangan save, hanya share)
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
            // Buka share sheet dulu via tombol share
            // Gunakan keyword yang BERBEDA dari autoShare agar tidak konflik
            // (autoShare dan autoSave tidak boleh aktif bersamaan idealnya)
            String[] shareKeywords = {"Bagikan video", "Share video", "Bagikan"};
            for (String kw : shareKeywords) {
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
            String[] saveKeywords = {"Simpan video", "Save video", "Unduh", "Download"};
            for (String kw : saveKeywords) {
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
                .getDefaultSharedPreferences(this).getString("activeHourStart", "8"));
            int end = Integer.parseInt(PreferenceManager
                .getDefaultSharedPreferences(this).getString("activeHourEnd", "22"));
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
            .getDefaultSharedPreferences(this).getString("scrollSpeed", "15000"));
        } catch (Exception e) { return 15000L; }
    }
    private long getSwipeSpeedFromPrefs() {
        try { return Long.parseLong(PreferenceManager
            .getDefaultSharedPreferences(this).getString("swipeSpeed", "300"));
        } catch (Exception e) { return 300L; }
    }
    private long getExtraDelayFromPrefs() {
        try { return Long.parseLong(PreferenceManager
            .getDefaultSharedPreferences(this).getString("extraDelay", "0"));
        } catch (Exception e) { return 0L; }
    }
    private boolean isSkipLiveEnabled() {
        return PreferenceManager.getDefaultSharedPreferences(this).getBoolean("skipLive", true); }
    private boolean isSkipAdsEnabled() {
        return PreferenceManager.getDefaultSharedPreferences(this).getBoolean("skipAds", true); }
    private boolean isSmartDurationEnabled() {
        return PreferenceManager.getDefaultSharedPreferences(this).getBoolean("smartDuration", true); }
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
