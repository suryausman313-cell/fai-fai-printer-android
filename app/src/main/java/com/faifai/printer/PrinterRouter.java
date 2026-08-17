package com.faifai.printer;

import android.content.Context;
import android.os.Build;

import org.json.JSONObject;

import java.util.Locale;

/** Chooses the best available printer without changing the Kitchen web app. */
final class PrinterRouter {
    private PrinterRouter() {}

    static String print(Context context, String payloadJson) throws Exception {
        boolean p58 = looksLikeP58Device();
        String bluetoothFailure = "";

        // On the NETUM/P58 handheld, prefer its local 58mm printer path.
        if (p58) {
            try {
                String printerName = BluetoothReceiptPrinter.print(context, payloadJson);
                return "Built-in 58mm (" + printerName + ")";
            } catch (Exception error) {
                bluetoothFailure = message(error);
            }
        }

        // Existing shop/network printer remains fully supported as backup.
        if (NetworkReceiptPrinter.hasPrinterIp(payloadJson)) {
            NetworkReceiptPrinter.print(context, payloadJson);
            return "Network printer";
        }

        // If a non-P58 device has a paired ESC/POS printer, use it too.
        if (!p58) {
            try {
                String printerName = BluetoothReceiptPrinter.print(context, payloadJson);
                return "Bluetooth 58mm (" + printerName + ")";
            } catch (Exception error) {
                bluetoothFailure = message(error);
            }
        }

        if (p58) {
            throw new IllegalStateException(
                    "P58 detected, but its built-in printer is not exposed as a paired Bluetooth printer. "
                            + "NETUM Mobile POS Printer SDK is required for direct built-in printing."
                            + (bluetoothFailure.isEmpty() ? "" : " Bluetooth: " + bluetoothFailure)
            );
        }

        throw new IllegalStateException(
                "No printer available. Set a network printer IP or pair a 58mm ESC/POS printer."
                        + (bluetoothFailure.isEmpty() ? "" : " Bluetooth: " + bluetoothFailure)
        );
    }

    static String status(Context context, String payloadJson) {
        try {
            JSONObject result = new JSONObject();
            result.put("device", Build.MANUFACTURER + " " + Build.MODEL);
            result.put("p58", looksLikeP58Device());
            result.put("bluetoothPrinter", BluetoothReceiptPrinter.findPrinterName(context));
            result.put("networkPrinterConfigured", NetworkReceiptPrinter.hasPrinterIp(payloadJson));
            return result.toString();
        } catch (Exception error) {
            return "{\"error\":\"" + escape(message(error)) + "\"}";
        }
    }

    private static boolean looksLikeP58Device() {
        String text = (
                String.valueOf(Build.MANUFACTURER) + " "
                        + String.valueOf(Build.BRAND) + " "
                        + String.valueOf(Build.MODEL) + " "
                        + String.valueOf(Build.PRODUCT) + " "
                        + String.valueOf(Build.DEVICE)
        ).toLowerCase(Locale.US);

        return text.contains("netum")
                || text.contains("p58")
                || text.contains("pda58")
                || text.contains("pda-58");
    }

    private static String message(Throwable error) {
        if (error == null) return "";
        String value = error.getMessage();
        if (value == null || value.trim().isEmpty()) {
            value = error.getClass().getSimpleName();
        }
        return value.trim();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
