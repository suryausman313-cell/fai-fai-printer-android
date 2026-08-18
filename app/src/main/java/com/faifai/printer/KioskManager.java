package com.faifai.printer;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
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

    private static ComponentName homeComponent(Context context) {
        return new ComponentName(context.getPackageName(), context.getPackageName() + ".KioskHomeActivity");
    }

    private static void setKitchenHomeEnabled(Context context, boolean enabled) {
        try {
            context.getPackageManager().setComponentEnabledSetting(
                    homeComponent(context),
                    enabled
                            ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                            : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
            );
        } catch (Exception ignored) { }
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
                manager.setLockTaskFeatures(
                    admin,
                    DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS
                );
            } catch (Exception ignored) { }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try { manager.setStatusBarDisabled(admin, true); } catch (Exception ignored) { }
            try { manager.setKeyguardDisabled(admin, true); } catch (Exception ignored) { }
        }

        setKitchenHomeEnabled(context, true);
        try {
            IntentFilter homeFilter = new IntentFilter(Intent.ACTION_MAIN);
            homeFilter.addCategory(Intent.CATEGORY_HOME);
            homeFilter.addCategory(Intent.CATEGORY_DEFAULT);
            manager.addPersistentPreferredActivity(admin, homeFilter, homeComponent(context));
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

        // Temporarily remove Fai Fai Kitchen from HOME candidates while the
        // administrator is unlocked. Reopening Kitchen or rebooting calls
        // applyPolicies(), which enables it and restores kiosk automatically.
        setKitchenHomeEnabled(activity, false);

        try {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            activity.startActivity(home);
            return;
        } catch (Exception ignored) { }

        // Very old/custom POS firmware fallback.
        try {
            Intent settings = new Intent(Settings.ACTION_SETTINGS);
            settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(settings);
        } catch (Exception ignored) { }
    }
}
