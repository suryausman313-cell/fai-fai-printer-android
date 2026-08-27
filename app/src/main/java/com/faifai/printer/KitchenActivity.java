package com.faifai.printer;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.text.InputType;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class KitchenActivity extends Activity {
    private static final String KITCHEN_URL = "https://fai-fai-juice.pages.dev/kitchen";
    private static final int PERMISSION_REQUEST = 54;

    private final ExecutorService printerExecutor = Executors.newSingleThreadExecutor();
    private IposBuiltInPrinter builtInPrinter;
    private WebView webView;
    private TextView connectionStatus;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private final Handler syncHandler = new Handler(Looper.getMainLooper());
    private final Handler webHandler = new Handler(Looper.getMainLooper());
    private boolean backLongPressHandled = false;
    private boolean mainFrameFailed = false;
    private boolean kitchenPageReady = false;
    private boolean destroyed = false;
    private TextToSpeech textToSpeech;
    private volatile boolean textToSpeechReady = false;

    private final Runnable retryKitchenPage = new Runnable() {
        @Override public void run() {
            if (destroyed || webView == null || kitchenPageReady) return;
            if (!isNetworkOnline()) {
                showConnectionStatus("No internet. Reconnecting...");
                scheduleKitchenRetry(5000);
                return;
            }
            showConnectionStatus("Connecting to Fai Fai Kitchen...");
            mainFrameFailed = false;
            webView.stopLoading();
            webView.loadUrl(KITCHEN_URL);
        }
    };

    private final Runnable syncKitchen = new Runnable() {
        @Override public void run() {
            if (webView != null) {
                webView.evaluateJavascript(
                        "(function(){try{return JSON.stringify({pin:localStorage.getItem('kitchen_pin')||'',sound:localStorage.getItem('kitchen_sound')!=='false',api:localStorage.getItem('fai_fai_api_base_url')||''});}catch(e){return '{}';}})()",
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
        KioskManager.ensureWifiReady(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        builtInPrinter = new IposBuiltInPrinter(this);
        builtInPrinter.bind();

        // Native Kitchen voice. The web page already calls VitaPrinter.speakText()
        // for late/ready announcements. Keeping TTS native makes the orange POS
        // device speak the same order-number message after the existing single Kitchen alert tone.
        textToSpeech = new TextToSpeech(getApplicationContext(), status -> {
            if (status == TextToSpeech.SUCCESS && textToSpeech != null) {
                int result = textToSpeech.setLanguage(Locale.US);
                textToSpeech.setSpeechRate(0.90f);
                textToSpeech.setPitch(1.0f);
                textToSpeechReady = result != TextToSpeech.LANG_MISSING_DATA
                        && result != TextToSpeech.LANG_NOT_SUPPORTED;
            }
        });

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
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        // NETUM/P58 has a narrow POS screen. Keep the Kitchen page readable but
        // slightly more compact without changing the public web app on other devices.
        float widthDp = getResources().getDisplayMetrics().widthPixels
                / getResources().getDisplayMetrics().density;
        // Keep the Q2I Kitchen only slightly compact. This changes the device
        // WebView UI only; receipt bitmap/font sizes are completely unaffected.
        settings.setTextZoom(95);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);

        settings.setUserAgentString(
                settings.getUserAgentString() + " FaiFaiKitchen/1.21.9"
        );

        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                if (url != null && url.contains("fai-fai-juice.pages.dev")) {
                    kitchenPageReady = false;
                    mainFrameFailed = false;
                    showConnectionStatus("Loading Fai Fai Kitchen...");
                }
            }

            @Override public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (!mainFrameFailed && url != null && url.contains("fai-fai-juice.pages.dev")) {
                    kitchenPageReady = true;
                    webHandler.removeCallbacks(retryKitchenPage);
                    hideConnectionStatus();
                    syncHandler.removeCallbacks(syncKitchen);
                    syncHandler.post(syncKitchen);
                }
            }

            @Override public void onReceivedError(
                    WebView view,
                    WebResourceRequest request,
                    WebResourceError error
            ) {
                super.onReceivedError(view, request, error);
                if (request != null && request.isForMainFrame()) {
                    mainFrameFailed = true;
                    kitchenPageReady = false;
                    String message = isNetworkOnline()
                            ? "Kitchen page did not load. Retrying..."
                            : "No internet. Reconnecting...";
                    showConnectionStatus(message);
                    scheduleKitchenRetry(3000);
                }
            }

            @Override public void onReceivedHttpError(
                    WebView view,
                    WebResourceRequest request,
                    WebResourceResponse errorResponse
            ) {
                super.onReceivedHttpError(view, request, errorResponse);
                if (request != null && request.isForMainFrame() && errorResponse != null) {
                    mainFrameFailed = true;
                    kitchenPageReady = false;
                    showConnectionStatus("Kitchen server error "
                            + errorResponse.getStatusCode() + ". Retrying...");
                    scheduleKitchenRetry(5000);
                }
            }

            @Override public boolean onRenderProcessGone(
                    WebView view,
                    RenderProcessGoneDetail detail
            ) {
                showConnectionStatus("Kitchen display restarting...");
                webHandler.postDelayed(() -> {
                    if (!destroyed) recreate();
                }, 800);
                return true;
            }
        });
        webView.setWebChromeClient(new WebChromeClient());
        webView.addJavascriptInterface(new PrinterBridge(), "VitaPrinter");

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(2, 8, 23));
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        connectionStatus = new TextView(this);
        connectionStatus.setTextColor(Color.WHITE);
        connectionStatus.setBackgroundColor(Color.rgb(2, 8, 23));
        connectionStatus.setGravity(Gravity.CENTER);
        connectionStatus.setTextSize(widthDp <= 600f ? 16f : 18f);
        connectionStatus.setPadding(24, 24, 24, 24);
        root.addView(connectionStatus, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        showConnectionStatus("Connecting to Fai Fai Kitchen...");

        setContentView(root);
        registerNetworkRecovery();

        // Full dedicated-device kiosk is enabled when this app is provisioned
        // as Android Device Owner. Normal app operation is unchanged otherwise.
        KioskManager.applyPolicies(this);
        if (isNetworkOnline()) {
            webView.loadUrl(KITCHEN_URL);
        } else {
            showConnectionStatus("No internet. Reconnecting...");
            scheduleKitchenRetry(3000);
        }
    }

    private void showConnectionStatus(String message) {
        runOnUiThread(() -> {
            if (connectionStatus == null) return;
            connectionStatus.setText(message == null ? "Connecting..." : message);
            connectionStatus.setVisibility(View.VISIBLE);
            connectionStatus.bringToFront();
        });
    }

    private void hideConnectionStatus() {
        runOnUiThread(() -> {
            if (connectionStatus != null) connectionStatus.setVisibility(View.GONE);
        });
    }

    private boolean isNetworkOnline() {
        try {
            ConnectivityManager manager = connectivityManager != null
                    ? connectivityManager
                    : (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (manager == null) return false;
            Network network = manager.getActiveNetwork();
            if (network == null) return false;
            NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
            return capabilities != null
                    && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Exception ignored) {
            return false;
        }
    }

    private void scheduleKitchenRetry(long delayMs) {
        webHandler.removeCallbacks(retryKitchenPage);
        webHandler.postDelayed(retryKitchenPage, Math.max(500L, delayMs));
    }

    private void registerNetworkRecovery() {
        connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (connectivityManager == null || networkCallback != null) return;

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                webHandler.post(() -> {
                    if (!destroyed && !kitchenPageReady) {
                        showConnectionStatus("Internet connected. Loading Kitchen...");
                        scheduleKitchenRetry(250);
                    }
                });
            }

            @Override public void onLost(Network network) {
                webHandler.post(() -> {
                    if (!destroyed && !isNetworkOnline()) {
                        kitchenPageReady = false;
                        showConnectionStatus("No internet. Reconnecting...");
                        scheduleKitchenRetry(3000);
                    }
                });
            }
        };

        try {
            connectivityManager.registerDefaultNetworkCallback(networkCallback);
        } catch (Exception ignored) {
            networkCallback = null;
        }
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

    /**
     * Physical Back follows the Kitchen UI first. The web page returns true
     * when it closed an order/detail/dialog/history view. Only at the real Live
     * root do we finish KitchenActivity and return to the Fai Fai home screen.
     */
    private void handleKitchenBack() {
        if (webView == null) {
            finish();
            return;
        }

        webView.evaluateJavascript(
                "(function(){try{return (typeof window.faiFaiKitchenBack==='function') ? !!window.faiFaiKitchenBack() : false;}catch(e){return false;}})()",
                value -> {
                    boolean handled = "true".equalsIgnoreCase(String.valueOf(value));
                    if (!handled) finish();
                }
        );
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.getRepeatCount() == 0) event.startTracking();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (!event.isCanceled()) handleKitchenBack();
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        handleKitchenBack();
    }

    private String currentAdminExitPin() {
        String saved = getSharedPreferences("fai_fai_kitchen", MODE_PRIVATE)
                .getString("pin", "");
        if (saved != null && saved.trim().length() >= 4) {
            return saved.trim();
        }
        // Never use a hard-coded fallback PIN. The current Kitchen PIN must
        // first be synced from the authenticated Kitchen web page.
        return "";
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
                    String expectedPin = currentAdminExitPin();
                    if (expectedPin.length() < 4) {
                        input.setError("Kitchen PIN not synced yet");
                        return;
                    }
                    if (!entered.equals(expectedPin)) {
                        input.setError("Wrong PIN");
                        return;
                    }
                    dialog.dismiss();
                    KioskManager.exitForAdmin(KitchenActivity.this);
                    showToast("Admin mode unlocked. Open Fai Fai Kitchen to lock again.");
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
            // If the background service started ringing before the WebView became
            // visible, silence it now. The web page will ring again only after the
            // New order card has actually been painted on screen.
            Intent stopEarlyAlarm = new Intent(this, KitchenOrderService.class);
            stopEarlyAlarm.setAction(KitchenOrderService.ACTION_STOP_ALARM);
            startKitchenService(stopEarlyAlarm);

            Intent service = new Intent(this, KitchenOrderService.class);
            service.setAction(KitchenOrderService.ACTION_START);
            startKitchenService(service);
        }

        // Saved Wi-Fi should reconnect automatically after boot. Device Owner
        // is also allowed to turn Wi-Fi back on if staff switched it off.
        KioskManager.ensureWifiReady(this);

        // Keep the device inside the Fai Fai launcher/Kitchen experience.
        KioskManager.enter(this);

        // If the device booted before Wi-Fi was ready, immediately recover the
        // WebView once connectivity exists instead of leaving a black screen.
        if (!kitchenPageReady) {
            scheduleKitchenRetry(isNetworkOnline() ? 150 : 2000);
        }
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
        destroyed = true;
        syncHandler.removeCallbacks(syncKitchen);
        webHandler.removeCallbacksAndMessages(null);
        if (connectivityManager != null && networkCallback != null) {
            try { connectivityManager.unregisterNetworkCallback(networkCallback); } catch (Exception ignored) { }
            networkCallback = null;
        }
        if (textToSpeech != null) {
            try { textToSpeech.stop(); } catch (Exception ignored) { }
            try { textToSpeech.shutdown(); } catch (Exception ignored) { }
            textToSpeech = null;
            textToSpeechReady = false;
        }
        if (builtInPrinter != null) {
            try { builtInPrinter.unbind(); } catch (Exception ignored) { }
            builtInPrinter = null;
        }
        printerExecutor.shutdownNow();
        if (webView != null) {
            webView.removeJavascriptInterface("VitaPrinter");
            webView.stopLoading();
            webView.destroy();
        }
        super.onDestroy();
    }

    private void speakNative(String message, int attempt) {
        if (destroyed || message == null || message.trim().isEmpty()) return;

        if (textToSpeech != null && textToSpeechReady) {
            try {
                textToSpeech.stop();
                textToSpeech.speak(
                        message,
                        TextToSpeech.QUEUE_FLUSH,
                        null,
                        "fai_fai_kitchen_" + System.currentTimeMillis()
                );
                return;
            } catch (Exception ignored) { }
        }

        // TTS initialization can take a moment after boot/app launch. Retry
        // briefly so the first late/ready announcement is not lost.
        if (attempt < 4) {
            webHandler.postDelayed(() -> speakNative(message, attempt + 1), 500L);
        }
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
                String apiBase = data.optString("api", "").trim();

                android.content.SharedPreferences.Editor editor =
                        getSharedPreferences("fai_fai_kitchen", MODE_PRIVATE)
                                .edit()
                                .putString("pin", pin)
                                .putBoolean("sound", sound);
                if (apiBase.startsWith("https://")) {
                    editor.putString("api_base_url", apiBase.replaceAll("/+$", ""));
                }
                editor.apply();

                if (pin.length() >= 4) {
                    Intent service = new Intent(
                            KitchenActivity.this,
                            KitchenOrderService.class
                    );
                    service.setAction(KitchenOrderService.ACTION_START);
                    startKitchenService(service);
                }
            } catch (Exception ignored) { }
        }

        @JavascriptInterface
        public void startOrderAlarm(int count) {
            Intent service = new Intent(KitchenActivity.this, KitchenOrderService.class);
            service.setAction(KitchenOrderService.ACTION_RING_NOW);
            service.putExtra(KitchenOrderService.EXTRA_ORDER_COUNT, Math.max(1, count));
            startKitchenService(service);
        }

        @JavascriptInterface
        public void stopOrderAlarm() {
            Intent service = new Intent(KitchenActivity.this, KitchenOrderService.class);
            service.setAction(KitchenOrderService.ACTION_STOP_ALARM);
            startKitchenService(service);
        }

        @JavascriptInterface
        public void speakText(String text) {
            final String message = text == null ? "" : text.trim();
            if (message.isEmpty()) return;

            runOnUiThread(() -> speakNative(message, 0));
        }

        @JavascriptInterface
        public String getBatteryInfo() {
            try {
                Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                if (battery == null) return "{\"level\":-1,\"charging\":false}";

                int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                        || status == BatteryManager.BATTERY_STATUS_FULL;
                int percent = (level >= 0 && scale > 0)
                        ? Math.max(0, Math.min(100, Math.round(level * 100f / scale)))
                        : -1;
                return "{\"level\":" + percent + ",\"charging\":" + charging + "}";
            } catch (Exception ignored) {
                return "{\"level\":-1,\"charging\":false}";
            }
        }

        @JavascriptInterface
        public boolean isAvailable() {
            return true;
        }

        @JavascriptInterface
        public String printerStatus(String payloadJson) {
            return PrinterRouter.status(KitchenActivity.this, payloadJson == null ? "{}" : payloadJson);
        }

        @JavascriptInterface
        public String printReceipt(String payloadJson) {
            if (payloadJson == null || payloadJson.trim().isEmpty()) {
                return "error: empty receipt";
            }

            printerExecutor.execute(() -> {
                Exception builtInError = null;
                try {
                    // Q2I built-in iPos printer is the primary path.
                    if (builtInPrinter == null) throw new IllegalStateException("Built-in printer unavailable");
                    builtInPrinter.printReceipt(payloadJson);
                    return;
                } catch (Exception error) {
                    builtInError = error;
                }

                try {
                    // Keep Bluetooth/network routing only as a backup.
                    PrinterRouter.print(KitchenActivity.this, payloadJson);
                } catch (Exception backupError) {
                    String first = builtInError == null ? "" : builtInError.getMessage();
                    String second = backupError.getMessage();
                    if (first == null || first.trim().isEmpty()) first = builtInError == null ? "" : builtInError.getClass().getSimpleName();
                    if (second == null || second.trim().isEmpty()) second = backupError.getClass().getSimpleName();
                    String message = first == null || first.isEmpty() ? second : first + " | backup: " + second;
                    showToast("Print failed: " + message);
                }
            });

            return "queued";
        }
    }
}
