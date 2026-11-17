package com.example.chargealert

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import java.util.concurrent.atomic.AtomicInteger

object Notifications {
    private const val OLD_CHANNEL_STATUS = "status"
    const val ID_ALERT = 2
    const val EXTRA_PERCENT = "extra_battery_percent"
    private const val RETRY_MS = 5_000L

    /** True from alert() until cancelAlert(): the 5 s retry loop runs only while this is set. */
    @Volatile private var alertActive = false

    /** يميّز حلقة إعادة المحاولة الحالية عن حلقات تنبيهات سابقة */
    private val retryGeneration = AtomicInteger()
    private val mainHandler = Handler(Looper.getMainLooper())

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
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    /** ينفّذ خطوة من خطوات التنبيه؛ فشل أي خطوة يُسجَّل ولا يمنع الخطوات التالية */
    private fun step(context: Context, name: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            
        }
    }

    fun alert(context: Context, percent: Int) {
        val app = context.applicationContext
        alertActive = true
        val generation = retryGeneration.incrementAndGet()
        DiagStats.lastAlertAtWall = System.currentTimeMillis()
        

        // 1) الصوت أولاً: حتى لو فشلت أي خطوة لاحقة (إشعار، شاشة) يبقى التنبيه مسموعاً.
        step(app, "sound") { AlarmPlayer.start(app) }
        // 2) إيقاظ الشاشة قبل أي عمل بطيء، فبعض الأجهزة تعيد النوم سريعاً.
        step(app, "wake") { wakeScreen(app) }
        step(app, "channels") { createChannels(app) }
        step(app, "environment") { logAlertEnvironment(app) }
        // 3) الإشعار دائماً (شبكة الأمان): منذ أندرويد 15 قد يُمنع فتح النشاط من الخلفية بصمت.
        step(app, "notification") { postAlertNotification(app, percent) }
        // 4) شاشة التنبيه الكاملة
        step(app, "overlay") { launchOverlay(app, percent, "first") }
        // 5) إعادة المحاولة كل 5 ثوانٍ حتى تظهر الشاشة أو يُغلق التنبيه
        step(app, "retry") { scheduleRetry(app, percent, generation) }
    }

    private fun logAlertEnvironment(app: Context) {
        val nm = app.getSystemService(NotificationManager::class.java)
        val am = app.getSystemService(AudioManager::class.java)
        val importance = nm.getNotificationChannel(channelId(app))?.importance
        val fsi = if (android.os.Build.VERSION.SDK_INT >= 34) nm.canUseFullScreenIntent() else true
        val vol = am.getStreamVolume(AudioManager.STREAM_ALARM)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        val dnd = nm.currentInterruptionFilter
        

        if (dnd == NotificationManager.INTERRUPTION_FILTER_NONE) {
            
        }

    }

    private fun postAlertNotification(app: Context, percent: Int) {
        val overlay = overlayIntent(app, percent)
        val notification = NotificationCompat.Builder(app, channelId(app))
            .setSmallIcon(R.drawable.ic_battery)
            .setContentTitle(app.getString(R.string.alert_title, percent))
            .setContentText(app.getString(R.string.alert_text))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(false)
            .setOngoing(true)
            .setFullScreenIntent(overlay, true)
            .setContentIntent(overlay)
            .addAction(0, app.getString(R.string.alert_stop), stopIntent(app))
            .setDeleteIntent(stopIntent(app))
            .build()
            .apply { flags = flags or Notification.FLAG_INSISTENT }
        val nm = app.getSystemService(NotificationManager::class.java)
        // إشعار قديم بنفس المعرّف يجعل النظام يعامل الإرسال كتحديث فلا يُطلق شاشة التنبيه الكاملة.
        nm.cancel(ID_ALERT)
        nm.notify(ID_ALERT, notification)
        val posted = nm.activeNotifications.any { it.id == ID_ALERT }
        
    }

    private fun launchOverlay(app: Context, percent: Int, why: String) {
        if (!Settings.canDrawOverlays(app)) {
            
            return
        }
        try {
            app.startActivity(
                Intent(app, AlertOverlayActivity::class.java)
                    .putExtra(EXTRA_PERCENT, percent)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
            
        } catch (t: Throwable) {
            
        }
    }

    private fun scheduleRetry(app: Context, percent: Int, generation: Int) {
        mainHandler.postDelayed(object : Runnable {
            override fun run() {
                if (!alertActive || retryGeneration.get() != generation) return
                if (AlertOverlayActivity.shown) {
                    
                    return
                }
                val pm = app.getSystemService(PowerManager::class.java)
                if (!pm.isInteractive) wakeScreen(app)
                step(app, "retry-sound") { AlarmPlayer.start(app) }
                launchOverlay(app, percent, "retry")
                
                mainHandler.postDelayed(this, RETRY_MS)
            }
        }, RETRY_MS)
    }

    /** يوقظ الشاشة عند التنبيه: قفل شاشة خافت مع إيقاظ، فقط إذا كانت الشاشة مقفلة أو غير مضيئة */
    @Suppress("DEPRECATION")
    private fun wakeScreen(context: Context) {
        runCatching {
            val pm = context.getSystemService(PowerManager::class.java)
            val km = context.getSystemService(KeyguardManager::class.java)
            // لا حاجة للإيقاظ إذا كانت الشاشة مضيئة وغير مقفلة
            if (pm.isInteractive && km?.isKeyguardLocked != true) return
            val lock = pm.newWakeLock(
                PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "chargealert:launching"
            )
            lock.acquire(5_000L)
            
        }.onFailure {  }
    }

    fun cancelAlert(context: Context) {
        alertActive = false
        retryGeneration.incrementAndGet()
        AlarmPlayer.stop(context)
        context.getSystemService(NotificationManager::class.java).cancel(ID_ALERT)
    }
}
