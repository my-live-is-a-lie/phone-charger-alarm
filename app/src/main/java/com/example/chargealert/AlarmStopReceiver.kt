package com.example.chargealert

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** زر «إيقاف» في إشعار التنبيه، أو إزالة الإشعار */
class AlarmStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Notifications.cancelAlert(context) // يوقف الصوت أيضاً
    }
}
