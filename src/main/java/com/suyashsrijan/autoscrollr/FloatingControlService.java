package com.suyashsrijan.autoscrollr;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

public class FloatingControlService extends Service {

    private static final String TAG = "AutoScrollr-Float";

    private WindowManager mWindowManager;
    private View mFloatingView;
    private WindowManager.LayoutParams params;

    private int initialX, initialY;
    private float initialTouchX, initialTouchY;
    private boolean isDragging = false;

    private TextView tvStatus;
    private TextView tvDuration;
    private ImageView playButton;

    private BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String status = intent.getStringExtra("status");
            if (status == null) return;
            switch (status) {
                case "started":
                case "scrolled":
                    updateUI(true, false);
                    tvStatus.post(() -> tvStatus.setText("Berjalan..."));
                    break;
                case "paused":
                    updateUI(true, true);
                    tvStatus.post(() -> tvStatus.setText("Dijeda"));
                    break;
                case "stopped":
                    updateUI(false, false);
                    tvStatus.post(() -> tvStatus.setText("Berhenti"));
                    break;
                case "live_skipped":
                    tvStatus.post(() -> tvStatus.setText("⏭ Skip LIVE!"));
                    break;
                case "duration":
                    long ms = intent.getLongExtra("duration_ms", 0);
                    long secs = ms / 1000;
                    tvDuration.post(() -> tvDuration.setText(
                        String.format("%d:%02d", secs / 60, secs % 60)));
                    break;
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();

        mWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        mFloatingView = LayoutInflater.from(this)
            .inflate(R.layout.layout_floating_scrollr_widget, null);

        int layoutFlag = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            : WindowManager.LayoutParams.TYPE_PHONE;

        params = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT);

        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 20;
        params.y = 200;

        setupViews();
        setupDragListener();
        mWindowManager.addView(mFloatingView, params);

        registerReceiver(statusReceiver,
            new IntentFilter("com.suyashsrijan.autoscrollr.STATUS_UPDATE"));

        Log.i(TAG, "Floating control shown");
    }

    private void setupViews() {
        tvStatus   = mFloatingView.findViewById(R.id.tv_float_status);
        tvDuration = mFloatingView.findViewById(R.id.tv_float_duration);
        playButton = mFloatingView.findViewById(R.id.play_btn);

        final View collapseView = mFloatingView.findViewById(R.id.collapse_view);
        final View expandedView = mFloatingView.findViewById(R.id.expanded_container);

        playButton.setOnClickListener(v -> {
            ScrollrService svc = ScrollrService.instance;
            if (svc == null) return;
            if (!svc.isRunning()) {
                svc.startAutoScroll();
            } else if (svc.isPaused()) {
                svc.resumeAutoScroll();
            } else {
                svc.pauseAutoScroll();
            }
        });

        // Tombol X di expanded view → collapse
        ImageView closeButton = mFloatingView.findViewById(R.id.close_button);
        closeButton.setOnClickListener(v -> {
            collapseView.setVisibility(View.VISIBLE);
            expandedView.setVisibility(View.GONE);
        });

        // Tombol X kecil di collapsed view → stop & tutup
        ImageView closeBtn = mFloatingView.findViewById(R.id.close_btn);
        closeBtn.setOnClickListener(v -> {
            ScrollrService svc = ScrollrService.instance;
            if (svc != null) svc.stopAutoScroll();
            stopSelf();
        });
    }

    private void setupDragListener() {
        View rootContainer = mFloatingView.findViewById(R.id.root_container);
        final View collapseView = mFloatingView.findViewById(R.id.collapse_view);
        final View expandedView = mFloatingView.findViewById(R.id.expanded_container);

        rootContainer.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    isDragging = false;
                    initialX = params.x;
                    initialY = params.y;
                    initialTouchX = event.getRawX();
                    initialTouchY = event.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - initialTouchX;
                    float dy = event.getRawY() - initialTouchY;
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) isDragging = true;
                    params.x = initialX + (int) dx;
                    params.y = initialY + (int) dy;
                    mWindowManager.updateViewLayout(mFloatingView, params);
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!isDragging) {
                        if (collapseView.getVisibility() == View.VISIBLE) {
                            collapseView.setVisibility(View.GONE);
                            expandedView.setVisibility(View.VISIBLE);
                        }
                    }
                    return isDragging;
            }
            return false;
        });
    }

    private void updateUI(boolean active, boolean paused) {
        if (playButton == null) return;
        playButton.post(() -> {
            playButton.setImageResource(
                !active || paused
                    ? R.drawable.ic_play_arrow_white_24dp
                    : R.drawable.ic_pause_white_24dp
            );
            mFloatingView.setAlpha(active ? 1.0f : 0.6f);
        });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mFloatingView != null) {
            try { mWindowManager.removeView(mFloatingView); } catch (Exception ignored) {}
        }
        try { unregisterReceiver(statusReceiver); } catch (Exception ignored) {}
    }

    @Override
    public IBinder onBind(Intent intent) {
        throw new UnsupportedOperationException("Not yet implemented");
    }
}
