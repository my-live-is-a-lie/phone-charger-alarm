package com.example.chargealert

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!Prefs.isEnabled(context)) return
        ChargeScheduler.start(context)
        // Android does not restore a foreground service across a reboot (or an app
        // update), so restart the battery listener explicitly when the charger is
        // connected. ChargeScheduler.start() already attempts this, but a second
        // explicit attempt here covers the case where the first start was refused
        // (Android 12+ background start) before the retry alarm could run.
        if (BatteryUtils.read(context).plugged) {
            BatteryMonitorService.ensureRunning(context)
        }
    }
}
