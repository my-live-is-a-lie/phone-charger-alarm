package com.example.chargealert

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat

/**
 * Foreground service that lives ONLY while the charger is connected.
 *
 * It is started when the charger is plugged in (and monitoring is enabled) and stops itself,
 * removing its notification, as soon as the charger is unplugged or monitoring is disabled.
 * While it runs it listens to every battery-level change, so the threshold is detected
 * immediately instead of depending on alarms the system may delay.
 */
class BatteryMonitorService : Service() {
    private var receiver: BroadcastReceiver? = null
    private var shownPercent = -1
    private var shownThreshold = -1
    private var lastLogged = ""

    // Some phones (Infinix/XOS, Samsung...) put the CPU to sleep with the screen off and hold back
    // both alarms and battery broadcasts, even for apps with a foreground service. While the
    // threshold has not been reached we keep the CPU awake with a partial wake lock and poll from
    // a Handler, which does not depend on AlarmManager or on the system delivering broadcasts.
    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var pollScheduled = false
    private var pollCount = 0
    private var lastPollAt = 0L

    private val poll = object : Runnable {
        override fun run() {
            pollScheduled = false
            pollCount++
            val service = this@BatteryMonitorService
            val info = BatteryUtils.read(service)
            // gap = الزمن الفعلي منذ الفحص السابق، ويجب أن يكون حوالي 20 ثانية إذا كان المعالج مستيقظاً
            val nowElapsed = SystemClock.elapsedRealtime()
            val gap = if (lastPollAt == 0L) 0L else (nowElapsed - lastPollAt) / 1000
            lastPollAt = nowElapsed
            DebugLog.add(service, "poll #$pollCount pct=${info.percent} plugged=${info.plugged} gap=${gap}s")
            if (!info.plugged && ChargeScheduler.isUnplugConfirmed(service)) {
                ChargeScheduler.onUnplugged(service)
                service.syncWork()
                return
            }
            service.ensureNotificationShown(info.percent)
            ChargeScheduler.check(service)
            syncWork()
        }
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        starting = false
        createMonitorChannel()

        val info = BatteryUtils.read(this)
        DebugLog.add(this, "service created pct=${info.percent} plugged=${info.plugged} enabled=${Prefs.isEnabled(this)}")
        val notification = buildNotification(info.percent)
        startAsForeground(notification)
        logNotificationState("service created")
        handler.postDelayed({ logNotificationState("1.5s later") }, 1500)

        // Nothing to watch: monitoring is off or the charger is not connected.
        if (!Prefs.isEnabled(this)) {
            stopSelf()
            return
        }

        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != Intent.ACTION_BATTERY_CHANGED) return
                val current = BatteryUtils.parse(intent)
                val tag = "${current.percent}/${current.plugged}"
                if (tag != lastLogged) {
                    lastLogged = tag
                    DebugLog.add(context, "battery event pct=${current.percent} plugged=${current.plugged}")
                }
                if (current.plugged) {
                    updateNotification(current.percent)
                    ChargeScheduler.check(context)
                    syncWork()
                } else {
                    // الشاحن مفصول: ننهي الجلسة بعد التأكد، والخدمة تبقى تعمل بانتظار التوصيل التالي
                    if (ChargeScheduler.isUnplugConfirmed(context)) ChargeScheduler.onUnplugged(context)
                    syncWork()
                }
            }
        }.also {
            ContextCompat.registerReceiver(
                this,
                it,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                ContextCompat.RECEIVER_EXPORTED
            )
        }
        ChargeScheduler.check(this)
        syncWork()
    }

    /** يبقي المعالج مستيقظاً ويفحص كل 20 ثانية إلى أن يصدر التنبيه */
    private fun syncWork() {
        if (!BatteryUtils.read(this).plugged || Prefs.alertedAt(this) != 0L) {
            handler.removeCallbacks(poll)
            pollScheduled = false
            releaseWakeLock()
            return
        }
        if (wakeLock?.isHeld != true) acquireWakeLock()
        if (!pollScheduled) {
            pollScheduled = true
            handler.postDelayed(poll, POLL_INTERVAL_MS)
        }
    }

    private fun acquireWakeLock() {
        runCatching {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "chargealert:monitor").apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
            DebugLog.add(this, "wakelock acquired")
        }.onFailure { DebugLog.add(this, "wakelock FAILED: ${it.javaClass.simpleName}") }
    }

    private fun releaseWakeLock() {
        val lock = wakeLock ?: return
        runCatching { if (lock.isHeld) lock.release() }
        wakeLock = null
        DebugLog.add(this, "wakelock released")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running = false
        DebugLog.add(this, "service destroyed")
        handler.removeCallbacksAndMessages(null)
        releaseWakeLock()
        receiver?.let { runCatching { unregisterReceiver(it) } }
        receiver = null
        // Make sure the status-bar notification disappears together with the service.
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** إشعار بلا نص قدر الإمكان: أهمية دنيا، بلا صوت، مخفي في شاشة القفل */
    private fun buildNotification(@Suppress("UNUSED_PARAMETER") percent: Int): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_battery)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .apply {
                if (Build.VERSION.SDK_INT >= 31) {
                    setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                }
            }
            .build()
    }

    private fun startAsForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /** يسجّل هل الإشعار الدائم ظاهر فعلاً وحالة قناته */
    private fun logNotificationState(tag: String) {
        val nm = getSystemService(NotificationManager::class.java)
        val importance = nm.getNotificationChannel(CHANNEL_ID)?.importance
        val shown = nm.activeNotifications.any { it.id == NOTIFICATION_ID }
        DebugLog.add(
            this,
            "$tag: monitor notification shown=$shown channelImportance=$importance appNotifsEnabled=${nm.areNotificationsEnabled()}"
        )
    }

    /** إذا أزال المستخدم الإشعار الدائم (أندرويد 13+ يسمح بذلك) نعيد إظهاره */
    private fun ensureNotificationShown(percent: Int) {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.activeNotifications.none { it.id == NOTIFICATION_ID }) {
            runCatching { startAsForeground(buildNotification(percent)) }
            DebugLog.add(this, "monitor notification was missing -> re-posted")
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun updateNotification(percent: Int) {
        // الإشعار ثابت وبلا نص، فلا شيء يحتاج إلى تحديث
    }

    private fun createMonitorChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel(OLD_CHANNEL_ID)
        nm.deleteNotificationChannel(PREVIOUS_CHANNEL_ID)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.monitor_notification_channel),
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        }
        nm.createNotificationChannel(channel)
    }

    companion object {
        private const val OLD_CHANNEL_ID = "charge_monitor"
        private const val PREVIOUS_CHANNEL_ID = "charge_monitor_v2"
        private const val CHANNEL_ID = "charge_monitor_v3"
        private const val NOTIFICATION_ID = 3
        private const val POLL_INTERVAL_MS = 20_000L
        private const val WAKE_LOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L

        @Volatile private var running = false
        @Volatile private var starting = false

        /**
         * Starts the service if it is not already running. Call only while the charger is
         * connected. Returns false when Android refuses to start a foreground service from the
         * background (Android 12+); the caller then retries from an exact alarm, which is an
         * allowed context.
         */
        fun ensureRunning(context: Context): Boolean {
            if (running || starting) return true
            val app = context.applicationContext
            return try {
                starting = true
                ContextCompat.startForegroundService(app, Intent(app, BatteryMonitorService::class.java))
                DebugLog.add(app, "FGS start requested")
                true
            } catch (e: RuntimeException) {
                starting = false
                DebugLog.add(app, "FGS start REFUSED: ${e.javaClass.simpleName}")
                false
            }
        }

        fun stop(context: Context) {
            // If a start is still pending, onCreate() re-checks the state and stops itself;
            // stopping it before startForeground() is called would crash the app.
            if (!running) return
            val app = context.applicationContext
            runCatching { app.stopService(Intent(app, BatteryMonitorService::class.java)) }
        }
    }
}
