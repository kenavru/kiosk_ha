package com.example.kiosk;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the kiosk automatically when the device boots. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            context.startActivity(new Intent(context, KioskActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }
}
