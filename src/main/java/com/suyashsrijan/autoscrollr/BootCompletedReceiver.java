package com.suyashsrijan.autoscrollr;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.preference.PreferenceManager;
import android.util.Log;

public class BootCompletedReceiver extends BroadcastReceiver {

    public static String TAG = "AutoScrollr-App";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;

        boolean autoStartOnBoot = PreferenceManager
            .getDefaultSharedPreferences(context)
            .getBoolean("autoStartOnBoot", false);

        Log.i(TAG, "Received BOOT_COMPLETED intent, autoStartOnBoot="
            + Boolean.toString(autoStartOnBoot));

        if (autoStartOnBoot) {
            // Accessibility Service tidak bisa di-start manual
            // Buka MainActivity sebagai reminder
            Log.i(TAG, "autoStartOnBoot=true - opening MainActivity");
            Intent mainIntent = new Intent(context, MainActivity.class);
            mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mainIntent.putExtra("from_boot", true);
            context.startActivity(mainIntent);
        }
    }
}
