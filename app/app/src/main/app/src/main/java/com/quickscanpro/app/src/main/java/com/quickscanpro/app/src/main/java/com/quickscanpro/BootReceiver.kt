package com.quickscanpro

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_USER_PRESENT) {
            val serviceIntent = Intent(context, QuickScanService::class.java)
            context.startService(serviceIntent)
        }
    }
}
