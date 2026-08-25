package com.faifai.printer;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.content.Intent;
import android.Manifest;
import android.content.pm.PackageManager;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String KITCHEN_URL = "https://fai-fai-juice.pages.dev/kitchen";

    private final ExecutorService printerExecutor = Executors.newSingleThreadExecutor();
    private WebView webView;
    private final Handler syncHandler = new Handler(Looper.getMainLooper());
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
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 54);
        }
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
        // Always load the latest live Kitchen frontend after an app/device restart.
        // This bypasses stale WebView HTML/JS cache without clearing login/localStorage.
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setUserAgentString(settings.getUserAgentString() + " FaiFaiPrinter/1.5");
        webView.clearCache(true);

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
        // Cache-busting query makes a new deploy visible immediately after restart.
        // /kitchen still remains the same route and localStorage login is preserved.
        webView.loadUrl(KITCHEN_URL + "?android_kitchen=" + System.currentTimeMillis());
    }

    private void handleKitchenBack() {
        if (webView == null) return;
        // Kitchen is a React single-page screen. Order details are local UI state,
        // not separate WebView history entries, so WebView.goBack() can exit the app.
        // Let the live Kitchen page close the current order/drawer/history view instead.
        webView.evaluateJavascript(
                "(function(){try{window.dispatchEvent(new Event('fai-fai-kitchen-back'));return true;}catch(e){return false;}})()",
                null
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

    public final class PrinterBridge {
        @JavascriptInterface
        public void configureKitchen(String rawJson) {
            String json = rawJson == null ? "" : rawJson;
            if (json.startsWith("\"") && json.endsWith("\"")) {
                json = json.substring(1, json.length() - 1)
                        .replace("\\\"", "\"").replace("\\\\", "\\");
            }
            try {
                org.json.JSONObject data = new org.json.JSONObject(json);
                String pin = data.optString("pin", "").trim();
                boolean sound = data.optBoolean("sound", true);
                getSharedPreferences("fai_fai_kitchen", MODE_PRIVATE).edit()
                        .putString("pin", pin).putBoolean("sound", sound).apply();
                if (pin.length() >= 4) {
                    Intent service = new Intent(MainActivity.this, KitchenOrderService.class);
                    service.setAction(KitchenOrderService.ACTION_START);
                    startForegroundService(service);
                }
            } catch (Exception ignored) { }
        }

        @JavascriptInterface
        public void stopOrderAlarm() {
            Intent service = new Intent(MainActivity.this, KitchenOrderService.class);
            service.setAction(KitchenOrderService.ACTION_STOP_ALARM);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
            else startService(service);
        }

        @JavascriptInterface
        public boolean isAvailable() {
            return true;
        }

        @JavascriptInterface
        public boolean handlesLateAlerts() {
            // KitchenOrderService polls the backend and owns late voice so
            // late voice alerts keep working even when this WebView is backgrounded.
            return true;
        }

        @JavascriptInterface
        public String printReceipt(String payloadJson) {
            if (payloadJson == null || payloadJson.trim().isEmpty()) {
                return "error: empty receipt";
            }

            printerExecutor.execute(() -> {
                try {
                    NetworkReceiptPrinter.print(MainActivity.this, payloadJson);
                    showToast("Receipt printed");
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
