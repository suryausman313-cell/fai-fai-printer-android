package com.faifai.printer;

import android.app.*;
import android.content.*;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
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
    public static final String ACTION_RING_NOW = "com.faifai.printer.RING_NOW";
    public static final String EXTRA_ORDER_COUNT = "com.faifai.printer.EXTRA_ORDER_COUNT";

    private static final String CHANNEL_ACTIVE = "kitchen_active";
    private static final String CHANNEL_ORDER = "kitchen_admin_ring_v1";
    private static final String CHANNEL_LATE = "kitchen_late_v1";

    private static final String API =
            "https://vita-napoli-backend-usman.onrender.com/api/v1/kitchen/orders?limit=100";

    private static final String SETTINGS_API =
            "https://vita-napoli-backend-usman.onrender.com/api/v1/receipt-settings";

    private final ScheduledExecutorService worker =
            Executors.newSingleThreadScheduledExecutor();

    private final Set<String> lateAnnounced =
            ConcurrentHashMap.newKeySet();

    private final Handler mainHandler =
            new Handler(Looper.getMainLooper());

    private MediaPlayer alarm;
    private TextToSpeech tts;
    private volatile boolean ttsReady = false;

    private boolean adminAlarmEnabled = true;
    private String adminAlarmAudio = "";
    private long lastSettingsCheck = 0;

    @Override
    public void onCreate() {
        super.onCreate();

        NotificationManager nm =
                getSystemService(NotificationManager.class);

        nm.createNotificationChannel(
                new NotificationChannel(
                        CHANNEL_ACTIVE,
                        "Kitchen service",
                        NotificationManager.IMPORTANCE_LOW
                )
        );

        NotificationChannel orders =
                new NotificationChannel(
                        CHANNEL_ORDER,
                        "New kitchen orders",
                        NotificationManager.IMPORTANCE_HIGH
                );

        orders.enableVibration(true);
        orders.setSound(null, null);
        nm.createNotificationChannel(orders);

        NotificationChannel late =
                new NotificationChannel(
                        CHANNEL_LATE,
                        "Late kitchen orders",
                        NotificationManager.IMPORTANCE_HIGH
                );

        late.enableVibration(true);
        late.setSound(null, null);
        nm.createNotificationChannel(late);

        tts = new TextToSpeech(
                getApplicationContext(),
                status -> {
                    if (status == TextToSpeech.SUCCESS) {
                        try {
                            tts.setLanguage(Locale.US);
                            tts.setSpeechRate(0.90f);
                            ttsReady = true;
                        } catch (Exception ignored) {
                            ttsReady = false;
                        }
                    }
                }
        );

        startForeground(41, activeNotification());

        worker.scheduleWithFixedDelay(
                this::poll,
                0,
                10,
                TimeUnit.SECONDS
        );
    }

    private Notification activeNotification() {
        return new NotificationCompat.Builder(
                this,
                CHANNEL_ACTIVE
        )
                .setSmallIcon(
                        android.R.drawable.ic_menu_agenda
                )
                .setContentTitle(
                        "Fai Fai Kitchen active"
                )
                .setContentText(
                        "New and late orders are checked in background"
                )
                .setContentIntent(openKitchen())
                .setOngoing(true)
                .build();
    }

    private PendingIntent openKitchen() {
        Intent i =
                new Intent(
                        this,
                        MainActivity.class
                )
                        .addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK
                                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        );

        return PendingIntent.getActivity(
                this,
                1,
                i,
                PendingIntent.FLAG_UPDATE_CURRENT
                        | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private void poll() {
        refreshAdminAlarm();

        String pin =
                getSharedPreferences(
                        "fai_fai_kitchen",
                        MODE_PRIVATE
                ).getString(
                        "pin",
                        ""
                );

        if (pin == null || pin.length() < 4) {
            stopAlarm();
            return;
        }

        HttpURLConnection c = null;

        try {
            c =
                    (HttpURLConnection)
                            new URL(API)
                                    .openConnection();

            c.setConnectTimeout(12000);
            c.setReadTimeout(12000);

            c.setRequestProperty(
                    "X-Kitchen-Pin",
                    pin
            );

            if (c.getResponseCode() != 200) {
                stopAlarm();
                return;
            }

            JSONArray items =
                    new JSONObject(
                            read(c.getInputStream())
                    ).optJSONArray("items");

            if (items == null) {
                items = new JSONArray();
            }

            int newCount = 0;

            long now =
                    System.currentTimeMillis();

            Set<String> activeLateKeys =
                    new HashSet<>();

            for (
                    int index = 0;
                    index < items.length();
                    index++
            ) {
                JSONObject order =
                        items.optJSONObject(index);

                if (order == null) {
                    continue;
                }

                String status =
                        normalizeStatus(
                                order.optString(
                                        "status",
                                        "new"
                                )
                        );

                if ("new".equals(status)) {
                    newCount++;
                }

                if (
                        !"accepted".equals(status)
                                && !"preparing".equals(status)
                ) {
                    continue;
                }

                int orderId =
                        order.optInt(
                                "id",
                                0
                        );

                String promisedReadyAt =
                        order.optString(
                                "promised_ready_at",
                                ""
                        );

                long deadline =
                        parseIsoMillis(
                                promisedReadyAt
                        );

                if (
                        orderId <= 0
                                || deadline <= 0
                ) {
                    continue;
                }

                String key =
                        orderId
                                + "_"
                                + promisedReadyAt;

                activeLateKeys.add(key);

                if (
                        now >= deadline
                                && lateAnnounced.add(key)
                ) {
                    notifyLateOrder(
                            orderId
                    );
                }
            }

            lateAnnounced.retainAll(
                    activeLateKeys
            );

            if (newCount > 0) {
                notifyOrder(newCount);
            } else {
                stopAlarm();
            }

        } catch (Exception ignored) {
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    private String normalizeStatus(
            String value
    ) {
        String status =
                value == null
                        ? "new"
                        : value.trim()
                        .toLowerCase(
                                Locale.US
                        );

        if (
                status.equals("pending")
                        || status.equals("placed")
                        || status.equals("created")
                        || status.equals("order_placed")
        ) {
            return "new";
        }

        return status;
    }

    private long parseIsoMillis(
            String raw
    ) {
        if (raw == null) {
            return -1L;
        }

        String value =
                raw.trim();

        if (value.isEmpty()) {
            return -1L;
        }

        try {
            if (value.endsWith("Z")) {
                value =
                        value.substring(
                                0,
                                value.length() - 1
                        ) + "+0000";

            } else {
                int t =
                        value.indexOf('T');

                int plus =
                        value.indexOf(
                                '+',
                                Math.max(
                                        0,
                                        t
                                )
                        );

                int minus =
                        value.indexOf(
                                '-',
                                Math.max(
                                        0,
                                        t + 1
                                )
                        );

                int zone =
                        plus >= 0
                                ? plus
                                : minus;

                if (
                        zone >= 0
                                && value.length()
                                >= zone + 6
                                && value.charAt(
                                zone + 3
                        ) == ':'
                ) {
                    value =
                            value.substring(
                                    0,
                                    zone + 3
                            )
                                    + value.substring(
                                    zone + 4
                            );
                }
            }

            int zoneIndex = -1;

            int t =
                    value.indexOf('T');

            for (
                    int i =
                    Math.max(
                            0,
                            t + 1
                    );
                    i < value.length();
                    i++
            ) {
                char ch =
                        value.charAt(i);

                if (
                        ch == '+'
                                || ch == '-'
                ) {
                    zoneIndex = i;
                    break;
                }
            }

            String zone =
                    zoneIndex >= 0
                            ? value.substring(
                            zoneIndex
                    )
                            : "";

            String base =
                    zoneIndex >= 0
                            ? value.substring(
                            0,
                            zoneIndex
                    )
                            : value;

            int dot =
                    base.indexOf('.');

            boolean hasMillis =
                    dot >= 0;

            if (hasMillis) {
                String whole =
                        base.substring(
                                0,
                                dot
                        );

                String fraction =
                        base.substring(
                                dot + 1
                        );

                if (
                        fraction.length()
                                > 3
                ) {
                    fraction =
                            fraction.substring(
                                    0,
                                    3
                            );
                }

                while (
                        fraction.length()
                                < 3
                ) {
                    fraction += "0";
                }

                base =
                        whole
                                + "."
                                + fraction;
            }

            String normalized =
                    base + zone;

            String pattern;

            if (!zone.isEmpty()) {
                pattern =
                        hasMillis
                                ? "yyyy-MM-dd'T'HH:mm:ss.SSSZ"
                                : "yyyy-MM-dd'T'HH:mm:ssZ";
            } else {
                pattern =
                        hasMillis
                                ? "yyyy-MM-dd'T'HH:mm:ss.SSS"
                                : "yyyy-MM-dd'T'HH:mm:ss";
            }

            SimpleDateFormat parser =
                    new SimpleDateFormat(
                            pattern,
                            Locale.US
                    );

            parser.setLenient(false);

            if (zone.isEmpty()) {
                parser.setTimeZone(
                        TimeZone.getTimeZone(
                                "UTC"
                        )
                );
            }

            Date parsed =
                    parser.parse(
                            normalized
                    );

            return parsed == null
                    ? -1L
                    : parsed.getTime();

        } catch (Exception ignored) {
            return -1L;
        }
    }

    private void refreshAdminAlarm() {
        long now =
                System.currentTimeMillis();

        if (
                now - lastSettingsCheck
                        < 60000
        ) {
            return;
        }

        lastSettingsCheck = now;

        HttpURLConnection c = null;

        try {
            c =
                    (HttpURLConnection)
                            new URL(
                                    SETTINGS_API
                            ).openConnection();

            c.setConnectTimeout(12000);
            c.setReadTimeout(20000);

            if (
                    c.getResponseCode()
                            != 200
            ) {
                return;
            }

            JSONObject settings =
                    new JSONObject(
                            read(
                                    c.getInputStream()
                            )
                    );

            adminAlarmEnabled =
                    settings.optBoolean(
                            "kitchen_alarm_enabled",
                            true
                    );

            adminAlarmAudio =
                    settings.optString(
                            "kitchen_alarm_audio",
                            ""
                    );

            if (!adminAlarmEnabled) {
                stopAlarm();
            }

        } catch (Exception ignored) {
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    private String read(
            InputStream in
    ) throws IOException {
        ByteArrayOutputStream out =
                new ByteArrayOutputStream();

        byte[] b =
                new byte[2048];

        int n;

        while (
                (n = in.read(b))
                        >= 0
        ) {
            out.write(
                    b,
                    0,
                    n
            );
        }

        return out.toString(
                StandardCharsets.UTF_8.name()
        );
    }

    private synchronized void notifyOrder(
            int count
    ) {
        Notification n =
                new NotificationCompat.Builder(
                        this,
                        CHANNEL_ORDER
                )
                        .setSmallIcon(
                                android.R.drawable.ic_dialog_alert
                        )
                        .setContentTitle(
                                "New Kitchen Order"
                        )
                        .setContentText(
                                count
                                        + " order(s) waiting — tap to accept or cancel"
                        )
                        .setContentIntent(
                                openKitchen()
                        )
                        .setPriority(
                                NotificationCompat.PRIORITY_MAX
                        )
                        .setCategory(
                                NotificationCompat.CATEGORY_ALARM
                        )
                        .setOngoing(true)
                        .build();

        getSystemService(
                NotificationManager.class
        ).notify(
                42,
                n
        );

        if (
                adminAlarmEnabled
                        && !adminAlarmAudio.isEmpty()
                        && alarm == null
        ) {
            try {
                File audio =
                        adminAudioFile(
                                adminAlarmAudio,
                                "kitchen_admin_ring"
                        );

                alarm =
                        new MediaPlayer();

                alarm.setDataSource(
                        audio.getAbsolutePath()
                );

                alarm.setAudioAttributes(
                        new AudioAttributes.Builder()
                                .setUsage(
                                        AudioAttributes.USAGE_ALARM
                                )
                                .build()
                );

                alarm.setLooping(true);
                alarm.prepare();
                alarm.start();

            } catch (Exception ignored) {
                stopAlarm();
            }
        }
    }

    private void notifyLateOrder(
            int orderId
    ) {
        if (!adminAlarmEnabled) {
            return;
        }

        Notification notification =
                new NotificationCompat.Builder(
                        this,
                        CHANNEL_LATE
                )
                        .setSmallIcon(
                                android.R.drawable.ic_dialog_alert
                        )
                        .setContentTitle(
                                "Order late"
                        )
                        .setContentText(
                                "Order #"
                                        + orderId
                                        + " is late — tap to open Kitchen"
                        )
                        .setContentIntent(
                                openKitchen()
                        )
                        .setPriority(
                                NotificationCompat.PRIORITY_MAX
                        )
                        .setCategory(
                                NotificationCompat.CATEGORY_ALARM
                        )
                        .setAutoCancel(true)
                        .build();

        getSystemService(
                NotificationManager.class
        ).notify(
                10000
                        + (
                        orderId
                                % 10000
                ),
                notification
        );

        // Late order = VOICE ONLY.
        // No tu-tu / no extra beep.
        speakLateOrderWhenReady(
                orderId,
                0
        );
    }

    private void speakLateOrderWhenReady(
            int orderId,
            int attempt
    ) {
        mainHandler.post(
                () -> {
                    if (
                            ttsReady
                                    && tts != null
                    ) {
                        try {
                            String text =
                                    "Order number "
                                            + orderId
                                            + " is late. "
                                            + "Time is finished. "
                                            + "Please make order number "
                                            + orderId
                                            + " ready.";

                            tts.speak(
                                    text,
                                    TextToSpeech.QUEUE_FLUSH,
                                    null,
                                    "late-order-"
                                            + orderId
                            );

                            return;

                        } catch (
                                Exception ignored
                        ) {
                        }
                    }

                    if (attempt < 20) {
                        mainHandler.postDelayed(
                                () ->
                                        speakLateOrderWhenReady(
                                                orderId,
                                                attempt + 1
                                        ),
                                500
                        );
                    }
                }
        );
    }

    private File adminAudioFile(
            String dataUrl,
            String name
    ) throws IOException {
        int comma =
                dataUrl.indexOf(',');

        if (
                !dataUrl.startsWith(
                        "data:audio/"
                )
                        || comma < 0
        ) {
            throw new IOException(
                    "Invalid Admin ring"
            );
        }

        byte[] bytes =
                Base64.decode(
                        dataUrl.substring(
                                comma + 1
                        ),
                        Base64.DEFAULT
                );

        File file =
                new File(
                        getCacheDir(),
                        name + ".audio"
                );

        try (
                FileOutputStream out =
                        new FileOutputStream(
                                file,
                                false
                        )
        ) {
            out.write(bytes);
        }

        return file;
    }

    private synchronized void stopAlarm() {
        getSystemService(
                NotificationManager.class
        ).cancel(42);

        if (alarm != null) {
            try {
                alarm.stop();
            } catch (
                    Exception ignored
            ) {
            }

            try {
                alarm.release();
            } catch (
                    Exception ignored
            ) {
            }

            alarm = null;
        }
    }

    @Override
    public int onStartCommand(
            Intent i,
            int flags,
            int startId
    ) {
        if (i != null) {
            String action =
                    i.getAction();

            if (
                    ACTION_STOP_ALARM.equals(
                            action
                    )
            ) {
                stopAlarm();

            } else if (
                    ACTION_RING_NOW.equals(
                            action
                    )
            ) {
                int count =
                        Math.max(
                                1,
                                i.getIntExtra(
                                        EXTRA_ORDER_COUNT,
                                        1
                                )
                        );

                notifyOrder(count);
            }
        }

        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(
            Intent rootIntent
    ) {
        super.onTaskRemoved(
                rootIntent
        );
    }

    @Override
    public void onDestroy() {
        worker.shutdownNow();

        stopAlarm();

        mainHandler.removeCallbacksAndMessages(
                null
        );

        if (tts != null) {
            try {
                tts.stop();
            } catch (
                    Exception ignored
            ) {
            }

            try {
                tts.shutdown();
            } catch (
                    Exception ignored
            ) {
            }

            tts = null;
        }

        super.onDestroy();
    }

    @Override
    public android.os.IBinder onBind(
            Intent intent
    ) {
        return null;
    }
}
