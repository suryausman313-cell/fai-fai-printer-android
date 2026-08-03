package com.faifai.printer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.Locale;

final class NetworkReceiptPrinter {
    private static final Charset PRINTER_CHARSET = Charset.forName("CP437");

    private NetworkReceiptPrinter() {}

    static void print(String payloadJson) throws Exception {
        JSONObject root = new JSONObject(payloadJson);
        Receipt receipt = Receipt.from(root);

        if (receipt.printerIp.isEmpty()) {
            throw new IllegalArgumentException("Printer IP is missing");
        }

        byte[] data = render(receipt);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(receipt.printerIp, receipt.printerPort), 6000);
            socket.setSoTimeout(10000);
            try (OutputStream output = socket.getOutputStream()) {
                output.write(data);
                output.flush();
            }
        }
    }

    private static byte[] render(Receipt receipt) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int width = receipt.paperWidth.equals("58mm") ? 32 : 48;

        command(out, 0x1B, 0x40); // Initialize
        align(out, 1);
        bold(out, true);
        size(out, 2);
        line(out, receipt.restaurantName);
        size(out, 0);
        bold(out, false);
        line(out, receipt.headerText);
        line(out, repeat('-', width));

        bold(out, true);
        size(out, 1);
        line(out, "ORDER #" + receipt.orderId);
        size(out, 0);
        line(out, receipt.copyLabel);
        bold(out, false);

        align(out, 0);
        pair(out, "Type", receipt.orderType, width);
        pair(out, "Time", receipt.createdAt, width);
        if (!receipt.customerName.isEmpty()) line(out, "Customer: " + receipt.customerName);
        if (receipt.showCustomerPhone && !receipt.customerPhone.isEmpty()) {
            line(out, "Phone: " + receipt.customerPhone);
        }
        if (receipt.showCustomerAddress && !receipt.customerAddress.isEmpty()) {
            wrapped(out, "Address: " + receipt.customerAddress, width);
        }
        if (!receipt.customerNote.isEmpty()) {
            wrapped(out, "Note: " + receipt.customerNote, width);
        }
        if (receipt.showPaymentMethod && !receipt.paymentMethod.isEmpty()) {
            line(out, "Payment: " + receipt.paymentMethod);
        }

        line(out, repeat('-', width));
        for (int i = 0; i < receipt.items.length(); i++) {
            JSONObject item = receipt.items.optJSONObject(i);
            if (item == null) continue;

            int quantity = Math.max(1, item.optInt("quantity", 1));
            String name = first(item, "name", "item_name", "title");
            String sizeName = first(item, "size", "size_name");
            String itemLabel = quantity + " x " + (name.isEmpty() ? "Item" : name);
            if (!sizeName.isEmpty()) itemLabel += " (" + sizeName + ")";

            double price = number(item, "price", "total_price", "unit_price");
            if (receipt.showItemPrices && price > 0) {
                pair(out, itemLabel, money(price), width);
            } else {
                wrapped(out, itemLabel, width);
            }

            JSONArray extras = array(item, "extras", "selected_extras", "toppings");
            for (int extraIndex = 0; extraIndex < extras.length(); extraIndex++) {
                Object extra = extras.opt(extraIndex);
                String extraText;
                if (extra instanceof JSONObject) {
                    extraText = first((JSONObject) extra, "name", "title", "label");
                } else {
                    extraText = String.valueOf(extra == null ? "" : extra);
                }
                if (!extraText.trim().isEmpty()) wrapped(out, "  + " + extraText, width);
            }
        }

        if (receipt.showOrderTotals) {
            line(out, repeat('-', width));
            if (receipt.serviceFee > 0) pair(out, "Service fee", money(receipt.serviceFee), width);
            if (receipt.smallOrderFee > 0) pair(out, "Small order fee", money(receipt.smallOrderFee), width);
            if (receipt.deliveryCharge > 0) pair(out, "Delivery", money(receipt.deliveryCharge), width);
            if (receipt.tipAmount > 0) pair(out, "Tip", money(receipt.tipAmount), width);
            bold(out, true);
            size(out, 1);
            pair(out, "TOTAL", money(receipt.totalAmount), width);
            size(out, 0);
            bold(out, false);
        }

        align(out, 1);
        line(out, repeat('-', width));
        wrapped(out, receipt.footerText, width);
        line(out, "");
        line(out, "");
        line(out, "");
        if (receipt.cutPaper) command(out, 0x1D, 0x56, 0x00);
        return out.toByteArray();
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

    private static void size(ByteArrayOutputStream out, int value) {
        int mode = value == 2 ? 0x22 : value == 1 ? 0x11 : 0x00;
        command(out, 0x1D, 0x21, mode);
    }

    private static void line(ByteArrayOutputStream out, String text) throws Exception {
        out.write(printable(text).getBytes(PRINTER_CHARSET));
        out.write('\n');
    }

    private static void wrapped(ByteArrayOutputStream out, String text, int width) throws Exception {
        String remaining = printable(text).trim();
        if (remaining.isEmpty()) return;
        while (remaining.length() > width) {
            int cut = remaining.lastIndexOf(' ', width);
            if (cut < width / 2) cut = width;
            line(out, remaining.substring(0, cut).trim());
            remaining = remaining.substring(cut).trim();
        }
        if (!remaining.isEmpty()) line(out, remaining);
    }

    private static void pair(ByteArrayOutputStream out, String left, String right, int width) throws Exception {
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
        for (int i = 0; i < Math.max(0, count); i++) builder.append(value);
        return builder.toString();
    }

    private static String padLeft(String value, int width) {
        if (value.length() >= width) return value;
        return repeat(' ', width - value.length()) + value;
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
                } catch (Exception ignored) {}
            }
        }
        return new JSONArray();
    }

    private static String first(JSONObject source, String... keys) {
        for (String key : keys) {
            String value = source.optString(key, "").trim();
            if (!value.isEmpty() && !value.equalsIgnoreCase("null")) return value;
        }
        return "";
    }

    private static double number(JSONObject source, String... keys) {
        for (String key : keys) {
            if (!source.has(key) || source.isNull(key)) continue;
            try {
                return source.optDouble(key, Double.parseDouble(source.optString(key, "0")));
            } catch (Exception ignored) {}
        }
        return 0;
    }

    private static int integer(JSONObject source, int fallback, String... keys) {
        for (String key : keys) {
            if (!source.has(key) || source.isNull(key)) continue;
            try {
                return source.optInt(key, Integer.parseInt(source.optString(key, String.valueOf(fallback))));
            } catch (Exception ignored) {}
        }
        return fallback;
    }

    private static boolean bool(JSONObject source, boolean fallback, String... keys) {
        for (String key : keys) {
            if (source.has(key) && !source.isNull(key)) return source.optBoolean(key, fallback);
        }
        return fallback;
    }

    private static final class Receipt {
        String printerIp;
        int printerPort;
        String paperWidth;
        boolean cutPaper;
        String restaurantName;
        String headerText;
        String footerText;
        boolean showCustomerPhone;
        boolean showCustomerAddress;
        boolean showPaymentMethod;
        boolean showItemPrices;
        boolean showOrderTotals;
        String orderId;
        String createdAt;
        String orderType;
        String customerName;
        String customerPhone;
        String customerAddress;
        String customerNote;
        String paymentMethod;
        JSONArray items;
        double serviceFee;
        double smallOrderFee;
        double deliveryCharge;
        double tipAmount;
        double totalAmount;
        String copyLabel;

        static Receipt from(JSONObject root) {
            JSONObject printer = object(root, "printer");
            JSONObject settings = object(root, "settings");
            JSONObject branding = object(root, "receipt");
            JSONObject order = object(root, "order");

            Receipt result = new Receipt();
            result.printerIp = first(printer, "ip");
            if (result.printerIp.isEmpty()) result.printerIp = first(settings, "printer_ip", "printerIp");
            result.printerPort = integer(printer, 9100, "port");
            if (printer.length() == 0) result.printerPort = integer(settings, 9100, "printer_port", "printerPort");
            result.paperWidth = first(printer, "paperWidth", "paper_width");
            if (result.paperWidth.isEmpty()) result.paperWidth = first(settings, "paper_width", "paperWidth");
            if (!result.paperWidth.equals("58mm")) result.paperWidth = "80mm";
            result.cutPaper = bool(printer, bool(settings, true, "cut_paper", "cutPaper"), "cutPaper", "cut_paper");

            result.restaurantName = first(branding, "restaurantName", "restaurant_name");
            if (result.restaurantName.isEmpty()) result.restaurantName = first(settings, "restaurant_name", "restaurantName");
            if (result.restaurantName.isEmpty()) result.restaurantName = "Fai Fai Juice";
            result.headerText = first(branding, "headerText", "header_text");
            if (result.headerText.isEmpty()) result.headerText = first(settings, "header_text", "headerText");
            result.footerText = first(branding, "footerText", "footer_text");
            if (result.footerText.isEmpty()) result.footerText = first(settings, "footer_text", "footerText");
            if (result.footerText.isEmpty()) result.footerText = "Thank you for your order!";

            result.showCustomerPhone = bool(branding, bool(settings, true, "show_customer_phone"), "showCustomerPhone");
            result.showCustomerAddress = bool(branding, bool(settings, true, "show_customer_address"), "showCustomerAddress");
            result.showPaymentMethod = bool(branding, bool(settings, true, "show_payment_method"), "showPaymentMethod");
            result.showItemPrices = bool(branding, bool(settings, true, "show_item_prices"), "showItemPrices");
            result.showOrderTotals = bool(branding, bool(settings, true, "show_order_totals"), "showOrderTotals");

            result.orderId = first(order, "id", "order_id");
            result.createdAt = first(order, "createdAt", "created_at", "order_time");
            result.orderType = first(order, "type", "order_type");
            result.customerName = first(order, "customerName", "customer_name");
            result.customerPhone = first(order, "customerPhone", "customer_phone");
            result.customerAddress = first(order, "customerAddress", "delivery_address", "address");
            result.customerNote = first(order, "customerNote", "customer_note", "order_notes", "notes");
            result.paymentMethod = first(order, "paymentMethod", "payment_method");
            result.items = array(order, "items", "items_json");
            result.serviceFee = number(order, "serviceFee", "service_fee");
            result.smallOrderFee = number(order, "smallOrderFee", "small_order_fee");
            result.deliveryCharge = number(order, "deliveryCharge", "delivery_charge");
            result.tipAmount = number(order, "tipAmount", "tip_amount");
            result.totalAmount = number(order, "totalAmount", "total_amount", "grand_total");

            result.copyLabel = first(root, "copy_label");
            if (result.copyLabel.isEmpty()) {
                String mode = first(root, "mode");
                result.copyLabel = mode.equalsIgnoreCase("reprint") ? "REPRINT / COPY" : "KITCHEN COPY";
            }
            return result;
        }
    }
}
