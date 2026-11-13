package com.example.chargealert
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
class CheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        DebugLog.add(context, "alarm fired")
        ChargeScheduler.check(context)
    }
}
