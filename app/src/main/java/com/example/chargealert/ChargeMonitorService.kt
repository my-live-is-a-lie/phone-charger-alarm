package com.example.chargealert

import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

class ChargeMonitorService : Service() {

    private var alerted = false
    private var lastText: String? = null

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            Prefs.KEY_THRESHOLD -> {
                val info = BatteryUtils.read(this)
                // لا نطلق تنبيهاً فورياً عند تغيير النسبة أثناء الشحن
                alerted = info.plugged && info.percent >= Prefs.threshold(this)
                evaluate(info)
            }
            Prefs.KEY_ENABLED -> if (!Prefs.isEnabled(this)) stopSelf()
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            evaluate(BatteryUtils.parse(intent))
        }
    }

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        ServiceCompat.startForeground(
            this,
            Notifications.ID_STATUS,
            Notifications.status(this, getString(R.string.status_waiting, Prefs.threshold(this))),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )

        Prefs.of(this).registerOnSharedPreferenceChangeListener(prefListener)

        val initial = ContextCompat.registerReceiver(
            this,
            receiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        if (initial != null) {
            val info = BatteryUtils.parse(initial)
            // إذا فُعّلت المراقبة وهي فوق النسبة أصلاً فلا ننبّه
            alerted = info.plugged && info.percent >= Prefs.threshold(this)
            evaluate(info)
        }
    }

    private fun evaluate(info: BatteryInfo) {
        val threshold = Prefs.threshold(this)

        if (info.plugged) {
            if (info.percent >= threshold) {
                if (!alerted) {
                    alerted = true
                    Notifications.alert(this, info.percent)
                }
            } else {
                alerted = false
            }
        } else {
            alerted = false
            Notifications.cancelAlert(this)
        }

        val text = if (info.plugged) {
            getString(R.string.status_charging, info.percent, threshold)
        } else {
            getString(R.string.status_waiting, threshold)
        }
        if (text != lastText) {
            lastText = text
            getSystemService(NotificationManager::class.java)
                .notify(Notifications.ID_STATUS, Notifications.status(this, text))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        Prefs.of(this).unregisterOnSharedPreferenceChangeListener(prefListener)
        Notifications.cancelAlert(this)
        super.onDestroy()
    }
}
