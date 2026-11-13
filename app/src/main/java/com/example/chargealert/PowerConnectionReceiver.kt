package com.example.chargealert

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class PowerConnectionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!Prefs.isEnabled(context)) return
        when (intent.action) {
            Intent.ACTION_POWER_CONNECTED -> {
                runCatching { BatteryMonitorService.start(context) }
                ChargeScheduler.onPlugged(context)
            }
            Intent.ACTION_POWER_DISCONNECTED -> {
                BatteryMonitorService.stop(context)
                ChargeScheduler.onUnplugged(context)
            }
        }
    }
}
