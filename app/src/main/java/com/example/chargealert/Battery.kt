package com.example.chargealert

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/**
 * @param status  قيمة BatteryManager.BATTERY_STATUS_* (أو -1 إن لم تتوفر)
 * @param plugType قيمة BatteryManager.BATTERY_PLUGGED_* (0 = غير متصل)
 * @param source  من أين قُرئت الحالة (للتشخيص فقط)
 */
data class BatteryInfo(
    val percent: Int,
    val plugged: Boolean,
    val status: Int = -1,
    val plugType: Int = 0,
    val tempC: Float = -1f,
    val voltageMv: Int = -1,
    val source: String = ""
)

object BatteryUtils {
    fun parse(intent: Intent): BatteryInfo {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val plugType = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val volt = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
        val plugged = plugType != 0 ||
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else 0
        return BatteryInfo(
            percent = percent,
            plugged = plugged,
            status = status,
            plugType = plugType,
            tempC = if (temp == Int.MIN_VALUE) -1f else temp / 10f,
            voltageMv = volt,
            source = "broadcast"
        )
    }

    fun read(context: Context): BatteryInfo {
        // BatteryManager reads the live state directly. The sticky BATTERY_CHANGED
        // broadcast can lag while the screen is off, so the percentage comes from
        // BatteryManager. "Plugged" is decided from BOTH sources: some phones report
        // NOT_CHARGING / DISCHARGING while a charger is still connected (battery
        // protection, weak charger), which used to end the session by mistake.
        val sticky = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        val stickyInfo = sticky?.let { parse(it) }

        val bm = context.getSystemService(BatteryManager::class.java)
        val percent = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val status = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
            ?: BatteryManager.BATTERY_STATUS_UNKNOWN
        val statusValid = status != Int.MIN_VALUE && status != BatteryManager.BATTERY_STATUS_UNKNOWN
        if (percent in 1..100 && statusValid) {
            val bmCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            val stickyPlugged = stickyInfo?.plugType?.let { it != 0 } ?: false
            return BatteryInfo(
                percent = percent,
                plugged = bmCharging || stickyPlugged,
                status = status,
                plugType = stickyInfo?.plugType ?: 0,
                tempC = stickyInfo?.tempC ?: -1f,
                voltageMv = stickyInfo?.voltageMv ?: -1,
                source = if (bmCharging) "bm" else if (stickyPlugged) "bm+sticky" else "bm"
            )
        }
        // Fallback to the sticky broadcast if BatteryManager has no data.
        return stickyInfo ?: BatteryInfo(0, false, source = "none")
    }

    fun statusName(status: Int): String = when (status) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "CHARGING"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "DISCHARGING"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "NOT_CHARGING"
        BatteryManager.BATTERY_STATUS_FULL -> "FULL"
        BatteryManager.BATTERY_STATUS_UNKNOWN -> "UNKNOWN"
        else -> "?($status)"
    }

    fun plugName(plug: Int): String = when {
        plug == 0 -> "none"
        else -> buildList {
            if (plug and BatteryManager.BATTERY_PLUGGED_AC != 0) add("AC")
            if (plug and BatteryManager.BATTERY_PLUGGED_USB != 0) add("USB")
            if (plug and BatteryManager.BATTERY_PLUGGED_WIRELESS != 0) add("WIRELESS")
            if (isEmpty()) add("?($plug)")
        }.joinToString("+")
    }
}
