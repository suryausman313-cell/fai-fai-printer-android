package com.faifai.printer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }

        // Restore Device Owner / Lock Task allow-list after every reboot and
        // clean any persistent HOME preference left by older builds.
        boolean kioskReady = KioskManager.applyPolicies(context);

        String pin = context.getSharedPreferences("fai_fai_kitchen", Context.MODE_PRIVATE)
                .getString("pin", "");
        if (pin != null && pin.trim().length() >= 4) {
            Intent service = new Intent(context, KitchenOrderService.class);
            service.setAction(KitchenOrderService.ACTION_START);
            try {
                if (Build.VERSION.SDK_INT >= 26) {
                    context.startForegroundService(service);
                } else {
                    context.startService(service);
                }
            } catch (Exception ignored) {
                // MainActivity starts the same service again when opened.
            }
        }

        // Device Owner apps are allowed to bring their dedicated UI up after
        // boot on the NETUM terminal. MainActivity enters Lock Task in onResume.
        if (kioskReady) {
            try {
                Intent launch = new Intent(context, MainActivity.class);
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                context.startActivity(launch);
            } catch (Exception ignored) { }
        }
    }
}
