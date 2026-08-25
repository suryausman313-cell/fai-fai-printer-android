package com.faifai.printer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

/** Starts background order checking and restores the dedicated Fai Fai home after reboot. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            Intent service = new Intent(context, KitchenOrderService.class);
            service.setAction(KitchenOrderService.ACTION_START);
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(service);
            else context.startService(service);
        } catch (Exception ignored) {
        }

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                Intent home = new Intent(context, MainActivity.class);
                home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                context.startActivity(home);
            } catch (Exception ignored) {
            }
        }, 2500L);
    }
}
