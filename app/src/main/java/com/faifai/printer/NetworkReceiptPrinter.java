package com.faifai.printer;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

final class NetworkReceiptPrinter {
    private static final Charset PRINTER_CHARSET = Charset.forName("CP437");

    private NetworkReceiptPrinter() {}

    static void print(Context context, String payloadJson) throws Exception {
        JSONObject root = new JSONObject(payloadJson);
        Receipt receipt = Receipt.from(root);

        if (receipt.printerIp.isEmpty()) {
            throw new IllegalArgumentException("Printer IP is missing");
        }

        byte[] data = render(context, receipt);
        try (Socket socket = new Socket()) {
            socket.connect(
                    new InetSocketAddress(receipt.printerIp, receipt.printerPort),
                    6000
            );
            socket.setSoTimeout(10000);
            try (OutputStream output = socket.getOutputStream()) {
                output.write(data);
                output.flush();
            }
        }
    }

    /**
     * Render the exact same receipt bytes used by the old network-printer path,
     * but do not open a network socket. The Q2I built-in printer sends these
     * bytes through the vendor iPos ESC/POS command API instead.
     */
    static byte[] renderForBuiltIn(Context context, String payloadJson) throws Exception {
        JSONObject root = new JSONObject(payloadJson);
        Receipt receipt = Receipt.from(root);
        return render(context, receipt);
    }

    /**
     * Compatibility entry point used by the existing BluetoothReceiptPrinter.
     * The two legacy flags are intentionally accepted so older router code keeps
     * compiling; receipt appearance still comes from this single Talabat-style
     * renderer, so Bluetooth/network fallback cannot switch to an old layout.
     */
    static byte[] renderReceipt(
            Context context,
            String payloadJson,
            boolean legacyFlagOne,
            boolean legacyFlagTwo
    ) throws Exception {
        JSONObject root = new JSONObject(payloadJson == null ? "{}" : payloadJson);
        Receipt receipt = Receipt.from(root);
        return render(context, receipt);
    }

    /**
     * Compatibility helper used by the existing PrinterRouter.
     */
    static boolean hasPrinterIp(String payloadJson) {
        if (payloadJson == null || payloadJson.trim().isEmpty()) return false;
        try {
            JSONObject root = new JSONObject(payloadJson);
            Receipt receipt = Receipt.from(root);
            return receipt.printerIp != null && !receipt.printerIp.trim().isEmpty();
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * Clean Talabat-style thermal receipt.
     * No bitmap/logo dependency: the receipt starts with a large shop name,
     * then order details, customer name/phone, item table and a large grand total. No address is printed.
     */
    /**
     * Large, open 58mm kitchen receipt inspired by the readable Talabat ticket
     * hierarchy: large order number, large item names, strong totals and generous
     * vertical spacing. No bitmap/logo is printed.
     */
    private static byte[] render(Context context, Receipt receipt) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        // Q2I built-in paper is 58mm / 384 dots. Keep the text grid at 32 chars
        // for normal-width lines, then use ESC/POS scaling only where the line is
        // intentionally short enough to stay inside the paper.
        final int width = 32;

        command(out, 0x1B, 0x40);        // ESC @ initialize
        command(out, 0x1B, 0x4D, 0x00);  // Font A (largest standard font)
        command(out, 0x1B, 0x33, 36);    // More open line spacing
        doubleStrike(out, true);         // Darker / clearer thermal text
        align(out, 1);

        // Restaurant name: large and dark, no logo.
        bold(out, true);
        textScale(out, 0x11);            // 2x width + 2x height
        line(out, shortCenteredTitle(receipt.restaurantName, "FAI FAI JUICE", 16));
        textScale(out, 0x00);
        line(out, "");

        // No shop address/header on the kitchen receipt. Keep the top clean
        // and use the full 58mm paper for the order number and order details.
        bold(out, false);
        textScale(out, 0x00);
        line(out, repeat('=', width));
        line(out, "");

        // Order number is the strongest element on the ticket.
        bold(out, true);
        textScale(out, 0x12);            // 2x width + 3x height
        line(out, "ORDER #" + (receipt.orderId.isEmpty() ? "-" : receipt.orderId));
        textScale(out, 0x00);
        bold(out, false);
        line(out, "");
        line(out, repeat('=', width));
        line(out, "");

        DateTimeParts dateTime = formatDateTime(receipt.createdAt);
        align(out, 0);

        // Talabat-like open details: one strong line per field instead of a tiny
        // compressed block.
        largeDetail(out, "DATE", dateTime.date, width);
        largeDetail(out, "TIME", dateTime.time, width);
        largeDetail(out, "ORDER TYPE", pretty(receipt.orderType).toUpperCase(Locale.US), width);
        if (receipt.showPaymentMethod && !receipt.paymentMethod.isEmpty()) {
            largeDetail(out, "PAYMENT", pretty(receipt.paymentMethod).toUpperCase(Locale.US), width);
        }

        line(out, repeat('-', width));
        line(out, "");

        if (!receipt.customerName.isEmpty()) {
            sectionLabel(out, "CUSTOMER");
            largeValue(out, receipt.customerName, width);
        }
        if (receipt.showCustomerPhone && !receipt.customerPhone.isEmpty()) {
            sectionLabel(out, "PHONE");
            largeValue(out, receipt.customerPhone, width);
        }
        if (!receipt.customerNotes.isEmpty()) {
            sectionLabel(out, "NOTE");
            largeValue(out, receipt.customerNotes, width);
        }

        line(out, repeat('=', width));
        line(out, "");

        printItemsLarge(out, receipt, width);

        if (receipt.showOrderTotals) {
            line(out, repeat('=', width));
            line(out, "");

            textScale(out, 0x00);
            bold(out, true);
            if (receipt.subtotalAmount > 0) {
                pair(out, "Subtotal", money(receipt.subtotalAmount), width);
                line(out, "");
            }
            if (receipt.discountAmount > 0) {
                pair(out, "Discount", "-" + money(receipt.discountAmount), width);
                line(out, "");
            }
            if (receipt.serviceFee > 0) {
                pair(out, "Service Fee", money(receipt.serviceFee), width);
                line(out, "");
            }
            if (receipt.smallOrderFee > 0) {
                pair(out, "Small Order Fee", money(receipt.smallOrderFee), width);
                line(out, "");
            }
            if (receipt.deliveryCharge > 0) {
                pair(out, "Delivery Fee", money(receipt.deliveryCharge), width);
                line(out, "");
            }
            if (receipt.tipAmount > 0) {
                pair(out, "Tip", money(receipt.tipAmount), width);
                line(out, "");
            }

            line(out, repeat('=', width));
            align(out, 1);
            bold(out, true);
            textScale(out, 0x01);        // tall TOTAL label
            line(out, "GRAND TOTAL");
            textScale(out, 0x12);        // very large amount, still fits 58mm
            line(out, money(receipt.totalAmount));
            textScale(out, 0x00);
            bold(out, false);
            line(out, repeat('=', width));
            line(out, "");
        }

        align(out, 1);
        bold(out, true);
        textScale(out, 0x01);
        multilineCentered(out, receipt.footerText, width);
        textScale(out, 0x00);
        bold(out, false);
        doubleStrike(out, false);
        command(out, 0x1B, 0x64, 5);     // generous bottom feed

        if (receipt.cutPaper) {
            command(out, 0x1D, 0x56, 0x00);
        }
        return out.toByteArray();
    }

    private static void largeDetail(
            ByteArrayOutputStream out,
            String label,
            String value,
            int width
    ) throws Exception {
        bold(out, true);
        textScale(out, 0x00);
        line(out, label);
        textScale(out, 0x01);            // double-height value
        for (String part : wrapChunks(value, width)) {
            line(out, part);
        }
        textScale(out, 0x00);
        bold(out, false);
        line(out, "");
    }

    private static void sectionLabel(ByteArrayOutputStream out, String label) throws Exception {
        bold(out, true);
        textScale(out, 0x00);
        line(out, label);
        bold(out, false);
    }

    private static void largeValue(
            ByteArrayOutputStream out,
            String value,
            int width
    ) throws Exception {
        bold(out, true);
        textScale(out, 0x01);            // double-height, normal width
        for (String part : wrapChunks(value, width)) {
            line(out, part);
        }
        textScale(out, 0x00);
        bold(out, false);
        line(out, "");
    }

    private static void printItemsLarge(
            ByteArrayOutputStream out,
            Receipt receipt,
            int width
    ) throws Exception {
        align(out, 0);
        bold(out, true);
        textScale(out, 0x01);
        line(out, "ITEMS");
        textScale(out, 0x00);
        line(out, repeat('-', width));
        bold(out, false);
        line(out, "");

        for (int index = 0; index < receipt.items.length(); index++) {
            JSONObject item = receipt.items.optJSONObject(index);
            if (item == null) continue;

            int quantity = Math.max(1, integer(item, 1, "quantity", "qty"));
            String name = first(item, "name", "item_name", "title");
            String sizeName = first(item, "size", "size_name");
            String label = name.isEmpty() ? "Item" : name;
            if (!sizeName.isEmpty()) label += " (" + sizeName + ")";

            // Qty + item name get the same large visual weight as Talabat tickets.
            bold(out, true);
            textScale(out, 0x01);
            String itemPrefix = quantity + " x ";
            List<String> parts = wrapChunks(itemPrefix + label, width);
            for (String part : parts) line(out, part);
            textScale(out, 0x00);
            bold(out, false);

            JSONArray extras = array(item, "extras", "selected_extras", "toppings");
            for (int extraIndex = 0; extraIndex < extras.length(); extraIndex++) {
                Object extra = extras.opt(extraIndex);
                String extraText;
                if (extra instanceof JSONObject) {
                    extraText = first((JSONObject) extra, "name", "title", "label");
                } else {
                    extraText = String.valueOf(extra == null ? "" : extra);
                }
                if (!extraText.trim().isEmpty()) {
                    bold(out, true);
                    wrapped(out, "+ " + extraText, width);
                    bold(out, false);
                }
            }

            double price = number(item, "total_price", "line_total");
            if (price <= 0) price = number(item, "price", "unit_price");
            if (receipt.showItemPrices && price > 0) {
                align(out, 2);
                bold(out, true);
                textScale(out, 0x01);
                line(out, money(price));
                textScale(out, 0x00);
                bold(out, false);
                align(out, 0);
            }

            line(out, "");
            if (index < receipt.items.length() - 1) {
                line(out, repeat('-', width));
                line(out, "");
            }
        }
    }

    private static String shortCenteredTitle(String raw, String fallback, int maxChars) {
        String value = printable(raw == null || raw.trim().isEmpty() ? fallback : raw)
                .trim().toUpperCase(Locale.US);
        if (value.length() <= maxChars) return value;
        String fb = printable(fallback).trim().toUpperCase(Locale.US);
        if (fb.length() <= maxChars) return fb;
        return fb.substring(0, maxChars);
    }

    private static DateTimeParts formatDateTime(String rawValue) {
        String raw = rawValue == null ? "" : rawValue.trim();
        if (raw.isEmpty()) return new DateTimeParts("-", "-");

        Date parsed = parseIsoDate(raw);
        if (parsed != null) {
            TimeZone dubai = TimeZone.getTimeZone("Asia/Dubai");
            SimpleDateFormat dateFormat = new SimpleDateFormat(
                    "dd/MM/yyyy",
                    Locale.US
            );
            SimpleDateFormat timeFormat = new SimpleDateFormat(
                    "hh:mm a",
                    Locale.US
            );
            dateFormat.setTimeZone(dubai);
            timeFormat.setTimeZone(dubai);
            return new DateTimeParts(
                    dateFormat.format(parsed),
                    timeFormat.format(parsed)
            );
        }

        String clean = printable(raw);
        int tIndex = clean.indexOf('T');
        if (tIndex > 0) {
            String date = clean.substring(0, tIndex);
            String time = clean.substring(tIndex + 1);
            int zoneIndex = Math.max(time.indexOf('+'), time.indexOf('Z'));
            if (zoneIndex > 0) time = time.substring(0, zoneIndex);
            if (time.length() > 8) time = time.substring(0, 8);
            return new DateTimeParts(date, time);
        }

        return new DateTimeParts(clean, "");
    }

    private static Date parseIsoDate(String rawValue) {
        String normalized = rawValue.trim();

        // SimpleDateFormat supports milliseconds, not arbitrary microseconds.
        int dot = normalized.indexOf('.');
        if (dot >= 0) {
            int end = dot + 1;
            while (end < normalized.length()
                    && Character.isDigit(normalized.charAt(end))) {
                end++;
            }
            String fraction = normalized.substring(dot + 1, end);
            if (fraction.length() > 3) {
                normalized = normalized.substring(0, dot + 1)
                        + fraction.substring(0, 3)
                        + normalized.substring(end);
            } else if (fraction.length() < 3) {
                StringBuilder padded = new StringBuilder(fraction);
                while (padded.length() < 3) padded.append('0');
                normalized = normalized.substring(0, dot + 1)
                        + padded
                        + normalized.substring(end);
            }
        }

        String[] patterns = {
                "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
                "yyyy-MM-dd'T'HH:mm:ssXXX",
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                "yyyy-MM-dd'T'HH:mm:ss'Z'",
                "yyyy-MM-dd'T'HH:mm:ss.SSS",
                "yyyy-MM-dd'T'HH:mm:ss"
        };

        for (String pattern : patterns) {
            try {
                SimpleDateFormat parser = new SimpleDateFormat(pattern, Locale.US);
                parser.setLenient(false);
                if (!pattern.contains("XXX")) {
                    parser.setTimeZone(TimeZone.getTimeZone("UTC"));
                }
                return parser.parse(normalized);
            } catch (Exception ignored) {
                // Try the next supported format.
            }
        }
        return null;
    }

    private static void multilineCentered(
            ByteArrayOutputStream out,
            String value,
            int width
    ) throws Exception {
        if (value == null || value.trim().isEmpty()) return;
        String[] lines = value.replace("\r", "").split("\n");
        for (String sourceLine : lines) {
            String clean = printable(sourceLine).trim();
            if (clean.isEmpty()) continue;
            for (String part : wrapChunks(clean, width)) {
                line(out, part);
            }
        }
    }

    private static List<String> wrapChunks(String value, int width) {
        List<String> result = new ArrayList<>();
        String remaining = printable(value).trim();
        if (remaining.isEmpty()) return result;

        while (remaining.length() > width) {
            int cut = remaining.lastIndexOf(' ', width);
            if (cut < Math.max(1, width / 2)) cut = width;
            result.add(remaining.substring(0, cut).trim());
            remaining = remaining.substring(cut).trim();
        }
        if (!remaining.isEmpty()) result.add(remaining);
        return result;
    }

    private static String pretty(String rawValue) {
        String clean = printable(rawValue)
                .replace('_', ' ')
                .replace('-', ' ')
                .trim()
                .replaceAll("\\s+", " ");
        if (clean.isEmpty()) return "-";

        StringBuilder result = new StringBuilder();
        String[] words = clean.split(" ");
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (result.length() > 0) result.append(' ');
            result.append(Character.toUpperCase(word.charAt(0)));
            if (word.length() > 1) {
                result.append(word.substring(1).toLowerCase(Locale.US));
            }
        }
        return result.toString();
    }

    private static void command(ByteArrayOutputStream out, int... values) {
        for (int value : values) out.write(value);
    }

    private static void align(ByteArrayOutputStream out, int value) {
        command(out, 0x1B, 0x61, value);
    }

    private static void bold(ByteArrayOutputStream out, boolean enabled) {
        command(out, 0x1B, 0x45, enabled ? 1 : 0);
    }

    private static void doubleStrike(ByteArrayOutputStream out, boolean enabled) {
        command(out, 0x1B, 0x47, enabled ? 1 : 0);
    }

    private static void textScale(ByteArrayOutputStream out, int mode) {
        command(out, 0x1D, 0x21, mode);
    }

    private static void line(ByteArrayOutputStream out, String text) throws Exception {
        out.write(printable(text).getBytes(PRINTER_CHARSET));
        out.write('\n');
    }

    private static void wrapped(
            ByteArrayOutputStream out,
            String text,
            int width
    ) throws Exception {
        for (String part : wrapChunks(text, width)) {
            line(out, part);
        }
    }

    private static void detailPair(
            ByteArrayOutputStream out,
            String label,
            String value,
            int width
    ) throws Exception {
        String left = printable(label).trim() + ":";
        bold(out, true);
        pair(out, left, value, width);
        bold(out, false);
    }

    private static void pair(
            ByteArrayOutputStream out,
            String left,
            String right,
            int width
    ) throws Exception {
        left = printable(left).trim();
        right = printable(right).trim();
        int spaces = width - left.length() - right.length();
        if (spaces < 1) {
            wrapped(out, left, width);
            line(out, padLeft(right, width));
            return;
        }
        line(out, left + repeat(' ', spaces) + right);
    }

    private static String printable(String value) {
        if (value == null) return "";
        return value
                .replace('–', '-')
                .replace('—', '-')
                .replace('’', '\'')
                .replace('“', '"')
                .replace('”', '"')
                .replaceAll("[\\r\\n\\t]+", " ")
                .replaceAll("[^\\x20-\\x7E]", "?");
    }

    private static String money(double amount) {
        return String.format(Locale.US, "AED %.2f", amount);
    }

    private static String repeat(char value, int count) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < Math.max(0, count); index++) {
            builder.append(value);
        }
        return builder.toString();
    }

    private static String padLeft(String value, int width) {
        String clean = printable(value);
        if (clean.length() >= width) return clean.substring(0, width);
        return repeat(' ', width - clean.length()) + clean;
    }

    private static String padRight(String value, int width) {
        String clean = printable(value);
        if (clean.length() >= width) return clean.substring(0, width);
        return clean + repeat(' ', width - clean.length());
    }

    private static JSONObject object(JSONObject source, String key) {
        JSONObject value = source.optJSONObject(key);
        return value == null ? new JSONObject() : value;
    }

    private static JSONArray array(JSONObject source, String... keys) {
        for (String key : keys) {
            JSONArray direct = source.optJSONArray(key);
            if (direct != null) return direct;
            String raw = source.optString(key, "").trim();
            if (raw.startsWith("[")) {
                try {
                    return new JSONArray(raw);
                } catch (Exception ignored) {
                    // Try the next key.
                }
            }
        }
        return new JSONArray();
    }

    private static String first(JSONObject source, String... keys) {
        for (String key : keys) {
            String value = source.optString(key, "").trim();
            if (!value.isEmpty() && !value.equalsIgnoreCase("null")) {
                return value;
            }
        }
        return "";
    }

    private static double number(JSONObject source, String... keys) {
        for (String key : keys) {
            if (!source.has(key) || source.isNull(key)) continue;
            try {
                return source.optDouble(
                        key,
                        Double.parseDouble(source.optString(key, "0"))
                );
            } catch (Exception ignored) {
                // Try the next key.
            }
        }
        return 0;
    }

    private static int integer(
            JSONObject source,
            int fallback,
            String... keys
    ) {
        for (String key : keys) {
            if (!source.has(key) || source.isNull(key)) continue;
            try {
                return source.optInt(
                        key,
                        Integer.parseInt(
                                source.optString(key, String.valueOf(fallback))
                        )
                );
            } catch (Exception ignored) {
                // Try the next key.
            }
        }
        return fallback;
    }

    private static boolean bool(
            JSONObject source,
            boolean fallback,
            String... keys
    ) {
        for (String key : keys) {
            if (source.has(key) && !source.isNull(key)) {
                return source.optBoolean(key, fallback);
            }
        }
        return fallback;
    }

    private static final class DateTimeParts {
        final String date;
        final String time;

        DateTimeParts(String date, String time) {
            this.date = date;
            this.time = time;
        }
    }

    private static final class Receipt {
        String printerIp;
        int printerPort;
        String paperWidth;
        boolean cutPaper;
        String restaurantName;
        boolean showLogo;
        String logoUrl;
        String headerText;
        String footerText;
        boolean showCustomerPhone;
        boolean showPaymentMethod;
        boolean showItemPrices;
        boolean showOrderTotals;
        String orderId;
        String createdAt;
        String orderType;
        String customerName;
        String customerPhone;
        String customerAddress;
        String customerNotes;
        String paymentMethod;
        JSONArray items;
        double subtotalAmount;
        double discountAmount;
        double serviceFee;
        double smallOrderFee;
        double deliveryCharge;
        double tipAmount;
        double totalAmount;

        static Receipt from(JSONObject root) {
            JSONObject printer = object(root, "printer");
            JSONObject settings = object(root, "settings");
            JSONObject branding = object(root, "receipt");
            JSONObject order = object(root, "order");

            Receipt result = new Receipt();
            result.printerIp = first(printer, "ip");
            if (result.printerIp.isEmpty()) {
                result.printerIp = first(
                        settings,
                        "printer_ip",
                        "printerIp"
                );
            }

            result.printerPort = integer(printer, 9100, "port");
            if (printer.length() == 0) {
                result.printerPort = integer(
                        settings,
                        9100,
                        "printer_port",
                        "printerPort"
                );
            }

            result.paperWidth = first(
                    printer,
                    "paperWidth",
                    "paper_width"
            );
            if (result.paperWidth.isEmpty()) {
                result.paperWidth = first(
                        settings,
                        "paper_width",
                        "paperWidth"
                );
            }
            if (!result.paperWidth.equals("58mm")) {
                result.paperWidth = "80mm";
            }

            result.cutPaper = bool(
                    printer,
                    bool(settings, true, "cut_paper", "cutPaper"),
                    "cutPaper",
                    "cut_paper"
            );

            result.restaurantName = first(
                    branding,
                    "restaurantName",
                    "restaurant_name"
            );
            if (result.restaurantName.isEmpty()) {
                result.restaurantName = first(
                        settings,
                        "restaurant_name",
                        "restaurantName"
                );
            }
            if (result.restaurantName.isEmpty()) {
                result.restaurantName = "Fai Fai Juice";
            }

            result.showLogo = bool(
                    branding,
                    bool(settings, true, "show_logo", "showLogo"),
                    "showLogo",
                    "show_logo"
            );

            result.logoUrl = first(branding, "logoUrl", "logo_url");
            if (result.logoUrl.isEmpty()) {
                result.logoUrl = first(settings, "logo_url", "logoUrl");
            }
            if (result.logoUrl.isEmpty()) result.logoUrl = "";

            result.headerText = first(
                    branding,
                    "headerText",
                    "header_text"
            );
            if (result.headerText.isEmpty()) {
                result.headerText = first(
                        settings,
                        "header_text",
                        "headerText"
                );
            }
            if (result.headerText.isEmpty()) {
                result.headerText = "Murbah, Fujairah, UAE\n052 3187415";
            }

            result.footerText = first(
                    branding,
                    "footerText",
                    "footer_text"
            );
            if (result.footerText.isEmpty()) {
                result.footerText = first(
                        settings,
                        "footer_text",
                        "footerText"
                );
            }
            if (result.footerText.isEmpty()) {
                result.footerText = "Thank you for ordering from Fai Fai Juice!";
            }

            result.showCustomerPhone = bool(
                    branding,
                    bool(settings, true, "show_customer_phone"),
                    "showCustomerPhone"
            );
            result.showPaymentMethod = bool(
                    branding,
                    bool(settings, true, "show_payment_method"),
                    "showPaymentMethod"
            );
            result.showItemPrices = bool(
                    branding,
                    bool(settings, true, "show_item_prices"),
                    "showItemPrices"
            );
            result.showOrderTotals = bool(
                    branding,
                    bool(settings, true, "show_order_totals"),
                    "showOrderTotals"
            );

            result.orderId = first(order, "id", "order_id");
            result.createdAt = first(
                    order,
                    "createdAt",
                    "created_at",
                    "order_time"
            );
            result.orderType = first(order, "type", "order_type");
            result.customerName = first(
                    order,
                    "customerName",
                    "customer_name"
            );
            result.customerPhone = first(
                    order,
                    "customerPhone",
                    "customer_phone"
            );
            result.customerAddress = first(
                    order,
                    "customerAddress",
                    "customer_address",
                    "delivery_address",
                    "delivery_area_name",
                    "delivery_zone_name"
            );
            result.customerNotes = first(
                    order,
                    "customerNotes",
                    "customer_notes",
                    "customerNote"
            );
            result.paymentMethod = first(
                    order,
                    "paymentMethod",
                    "payment_method"
            );
            result.items = array(order, "items", "items_json");
            result.subtotalAmount = number(
                    order,
                    "subtotalAmount",
                    "subtotal_amount",
                    "food_subtotal",
                    "subtotal"
            );
            result.discountAmount = number(
                    order,
                    "discountAmount",
                    "discount_amount",
                    "discount"
            );
            result.serviceFee = number(order, "serviceFee", "service_fee");
            result.smallOrderFee = number(
                    order,
                    "smallOrderFee",
                    "small_order_fee"
            );
            result.deliveryCharge = number(
                    order,
                    "deliveryCharge",
                    "delivery_charge"
            );
            result.tipAmount = number(order, "tipAmount", "tip_amount");
            result.totalAmount = number(
                    order,
                    "totalAmount",
                    "total_amount",
                    "grand_total"
            );

            // copy_label is deliberately ignored. No KITCHEN COPY or
            // REPRINT / COPY text is printed on the new receipt.
            return result;
        }
    }
}
