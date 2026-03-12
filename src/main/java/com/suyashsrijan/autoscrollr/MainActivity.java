package com.suyashsrijan.autoscrollr;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.SwitchCompat;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.TextView;

import java.util.List;

public class MainActivity extends AppCompatActivity implements CompoundButton.OnCheckedChangeListener {

    private static final String TAG = "AutoScrollr-App";

    private SharedPreferences settings;
    private SharedPreferences.Editor editor;
    private boolean isServiceEnabled = false;

    private SwitchCompat toggleButtonService;
    private TextView textViewStatus;
    private TextView tvVideoDuration;
    private TextView tvScrollCount;
    private int scrollCount = 0;

    private BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String status = intent.getStringExtra("status");
            if (status == null) return;
            switch (status) {
                case "started":
                    textViewStatus.setText("🟢 Auto scroll aktif");
                    break;
                case "paused":
                    textViewStatus.setText("🟡 Dijeda");
                    break;
                case "stopped":
                    textViewStatus.setText("🔴 Tidak aktif");
                    toggleButtonService.setChecked(false);
                    break;
                case "scrolled":
                    scrollCount++;
                    tvScrollCount.setText("Video: " + scrollCount);
                    textViewStatus.setText("⏭ Scrolling...");
                    break;
                case "live_skipped":
                    scrollCount++;
                    tvScrollCount.setText("Video: " + scrollCount);
                    textViewStatus.setText("🔴 LIVE dideteksi - skip!");
                    break;
                case "duration":
                    long ms = intent.getLongExtra("duration_ms", 0);
                    long secs = ms / 1000;
                    tvVideoDuration.setText(String.format("Durasi: %d:%02d", secs / 60, secs % 60));
                    break;
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setElevation(0.0f);
            getSupportActionBar().setTitle("TikTok AutoScrollr");
        }

        settings = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        isServiceEnabled = settings.getBoolean("isServiceEnabled", false);

        toggleButtonService = findViewById(R.id.switch1);
        textViewStatus      = findViewById(R.id.textView2);
        tvVideoDuration     = findViewById(R.id.tv_video_duration);
        tvScrollCount       = findViewById(R.id.tv_scroll_count);

        toggleButtonService.setOnCheckedChangeListener(null);
        toggleButtonService.setChecked(isAccessibilityEnabled() && isServiceRunning());
        toggleButtonService.setOnCheckedChangeListener(this);

        findViewById(R.id.card_accessibility).setOnClickListener(v ->
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        findViewById(R.id.card_overlay).setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            }
        });

        findViewById(R.id.btn_open_tiktok).setOnClickListener(v -> openTikTok());

        registerReceiver(statusReceiver,
            new IntentFilter("com.suyashsrijan.autoscrollr.STATUS_UPDATE"));
    }

    @Override
    public void onCheckedChanged(CompoundButton compoundButton, boolean isChecked) {
        if (isChecked) {
            if (!isAccessibilityEnabled()) {
                toggleButtonService.setChecked(false);
                showAccessibilityDialog();
                return;
            }
            editor = settings.edit();
            editor.putBoolean("isServiceEnabled", true);
            editor.apply();
            isServiceEnabled = true;
            textViewStatus.setText("🟢 Auto scroll aktif");

            if (canDrawOverlays()) {
                startService(new Intent(this, FloatingControlService.class));
            }
            if (ScrollrService.instance != null) {
                ScrollrService.instance.startAutoScroll();
            }
            showScrollrActiveDialog();
            openTikTok();
        } else {
            editor = settings.edit();
            editor.putBoolean("isServiceEnabled", false);
            editor.apply();
            isServiceEnabled = false;
            textViewStatus.setText("🔴 Tidak aktif");
            scrollCount = 0;
            tvScrollCount.setText("Video: 0");
            tvVideoDuration.setText("Durasi: -");

            if (ScrollrService.instance != null) {
                ScrollrService.instance.stopAutoScroll();
            }
            stopService(new Intent(this, FloatingControlService.class));
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        switch (id) {
            case R.id.action_app_settings:
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
                break;
        }
        return super.onOptionsItemSelected(item);
    }

    private boolean isAccessibilityEnabled() {
        AccessibilityManager am =
            (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        List<AccessibilityServiceInfo> services =
            am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        for (AccessibilityServiceInfo info : services) {
            if (info.getId().contains(getPackageName())) return true;
        }
        return false;
    }

    private boolean isServiceRunning() {
        return ScrollrService.instance != null && ScrollrService.instance.isRunning();
    }

    private boolean canDrawOverlays() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
            || Settings.canDrawOverlays(this);
    }

    private void openTikTok() {
        String[] packages = {
            "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill"
        };
        for (String pkg : packages) {
            Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch != null) {
                startActivity(launch);
                return;
            }
        }
        new AlertDialog.Builder(this, R.style.AppCompatAlertDialogStyle)
            .setTitle("TikTok Tidak Ditemukan")
            .setMessage("Install TikTok terlebih dahulu.")
            .setPositiveButton("OK", null)
            .show();
    }

    private void showAccessibilityDialog() {
        new AlertDialog.Builder(this, R.style.AppCompatAlertDialogStyle)
            .setTitle("Aktifkan Accessibility")
            .setMessage("Settings → Accessibility → TikTok AutoScrollr → Aktifkan")
            .setPositiveButton("Buka Settings", (d, i) ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
            .setNegativeButton("Batal", null)
            .show();
    }

    private void showScrollrActiveDialog() {
        new AlertDialog.Builder(this, R.style.AppCompatAlertDialogStyle)
            .setTitle("TikTok AutoScrollr Aktif! 🎵")
            .setMessage("Auto scroll berjalan!\n\n• Mengikuti durasi setiap video\n• Skip LIVE otomatis\n• Gunakan floating button untuk pause/stop")
            .setPositiveButton("Mengerti", (d, i) -> d.dismiss())
            .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        TextView tvAccessStatus = findViewById(R.id.tv_access_status);
        TextView tvOverlayStatus = findViewById(R.id.tv_overlay_status);
        if (tvAccessStatus != null)
            tvAccessStatus.setText(isAccessibilityEnabled() ? "✅ Aktif" : "❌ Belum");
        if (tvOverlayStatus != null)
            tvOverlayStatus.setText(canDrawOverlays() ? "✅ Aktif" : "❌ Belum");

        if (isAccessibilityEnabled()) {
            textViewStatus.setText(isServiceRunning() ? "🟢 Auto scroll aktif" : "🔴 Tidak aktif");
        } else {
            textViewStatus.setText("⚠️ Aktifkan Accessibility Service dulu");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(statusReceiver); } catch (Exception ignored) {}
    }
}
