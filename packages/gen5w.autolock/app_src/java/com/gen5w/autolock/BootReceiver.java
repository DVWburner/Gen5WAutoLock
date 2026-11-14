package com.gen5w.autolock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts AutoLockBridgeService automatically when the head unit boots. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            context.startService(new Intent(context, AutoLockBridgeService.class));
        }
    }
}
