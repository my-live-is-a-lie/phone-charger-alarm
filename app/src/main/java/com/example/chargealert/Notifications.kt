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
import android.provider.Settings
import androidx.core.app.NotificationCompat

object Notifications {
    private const val OLD_CHANNEL_STATUS = "status"
    const val ID_ALERT = 2
    const val EXTRA_PERCENT = "extra_battery_percent"

    private fun channelId(context: Context): String {
        val selected = Prefs.alertSoundUri(context)
        val key = (selected ?: "default").hashCode().toUInt().toString(16)
        return "alert_sound_" + key
    }

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel(OLD_CHANNEL_STATUS)
        val selectedUri = Prefs.alertSoundUri(context)?.let { Uri.parse(it) }
        val soundUri = selectedUri ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val alert = NotificationChannel(
            channelId(context),
            context.getString(R.string.channel_alert),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            setSound(
                soundUri,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 600, 300, 600)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(alert)
    }

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
        createChannels(context)
        run {
            val nm = context.getSystemService(NotificationManager::class.java)
            val importance = nm.getNotificationChannel(channelId(context))?.importance
            val fsi = if (android.os.Build.VERSION.SDK_INT >= 34) nm.canUseFullScreenIntent() else true
            DebugLog.add(
                context,
                "alert(): notifsEnabled=${nm.areNotificationsEnabled()} channelImportance=$importance overlay=${Settings.canDrawOverlays(context)} fullScreenIntent=$fsi"
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
            .build()
            .apply { flags = flags or Notification.FLAG_INSISTENT }
        runCatching {
            context.getSystemService(NotificationManager::class.java).notify(ID_ALERT, notification)
            DebugLog.add(context, "alert notification posted")
        }.onFailure { DebugLog.add(context, "alert notify FAILED: ${it.javaClass.simpleName}") }

        if (Settings.canDrawOverlays(context)) {
            runCatching {
                context.startActivity(
                    Intent(context, AlertOverlayActivity::class.java)
                        .putExtra(EXTRA_PERCENT, percent)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
            }
        }
    }

    fun cancelAlert(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(ID_ALERT)
    }
}
