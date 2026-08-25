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
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Dedicated Fai Fai device home.
 *
 * Normal screen: OPEN KITCHEN only.
 * Long press OPEN KITCHEN -> hidden PIN -> DEVICE APPS screen.
 * The admin screen exits lock task first, then lists launchable apps directly,
 * so access does not depend on the Q2I vendor launcher's package name.
 */
public class MainActivity extends Activity {
    private static final String PREFS = "fai_fai_kitchen";
    private static final String PREF_PIN = "pin";

    private boolean adminDialogOpen = false;
    private boolean adminMode = false;

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
        startKitchenService();

        // Do not immediately re-enter kiosk while the owner is intentionally
        // using the hidden admin app drawer.
        if (!adminMode) {
            configureDedicatedDeviceMode();
        }
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
            // Kitchen remains usable even when an owner/kiosk API is unavailable.
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
        openKitchen.setLongClickable(true);
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
        params.bottomMargin = dp(12);
        button.setLayoutParams(params);
        return button;
    }

    private void openKitchen() {
        adminMode = false;
        configureDedicatedDeviceMode();
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
                    "Open Kitchen, login once, then hold OPEN KITCHEN again",
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
            if (dialog.getWindow() != null) {
                dialog.getWindow().setSoftInputMode(
                        WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                );
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String entered = input.getText() == null
                        ? ""
                        : input.getText().toString().trim();
                if (!expectedPin.equals(entered)) {
                    input.setError("Wrong PIN");
                    return;
                }

                dialog.dismiss();
                releaseKioskForAdmin();
                adminMode = true;
                setContentView(buildAdminAppsScreen());
            });
        });

        dialog.show();
    }

    private void releaseKioskForAdmin() {
        try {
            stopLockTask();
        } catch (Exception ignored) {
        }

        try {
            DevicePolicyManager dpm =
                    (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName admin = new ComponentName(this, KioskDeviceAdminReceiver.class);
            if (dpm != null && dpm.isDeviceOwnerApp(getPackageName())) {
                try {
                    dpm.clearPackagePersistentPreferredActivities(admin, getPackageName());
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
    }

    private LinearLayout buildAdminAppsScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(18));
        root.setBackgroundColor(Color.rgb(2, 8, 23));
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        TextView title = new TextView(this);
        title.setText("DEVICE APPS");
        title.setTextColor(Color.WHITE);
        title.setTextSize(24f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        titleParams.bottomMargin = dp(14);
        title.setLayoutParams(titleParams);
        root.addView(title);

        Button settings = makeActionButton("ANDROID SETTINGS", Color.rgb(37, 99, 235));
        settings.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (Exception e) {
                Toast.makeText(this, "Settings could not open", Toast.LENGTH_LONG).show();
            }
        });
        root.addView(settings);

        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        List<AppEntry> apps = loadLaunchableApps();
        if (apps.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No launchable apps found. Use Android Settings above.");
            empty.setTextColor(Color.rgb(203, 213, 225));
            empty.setTextSize(15f);
            empty.setPadding(dp(8), dp(18), dp(8), dp(18));
            list.addView(empty);
        } else {
            for (AppEntry app : apps) {
                Button button = makeActionButton(app.label, Color.rgb(30, 41, 59));
                button.setOnClickListener(v -> launchApp(app));
                list.addView(button);
            }
        }

        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
        );
        scroll.setLayoutParams(scrollParams);
        root.addView(scroll);

        Button back = makeActionButton("BACK TO KITCHEN KIOSK", Color.rgb(234, 88, 12));
        back.setOnClickListener(v -> {
            adminMode = false;
            configureDedicatedDeviceMode();
            setContentView(buildHomeScreen());
        });
        root.addView(back);

        return root;
    }

    private List<AppEntry> loadLaunchableApps() {
        List<AppEntry> result = new ArrayList<>();
        try {
            Intent launcherIntent = new Intent(Intent.ACTION_MAIN);
            launcherIntent.addCategory(Intent.CATEGORY_LAUNCHER);

            List<ResolveInfo> matches = getPackageManager().queryIntentActivities(
                    launcherIntent,
                    PackageManager.MATCH_DEFAULT_ONLY
            );

            for (ResolveInfo info : matches) {
                if (info.activityInfo == null) continue;
                String pkg = info.activityInfo.packageName;
                String cls = info.activityInfo.name;
                if (pkg == null || cls == null || pkg.equals(getPackageName())) continue;

                CharSequence rawLabel = info.loadLabel(getPackageManager());
                String label = rawLabel == null ? pkg : rawLabel.toString().trim();
                if (label.isEmpty()) label = pkg;
                result.add(new AppEntry(label, pkg, cls));
            }

            Collections.sort(result, Comparator.comparing(
                    app -> app.label.toLowerCase()
            ));
        } catch (Exception ignored) {
        }
        return result;
    }

    private void launchApp(AppEntry app) {
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
            intent.setComponent(new ComponentName(app.packageName, app.activityName));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(intent);
        } catch (Exception error) {
            Toast.makeText(this, "Could not open " + app.label, Toast.LENGTH_LONG).show();
        }
    }

    private static final class AppEntry {
        final String label;
        final String packageName;
        final String activityName;

        AppEntry(String label, String packageName, String activityName) {
            this.label = label;
            this.packageName = packageName;
            this.activityName = activityName;
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
        if (keyCode == KeyEvent.KEYCODE_BACK && !adminMode) return true;
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && !adminMode) return true;
        return super.onKeyUp(keyCode, event);
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (adminMode) {
            adminMode = false;
            configureDedicatedDeviceMode();
            setContentView(buildHomeScreen());
            return;
        }
        // Keep short Back inside the dedicated Fai Fai home.
    }
}
