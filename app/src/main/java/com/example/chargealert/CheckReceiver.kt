package com.example.chargealert

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager

class CheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wl = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ChargeAlert:CheckWakeLock")
        wl?.acquire(5000L)
        try {
            ChargeScheduler.check(context)
        } finally {
            runCatching {
                if (wl?.isHeld == true) wl.release()
            }
        }
    }
}
