package com.example.chargealert
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
class CheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { ChargeScheduler.check(context) }
}
