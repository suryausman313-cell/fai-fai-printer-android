package com.faifai.printer;

import android.Manifest;
import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Dedicated-device home screen.
 *
 * The Kitchen WebView lives in KitchenActivity. Keeping a separate MainActivity
 * restores the old "Tap to open" screen and gives the hardware Back button a
 * real destination when Kitchen is already at its Live root.
 */
public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 54);
        }

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        configureDedicatedDeviceMode();
        startKitchenService();
        setContentView(buildHomeScreen());
    }

    @Override
    protected void onResume() {
        super.onResume();
        configureDedicatedDeviceMode();
        startKitchenService();
    }

    private void configureDedicatedDeviceMode() {
        try {
            DevicePolicyManager dpm =
                    (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName admin = new ComponentName(this, KioskDeviceAdminReceiver.class);

            if (dpm != null && dpm.isDeviceOwnerApp(getPackageName())) {
                dpm.setLockTaskPackages(admin, new String[]{getPackageName()});

                // Make this clean Fai Fai screen the dedicated device HOME.
                // This also prevents the black/empty launcher screen after reboot.
                IntentFilter homeFilter = new IntentFilter(Intent.ACTION_MAIN);
                homeFilter.addCategory(Intent.CATEGORY_HOME);
                homeFilter.addCategory(Intent.CATEGORY_DEFAULT);
                dpm.addPersistentPreferredActivity(
                        admin,
                        homeFilter,
                        new ComponentName(this, MainActivity.class)
                );
            }

            if (dpm != null && dpm.isLockTaskPermitted(getPackageName())) {
                try {
                    startLockTask();
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
            // If the device is not owner-managed, the app still works normally.
        }
    }

    private LinearLayout buildHomeScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(28), dp(28), dp(28), dp(28));
        root.setBackgroundColor(Color.rgb(2, 8, 23));
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        ImageView icon = new ImageView(this);
        icon.setImageResource(com.faifai.printer.R.drawable.ic_launcher);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(96), dp(96));
        iconParams.bottomMargin = dp(20);
        icon.setLayoutParams(iconParams);
        root.addView(icon);

        TextView title = new TextView(this);
        title.setText("FAI FAI KITCHEN");
        title.setTextColor(Color.WHITE);
        title.setTextSize(27f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView status = new TextView(this);
        status.setText("Kitchen device ready\nNew orders stay active in background");
        status.setTextColor(Color.rgb(148, 163, 184));
        status.setTextSize(15f);
        status.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        statusParams.topMargin = dp(10);
        statusParams.bottomMargin = dp(30);
        status.setLayoutParams(statusParams);
        root.addView(status);

        Button openKitchen = makeActionButton("OPEN KITCHEN", Color.rgb(234, 88, 12));
        openKitchen.setOnClickListener(v -> openKitchen());
        root.addView(openKitchen);

        Button openAdmin = makeActionButton("OPEN ADMIN", Color.rgb(22, 101, 52));
        LinearLayout.LayoutParams adminParams = (LinearLayout.LayoutParams) openAdmin.getLayoutParams();
        adminParams.topMargin = dp(12);
        openAdmin.setLayoutParams(adminParams);
        openAdmin.setOnClickListener(v -> openAdmin());
        root.addView(openAdmin);

        TextView hint = new TextView(this);
        hint.setText("Back from Kitchen/Admin returns here");
        hint.setTextColor(Color.rgb(100, 116, 139));
        hint.setTextSize(12f);
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        hintParams.topMargin = dp(18);
        hint.setLayoutParams(hintParams);
        root.addView(hint);

        return root;
    }

    private Button makeActionButton(String label, int backgroundColor) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(Color.WHITE);
        button.setTextSize(17f);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setAllCaps(false);
        button.setPadding(dp(22), dp(16), dp(22), dp(16));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(backgroundColor);
        bg.setCornerRadius(dp(16));
        button.setBackground(bg);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.leftMargin = dp(14);
        params.rightMargin = dp(14);
        button.setLayoutParams(params);
        return button;
    }

    private void openAdmin() {
        Intent intent = new Intent(this, AdminActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
    }

    private void openKitchen() {
        Intent intent = new Intent(this, KitchenActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
    }

    private void startKitchenService() {
        try {
            Intent service = new Intent(this, KitchenOrderService.class);
            service.setAction(KitchenOrderService.ACTION_START);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
            else startService(service);
        } catch (Exception ignored) {
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // Dedicated home screen must not exit to the normal Android launcher.
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) return true;
        return super.onKeyDown(keyCode, event);
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        // Stay on dedicated Fai Fai home.
    }
}
