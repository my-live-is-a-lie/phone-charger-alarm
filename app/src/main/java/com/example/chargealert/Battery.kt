package com.example.chargealert

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build

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
        // Query the hardware battery driver directly via BatteryManager
        // This is critical when the screen is off, because ACTION_BATTERY_CHANGED
        // sticky broadcasts are throttled/suspended by the OS during sleep.
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val directCapacity = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1

        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val infoFromIntent = if (intent != null) parse(intent) else BatteryInfo(0, false)

        val finalPercent = if (directCapacity in 0..100) directCapacity else infoFromIntent.percent
        val isPlugged = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && bm != null) {
            bm.isCharging || infoFromIntent.plugged
        } else {
            infoFromIntent.plugged
        }

        return BatteryInfo(finalPercent, isPlugged)
    }
}
