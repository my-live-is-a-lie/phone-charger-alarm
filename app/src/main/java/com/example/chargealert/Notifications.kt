package com.example.chargealert

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat

object Notifications {
    private const val OLD_CHANNEL_STATUS = "status"
    const val ID_ALERT = 2
    const val EXTRA_PERCENT = "extra_battery_percent"

    /** قناة التنبيه صامتة عمداً: الصوت والاهتزاز يتولاهما AlarmPlayer */
    private fun channelId(@Suppress("UNUSED_PARAMETER") context: Context): String = "alert_silent_v1"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel(OLD_CHANNEL_STATUS)
        val alert = NotificationChannel(
            channelId(context),
            context.getString(R.string.channel_alert),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(alert)
    }

    private fun stopIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            1,
            Intent(context, AlarmStopReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun overlayIntent(context: Context, percent: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, AlertOverlayActivity::class.java)
                .putExtra(EXTRA_PERCENT, percent)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    fun alert(context: Context, percent: Int) {
        // أول شيء: إيقاظ الشاشة قبل أي عمل بطيء (تجهيز الصوت وغيره)، فبعض الأجهزة تعيد النوم سريعاً.
        wakeScreen(context)
        createChannels(context)
        run {
            val nm = context.getSystemService(NotificationManager::class.java)
            val importance = nm.getNotificationChannel(channelId(context))?.importance
            val fsi = if (android.os.Build.VERSION.SDK_INT >= 34) nm.canUseFullScreenIntent() else true
            DebugLog.add(
                context,
                "alert(): notifsEnabled=${nm.areNotificationsEnabled()} channelImportance=$importance overlay=${Settings.canDrawOverlays(context)} fullScreenIntent=$fsi ${DebugLog.screenState(context)}"
            )
        }
        val overlay = overlayIntent(context, percent)

        // Always post the notification first. Since Android 15 a background app cannot reliably
        // start an activity (even with the overlay permission), and startActivity() does not
        // throw when the system silently blocks it. The notification (sound + full-screen intent)
        // is therefore the safety net. AlertOverlayActivity cancels it as soon as it is shown,
        // so the overlay still owns the audio when it manages to launch.
        val notification = NotificationCompat.Builder(context, channelId(context))
            .setSmallIcon(R.drawable.ic_battery)
            .setContentTitle(context.getString(R.string.alert_title, percent))
            .setContentText(context.getString(R.string.alert_text))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(false)
            .setOngoing(true)
            .setFullScreenIntent(overlay, true)
            .setContentIntent(overlay)
            .addAction(0, context.getString(R.string.alert_stop), stopIntent(context))
            .setDeleteIntent(stopIntent(context))
            .build()
            .apply { flags = flags or Notification.FLAG_INSISTENT }
        runCatching {
            val nm = context.getSystemService(NotificationManager::class.java)
            // إشعار قديم بنفس المعرّف يجعل النظام يعامل الإرسال كتحديث فلا يُطلق شاشة التنبيه الكاملة.
            nm.cancel(ID_ALERT)
            nm.notify(ID_ALERT, notification)
            DebugLog.add(context, "alert notification posted")
        }.onFailure { DebugLog.add(context, "alert notify FAILED: ${it.javaClass.simpleName}") }

        AlarmPlayer.start(context)

        if (Settings.canDrawOverlays(context)) {
            runCatching {
                context.startActivity(
                    Intent(context, AlertOverlayActivity::class.java)
                        .putExtra(EXTRA_PERCENT, percent)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
                DebugLog.add(context, "overlay startActivity called")
            }.onFailure { DebugLog.add(context, "overlay startActivity FAILED: ${it.javaClass.simpleName}") }
        }

        // بعد ثانيتين ونصف: إن لم تُضَأ الشاشة أو لم تظهر شاشة التنبيه نعيد محاولة الإيقاظ، ونسجّل
        // الحالة الفعلية في السجل لنعرف أي خطوة فشلت على هذا الجهاز.
        val app = context.applicationContext
        Handler(Looper.getMainLooper()).postDelayed({
            val pm = app.getSystemService(PowerManager::class.java)
            if (!pm.isInteractive) wakeScreen(app)
            DebugLog.add(
                app,
                "alert +2.5s: overlayShown=${AlertOverlayActivity.shown} ${DebugLog.screenState(app)}"
            )
        }, 2_500L)
    }

    /** يوقظ الشاشة لثوانٍ عند التنبيه حتى لو كانت مغلقة */
    @Suppress("DEPRECATION")
    private fun wakeScreen(context: Context) {
        runCatching {
            val pm = context.getSystemService(PowerManager::class.java)
            val lock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                "chargealert:alert"
            )
            lock.acquire(30_000L)
            DebugLog.add(context, "screen wake requested")
        }.onFailure { DebugLog.add(context, "screen wake FAILED: ${it.javaClass.simpleName}") }
    }

    fun cancelAlert(context: Context) {
        AlarmPlayer.stop(context)
        context.getSystemService(NotificationManager::class.java).cancel(ID_ALERT)
    }
}
