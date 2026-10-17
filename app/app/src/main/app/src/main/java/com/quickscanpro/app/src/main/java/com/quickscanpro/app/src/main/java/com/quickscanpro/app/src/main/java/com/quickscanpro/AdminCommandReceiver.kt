package com.quickscanpro

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AdminCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getStringExtra("command")) {
            "UNLOCK" -> {
                val serviceIntent = Intent(context, QuickScanService::class.java)
                serviceIntent.action = "UNLOCK"
                context.startService(serviceIntent)
            }
            "STOP_SOUND" -> {
                val serviceIntent = Intent(context, QuickScanService::class.java)
                serviceIntent.action = "STOP_SOUND"
                context.startService(serviceIntent)
            }
        }
    }
}
