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
    private const val MINUTE = 60_000L
    private const val MIN_RATE = 15_000L
    private const val MAX_RATE = 10 * MINUTE
    private const val FIRST_MEASURE_MAX = 5 * MINUTE
    private const val EXACT_WINDOW = 12 * MINUTE
    private const val MIN_DELAY = 30_000L
    private const val MAX_DELAY = 15 * MINUTE
    private const val MONITOR_RETRY_DELAY = 3_000L
    private const val UNPLUG_CONFIRM_MS = 2_500L

    @Volatile private var unplugSeenAt = 0L
    private val mainHandler = Handler(Looper.getMainLooper())

    fun start(context: Context) {
        if (!Prefs.isEnabled(context)) return
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
        BatteryMonitorService.stop(context)
        Prefs.setSession(context, Prefs.Session.EMPTY)
        Prefs.setAlertedAt(context, 0L)
        Notifications.cancelAlert(context)
        cancelAlarm(context)
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
                learnRate(context, info, session, now)
                Notifications.alert(context, info.percent)
            }
        } else if (Prefs.alertedAt(context) != 0L) {
            Prefs.setAlertedAt(context, 0L)
        }

        if (updateLast) {
            Prefs.setSession(context, session.copy(lastTime = now, lastPct = info.percent))
        }

        val alertedAt = Prefs.alertedAt(context)
        val delay = if (alertedAt != 0L) {
            2 * MINUTE
        } else {
            nextDelay(context, info, session, now, threshold)
        }
        // قبل التنبيه: منبّه من نوع alarmClock لأنه لا يتأخر والشاشة مغلقة. بعد التنبيه يكفي منبّه عادي.
        scheduleAlarm(context, delay, urgent = alertedAt == 0L)
    }

    private fun nextDelay(
        context: Context,
        info: BatteryInfo,
        session: Prefs.Session,
        now: Long,
        threshold: Int
    ): Long {
        val remaining = (threshold - info.percent).coerceAtLeast(1)
        val recentDelta = info.percent - session.lastPct
        val recentTime = now - session.lastTime
        val sessionDelta = info.percent - session.startPct
        val sessionTime = now - session.startTime
        val prior = Prefs.learnedRate(context)
        val measured = recentDelta >= 2 || sessionDelta >= 2
        val rate = when {
            recentDelta >= 2 -> recentTime / recentDelta
            sessionDelta >= 2 -> sessionTime / sessionDelta
            sessionTime > 10 * MINUTE -> maxOf(prior, sessionTime / (sessionDelta.coerceAtLeast(0) + 1))
            else -> prior
        }.coerceIn(MIN_RATE, MAX_RATE)
        val estimate = remaining * rate
        val estimateDelay = when {
            estimate <= EXACT_WINDOW -> estimate
            !measured -> minOf(FIRST_MEASURE_MAX, estimate / 2)
            else -> estimate * 8 / 10
        }

        // Estimates based on a default or stale charging rate can be far too slow near
        // the target (for example, 72% -> 75% could otherwise sleep for five minutes).
        // Tighten polling as the target approaches so a faster-than-expected charge is
        // detected promptly, even before we have enough samples to learn its rate.
        val proximityLimit = when {
            remaining <= 5 -> MIN_DELAY
            remaining <= 10 -> 2 * MINUTE
            else -> MAX_DELAY
        }
        return estimateDelay.coerceIn(MIN_DELAY, minOf(MAX_DELAY, proximityLimit))
    }

    private fun learnRate(context: Context, info: BatteryInfo, session: Prefs.Session, now: Long) {
        val delta = info.percent - session.startPct
        if (delta < 2) return
        val sample = ((now - session.startTime) / delta).coerceIn(MIN_RATE, MAX_RATE)
        val old = Prefs.learnedRate(context)
        Prefs.setLearnedRate(context, (old + sample) / 2)
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
