package com.faifai.printer;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Live Fai Fai Kitchen WebView + printer bridge. */
public class KitchenActivity extends Activity {
    private static final String KITCHEN_URL = "https://fai-fai-juice.pages.dev/kitchen";
    private static final long RETRY_DELAY_MS = 4000L;

    private final ExecutorService printerExecutor = Executors.newSingleThreadExecutor();
    private IposBuiltInPrinter builtInPrinter;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Handler syncHandler = new Handler(Looper.getMainLooper());

    private WebView webView;
    private TextView loadingView;
    private boolean mainFrameFailed = false;

    private final Runnable retryKitchen = new Runnable() {
        @Override
        public void run() {
            loadKitchenFresh();
        }
    };

    private final Runnable syncKitchen = new Runnable() {
        @Override
        public void run() {
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
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        builtInPrinter = new IposBuiltInPrinter(this);
        builtInPrinter.bind();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(2, 8, 23));

        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(2, 8, 23));
        webView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        loadingView = new TextView(this);
        loadingView.setText("FAI FAI KITCHEN\nConnecting...");
        loadingView.setTextColor(Color.WHITE);
        loadingView.setTextSize(20f);
        loadingView.setGravity(Gravity.CENTER);
        loadingView.setBackgroundColor(Color.rgb(2, 8, 23));
        loadingView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        root.addView(webView);
        root.addView(loadingView);
        setContentView(root);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setUserAgentString(settings.getUserAgentString() + " FaiFaiPrinter/2.0");

        // Clear stale HTML/JS only. DOM storage (including kitchen login) is preserved.
        webView.clearCache(true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                if (url != null && url.startsWith(KITCHEN_URL)) {
                    mainFrameFailed = false;
                    showConnecting("FAI FAI KITCHEN\nLoading...");
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (url != null && url.startsWith(KITCHEN_URL) && !mainFrameFailed) {
                    mainHandler.removeCallbacks(retryKitchen);
                    hideConnecting();
                    syncHandler.removeCallbacks(syncKitchen);
                    syncHandler.post(syncKitchen);
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request != null && request.isForMainFrame()) {
                    mainFrameFailed = true;
                    showConnecting("FAI FAI KITCHEN\nWaiting for internet...");
                    scheduleRetry();
                }
            }

            @SuppressWarnings("deprecation")
            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                super.onReceivedError(view, errorCode, description, failingUrl);
                mainFrameFailed = true;
                showConnecting("FAI FAI KITCHEN\nWaiting for internet...");
                scheduleRetry();
            }
        });

        webView.setWebChromeClient(new WebChromeClient());
        webView.addJavascriptInterface(new PrinterBridge(), "VitaPrinter");

        startKitchenService();
        loadKitchenFresh();
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

    private void loadKitchenFresh() {
        if (webView == null) return;
        mainHandler.removeCallbacks(retryKitchen);
        mainFrameFailed = false;
        showConnecting("FAI FAI KITCHEN\nLoading...");

        Map<String, String> headers = new HashMap<>();
        headers.put("Cache-Control", "no-cache, no-store, must-revalidate");
        headers.put("Pragma", "no-cache");
        headers.put("Expires", "0");

        webView.loadUrl(
                KITCHEN_URL + "?android_kitchen=" + System.currentTimeMillis(),
                headers
        );
    }

    private void scheduleRetry() {
        mainHandler.removeCallbacks(retryKitchen);
        mainHandler.postDelayed(retryKitchen, RETRY_DELAY_MS);
    }

    private void showConnecting(String text) {
        if (loadingView == null) return;
        loadingView.setText(text);
        loadingView.setVisibility(View.VISIBLE);
        loadingView.bringToFront();
    }

    private void hideConnecting() {
        if (loadingView != null) loadingView.setVisibility(View.GONE);
    }

    /**
     * Back order:
     * order/dialog/menu/history -> Kitchen handles it;
     * real Live/No orders root -> finish KitchenActivity -> native Tap-to-open screen.
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

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        handleKitchenBack();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            handleKitchenBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        startKitchenService();
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        syncHandler.removeCallbacks(syncKitchen);
        printerExecutor.shutdownNow();
        if (builtInPrinter != null) {
            builtInPrinter.unbind();
            builtInPrinter = null;
        }
        if (webView != null) {
            webView.removeJavascriptInterface("VitaPrinter");
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    private void showToast(String message) {
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
    }

    public final class PrinterBridge {
        @JavascriptInterface
        public void configureKitchen(String rawJson) {
            String json = rawJson == null ? "" : rawJson;
            if (json.startsWith("\"") && json.endsWith("\"")) {
                json = json.substring(1, json.length() - 1)
                        .replace("\\\"", "\"").replace("\\\\", "\\");
            }
            try {
                JSONObject data = new JSONObject(json);
                String pin = data.optString("pin", "").trim();
                boolean sound = data.optBoolean("sound", true);
                getSharedPreferences("fai_fai_kitchen", MODE_PRIVATE).edit()
                        .putString("pin", pin)
                        .putBoolean("sound", sound)
                        .apply();
                if (pin.length() >= 4) startKitchenService();
            } catch (Exception ignored) {
            }
        }

        @JavascriptInterface
        public void stopOrderAlarm() {
            Intent service = new Intent(KitchenActivity.this, KitchenOrderService.class);
            service.setAction(KitchenOrderService.ACTION_STOP_ALARM);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
            else startService(service);
        }

        @JavascriptInterface
        public void startOrderAlarm(int count) {
            Intent service = new Intent(KitchenActivity.this, KitchenOrderService.class);
            service.setAction(KitchenOrderService.ACTION_RING_NOW);
            service.putExtra(KitchenOrderService.EXTRA_ORDER_COUNT, Math.max(1, count));
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
            else startService(service);
        }

        @JavascriptInterface
        public boolean isAvailable() {
            return true;
        }

        @JavascriptInterface
        public boolean handlesLateAlerts() {
            return true;
        }

        @JavascriptInterface
        public String getBatteryInfo() {
            try {
                Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                if (battery == null) return "{}";
                int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                int percent = scale > 0 ? Math.round(level * 100f / scale) : level;
                boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                        || status == BatteryManager.BATTERY_STATUS_FULL;
                JSONObject result = new JSONObject();
                result.put("level", percent);
                result.put("charging", charging);
                return result.toString();
            } catch (Exception ignored) {
                return "{}";
            }
        }

        @JavascriptInterface
        public String printReceipt(String payloadJson) {
            if (payloadJson == null || payloadJson.trim().isEmpty()) {
                return "error: empty receipt";
            }

            printerExecutor.execute(() -> {
                try {
                    if (builtInPrinter == null) throw new IllegalStateException("Built-in printer unavailable");
                    builtInPrinter.printReceipt(payloadJson);
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
