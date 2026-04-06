package com.t527.smart_service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("BootReceiver", "Boot completed, starting VoiceAiService")
            val svcIntent = Intent(context, VoiceAiService::class.java).apply {
                action = VoiceAiService.ACTION_MIC_GRANTED
            }
            context.startForegroundService(svcIntent)
        }
    }
}
