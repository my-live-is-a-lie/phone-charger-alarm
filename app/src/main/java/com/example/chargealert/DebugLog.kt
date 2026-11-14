package com.example.chargealert

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** سجل أحداث صغير (آخر 60 سطراً) لمعرفة ما يحدث فعلاً عندما تكون الشاشة مغلقة */
object DebugLog {
    private const val FILE = "debug_log"
    private const val KEY = "lines"
    private const val MAX_LINES = 60

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    @Synchronized
    fun add(context: Context, message: String) {
        runCatching {
            val p = prefs(context)
            val lines = (p.getString(KEY, "") ?: "").split('\n').filter { it.isNotBlank() }
            // لا نكرر نفس الرسالة مرتين متتاليتين
            if (lines.isNotEmpty() && lines.last().substringAfter(' ').substringAfter(' ') == message) return
            val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())
            val updated = (lines + "$stamp $message").takeLast(MAX_LINES).joinToString("\n")
            p.edit().putString(KEY, updated).commit()
        }
    }

    /** حالة الشاشة وقفلها لحظة الحدث */
    fun screenState(context: Context): String {
        val pm = context.getSystemService(android.os.PowerManager::class.java)
        val km = context.getSystemService(android.app.KeyguardManager::class.java)
        val bucket = runCatching {
            context.getSystemService(android.app.usage.UsageStatsManager::class.java).appStandbyBucket
        }.getOrNull()
        val restricted = runCatching {
            context.getSystemService(android.app.ActivityManager::class.java).isBackgroundRestricted
        }.getOrNull()
        // bucket: 10 نشط، 20 مستخدم، 30 متكرر، 40 نادر، 45 مقيّد
        return "interactive=${pm.isInteractive} locked=${km.isKeyguardLocked} bucket=$bucket restricted=$restricted"
    }

    fun read(context: Context): String = prefs(context).getString(KEY, "") ?: ""

    @Synchronized
    fun clear(context: Context) {
        prefs(context).edit().remove(KEY).commit()
    }
}
