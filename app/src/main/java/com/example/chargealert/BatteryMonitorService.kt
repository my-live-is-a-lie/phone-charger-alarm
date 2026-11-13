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
import android.os.IBinder
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

    override fun onCreate() {
        super.onCreate()
        running = true
        starting = false
        createMonitorChannel()

        val info = BatteryUtils.read(this)
        DebugLog.add(this, "service created pct=${info.percent} plugged=${info.plugged} enabled=${Prefs.isEnabled(this)}")
        val notification = buildNotification(info.percent)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // Nothing to watch: monitoring is off or the charger is not connected.
        if (!Prefs.isEnabled(this) || !info.plugged) {
            if (Prefs.isEnabled(this)) ChargeScheduler.onUnplugged(this)
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
                } else {
                    // Charger removed: end the session and remove the persistent notification.
                    ChargeScheduler.onUnplugged(context)
                    stopSelf()
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running = false
        DebugLog.add(this, "service destroyed")
        receiver?.let { runCatching { unregisterReceiver(it) } }
        receiver = null
        // Make sure the status-bar notification disappears together with the service.
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(percent: Int): Notification {
        val threshold = Prefs.threshold(this)
        shownPercent = percent
        shownThreshold = threshold
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_battery)
            .setContentTitle(getString(R.string.monitor_notification_title))
            .setContentText(getString(R.string.monitor_notification_progress, percent, threshold))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(percent: Int) {
        if (percent == shownPercent && Prefs.threshold(this) == shownThreshold) return
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(percent))
        }
    }

    private fun createMonitorChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.monitor_notification_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "charge_monitor"
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
