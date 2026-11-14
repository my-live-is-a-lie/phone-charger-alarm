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
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Foreground service that stays alive while monitoring is enabled.
 *
 * Detection follows the reference app (Battery Charged Alarm): the threshold is detected
 * purely from the BATTERY_CHANGED broadcast. The receiver is registered with priority 100
 * and every broadcast is processed on a background thread via goAsync(), so the system
 * holds the broadcast wake lock while the check runs. There is no periodic polling and no
 * continuous wake lock — the service simply waits for the system to deliver battery
 * changes, which keeps it light enough that the OS does not freeze it in the background.
 */
class BatteryMonitorService : Service() {
    private var receiver: BroadcastReceiver? = null
    private var lastLogged = ""
    private var shownPercent = -1
    private val handler = Handler(Looper.getMainLooper())
    private val batteryExecutor: ExecutorService = Executors.newSingleThreadExecutor()

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

        // Nothing to watch: monitoring is off.
        if (!Prefs.isEnabled(this)) {
            stopSelf()
            return
        }

        registerBatteryReceiver()
        ChargeScheduler.check(this)
    }

    private fun registerBatteryReceiver() {
        receiver?.let { runCatching { unregisterReceiver(it) } }
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != Intent.ACTION_BATTERY_CHANGED) return
                // Keep the broadcast wake lock held while the check runs on a background
                // thread (same pattern as the reference app: goAsync + background task).
                val pending = goAsync()
                batteryExecutor.execute {
                    try {
                        val current = BatteryUtils.parse(intent)
                        val tag = "${current.percent}/${current.plugged}"
                        if (tag != lastLogged) {
                            lastLogged = tag
                            DebugLog.add(context, "battery event pct=${current.percent} plugged=${current.plugged}")
                        }
                        ensureNotificationShown(current.percent)
                        updateNotification(current.percent)
                        if (current.plugged) {
                            ChargeScheduler.check(context)
                        } else {
                            // الشاحن مفصول: ننهي الجلسة بعد التأكد، والخدمة تبقى تعمل بانتظار التوصيل التالي
                            if (ChargeScheduler.isUnplugConfirmed(context)) ChargeScheduler.onUnplugged(context)
                        }
                    } finally {
                        pending.finish()
                    }
                }
            }
        }.also {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED).apply { priority = 100 }
            ContextCompat.registerReceiver(
                this,
                it,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Refresh heartbeat (reference-app pattern): the 90 s exact alarm targets this
        // service itself (getForegroundService). When it fires, re-registering the receiver
        // makes the system deliver the current sticky BATTERY_CHANGED intent immediately,
        // so the threshold is checked even if broadcasts stopped arriving with the screen
        // off. The direct check() is a safety net in case that delivery is delayed.
        if (intent?.getBooleanExtra(EXTRA_REFRESH, false) == true) {
            registerBatteryReceiver()
            DebugLog.add(this, "refresh: receiver re-registered")
            ChargeScheduler.check(this)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        DebugLog.add(this, "service destroyed")
        handler.removeCallbacksAndMessages(null)
        batteryExecutor.shutdownNow()
        receiver?.let { runCatching { unregisterReceiver(it) } }
        receiver = null
        // Make sure the status-bar notification disappears together with the service.
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** إشعار دائم يعرض النسبة الحية، مثل التطبيق المرجعي: إشعار مرئي ومفيد يوضّح أن الخدمة تعمل */
    private fun buildNotification(percent: Int): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_battery)
            .setContentTitle(getString(R.string.monitor_notification_title))
            .setContentText(
                getString(
                    R.string.monitor_notification_progress,
                    percent.coerceIn(0, 100),
                    Prefs.threshold(this)
                )
            )
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

    /** يحدّث نص الإشعار بالنسبة الحية عند تغيّرها */
    private fun updateNotification(percent: Int) {
        if (percent == shownPercent) return
        shownPercent = percent
        runCatching { startAsForeground(buildNotification(percent)) }
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
        /** Extra set on the refresh alarm's intent: re-register the battery receiver on start. */
        const val EXTRA_REFRESH = "refresh"
        private const val OLD_CHANNEL_ID = "charge_monitor"
        private const val PREVIOUS_CHANNEL_ID = "charge_monitor_v2"
        private const val CHANNEL_ID = "charge_monitor_v3"
        private const val NOTIFICATION_ID = 3

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
