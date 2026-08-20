package com.faifai.printer;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;
import android.net.wifi.WifiManager;

/**
 * Dedicated-device kiosk helper for Fai Fai Kitchen.
 *
 * The app uses Android Device Owner + Lock Task only. It intentionally does
 * NOT register itself as the system HOME launcher. This avoids the blank/black
 * HOME-alias screen seen on some NETUM firmware while still blocking Home and
 * Recents during Kitchen use.
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

    /**
     * Keep Wi-Fi enabled and ask Android to reconnect to a previously saved
     * network. No SSID/password is stored in the app. The network must have
     * been connected/saved once in Android settings. Device Owner apps are
     * allowed to restore Wi-Fi on modern Android versions.
     */
    public static void ensureWifiReady(Context context) {
        try {
            Context appContext = context.getApplicationContext();
            WifiManager wifi = (WifiManager) appContext.getSystemService(Context.WIFI_SERVICE);
            if (wifi == null) return;
            if (!wifi.isWifiEnabled()) {
                try { wifi.setWifiEnabled(true); } catch (Exception ignored) { }
            }
            try { wifi.reconnect(); } catch (Exception ignored) { }
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

        // Clean up the persistent HOME policy from older builds that used the
        // KioskHomeActivity alias. The normal Android launcher remains HOME.
        try {
            manager.clearPackagePersistentPreferredActivities(admin, pkg);
        } catch (Exception ignored) { }

        try {
            manager.setLockTaskPackages(admin, new String[]{pkg});
        } catch (Exception ignored) { }

        // Keep the physical power-menu available for Restart / Power Off.
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
     * installed. Opening Fai Fai Kitchen again or rebooting re-enters kiosk.
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

        // With no Kitchen HOME alias, this resolves to the device's normal
        // launcher so the administrator can use Settings/other apps.
        try {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            activity.startActivity(home);
            return;
        } catch (Exception ignored) { }

        // Custom POS firmware fallback if a launcher cannot be resolved.
        try {
            Intent settings = new Intent(Settings.ACTION_SETTINGS);
            settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(settings);
        } catch (Exception ignored) { }
    }
}
