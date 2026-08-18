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
        String localFailure = "";

        // IMPORTANT: Kitchen runs on a NETUM handheld with its own 58mm printer.
        // Always try the local/paired ESC/POS path BEFORE any configured IP printer.
        // Older builds only did this when Android Build.MODEL contained "P58"; many
        // NETUM firmwares report a generic model, so they incorrectly printed to IP.
        try {
            String printerName = BluetoothReceiptPrinter.print(context, payloadJson);
            return "Local 58mm (" + printerName + ")";
        } catch (Exception error) {
            localFailure = message(error);
        }

        // Keep the shop/network printer as a safe backup only when local printing
        // is unavailable. Existing IP printing therefore never gets broken.
        if (NetworkReceiptPrinter.hasPrinterIp(payloadJson)) {
            NetworkReceiptPrinter.print(context, payloadJson);
            return "Network printer (local unavailable: " + localFailure + ")";
        }

        if (p58) {
            throw new IllegalStateException(
                    "NETUM/P58 local printer was not found. Pair/enable the local 58mm printer "
                            + "or integrate NETUM Mobile POS Printer SDK."
                            + (localFailure.isEmpty() ? "" : " Local: " + localFailure)
            );
        }

        throw new IllegalStateException(
                "No printer available. Local 58mm: " + localFailure
                        + ". Configure a network printer only as backup."
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
