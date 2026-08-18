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

        String pin = context.getSharedPreferences("fai_fai_kitchen", Context.MODE_PRIVATE)
                .getString("pin", "");
        if (pin == null || pin.trim().length() < 4) {
            return;
        }

        Intent service = new Intent(context, KitchenOrderService.class);
        service.setAction(KitchenOrderService.ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(service);
            } else {
                context.startService(service);
            }
        } catch (Exception ignored) {
            // Some manufacturers defer boot-time background work. Opening
            // Fai Fai Kitchen once will start the same service again.
        }
    }
}
