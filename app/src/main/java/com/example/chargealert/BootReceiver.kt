package com.example.chargealert

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Prefs.isEnabled(context)) {
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, ChargeMonitorService::class.java)
                )
            } catch (e: Exception) {
                // قد يمنع النظام البدء في بعض الحالات
            }
        }
    }
}
