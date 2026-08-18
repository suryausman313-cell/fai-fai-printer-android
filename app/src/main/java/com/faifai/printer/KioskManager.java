package com.faifai.printer;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.provider.Settings;

/**
 * Dedicated-device kiosk helper.
 *
 * Full kiosk mode is activated only when this package has been provisioned as
 * Android Device Owner. Without Device Owner privileges the Kitchen app still
 * works normally and never falls back to user-escapable screen pinning.
 */
public final class KioskManager {
    private KioskManager() {}

    public static ComponentName adminComponent(Context context) {
        return new ComponentName(context, KioskDeviceAdminReceiver.class);
    }

    public static DevicePolicyManager dpm(Context context) {
        return (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
    }

    public static boolean isDeviceOwner(Context context) {
        DevicePolicyManager manager = dpm(context);
        return manager != null && manager.isDeviceOwnerApp(context.getPackageName());
    }

    /** Apply persistent single-app policies. Safe to call repeatedly. */
    public static boolean applyPolicies(Context context) {
        DevicePolicyManager manager = dpm(context);
        if (manager == null || !manager.isDeviceOwnerApp(context.getPackageName())) {
            return false;
        }

        ComponentName admin = adminComponent(context);
        String pkg = context.getPackageName();

        try {
            manager.setLockTaskPackages(admin, new String[]{pkg});
        } catch (Exception ignored) { }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                manager.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE);
            } catch (Exception ignored) { }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try { manager.setStatusBarDisabled(admin, true); } catch (Exception ignored) { }
            try { manager.setKeyguardDisabled(admin, true); } catch (Exception ignored) { }
        }

        try {
            IntentFilter homeFilter = new IntentFilter(Intent.ACTION_MAIN);
            homeFilter.addCategory(Intent.CATEGORY_HOME);
            homeFilter.addCategory(Intent.CATEGORY_DEFAULT);
            ComponentName home = new ComponentName(context, MainActivity.class);
            manager.addPersistentPreferredActivity(admin, homeFilter, home);
        } catch (Exception ignored) { }

        return true;
    }

    /** Enter real lock-task mode only when allowlisted by Device Owner. */
    public static void enter(Activity activity) {
        if (!applyPolicies(activity)) return;
        DevicePolicyManager manager = dpm(activity);
        if (manager == null || !manager.isLockTaskPermitted(activity.getPackageName())) return;
        try {
            activity.startLockTask();
        } catch (Exception ignored) { }
    }

    /**
     * Temporarily release kiosk for the administrator. Device Owner remains
     * installed. Reopening Fai Fai Kitchen or rebooting applies kiosk again.
     */
    public static void exitForAdmin(Activity activity) {
        DevicePolicyManager manager = dpm(activity);
        ComponentName admin = adminComponent(activity);

        try { activity.stopLockTask(); } catch (Exception ignored) { }

        if (manager != null && manager.isDeviceOwnerApp(activity.getPackageName())) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try { manager.setStatusBarDisabled(admin, false); } catch (Exception ignored) { }
                try { manager.setKeyguardDisabled(admin, false); } catch (Exception ignored) { }
            }
            try { manager.setLockTaskPackages(admin, new String[]{}); } catch (Exception ignored) { }
            try {
                manager.clearPackagePersistentPreferredActivities(admin, activity.getPackageName());
            } catch (Exception ignored) { }
        }

        // Open Settings after a successful admin unlock so the administrator
        // can reach the launcher/apps without hunting for another escape path.
        try {
            Intent settings = new Intent(Settings.ACTION_SETTINGS);
            settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(settings);
        } catch (Exception ignored) { }
    }
}
