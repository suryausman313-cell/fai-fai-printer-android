package com.faifai.printer;

import android.app.*;
import android.content.*;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.ToneGenerator;
import android.os.*;
import android.speech.tts.TextToSpeech;
import android.util.Base64;

import androidx.core.app.NotificationCompat;

import org.json.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

public class KitchenOrderService extends Service {
    public static final String ACTION_START = "com.faifai.printer.START";
    public static final String ACTION_STOP_ALARM = "com.faifai.printer.STOP_ALARM";

    private static final String CHANNEL_ACTIVE = "kitchen_active";
    private static final String CHANNEL_ORDER = "kitchen_admin_ring_v1";
    private static final String CHANNEL_LATE = "kitchen_late_order_v1";

    private static final String API =
            "https://vita-napoli-backend-usman.onrender.com/api/v1/kitchen/orders?status=all&limit=80";
    private static final String SETTINGS_API =
            "https://vita-napoli-backend-usman.onrender.com/api/v1/receipt-settings";

    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Set<String> lateNoticeShown = Collections.synchronizedSet(new HashSet<>());

    private MediaPlayer alarm;
    private TextToSpeech textToSpeech;
    private volatile boolean ttsReady = false;
    private boolean adminAlarmEnabled = true;
    private String adminAlarmAudio = "";
    private long lastSettingsCheck = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannels();
        startForeground(41, activeNotification());
        initTextToSpeech();
        worker.scheduleWithFixedDelay(this::poll, 0, 10, TimeUnit.SECONDS);
    }

    private void createChannels() {
        NotificationManager nm = getSystemService(NotificationManager.class);

        NotificationChannel active = new NotificationChannel(
                CHANNEL_ACTIVE,
                "Fai Fai Kitchen background",
                NotificationManager.IMPORTANCE_LOW
        );
        active.setDescription("Keeps Fai Fai Kitchen order monitoring active");
        nm.createNotificationChannel(active);

        NotificationChannel orders = new NotificationChannel(
                CHANNEL_ORDER,
                "New kitchen orders",
                NotificationManager.IMPORTANCE_HIGH
        );
        orders.enableVibration(true);
        // Admin-selected audio is played by MediaPlayer so the sound can change
        // without recreating an Android notification channel.
        orders.setSound(null, null);
        nm.createNotificationChannel(orders);

        NotificationChannel late = new NotificationChannel(
                CHANNEL_LATE,
                "Late kitchen orders",
                NotificationManager.IMPORTANCE_HIGH
        );
        late.enableVibration(true);
        late.setSound(null, null);
        nm.createNotificationChannel(late);
    }

    private void initTextToSpeech() {
        textToSpeech = new TextToSpeech(getApplicationContext(), status -> {
            if (status == TextToSpeech.SUCCESS && textToSpeech != null) {
                textToSpeech.setLanguage(Locale.US);
                textToSpeech.setSpeechRate(0.92f);
                ttsReady = true;
            }
        });
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("fai_fai_kitchen", MODE_PRIVATE);
    }

    private boolean localSoundEnabled() {
        return prefs().getBoolean("sound", true);
    }

    private boolean appIsForeground() {
        return prefs().getBoolean("app_foreground", false);
    }

    private Notification activeNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ACTIVE)
                .setSmallIcon(android.R.drawable.ic_menu_agenda)
                .setContentTitle("Fai Fai Kitchen active")
                .setContentText("Orders are being monitored in background")
                .setContentIntent(openKitchen())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    private PendingIntent openKitchen() {
        Intent i = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(
                this,
                1,
                i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private void poll() {
        refreshAdminAlarm();

        String pin = prefs().getString("pin", "");
        if (pin == null || pin.trim().length() < 4) {
            stopNewOrderAlarm();
            return;
        }

        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(API).openConnection();
            connection.setConnectTimeout(12000);
            connection.setReadTimeout(16000);
            connection.setRequestProperty("X-Kitchen-Pin", pin.trim());

            if (connection.getResponseCode() != 200) {
                stopNewOrderAlarm();
                return;
            }

            String body = read(connection.getInputStream());
            JSONArray items = new JSONObject(body).optJSONArray("items");
            if (items == null) {
                stopNewOrderAlarm();
                return;
            }

            int newCount = 0;
            long now = System.currentTimeMillis();

            for (int index = 0; index < items.length(); index++) {
                JSONObject order = items.optJSONObject(index);
                if (order == null) continue;

                String status = normalizeStatus(order.optString("status", "new"));
                if ("new".equals(status)) {
                    newCount++;
                }

                int orderId = order.optInt("id", 0);
                if (orderId <= 0) continue;

                if ("accepted".equals(status) || "preparing".equals(status)) {
                    long deadline = readyDeadlineMillis(order);
                    if (deadline > 0 && now >= deadline) {
                        notifyLateOrder(orderId, deadline);
                    }
                } else {
                    getSystemService(NotificationManager.class).cancel(lateNotificationId(orderId));
                }
            }

            if (newCount > 0) {
                notifyNewOrder(newCount);
            } else {
                stopNewOrderAlarm();
            }
        } catch (Exception ignored) {
            // Keep the service alive across temporary network problems.
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private String normalizeStatus(String value) {
        String status = value == null ? "new" : value.trim().toLowerCase(Locale.US);
        if (status.equals("pending") || status.equals("placed") || status.equals("created")
                || status.equals("order_placed")) {
            return "new";
        }
        return status;
    }

    private long readyDeadlineMillis(JSONObject order) {
        String exact = order.optString("promised_ready_at", "").trim();
        if (!exact.isEmpty()) {
            long parsed = parseIsoMillis(exact);
            if (parsed > 0) return parsed;
        }

        String estimated = order.optString("estimated_time", "");
        int divider = estimated.indexOf('|');
        if (divider >= 0 && divider + 1 < estimated.length()) {
            return parseIsoMillis(estimated.substring(divider + 1).trim());
        }
        return 0;
    }

    private long parseIsoMillis(String raw) {
        try {
            String value = raw == null ? "" : raw.trim();
            if (value.isEmpty()) return 0;

            if (value.endsWith("Z")) {
                value = value.substring(0, value.length() - 1) + "+00:00";
            }

            int t = value.indexOf('T');
            if (t < 0) return 0;

            int zone = -1;
            for (int i = t + 1; i < value.length(); i++) {
                char ch = value.charAt(i);
                if (ch == '+' || ch == '-') zone = i;
            }

            String timePart = zone > 0 ? value.substring(0, zone) : value;
            String zonePart = zone > 0 ? value.substring(zone) : "+00:00";

            int dot = timePart.indexOf('.');
            if (dot < 0) {
                timePart = timePart + ".000";
            } else {
                String base = timePart.substring(0, dot);
                String fraction = timePart.substring(dot + 1).replaceAll("[^0-9]", "");
                if (fraction.length() > 3) fraction = fraction.substring(0, 3);
                while (fraction.length() < 3) fraction += "0";
                timePart = base + "." + fraction;
            }

            SimpleDateFormat format = new SimpleDateFormat(
                    "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
                    Locale.US
            );
            Date parsed = format.parse(timePart + zonePart);
            return parsed == null ? 0 : parsed.getTime();
        } catch (Exception ignored) {
            return 0;
        }
    }

    private void refreshAdminAlarm() {
        long now = System.currentTimeMillis();
        if (now - lastSettingsCheck < 60000) return;
        lastSettingsCheck = now;

        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(SETTINGS_API).openConnection();
            connection.setConnectTimeout(12000);
            connection.setReadTimeout(20000);
            if (connection.getResponseCode() != 200) return;

            JSONObject settings = new JSONObject(read(connection.getInputStream()));
            adminAlarmEnabled = settings.optBoolean("kitchen_alarm_enabled", true);
            adminAlarmAudio = settings.optString("kitchen_alarm_audio", "");
            if (!adminAlarmEnabled || !localSoundEnabled()) {
                stopNewOrderAlarm();
            }
        } catch (Exception ignored) {
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[2048];
        int count;
        while ((count = in.read(buffer)) >= 0) {
            out.write(buffer, 0, count);
        }
        return out.toString(StandardCharsets.UTF_8.name());
    }

    private synchronized void notifyNewOrder(int count) {
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ORDER)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("New Kitchen Order")
                .setContentText(count + " order(s) waiting — open Fai Fai Kitchen")
                .setContentIntent(openKitchen())
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setOngoing(true)
                .build();

        getSystemService(NotificationManager.class).notify(42, notification);

        if (!localSoundEnabled() || !adminAlarmEnabled || adminAlarmAudio.isEmpty() || alarm != null) {
            return;
        }

        try {
            File audio = adminAudioFile(adminAlarmAudio, "kitchen_admin_ring");
            alarm = new MediaPlayer();
            alarm.setDataSource(audio.getAbsolutePath());
            alarm.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .build());
            alarm.setLooping(true);
            alarm.prepare();
            alarm.start();
        } catch (Exception ignored) {
            stopNewOrderAlarm();
        }
    }

    private void notifyLateOrder(int orderId, long deadline) {
        String key = orderId + ":" + deadline;
        if (!lateNoticeShown.add(key)) return;

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_LATE)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("Order #" + orderId + " is late")
                .setContentText("Time is finished. Please make order #" + orderId + " ready.")
                .setContentIntent(openKitchen())
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(false)
                .build();
        getSystemService(NotificationManager.class)
                .notify(lateNotificationId(orderId), notification);

        // Always let the native Android service speak the late warning.
        // NETUM/P58 WebView speech can be blocked even while the Kitchen page
        // is visible, so foreground mode must not suppress the native voice.
        if (!localSoundEnabled()) {
            return;
        }

        if (!ttsReady || textToSpeech == null) {
            // Let the next poll retry once Android's TTS engine is ready.
            lateNoticeShown.remove(key);
            return;
        }

        mainHandler.post(() -> {
            try {
                ToneGenerator tone = new ToneGenerator(AudioManager.STREAM_ALARM, 90);
                tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 650);
                mainHandler.postDelayed(tone::release, 900);
            } catch (Exception ignored) { }

            mainHandler.postDelayed(() -> {
                if (textToSpeech == null) return;
                String message = "Order " + orderId + " late. Please ready.";
                int result = textToSpeech.speak(
                        message,
                        TextToSpeech.QUEUE_FLUSH,
                        null,
                        "late-order-" + orderId
                );
                if (result == TextToSpeech.ERROR) {
                    lateNoticeShown.remove(key);
                }
            }, 700);
        });
    }

    private int lateNotificationId(int orderId) {
        return 5000 + Math.abs(orderId % 3000);
    }

    private File adminAudioFile(String dataUrl, String name) throws IOException {
        int comma = dataUrl.indexOf(',');
        if (!dataUrl.startsWith("data:audio/") || comma < 0) {
            throw new IOException("Invalid Admin ring");
        }

        byte[] bytes = Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT);
        File file = new File(getCacheDir(), name + ".audio");
        try (FileOutputStream out = new FileOutputStream(file, false)) {
            out.write(bytes);
        }
        return file;
    }

    private synchronized void stopNewOrderAlarm() {
        getSystemService(NotificationManager.class).cancel(42);
        if (alarm != null) {
            try {
                alarm.stop();
            } catch (Exception ignored) { }
            alarm.release();
            alarm = null;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP_ALARM.equals(intent.getAction())) {
            stopNewOrderAlarm();
            return START_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        prefs().edit().putBoolean("app_foreground", false).apply();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        worker.shutdownNow();
        stopNewOrderAlarm();
        if (textToSpeech != null) {
            try {
                textToSpeech.stop();
                textToSpeech.shutdown();
            } catch (Exception ignored) { }
            textToSpeech = null;
        }
        super.onDestroy();
    }

    @Override
    public android.os.IBinder onBind(Intent intent) {
        return null;
    }
}
