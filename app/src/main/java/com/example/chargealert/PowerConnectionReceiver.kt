package com.example.chargealert

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class PowerConnectionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_POWER_CONNECTED -> ChargeScheduler.onPlugged(context)
            Intent.ACTION_POWER_DISCONNECTED -> ChargeScheduler.onUnplugged(context)
        }
    }
}
