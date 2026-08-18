package com.faifai.printer;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.text.InputType;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String KITCHEN_URL = "https://fai-fai-juice.pages.dev/kitchen";
    private static final int PERMISSION_REQUEST = 54;

    private final ExecutorService printerExecutor = Executors.newSingleThreadExecutor();
    private WebView webView;
    private final Handler syncHandler = new Handler(Looper.getMainLooper());
    private boolean backLongPressHandled = false;

    private final Runnable syncKitchen = new Runnable() {
        @Override public void run() {
            if (webView != null) {
                webView.evaluateJavascript(
                        "(function(){try{return JSON.stringify({pin:localStorage.getItem('kitchen_pin')||'',sound:localStorage.getItem('kitchen_sound')!=='false'});}catch(e){return '{}';}})()",
                        value -> new PrinterBridge().configureKitchen(value)
                );
            }
            syncHandler.postDelayed(this, 5000);
        }
    };

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestRequiredPermissions();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(2, 8, 23));
        webView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);

        // NETUM/P58 has a narrow POS screen. Keep the Kitchen page readable but
        // slightly more compact without changing the public web app on other devices.
        float widthDp = getResources().getDisplayMetrics().widthPixels
                / getResources().getDisplayMetrics().density;
        settings.setTextZoom(widthDp <= 600f ? 75 : 100);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);

        settings.setUserAgentString(
                settings.getUserAgentString() + " FaiFaiKitchen/1.9.1"
        );

        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                syncHandler.removeCallbacks(syncKitchen);
                syncHandler.post(syncKitchen);
            }
        });
        webView.setWebChromeClient(new WebChromeClient());
        webView.addJavascriptInterface(new PrinterBridge(), "VitaPrinter");

        setContentView(webView);

        // Full dedicated-device kiosk is enabled when this app is provisioned
        // as Android Device Owner. Normal app operation is unchanged otherwise.
        KioskManager.applyPolicies(this);
        webView.loadUrl(KITCHEN_URL);
    }

    private void requestRequiredPermissions() {
        if (Build.VERSION.SDK_INT < 31) {
            if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        PERMISSION_REQUEST
                );
            }
            return;
        }

        List<String> missing = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.BLUETOOTH_SCAN);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), PERMISSION_REQUEST);
        }
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
            if (!backLongPressHandled && webView != null && webView.canGoBack()) {
                webView.goBack();
            }
            backLongPressHandled = false;
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    private String currentAdminExitPin() {
        String saved = getSharedPreferences("fai_fai_kitchen", MODE_PRIVATE)
                .getString("pin", "");
        if (saved != null && saved.trim().length() >= 4) {
            return saved.trim();
        }
        // Initial safety fallback matches the current Kitchen PIN. Once the
        // Kitchen page syncs a PIN, that current PIN is used automatically.
        return "2468";
    }

    private void showAdminExitDialog() {
        if (!KioskManager.isDeviceOwner(this)) {
            showToast("Kiosk setup is not activated on this device");
            return;
        }

        final EditText input = new EditText(this);
        input.setHint("Admin PIN");
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Admin unlock")
                .setMessage("Enter Admin/Kitchen PIN to leave kiosk mode")
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
                    showToast("Admin mode unlocked. Restart device to lock again.");
                }));
        dialog.show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Keep the NETUM/Kitchen display awake while Fai Fai Kitchen is visible.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getSharedPreferences("fai_fai_kitchen", MODE_PRIVATE)
                .edit()
                .putBoolean("app_foreground", true)
                .apply();

        String savedPin = getSharedPreferences("fai_fai_kitchen", MODE_PRIVATE)
                .getString("pin", "");
        if (savedPin != null && savedPin.trim().length() >= 4) {
            Intent service = new Intent(this, KitchenOrderService.class);
            service.setAction(KitchenOrderService.ACTION_START);
            startKitchenService(service);
        }

        // Re-enter kiosk whenever Fai Fai Kitchen comes to the foreground.
        KioskManager.enter(this);
    }

    @Override
    protected void onPause() {
        // As soon as the Kitchen app is no longer visible, return to the phone's
        // normal screen-timeout behavior.
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getSharedPreferences("fai_fai_kitchen", MODE_PRIVATE)
                .edit()
                .putBoolean("app_foreground", false)
                .apply();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        syncHandler.removeCallbacks(syncKitchen);
        printerExecutor.shutdownNow();
        if (webView != null) {
            webView.removeJavascriptInterface("VitaPrinter");
            webView.destroy();
        }
        super.onDestroy();
    }

    private void showToast(String message) {
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
    }

    private void startKitchenService(Intent service) {
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(service);
        } else {
            startService(service);
        }
    }

    public final class PrinterBridge {
        @JavascriptInterface
        public void configureKitchen(String rawJson) {
            String json = rawJson == null ? "" : rawJson;
            if (json.startsWith("\"") && json.endsWith("\"")) {
                json = json.substring(1, json.length() - 1)
                        .replace("\\\"", "\"")
                        .replace("\\\\", "\\");
            }

            try {
                org.json.JSONObject data = new org.json.JSONObject(json);
                String pin = data.optString("pin", "").trim();
                boolean sound = data.optBoolean("sound", true);

                getSharedPreferences("fai_fai_kitchen", MODE_PRIVATE)
                        .edit()
                        .putString("pin", pin)
                        .putBoolean("sound", sound)
                        .apply();

                if (pin.length() >= 4) {
                    Intent service = new Intent(
                            MainActivity.this,
                            KitchenOrderService.class
                    );
                    service.setAction(KitchenOrderService.ACTION_START);
                    startKitchenService(service);
                }
            } catch (Exception ignored) { }
        }

        @JavascriptInterface
        public void stopOrderAlarm() {
            Intent service = new Intent(MainActivity.this, KitchenOrderService.class);
            service.setAction(KitchenOrderService.ACTION_STOP_ALARM);
            startKitchenService(service);
        }

        @JavascriptInterface
        public boolean isAvailable() {
            return true;
        }

        @JavascriptInterface
        public String printerStatus(String payloadJson) {
            return PrinterRouter.status(MainActivity.this, payloadJson == null ? "{}" : payloadJson);
        }

        @JavascriptInterface
        public String printReceipt(String payloadJson) {
            if (payloadJson == null || payloadJson.trim().isEmpty()) {
                return "error: empty receipt";
            }

            printerExecutor.execute(() -> {
                try {
                    String route = PrinterRouter.print(MainActivity.this, payloadJson);
                    showToast("Receipt printed - " + route);
                } catch (Exception error) {
                    String message = error.getMessage();
                    if (message == null || message.trim().isEmpty()) {
                        message = error.getClass().getSimpleName();
                    }
                    showToast("Print failed: " + message);
                }
            });

            return "queued";
        }
    }
}
