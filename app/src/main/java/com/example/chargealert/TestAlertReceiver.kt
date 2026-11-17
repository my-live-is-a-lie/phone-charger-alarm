package com.example.chargealert

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * اختبار توصيل التنبيه والشاشة مغلقة: يُجدوَل منبّه ساعة بعد N ثانية، وعند حلّه يُطلق التنبيه
 * الحقيقي نفسه ويسجّل التأخير وحالة الشاشة. أغلق الشاشة بعد الضغط وانتظر.
 */
class TestAlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val due = intent.getLongExtra(EXTRA_DUE, 0L)
        val late = if (due == 0L) -1L else (System.currentTimeMillis() - due) / 1000
        DebugLog.i(context, "TEST", "delayed alert test FIRED late=${late}s ${DebugLog.screenState(context)}")
        Notifications.createChannels(context)
        Notifications.alert(context, Prefs.threshold(context))
    }

    companion object {
        private const val EXTRA_DUE = "due"
        private const val REQUEST = 4001

        fun schedule(context: Context, delaySeconds: Int): Boolean {
            val app = context.applicationContext
            val due = System.currentTimeMillis() + delaySeconds * 1000L
            val intent = Intent(app, TestAlertReceiver::class.java).putExtra(EXTRA_DUE, due)
            val pending = PendingIntent.getBroadcast(
                app, REQUEST, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val show = PendingIntent.getActivity(
                app, REQUEST, Intent(app, DiagnosticsActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val ok = runCatching {
                app.getSystemService(AlarmManager::class.java)
                    .setAlarmClock(AlarmManager.AlarmClockInfo(due, show), pending)
            }.isSuccess
            DebugLog.i(app, "TEST", "delayed alert test scheduled in ${delaySeconds}s ok=$ok ${DebugLog.screenState(app)}")
            return ok
        }
    }
}
