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
        settings.setUserAgentString(settings.getUserAgentString() + " FaiFaiPrinter/1.0");

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
        webView.loadUrl(KITCHEN_URL);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView != null && webView.canGoBack()) {
            webView.goBack();
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
        public boolean isAvailable() {
            return true;
        }

        @JavascriptInterface
        public String printReceipt(String payloadJson) {
            if (payloadJson == null || payloadJson.trim().isEmpty()) {
                return "error: empty receipt";
            }

            printerExecutor.execute(() -> {
                try {
                    NetworkReceiptPrinter.print(payloadJson);
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
