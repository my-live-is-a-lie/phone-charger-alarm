package com.example.chargealert
import android.content.Context
import android.content.SharedPreferences
object Prefs {
    private const val FILE = "charge_alert_prefs"
    const val KEY_THRESHOLD = "threshold"
    const val KEY_ENABLED = "enabled"
    const val KEY_ALERTED_AT = "alerted_at"
    private const val KEY_RATE = "learned_rate_ms_per_pct"
    private const val KEY_S_START_TIME = "session_start_time"
    private const val KEY_S_START_PCT = "session_start_pct"
    private const val KEY_S_LAST_TIME = "session_last_time"
    private const val KEY_S_LAST_PCT = "session_last_pct"
    const val DEFAULT_THRESHOLD = 80
    private const val DEFAULT_RATE_MS = 90_000L
    data class Session(val startTime: Long, val startPct: Int, val lastTime: Long, val lastPct: Int) {
        companion object { val EMPTY = Session(0L, 0, 0L, 0) }
    }
    fun of(context: Context): SharedPreferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    fun threshold(context: Context): Int = of(context).getInt(KEY_THRESHOLD, DEFAULT_THRESHOLD)
    fun setThreshold(context: Context, value: Int) { of(context).edit().putInt(KEY_THRESHOLD, value.coerceIn(50, 100)).apply() }
    fun isEnabled(context: Context): Boolean = of(context).getBoolean(KEY_ENABLED, false)
    fun setEnabled(context: Context, value: Boolean) { of(context).edit().putBoolean(KEY_ENABLED, value).apply() }
    fun alertedAt(context: Context): Long = of(context).getLong(KEY_ALERTED_AT, 0L)
    fun setAlertedAt(context: Context, value: Long) { of(context).edit().putLong(KEY_ALERTED_AT, value).apply() }
    fun learnedRate(context: Context): Long = of(context).getLong(KEY_RATE, DEFAULT_RATE_MS)
    fun setLearnedRate(context: Context, value: Long) { of(context).edit().putLong(KEY_RATE, value).apply() }
    fun session(context: Context): Session {
        val p=of(context)
        return Session(p.getLong(KEY_S_START_TIME,0L),p.getInt(KEY_S_START_PCT,0),p.getLong(KEY_S_LAST_TIME,0L),p.getInt(KEY_S_LAST_PCT,0))
    }
    fun setSession(context: Context, s: Session) {
        of(context).edit().putLong(KEY_S_START_TIME,s.startTime).putInt(KEY_S_START_PCT,s.startPct).putLong(KEY_S_LAST_TIME,s.lastTime).putInt(KEY_S_LAST_PCT,s.lastPct).apply()
    }
}
