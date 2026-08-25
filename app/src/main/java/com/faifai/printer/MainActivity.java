package com.faifai.printer;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
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
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Dedicated Fai Fai device home.
 *
 * Visible UI intentionally contains only OPEN KITCHEN.
 * A long-press on OPEN KITCHEN is the hidden owner/admin escape: it asks for
 * the Kitchen PIN already synced by KitchenActivity, then opens the Q2I's
 * normal Android launcher so the owner can reach other device apps.
 */
public class MainActivity extends Activity {
    private static final String PREFS = "fai_fai_kitchen";
    private static final String PREF_PIN = "pin";
    private static final String DEVICE_LAUNCHER_PACKAGE = "com.android.launcher3";

    private boolean adminDialogOpen = false;

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
        adminDialogOpen = false;
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
            // The Kitchen still works even if owner/kiosk APIs are unavailable.
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
        icon.setImageResource(R.drawable.ic_launcher);
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
        openKitchen.setOnLongClickListener(v -> {
            showHiddenAdminPinDialog();
            return true;
        });
        root.addView(openKitchen);

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

        GradientDrawable background = new GradientDrawable();
        background.setColor(backgroundColor);
        background.setCornerRadius(dp(16));
        button.setBackground(background);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.leftMargin = dp(14);
        params.rightMargin = dp(14);
        button.setLayoutParams(params);
        return button;
    }

    private void openKitchen() {
        Intent intent = new Intent(this, KitchenActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
    }

    private void showHiddenAdminPinDialog() {
        if (adminDialogOpen) return;

        String savedPin = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(PREF_PIN, "");
        savedPin = savedPin == null ? "" : savedPin.trim();

        if (savedPin.length() < 4) {
            Toast.makeText(
                    this,
                    "Open Kitchen and login once first",
                    Toast.LENGTH_LONG
            ).show();
            return;
        }

        final String expectedPin = savedPin;
        adminDialogOpen = true;

        EditText input = new EditText(this);
        input.setHint("PIN");
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setPadding(dp(18), dp(10), dp(18), dp(10));

        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(20), dp(4), dp(20), 0);
        box.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Admin Access")
                .setView(box)
                .setNegativeButton("Cancel", (d, which) -> adminDialogOpen = false)
                .setPositiveButton("Open", null)
                .create();

        dialog.setOnCancelListener(d -> adminDialogOpen = false);
        dialog.setOnDismissListener(d -> adminDialogOpen = false);
        dialog.setOnShowListener(d -> {
            input.requestFocus();
            dialog.getWindow().setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
            );
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String entered = input.getText() == null
                        ? ""
                        : input.getText().toString().trim();
                if (!expectedPin.equals(entered)) {
                    input.setError("Wrong PIN");
                    return;
                }

                dialog.dismiss();
                openDeviceLauncher();
            });
        });

        dialog.show();
    }

    private void openDeviceLauncher() {
        try {
            try {
                stopLockTask();
            } catch (Exception ignored) {
            }

            DevicePolicyManager dpm =
                    (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName admin = new ComponentName(this, KioskDeviceAdminReceiver.class);

            if (dpm != null && dpm.isDeviceOwnerApp(getPackageName())) {
                try {
                    dpm.clearPackagePersistentPreferredActivities(admin, getPackageName());
                } catch (Exception ignored) {
                }
            }

            Intent launcher = new Intent(Intent.ACTION_MAIN);
            launcher.addCategory(Intent.CATEGORY_HOME);
            launcher.setPackage(DEVICE_LAUNCHER_PACKAGE);
            launcher.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            );

            if (launcher.resolveActivity(getPackageManager()) != null) {
                startActivity(launcher);
                return;
            }

            Intent fallback = getPackageManager().getLaunchIntentForPackage(DEVICE_LAUNCHER_PACKAGE);
            if (fallback != null) {
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(fallback);
                return;
            }

            Toast.makeText(this, "Device launcher not found", Toast.LENGTH_LONG).show();
        } catch (Exception error) {
            Toast.makeText(this, "Could not open device apps", Toast.LENGTH_LONG).show();
        }
    }

    private void startKitchenService() {
        try {
            Intent service = new Intent(this, KitchenOrderService.class);
            service.setAction(KitchenOrderService.ACTION_START);
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(service);
            } else {
                startService(service);
            }
        } catch (Exception ignored) {
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) return true;
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) return true;
        return super.onKeyUp(keyCode, event);
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        // Keep short Back inside the dedicated Fai Fai home.
    }
}
