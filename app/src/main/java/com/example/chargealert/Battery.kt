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
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return if (intent != null) parse(intent) else BatteryInfo(0, false)
    }
}
