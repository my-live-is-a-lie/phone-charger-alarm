package com.example.chargealert

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

data class BatteryInfo(val percent: Int, val plugged: Boolean)

object BatteryUtils {
    fun parse(intent: Intent): BatteryInfo {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else 0
        return BatteryInfo(percent, plugged)
    }

    fun read(context: Context): BatteryInfo {
        // BatteryManager reads the live state directly. The sticky BATTERY_CHANGED
        // broadcast can go stale while the screen is off (the system throttles it),
        // which once made a check read plugged=false while the charger was still
        // connected, ending the session instead of firing the alert.
        val bm = context.getSystemService(BatteryManager::class.java)
        val percent = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val status = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
            ?: BatteryManager.BATTERY_STATUS_UNKNOWN
        if (percent in 1..100 && status != BatteryManager.BATTERY_STATUS_UNKNOWN) {
            val plugged = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            return BatteryInfo(percent, plugged)
        }
        // Fallback to the sticky broadcast if BatteryManager has no data.
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return if (intent != null) parse(intent) else BatteryInfo(0, false)
    }
}
