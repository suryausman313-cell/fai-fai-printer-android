package com.faifai.printer;

import android.app.*;
import android.content.*;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.*;
import androidx.core.app.NotificationCompat;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import android.util.Base64;
import java.util.concurrent.*;

public class KitchenOrderService extends Service {
    public static final String ACTION_START = "com.faifai.printer.START";
    public static final String ACTION_STOP_ALARM = "com.faifai.printer.STOP_ALARM";
    public static final String ACTION_RING_NOW = "com.faifai.printer.RING_NOW";
    public static final String EXTRA_ORDER_COUNT = "order_count";
    private static final String CHANNEL_ACTIVE = "kitchen_active";
    private static final String CHANNEL_ORDER = "kitchen_admin_ring_v1";
    private static final String DEFAULT_API_BASE = "https://vita-napoli-backend-usman.onrender.com";
    private static final String ORDERS_PATH = "/api/v1/admin/kitchen/orders?status=new&limit=10";
    private static final String SETTINGS_PATH = "/api/v1/receipt-settings";
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private MediaPlayer alarm;
    private boolean adminAlarmEnabled = true;
    private String adminAlarmAudio = "";
    private long lastSettingsCheck = 0;

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_ACTIVE, "Kitchen service", NotificationManager.IMPORTANCE_LOW));
        NotificationChannel orders = new NotificationChannel(CHANNEL_ORDER, "New kitchen orders", NotificationManager.IMPORTANCE_HIGH);
        orders.enableVibration(true); orders.setSound(null, null); nm.createNotificationChannel(orders);
        startForeground(41, activeNotification());
        worker.scheduleWithFixedDelay(this::poll, 0, 10, TimeUnit.SECONDS);
    }

    private Notification activeNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ACTIVE)
                .setSmallIcon(android.R.drawable.ic_menu_agenda)
                .setContentTitle("Fai Fai Kitchen active")
                .setContentText("New orders are being checked in background")
                .setContentIntent(openKitchen()).setOngoing(true).build();
    }

    private PendingIntent openKitchen() {
        Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(this, 1, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void poll() {
        refreshAdminAlarm();
        android.content.SharedPreferences prefs = getSharedPreferences("fai_fai_kitchen", MODE_PRIVATE);
        String pin = prefs.getString("pin", "");
        if (pin == null || pin.length() < 4) { stopAlarm(); return; }

        // While the Kitchen WebView is visible, it owns the timing of the ring.
        // This prevents the background poll from ringing a few seconds before
        // the New order card is visible on screen.
        if (prefs.getBoolean("app_foreground", false)) return;
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(apiBase(prefs) + ORDERS_PATH).openConnection();
            c.setConnectTimeout(12000); c.setReadTimeout(12000);
            c.setRequestProperty("X-Kitchen-Pin", pin);
            if (c.getResponseCode() != 200) { stopAlarm(); return; }
            String body = read(c.getInputStream());
            JSONArray items = new JSONObject(body).optJSONArray("items");
            boolean hasNew = items != null && items.length() > 0;
            if (hasNew) notifyOrder(items.length()); else stopAlarm();
        } catch (Exception ignored) { } finally { if (c != null) c.disconnect(); }
    }

    private void refreshAdminAlarm() {
        long now = System.currentTimeMillis();
        if (now - lastSettingsCheck < 60000) return;
        lastSettingsCheck = now;
        HttpURLConnection c = null;
        try {
            android.content.SharedPreferences prefs = getSharedPreferences("fai_fai_kitchen", MODE_PRIVATE);
            c = (HttpURLConnection) new URL(apiBase(prefs) + SETTINGS_PATH).openConnection();
            c.setConnectTimeout(12000); c.setReadTimeout(20000);
            if (c.getResponseCode() != 200) return;
            JSONObject settings = new JSONObject(read(c.getInputStream()));
            adminAlarmEnabled = settings.optBoolean("kitchen_alarm_enabled", true);
            adminAlarmAudio = settings.optString("kitchen_alarm_audio", "");
            if (!adminAlarmEnabled) stopAlarm();
        } catch (Exception ignored) { } finally { if (c != null) c.disconnect(); }
    }

    private String apiBase(android.content.SharedPreferences prefs) {
        String value = prefs == null ? "" : prefs.getString("api_base_url", "");
        if (value == null) value = "";
        value = value.trim();
        if (!value.startsWith("https://")) {
            value = DEFAULT_API_BASE;
        }
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    private String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[2048]; int n;
        while ((n = in.read(b)) >= 0) out.write(b, 0, n);
        return out.toString(StandardCharsets.UTF_8.name());
    }

    private synchronized void notifyOrder(int count) {
        Notification n = new NotificationCompat.Builder(this, CHANNEL_ORDER)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("New Kitchen Order")
                .setContentText(count + " order(s) waiting — tap to accept or cancel")
                .setContentIntent(openKitchen()).setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM).setOngoing(true).build();
        getSystemService(NotificationManager.class).notify(42, n);
        if (adminAlarmEnabled && !adminAlarmAudio.isEmpty() && alarm == null) {
            try {
                File audio = adminAudioFile(adminAlarmAudio, "kitchen_admin_ring");
                alarm = new MediaPlayer(); alarm.setDataSource(audio.getAbsolutePath());
                alarm.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build());
                alarm.setLooping(true); alarm.prepare(); alarm.start();
            } catch (Exception ignored) { stopAlarm(); }
        }
    }

    private File adminAudioFile(String dataUrl, String name) throws IOException {
        int comma = dataUrl.indexOf(',');
        if (!dataUrl.startsWith("data:audio/") || comma < 0) throw new IOException("Invalid Admin ring");
        byte[] bytes = Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT);
        File file = new File(getCacheDir(), name + ".audio");
        try (FileOutputStream out = new FileOutputStream(file, false)) { out.write(bytes); }
        return file;
    }

    private synchronized void stopAlarm() {
        getSystemService(NotificationManager.class).cancel(42);
        if (alarm != null) { try { alarm.stop(); } catch (Exception ignored) {} alarm.release(); alarm = null; }
    }
    @Override public int onStartCommand(Intent i, int f, int id) {
        if (i != null && ACTION_STOP_ALARM.equals(i.getAction())) {
            stopAlarm();
            return START_STICKY;
        }
        if (i != null && ACTION_RING_NOW.equals(i.getAction())) {
            final int count = Math.max(1, i.getIntExtra(EXTRA_ORDER_COUNT, 1));
            worker.execute(() -> {
                refreshAdminAlarm();
                notifyOrder(count);
            });
            return START_STICKY;
        }
        return START_STICKY;
    }
    @Override public void onDestroy() { worker.shutdownNow(); stopAlarm(); super.onDestroy(); }
    @Override public android.os.IBinder onBind(Intent intent) { return null; }
}
