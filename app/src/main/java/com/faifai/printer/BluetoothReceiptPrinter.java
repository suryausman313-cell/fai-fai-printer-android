package com.faifai.printer;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import java.io.OutputStream;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * ESC/POS Bluetooth fallback for handheld POS terminals.
 *
 * Some P58 firmwares expose the built-in 58mm printer as a paired Bluetooth
 * ESC/POS device. This class uses that path without adding a third-party SDK.
 * If the firmware does not expose the printer over Bluetooth, PrinterRouter
 * falls back to the configured network printer and reports that the vendor
 * SDK is required for direct built-in printing.
 */
final class BluetoothReceiptPrinter {
    private static final UUID SPP_UUID = UUID.fromString(
            "00001101-0000-1000-8000-00805F9B34FB"
    );

    private BluetoothReceiptPrinter() {}

    static String print(Context context, String payloadJson) throws Exception {
        if (Build.VERSION.SDK_INT >= 31
                && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            throw new IllegalStateException("Bluetooth permission is not granted");
        }

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            throw new IllegalStateException("Bluetooth is not available on this device");
        }
        if (!adapter.isEnabled()) {
            throw new IllegalStateException("Bluetooth is turned off");
        }

        BluetoothDevice printer = choosePrinter(adapter.getBondedDevices());
        if (printer == null) {
            throw new IllegalStateException(
                    "No paired P58/thermal printer found over Bluetooth"
            );
        }

        byte[] receipt = NetworkReceiptPrinter.renderReceipt(
                context,
                payloadJson,
                true,
                false
        );

        BluetoothSocket socket = null;
        try {
            socket = printer.createRfcommSocketToServiceRecord(SPP_UUID);
            adapter.cancelDiscovery();
            socket.connect();
            try (OutputStream output = socket.getOutputStream()) {
                output.write(receipt);
                output.flush();
            }
        } finally {
            if (socket != null) {
                try { socket.close(); } catch (Exception ignored) { }
            }
        }

        String name = safeName(printer);
        return name.isEmpty() ? "Bluetooth 58mm" : name;
    }

    static String findPrinterName(Context context) {
        try {
            if (Build.VERSION.SDK_INT >= 31
                    && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                return "";
            }
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) return "";
            BluetoothDevice device = choosePrinter(adapter.getBondedDevices());
            return device == null ? "" : safeName(device);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static BluetoothDevice choosePrinter(Set<BluetoothDevice> devices) {
        if (devices == null || devices.isEmpty()) return null;

        BluetoothDevice best = null;
        int bestScore = 0;

        for (BluetoothDevice device : devices) {
            String name = safeName(device).toLowerCase(Locale.US);
            int score = 0;

            if (name.contains("p58") || name.contains("pda58")) score += 100;
            if (name.contains("netum")) score += 80;
            if (name.contains("printer")) score += 60;
            if (name.contains("thermal")) score += 50;
            if (name.contains("inner") || name.contains("built-in") || name.contains("builtin")) score += 70;
            if (name.contains("pos")) score += 35;
            if (name.contains("esc") || name.contains("mpt") || name.contains("rpp")) score += 35;
            if (name.contains("58")) score += 15;

            try {
                BluetoothClass bluetoothClass = device.getBluetoothClass();
                if (bluetoothClass != null
                        && bluetoothClass.getMajorDeviceClass() == BluetoothClass.Device.Major.IMAGING) {
                    score += 45;
                }
            } catch (Exception ignored) { }

            // Avoid accidentally sending raw ESC/POS bytes to obvious audio devices.
            if (name.contains("buds") || name.contains("airpod")
                    || name.contains("head") || name.contains("speaker")) {
                score = 0;
            }

            if (score > bestScore) {
                bestScore = score;
                best = device;
            }
        }

        return bestScore >= 35 ? best : null;
    }

    private static String safeName(BluetoothDevice device) {
        try {
            String name = device.getName();
            return name == null ? "" : name.trim();
        } catch (Exception ignored) {
            return "";
        }
    }
}
