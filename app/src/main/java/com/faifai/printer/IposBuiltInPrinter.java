package com.faifai.printer;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Built-in 58mm printer driver for the Newpas/Newpos Q2I.
 *
 * The device exposes the vendor iPos printer service:
 *   package: com.iposprinter.iposprinterservice
 *   action : com.iposprinter.iposprinterservice.IPosPrintService
 *
 * We talk to the Binder directly so this project does not need vendor JAR/AAR or
 * AIDL build configuration. Transaction numbers follow the vendor IPosPrinterService
 * AIDL used by Q-series terminals.
 */
final class IposBuiltInPrinter {
    private static final String SERVICE_PACKAGE = "com.iposprinter.iposprinterservice";
    private static final String SERVICE_ACTION = "com.iposprinter.iposprinterservice.IPosPrintService";
    private static final String SERVICE_DESCRIPTOR = "com.iposprinter.iposprinterservice.IPosPrinterService";
    private static final String CALLBACK_DESCRIPTOR = "com.iposprinter.iposprinterservice.IPosPrinterCallback";

    // Vendor AIDL transaction positions.
    private static final int TX_GET_STATUS = 1;
    private static final int TX_PRINTER_INIT = 2;
    private static final int TX_PRINT_SPEC_FORMAT_TEXT = 11;
    private static final int TX_PERFORM_PRINT = 18;

    private final Context appContext;
    private final Object lock = new Object();
    private volatile IBinder service;
    private volatile boolean binding;
    private CountDownLatch bindLatch = new CountDownLatch(1);

    private final CallbackBinder callback = new CallbackBinder();

    IposBuiltInPrinter(Context context) {
        this.appContext = context.getApplicationContext();
    }

    void bind() {
        synchronized (lock) {
            if (service != null || binding) return;
            binding = true;
            bindLatch = new CountDownLatch(1);
        }

        Intent intent = new Intent(SERVICE_ACTION);
        intent.setPackage(SERVICE_PACKAGE);
        try {
            boolean ok = appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE);
            if (!ok) {
                synchronized (lock) {
                    binding = false;
                    bindLatch.countDown();
                }
            }
        } catch (Exception error) {
            synchronized (lock) {
                binding = false;
                bindLatch.countDown();
            }
        }
    }

    void unbind() {
        try {
            appContext.unbindService(connection);
        } catch (Exception ignored) {
        }
        service = null;
        binding = false;
    }

    void printReceipt(String payloadJson) throws Exception {
        ensureConnected();

        JSONObject root = new JSONObject(payloadJson);
        JSONObject order = root.optJSONObject("order");
        if (order == null) throw new IllegalArgumentException("Order data missing");
        JSONObject settings = root.optJSONObject("settings");
        if (settings == null) settings = new JSONObject();

        waitUntilReady();
        transactVoid(TX_PRINTER_INIT, data -> data.writeStrongBinder(callback));

        String restaurantName = clean(settings.optString("restaurant_name", "Fai Fai Juice"));
        String orderId = clean(String.valueOf(order.opt("id")));
        String orderType = clean(order.optString("order_type", "pickup")).toUpperCase(Locale.US);
        String payment = clean(order.optString("payment_method", ""));
        String customer = clean(order.optString("customer_name", ""));
        String phone = clean(order.optString("customer_phone", ""));
        String createdAt = clean(order.optString("created_at", ""));
        String notes = clean(firstNonEmpty(
                order.optString("customer_notes", ""),
                order.optString("order_notes", "")
        ));

        printText(restaurantName + "\n", 32, 1);
        printText("ORDER #" + orderId + "\n", 32, 1);
        printText(repeat('-', 32) + "\n", 24, 0);
        printText("Type: " + orderType + "\n", 24, 0);
        String time = formatUaeTime(createdAt);
        if (!time.isEmpty()) printText("Time: " + time + "\n", 24, 0);
        if (!customer.isEmpty()) printText("Customer: " + customer + "\n", 24, 0);
        if (!phone.isEmpty()) printText("Phone: " + phone + "\n", 24, 0);
        if (!payment.isEmpty()) printText("Payment: " + payment + "\n", 24, 0);
        printText(repeat('-', 32) + "\n", 24, 0);

        JSONArray items = order.optJSONArray("items");
        if (items == null) {
            String rawItems = order.optString("items_json", "");
            if (rawItems.trim().startsWith("[")) {
                try { items = new JSONArray(rawItems); } catch (Exception ignored) { }
            }
        }
        if (items == null) items = new JSONArray();

        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            int qty = Math.max(1, item.optInt("quantity", 1));
            String name = clean(firstNonEmpty(item.optString("name", ""), "Item"));
            String size = clean(firstNonEmpty(item.optString("size", ""), item.optString("selectedSize", "")));
            String line = qty + " x " + name + (size.isEmpty() ? "" : " (" + size + ")");
            printWrapped(line, 24);

            JSONArray extras = item.optJSONArray("extras");
            if (extras != null) {
                for (int x = 0; x < extras.length(); x++) {
                    Object extraValue = extras.opt(x);
                    String extra;
                    if (extraValue instanceof JSONObject) {
                        extra = clean(((JSONObject) extraValue).optString("name", ""));
                    } else {
                        extra = clean(String.valueOf(extraValue));
                    }
                    if (!extra.isEmpty()) printWrapped("  + " + extra, 24);
                }
            }
        }

        if (!notes.isEmpty()) {
            printText(repeat('-', 32) + "\n", 24, 0);
            printText("NOTES\n", 24, 0);
            printWrapped(notes, 24);
        }

        double total = order.optDouble("total_amount", Double.NaN);
        if (!Double.isNaN(total)) {
            printText(repeat('-', 32) + "\n", 24, 0);
            printText(String.format(Locale.US, "TOTAL: AED %.2f\n", total), 32, 1);
        }

        boolean reprint = root.optBoolean("reprint", false);
        if (reprint) printText("REPRINT / COPY\n", 24, 1);

        printText("\n", 24, 0);
        transactVoid(TX_PERFORM_PRINT, data -> {
            data.writeInt(160);
            data.writeStrongBinder(callback);
        });
    }

    private void printWrapped(String text, int fontSize) throws Exception {
        String safe = clean(text);
        if (safe.isEmpty()) return;
        final int width = fontSize >= 32 ? 22 : 32;
        int pos = 0;
        while (pos < safe.length()) {
            int end = Math.min(safe.length(), pos + width);
            if (end < safe.length()) {
                int space = safe.lastIndexOf(' ', end);
                if (space > pos + 8) end = space;
            }
            String part = safe.substring(pos, end).trim();
            if (!part.isEmpty()) printText(part + "\n", fontSize, 0);
            pos = end;
            while (pos < safe.length() && safe.charAt(pos) == ' ') pos++;
        }
    }

    private void printText(String text, int fontSize, int alignment) throws Exception {
        transactVoid(TX_PRINT_SPEC_FORMAT_TEXT, data -> {
            data.writeString(text);
            data.writeString("ST");
            data.writeInt(fontSize);
            data.writeInt(alignment);
            data.writeStrongBinder(callback);
        });
    }

    private void waitUntilReady() throws Exception {
        for (int attempt = 0; attempt < 10; attempt++) {
            int status = getPrinterStatus();
            if (status == 0) return;
            if (status == 1) throw new IllegalStateException("Printer is out of paper");
            if (status == 2) throw new IllegalStateException("Printer head is too hot");
            if (status == 3) throw new IllegalStateException("Printer motor is too hot");
            if (status == 5) throw new IllegalStateException("Built-in printer error");
            Thread.sleep(200L);
        }
        throw new IllegalStateException("Built-in printer is busy");
    }

    private int getPrinterStatus() throws Exception {
        IBinder target = connectedBinder();
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SERVICE_DESCRIPTOR);
            boolean handled = target.transact(TX_GET_STATUS, data, reply, 0);
            if (!handled) throw new RemoteException("Printer status transaction failed");
            reply.readException();
            return reply.readInt();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private void transactVoid(int code, ParcelWriter writer) throws Exception {
        IBinder target = connectedBinder();
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SERVICE_DESCRIPTOR);
            writer.write(data);
            boolean handled = target.transact(code, data, reply, 0);
            if (!handled) throw new RemoteException("Printer transaction " + code + " failed");
            reply.readException();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private IBinder connectedBinder() throws Exception {
        ensureConnected();
        IBinder result = service;
        if (result == null) throw new IllegalStateException("Built-in printer service unavailable");
        return result;
    }

    private void ensureConnected() throws Exception {
        if (service != null && service.isBinderAlive()) return;
        bind();
        CountDownLatch latch = bindLatch;
        if (!latch.await(2500L, TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException("Built-in printer connection timed out");
        }
        if (service == null || !service.isBinderAlive()) {
            throw new IllegalStateException("Built-in printer service not found");
        }
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = binder;
            synchronized (lock) {
                binding = false;
                bindLatch.countDown();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            binding = false;
        }
    };

    private interface ParcelWriter {
        void write(Parcel data) throws Exception;
    }

    /** Minimal Binder callback compatible with the vendor IPosPrinterCallback AIDL. */
    private static final class CallbackBinder extends Binder implements IInterface {
        CallbackBinder() {
            attachInterface(this, CALLBACK_DESCRIPTOR);
        }

        @Override
        public IBinder asBinder() {
            return this;
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString(CALLBACK_DESCRIPTOR);
                return true;
            }
            if (code == 1) { // onRunResult(boolean)
                data.enforceInterface(CALLBACK_DESCRIPTOR);
                data.readInt();
                return true;
            }
            if (code == 2) { // onReturnString(String)
                data.enforceInterface(CALLBACK_DESCRIPTOR);
                data.readString();
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    }

    private static String clean(String value) {
        if (value == null) return "";
        return value
                .replace('\r', ' ')
                .replace('\n', ' ')
                .replace('\t', ' ')
                .replace('–', '-')
                .replace('—', '-')
                .replace('’', '\'')
                .replace('“', '"')
                .replace('”', '"')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty() && !"null".equalsIgnoreCase(value.trim())) {
                return value;
            }
        }
        return "";
    }

    private static String repeat(char c, int count) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < count; i++) result.append(c);
        return result.toString();
    }

    private static String formatUaeTime(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "";
        try {
            String value = raw.trim();
            Date date;
            // Backend timestamps without a timezone are UTC.
            if (value.matches(".*(?:Z|[+-]\\d{2}:?\\d{2})$")) {
                date = parseIso(value);
            } else {
                date = parseIso(value + "Z");
            }
            if (date == null) return "";
            SimpleDateFormat out = new SimpleDateFormat("dd/MM HH:mm", Locale.US);
            out.setTimeZone(TimeZone.getTimeZone("Asia/Dubai"));
            return out.format(date);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static Date parseIso(String value) {
        String[] patterns = {
                "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
                "yyyy-MM-dd'T'HH:mm:ssXXX",
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                "yyyy-MM-dd'T'HH:mm:ss'Z'"
        };
        for (String pattern : patterns) {
            try {
                SimpleDateFormat parser = new SimpleDateFormat(pattern, Locale.US);
                parser.setTimeZone(TimeZone.getTimeZone("UTC"));
                return parser.parse(value);
            } catch (Exception ignored) {
            }
        }
        return null;
    }
}
