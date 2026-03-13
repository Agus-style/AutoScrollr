package com.suyashsrijan.autoscrollr;

import android.content.Context;
import android.os.Build;
import android.provider.Settings;

public class Utils {

    public static boolean isSystemAlertWindowPermissionGranted(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(context);
        }
        return true;
    }
}
