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
import java.util.concurrent.*;

public class KitchenOrderService extends Service {
    public static final String ACTION_START = "com.faifai.printer.START";
    private static final String CHANNEL_ACTIVE = "kitchen_active";
    private static final String CHANNEL_ORDER = "kitchen_new_order";
    private static final String API = "https://vita-napoli-backend-usman.onrender.com/api/v1/kitchen/orders?status=new&limit=10";
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private MediaPlayer alarm;

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
        String pin = getSharedPreferences("fai_fai_kitchen", MODE_PRIVATE).getString("pin", "");
        if (pin == null || pin.length() < 4) { stopAlarm(); return; }
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(API).openConnection();
            c.setConnectTimeout(12000); c.setReadTimeout(12000);
            c.setRequestProperty("X-Kitchen-Pin", pin);
            if (c.getResponseCode() != 200) { stopAlarm(); return; }
            String body = read(c.getInputStream());
            JSONArray items = new JSONObject(body).optJSONArray("items");
            boolean hasNew = items != null && items.length() > 0;
            if (hasNew) notifyOrder(items.length()); else stopAlarm();
        } catch (Exception ignored) { } finally { if (c != null) c.disconnect(); }
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
        boolean sound = getSharedPreferences("fai_fai_kitchen", MODE_PRIVATE).getBoolean("sound", true);
        if (sound && alarm == null) {
            try {
                Uri uri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM);
                alarm = new MediaPlayer(); alarm.setDataSource(this, uri);
                alarm.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build());
                alarm.setLooping(true); alarm.prepare(); alarm.start();
            } catch (Exception ignored) { stopAlarm(); }
        }
    }

    private synchronized void stopAlarm() {
        getSystemService(NotificationManager.class).cancel(42);
        if (alarm != null) { try { alarm.stop(); } catch (Exception ignored) {} alarm.release(); alarm = null; }
    }
    @Override public int onStartCommand(Intent i, int f, int id) { return START_STICKY; }
    @Override public void onDestroy() { worker.shutdownNow(); stopAlarm(); super.onDestroy(); }
    @Override public android.os.IBinder onBind(Intent intent) { return null; }
}
