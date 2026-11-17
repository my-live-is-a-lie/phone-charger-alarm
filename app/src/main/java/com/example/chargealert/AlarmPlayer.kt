package com.example.chargealert

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * يشغّل صوت المنبّه ويهتز من داخل التطبيق نفسه، دون الاعتماد على صوت قناة الإشعار الذي
 * لم يكن يُسمع عندما تكون الشاشة مغلقة. يستمر حتى يوقفه المستخدم أو يُفصل الشاحن
 * أو تمر 5 دقائق.
 */
object AlarmPlayer {
    private const val MAX_DURATION_MS = 5 * 60_000L

    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    val isPlaying: Boolean
        get() = synchronized(this) { runCatching { player?.isPlaying == true }.getOrDefault(false) }

    @Synchronized
    fun start(context: Context) {
        val app = context.applicationContext
        if (player != null) return

        val custom = Prefs.alertSoundUri(app)?.let { Uri.parse(it) }
        val default = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        player = tryPlay(app, custom, "custom") ?: tryPlay(app, default, "default")

        if (player == null) {
            DebugLog.e(app, "SOUND", "alarm sound FAILED to start (custom=$custom default=$default)")
            return
        }

        runCatching {
            val lock = app.getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "chargealert:alarm")
            lock.setReferenceCounted(false)
            lock.acquire(MAX_DURATION_MS)
            wakeLock = lock
        }
        runCatching {
            app.getSystemService(Vibrator::class.java)
                .vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 400), 0))
        }
        handler.postDelayed({ stop(app) }, MAX_DURATION_MS)
        val am = app.getSystemService(AudioManager::class.java)
        DebugLog.i(
            app, "SOUND",
            "alarm sound started playing=${runCatching { player?.isPlaying }.getOrNull()} alarmVol=${am.getStreamVolume(AudioManager.STREAM_ALARM)}/${am.getStreamMaxVolume(AudioManager.STREAM_ALARM)}"
        )
    }

    private fun tryPlay(context: Context, uri: Uri?, label: String): MediaPlayer? {
        if (uri == null) return null
        val mp = MediaPlayer()
        return try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            mp.setDataSource(context, uri)
            mp.isLooping = true
            mp.prepare()
            mp.start()
            mp
        } catch (e: Exception) {
            DebugLog.w(context, "SOUND", "tryPlay($label) failed: ${e.javaClass.simpleName}: ${e.message}")
            runCatching { mp.release() }
            null
        }
    }

    @Synchronized
    fun stop(context: Context) {
        val app = context.applicationContext
        handler.removeCallbacksAndMessages(null)
        val mp = player
        if (mp != null) {
            runCatching { mp.stop() }
            runCatching { mp.release() }
            player = null
            runCatching { app.getSystemService(Vibrator::class.java).cancel() }
            DebugLog.i(app, "SOUND", "alarm sound stopped")
        }
        wakeLock?.let { lock -> runCatching { if (lock.isHeld) lock.release() } }
        wakeLock = null
    }
}
