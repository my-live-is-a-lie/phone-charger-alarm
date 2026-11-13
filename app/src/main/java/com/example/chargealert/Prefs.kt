package com.example.chargealert

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private const val FILE = "charge_alert_prefs"
    const val KEY_THRESHOLD = "threshold"
    const val KEY_ENABLED = "enabled"
    const val DEFAULT_THRESHOLD = 80

    fun of(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun threshold(context: Context): Int =
        of(context).getInt(KEY_THRESHOLD, DEFAULT_THRESHOLD)

    fun setThreshold(context: Context, value: Int) {
        of(context).edit().putInt(KEY_THRESHOLD, value.coerceIn(50, 100)).apply()
    }

    fun isEnabled(context: Context): Boolean =
        of(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, value: Boolean) {
        of(context).edit().putBoolean(KEY_ENABLED, value).apply()
    }
}
