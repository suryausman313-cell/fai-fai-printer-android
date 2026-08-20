package com.faifai.printer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Fai Fai dedicated-device launcher.
 *
 * Boot lands here instead of opening the Kitchen WebView directly. Staff see
 * one Fai Fai Kitchen tile. Tapping it opens KitchenActivity. Device Owner +
 * Lock Task keeps Android Home/Recents/other apps hidden until Admin unlock.
 */
public class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView networkStatus;
    private boolean backLongPressHandled = false;

    private final Runnable wifiKeepAlive = new Runnable() {
        @Override public void run() {
            KioskManager.ensureWifiReady(MainActivity.this);
            updateNetworkStatus();
            handler.postDelayed(this, 8000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        KioskManager.ensureWifiReady(this);
        setContentView(buildLauncher());
        KioskManager.applyPolicies(this);
    }

    private View buildLauncher() {
        final float density = getResources().getDisplayMetrics().density;
        int pad = Math.round(24 * density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.rgb(247, 248, 250));

        LinearLayout.LayoutParams spacerTop = new LinearLayout.LayoutParams(1, 0, 2.2f);
        root.addView(new View(this), spacerTop);

        ImageView brandIcon = new ImageView(this);
        brandIcon.setImageResource(R.drawable.ic_launcher);
        brandIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int logoSize = Math.round(88 * density);
        root.addView(brandIcon, new LinearLayout.LayoutParams(logoSize, logoSize));

        TextView title = new TextView(this);
        title.setText("Fai Fai Juice");
        title.setTextColor(Color.rgb(20, 28, 38));
        title.setTextSize(24);
        title.setGravity(Gravity.CENTER);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        titleLp.topMargin = Math.round(8 * density);
        root.addView(title, titleLp);

        TextView byline = new TextView(this);
        byline.setText("Mahi Shah");
        byline.setTextColor(Color.rgb(35, 42, 52));
        byline.setTextSize(11);
        byline.setGravity(Gravity.CENTER);
        byline.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        root.addView(byline, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout.LayoutParams spacerMiddle = new LinearLayout.LayoutParams(1, 0, 0.8f);
        root.addView(new View(this), spacerMiddle);

        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setPadding(Math.round(18 * density), Math.round(18 * density),
                Math.round(18 * density), Math.round(16 * density));
        tile.setClickable(true);
        tile.setFocusable(true);
        tile.setBackground(rounded(Color.WHITE, 22 * density, Color.rgb(226, 229, 234), 1 * density));
        tile.setElevation(5 * density);
        tile.setOnClickListener(v -> openKitchen());

        ImageView appIcon = new ImageView(this);
        appIcon.setImageResource(R.drawable.ic_launcher);
        appIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int appIconSize = Math.round(92 * density);
        tile.addView(appIcon, new LinearLayout.LayoutParams(appIconSize, appIconSize));

        TextView appName = new TextView(this);
        appName.setText("Fai Fai Kitchen");
        appName.setTextColor(Color.rgb(20, 28, 38));
        appName.setTextSize(18);
        appName.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        appName.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        nameLp.topMargin = Math.round(10 * density);
        tile.addView(appName, nameLp);

        TextView tapText = new TextView(this);
        tapText.setText("Tap to open");
        tapText.setTextColor(Color.rgb(55, 63, 74));
        tapText.setTextSize(11);
        tapText.setGravity(Gravity.CENTER);
        tapText.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tile.addView(tapText, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout.LayoutParams tileLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        tileLp.leftMargin = Math.round(22 * density);
        tileLp.rightMargin = Math.round(22 * density);
        root.addView(tile, tileLp);

        LinearLayout.LayoutParams spacerBottom = new LinearLayout.LayoutParams(1, 0, 1.9f);
        root.addView(new View(this), spacerBottom);

        networkStatus = new TextView(this);
        networkStatus.setTextSize(12);
        networkStatus.setTextColor(Color.rgb(35, 42, 52));
        networkStatus.setGravity(Gravity.CENTER);
        networkStatus.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        networkStatus.setPadding(0, Math.round(4 * density), 0, Math.round(8 * density));
        root.addView(networkStatus, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        TextView adminHint = new TextView(this);
        adminHint.setText("Admin");
        adminHint.setTextColor(Color.rgb(180, 184, 191));
        adminHint.setTextSize(9);
        adminHint.setGravity(Gravity.CENTER);
        adminHint.setPadding(Math.round(20 * density), Math.round(8 * density),
                Math.round(20 * density), Math.round(10 * density));
        adminHint.setOnLongClickListener(v -> {
            showAdminExitDialog();
            return true;
        });
        root.addView(adminHint, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        updateNetworkStatus();
        return root;
    }

    private GradientDrawable rounded(int fill, float radius, int stroke, float strokeWidth) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(fill);
        bg.setCornerRadius(radius);
        bg.setStroke(Math.max(1, Math.round(strokeWidth)), stroke);
        return bg;
    }

    private void openKitchen() {
        KioskManager.ensureWifiReady(this);
        Intent intent = new Intent(this, KitchenActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
    }

    private boolean isOnline() {
        try {
            ConnectivityManager manager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (manager == null) return false;
            Network network = manager.getActiveNetwork();
            if (network == null) return false;
            NetworkCapabilities caps = manager.getNetworkCapabilities(network);
            return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Exception ignored) {
            return false;
        }
    }

    private void updateNetworkStatus() {
        if (networkStatus == null) return;
        networkStatus.setText(isOnline() ? "Wi-Fi connected" : "Connecting Wi-Fi...");
    }

    private String currentAdminExitPin() {
        String saved = getSharedPreferences("fai_fai_kitchen", MODE_PRIVATE)
                .getString("pin", "");
        if (saved != null && saved.trim().length() >= 4) return saved.trim();
        return "2468";
    }

    private void showAdminExitDialog() {
        if (!KioskManager.isDeviceOwner(this)) {
            Toast.makeText(this, "Kiosk setup is not activated on this device", Toast.LENGTH_LONG).show();
            return;
        }

        EditText input = new EditText(this);
        input.setHint("Admin PIN");
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Admin unlock")
                .setMessage("Enter Admin/Kitchen PIN to show Android apps and Settings")
                .setView(input)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Unlock", null)
                .create();

        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String entered = input.getText() == null ? "" : input.getText().toString().trim();
                    if (!entered.equals(currentAdminExitPin())) {
                        input.setError("Wrong PIN");
                        return;
                    }
                    dialog.dismiss();
                    KioskManager.exitForAdmin(MainActivity.this);
                }));
        dialog.show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        KioskManager.ensureWifiReady(this);
        KioskManager.enter(this);
        handler.removeCallbacks(wifiKeepAlive);
        handler.post(wifiKeepAlive);

        String pin = getSharedPreferences("fai_fai_kitchen", Context.MODE_PRIVATE)
                .getString("pin", "");
        if (pin != null && pin.trim().length() >= 4) {
            Intent service = new Intent(this, KitchenOrderService.class);
            service.setAction(KitchenOrderService.ACTION_START);
            try {
                if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(service);
                else startService(service);
            } catch (Exception ignored) { }
        }
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(wifiKeepAlive);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.getRepeatCount() == 0) {
                backLongPressHandled = false;
                event.startTracking();
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            backLongPressHandled = true;
            showAdminExitDialog();
            return true;
        }
        return super.onKeyLongPress(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            backLongPressHandled = false;
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }
}
