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

/** Keeps a live battery-level listener while monitoring is enabled. */
class BatteryMonitorService : Service() {
    private var receiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        createMonitorChannel()
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_battery)
            .setContentTitle(getString(R.string.monitor_notification_title))
            .setContentText(getString(R.string.monitor_notification_text))
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_BATTERY_CHANGED && BatteryUtils.parse(intent).plugged) {
                    ChargeScheduler.check(context)
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
        receiver?.let { runCatching { unregisterReceiver(it) } }
        receiver = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createMonitorChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.monitor_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "charge_monitor"
        private const val NOTIFICATION_ID = 3

        fun start(context: Context) {
            val intent = Intent(context, BatteryMonitorService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BatteryMonitorService::class.java))
        }
    }
}
