package com.t527.wav2vecdemo;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Log.d("BootReceiver", "Boot completed, starting VadPipelineService");
            Intent serviceIntent = new Intent(context, VadPipelineService.class);
            context.startForegroundService(serviceIntent);
        }
    }
}
