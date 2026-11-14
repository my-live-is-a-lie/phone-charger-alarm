package com.example.chargealert

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

object ChargeScheduler {
    private const val JOB_ID = 1001
    private const val ALARM_REQUEST = 2001
    private const val REFRESH_REQUEST = 2002
    private const val MIN_DELAY = 30_000L
    private const val MONITOR_RETRY_DELAY = 3_000L
    private const val UNPLUG_CONFIRM_MS = 2_500L
    // Periodic refresh, like the reference app (90 s while charging): an exact alarm that
    // wakes the app even if the system holds back battery broadcasts with the screen off.
    private const val REFRESH_INTERVAL_MS = 90_000L

    @Volatile private var unplugSeenAt = 0L
    private val mainHandler = Handler(Looper.getMainLooper())

    fun start(context: Context) {
        if (!Prefs.isEnabled(context)) return
        // الخدمة تعمل طوال فترة تفعيل المراقبة، موصولاً الشاحن أم لا
        ensureMonitor(context, retrySoon = true)
        val info = BatteryUtils.read(context)
        if (info.plugged) {
            val session = Prefs.session(context)
            if (session.startTime == 0L) {
                beginSession(context, info, System.currentTimeMillis())
            } else {
                check(context)
            }
            ensureMonitor(context, retrySoon = true)
        } else {
            endSession(context)
        }
    }

    fun stop(context: Context) {
        BatteryMonitorService.stop(context)
        context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
        cancelAlarm(context)
        cancelRefreshAlarm(context)
        Prefs.setSession(context, Prefs.Session.EMPTY)
        Prefs.setAlertedAt(context, 0L)
        Notifications.cancelAlert(context)
    }

    fun onPlugged(context: Context) {
        if (!Prefs.isEnabled(context)) return
        val info = BatteryUtils.read(context)
        if (!info.plugged) {
            // تذبذب عابر لحظة التوصيل: نعيد جدولة المهمة وننتظر
            scheduleJob(context)
            return
        }
        val session = Prefs.session(context)
        if (session.startTime == 0L) {
            beginSession(context, info, System.currentTimeMillis())
        } else {
            check(context)
        }
        ensureMonitor(context, retrySoon = true)
    }

    /**
     * عند توصيل الشاحن تتذبذب حالة "متصل" لثوانٍ بسبب مصافحة الشحن، فلا نعتبر الفصل حقيقياً
     * إلا إذا استمر 2.5 ثانية. بدون انتظار أو تجميد للواجهة: أول ملاحظة تسجّل الوقت وتجدول فحصاً
     * بعد قليل، والفحص التالي يحسم الأمر.
     */
    fun isUnplugConfirmed(context: Context): Boolean {
        val info = BatteryUtils.read(context)
        if (info.plugged) {
            unplugSeenAt = 0L
            return false
        }
        val now = SystemClock.elapsedRealtime()
        if (unplugSeenAt == 0L) {
            unplugSeenAt = now
            DebugLog.add(context, "unplug suspected, confirming in ${UNPLUG_CONFIRM_MS}ms")
            val app = context.applicationContext
            mainHandler.postDelayed({ check(app) }, UNPLUG_CONFIRM_MS + 300)
            return false
        }
        return now - unplugSeenAt >= UNPLUG_CONFIRM_MS
    }

    fun onUnplugged(context: Context) {
        if (Prefs.isEnabled(context)) endSession(context)
    }

    fun onThresholdChanged(context: Context) {
        if (!Prefs.isEnabled(context)) return
        val info = BatteryUtils.read(context)
        if (!info.plugged) return
        val session = Prefs.session(context)
        val now = System.currentTimeMillis()
        if (session.startTime == 0L) {
            beginSession(context, info, now)
            return
        }
        if (info.percent >= Prefs.threshold(context) && Prefs.alertedAt(context) == 0L) {
            Prefs.setAlertedAt(context, now)
            Notifications.alert(context, info.percent)
        } else if (info.percent < Prefs.threshold(context)) {
            Prefs.setAlertedAt(context, 0L)
        }
        process(context, info, session, now, updateLast = false)
    }

    fun check(context: Context) {
        if (!Prefs.isEnabled(context)) {
            stop(context)
            return
        }
        val info = BatteryUtils.read(context)
        val now = System.currentTimeMillis()
        if (!info.plugged) {
            ensureMonitor(context, retrySoon = false)
            // لا جلسة نشطة: المهمة مجدولة أصلاً وننتظر التوصيل
            if (Prefs.session(context).startTime == 0L) return
            // قد يكون تذبذباً عابراً: لا ننهي الجلسة قبل أن يستمر الفصل فترة قصيرة
            if (!isUnplugConfirmed(context)) return
            endSession(context)
            return
        }
        unplugSeenAt = 0L
        val session = Prefs.session(context)
        if (session.startTime == 0L || info.percent < session.lastPct - 1) {
            beginSession(context, info, now)
            ensureMonitor(context, retrySoon = false)
            return
        }
        process(context, info, session, now, updateLast = true)
        ensureMonitor(context, retrySoon = false)
    }

    private fun ensureMonitor(context: Context, retrySoon: Boolean) {
        if (BatteryMonitorService.ensureRunning(context)) return
        if (retrySoon && Prefs.alertedAt(context) == 0L) {
            scheduleAlarm(context, MONITOR_RETRY_DELAY, allowShort = true)
        }
    }

    private fun beginSession(context: Context, info: BatteryInfo, now: Long) {
        val session = Prefs.Session(now, info.percent, now, info.percent)
        Prefs.setSession(context, session)
        Prefs.setAlertedAt(context, 0L)
        DebugLog.add(context, "session begin pct=${info.percent} threshold=${Prefs.threshold(context)}")
        process(context, info, session, now, updateLast = false)
    }

    private fun endSession(context: Context) {
        DebugLog.add(context, "session end (not charging)")
        unplugSeenAt = 0L
        Prefs.setSession(context, Prefs.Session.EMPTY)
        Prefs.setAlertedAt(context, 0L)
        Notifications.cancelAlert(context)
        cancelAlarm(context)
        cancelRefreshAlarm(context)
        scheduleJob(context)
    }

    private fun process(
        context: Context,
        info: BatteryInfo,
        session: Prefs.Session,
        now: Long,
        updateLast: Boolean
    ) {
        val threshold = Prefs.threshold(context)
        if (info.percent >= threshold) {
            if (Prefs.alertedAt(context) == 0L) {
                Prefs.setAlertedAt(context, now)
                DebugLog.add(context, "THRESHOLD REACHED pct=${info.percent} threshold=$threshold -> alert")
                Notifications.alert(context, info.percent)
            }
        } else if (Prefs.alertedAt(context) != 0L) {
            Prefs.setAlertedAt(context, 0L)
        }

        if (updateLast) {
            Prefs.setSession(context, session.copy(lastTime = now, lastPct = info.percent))
        }
        // Periodic refresh (reference-app pattern): the 90 s exact alarm targets the
        // monitor service itself (getForegroundService), not a BroadcastReceiver. When it
        // fires, the service re-registers the BATTERY_CHANGED receiver, which makes the
        // system deliver the current battery state immediately, so the threshold is caught
        // even if broadcasts stop arriving with the screen off.
        if (Prefs.alertedAt(context) == 0L) {
            scheduleRefreshAlarm(context)
        } else {
            // Alert already sent: no more refresh needed.
            cancelRefreshAlarm(context)
        }
    }

    private fun scheduleJob(context: Context) {
        val job = JobInfo.Builder(
            JOB_ID,
            ComponentName(context, PlugJobService::class.java)
        ).setRequiresCharging(true).setPersisted(true).build()
        context.getSystemService(JobScheduler::class.java).schedule(job)
    }

    private fun alarmIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST,
            Intent(context, CheckReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    /**
     * The refresh alarm's target: the monitor service itself, like the reference app
     * (BatteryService.h). Starting a foreground service wakes the app more reliably than
     * delivering a broadcast to a receiver, and onStartCommand() re-registers the battery
     * receiver, forcing an immediate fresh battery reading.
     */
    private fun refreshIntent(context: Context): PendingIntent {
        val intent = Intent(context, BatteryMonitorService::class.java).apply {
            putExtra(BatteryMonitorService.EXTRA_REFRESH, true)
        }
        // Match the reference app exactly: FLAG_MUTABLE | FLAG_CANCEL_CURRENT.
        // (FLAG_MUTABLE exists from API 31; on older versions the bit is ignored.)
        val flags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_CANCEL_CURRENT
        } else {
            PendingIntent.FLAG_CANCEL_CURRENT
        }
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(context, REFRESH_REQUEST, intent, flags)
        } else {
            PendingIntent.getService(context, REFRESH_REQUEST, intent, flags)
        }
    }

    /** Schedules the next 90 s refresh (reference-app pattern: RTC_WAKEUP exact alarm). */
    private fun scheduleRefreshAlarm(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val exact = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S ||
            alarmManager.canScheduleExactAlarms()
        val triggerAt = System.currentTimeMillis() + REFRESH_INTERVAL_MS
        if (!exact) {
            DebugLog.add(context, "next refresh in 90s type=skipped (exact alarms not allowed)")
            return
        }
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerAt,
            refreshIntent(context)
        )
        DebugLog.add(context, "next refresh in 90s type=exact (service)")
    }

    private fun cancelRefreshAlarm(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(refreshIntent(context))
    }

    private fun scheduleAlarm(
        context: Context,
        delayMs: Long,
        allowShort: Boolean = false,
        urgent: Boolean = true
    ) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val delay = if (allowShort) delayMs else delayMs.coerceAtLeast(MIN_DELAY)
        val pendingIntent = alarmIntent(context)

        if (urgent) {
            // The diagnostic log showed ordinary exact alarms being held back for minutes while
            // the screen was off (a 30 s alarm fired 2+ minutes late). setAlarmClock() is exempt
            // from those restrictions, so it is used until the alert has been sent.
            val showIntent = PendingIntent.getActivity(
                context,
                ALARM_REQUEST,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val ok = runCatching {
                alarmManager.setAlarmClock(
                    AlarmManager.AlarmClockInfo(System.currentTimeMillis() + delay, showIntent),
                    pendingIntent
                )
            }.isSuccess
            DebugLog.add(context, "next alarm in ${delay / 1000}s type=alarmClock ok=$ok")
            if (ok) return
        }

        val triggerAt = SystemClock.elapsedRealtime() + delay
        val exact = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S ||
            alarmManager.canScheduleExactAlarms()
        DebugLog.add(context, "next alarm in ${delay / 1000}s type=${if (exact) "exact" else "inexact"}")
        if (exact) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAt,
                pendingIntent
            )
        } else {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAt,
                pendingIntent
            )
        }
    }

    private fun cancelAlarm(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(alarmIntent(context))
    }
}
