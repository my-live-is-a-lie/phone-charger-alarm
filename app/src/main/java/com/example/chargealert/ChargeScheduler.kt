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

    // فحص عبر JobScheduler بجانب المنبّهات: مؤقتات النظام لا يؤخرها نظام الهاتف
    private const val POLL_JOB_A = 1101
    private const val POLL_JOB_B = 1102
    private const val POLL_DELAY_MS = 30_000L
    private const val POLL_AFTER_ALERT_DELAY_MS = 60_000L
    private const val POLL_DEADLINE_SLACK_MS = 20_000L
    private const val KEY_POLL_TOGGLE = "poll_toggle"
    private const val KEY_POLL_DUE = "poll_due_elapsed"
    private const val KEY_REFRESH_DUE = "refresh_due_wall"
    private const val REFRESH_RESCHEDULE_BELOW_MS = 30_000L
    private const val POLL_RESCHEDULE_BELOW_MS = 10_000L

    @Volatile private var unplugSeenAt = 0L
    private val mainHandler = Handler(Looper.getMainLooper())

    @Synchronized
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

    @Synchronized
    fun stop(context: Context) {
        BatteryMonitorService.stop(context)
        context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
        cancelAlarm(context)
        cancelRefreshAlarm(context)
        cancelPollJobs(context)
        Prefs.setSession(context, Prefs.Session.EMPTY)
        Prefs.setAlertedAt(context, 0L)
        Notifications.cancelAlert(context)
    }

    @Synchronized
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
    @Synchronized
    fun isUnplugConfirmed(context: Context): Boolean {
        val info = BatteryUtils.read(context)
        if (info.plugged) {
            unplugSeenAt = 0L
            return false
        }
        val now = SystemClock.elapsedRealtime()
        if (unplugSeenAt == 0L) {
            unplugSeenAt = now
            
            val app = context.applicationContext
            mainHandler.postDelayed({ check(app) }, UNPLUG_CONFIRM_MS + 300)
            // نسخة احتياطية عبر JobScheduler إذا جُمّدت العملية قبل تنفيذ الفحص المؤجل
            schedulePollJob(app, 4_000L, requireCharging = false)
            return false
        }
        return now - unplugSeenAt >= UNPLUG_CONFIRM_MS
    }

    @Synchronized
    fun onUnplugged(context: Context) {
        if (Prefs.isEnabled(context)) endSession(context)
    }

    @Synchronized
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

    @Synchronized
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
        
        process(context, info, session, now, updateLast = false)
    }

    private fun endSession(context: Context) {
        
        unplugSeenAt = 0L
        Prefs.setSession(context, Prefs.Session.EMPTY)
        Prefs.setAlertedAt(context, 0L)
        Notifications.cancelAlert(context)
        cancelAlarm(context)
        cancelRefreshAlarm(context)
        cancelPollJobs(context)
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
        // نبضة الخدمة تعمل كل 10 ثوانٍ وتستدعي check() في كل مرة، فلا نعيد الجدولة إلا عند اقتراب
        // موعد المنبّه/المهمة السابقة. بهذا يعمل المنبّه والمهمة كحارس احتياطي: لا ينطلقان فعلياً
        // إلا إذا توقفت النبضات (العملية قُتلت أو جُمّدت).
        val prefs = Prefs.of(context)
        // remaining خارج النطاق المنطقي (بعد إعادة تشغيل الهاتف مثلاً) يعني أن القيمة قديمة فنعيد الجدولة
        val pollRemaining = prefs.getLong(KEY_POLL_DUE, 0L) - SystemClock.elapsedRealtime()
        val pollSoon = pollRemaining < POLL_RESCHEDULE_BELOW_MS || pollRemaining > 120_000L
        if (Prefs.alertedAt(context) == 0L) {
            val refreshRemaining = prefs.getLong(KEY_REFRESH_DUE, 0L) - now
            val refreshSoon = refreshRemaining < REFRESH_RESCHEDULE_BELOW_MS || refreshRemaining > 180_000L
            if (refreshSoon) scheduleRefreshAlarm(context)
            if (pollSoon) schedulePollJob(context, POLL_DELAY_MS)
        } else {
            // Alert already sent: no more refresh needed.
            if (prefs.getLong(KEY_REFRESH_DUE, 0L) != 0L) cancelRefreshAlarm(context)
            // فحص أبطأ فقط لاكتشاف الفصل وإيقاف صوت التنبيه
            if (pollSoon) schedulePollJob(context, POLL_AFTER_ALERT_DELAY_MS)
        }
    }

    /** يجدول الفحص التالي كمهمة JobScheduler (معرّفان بالتناوب حتى لا تُلغى المهمة الجارية) */
    fun schedulePollJob(context: Context, delayMs: Long, requireCharging: Boolean = true) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val prefs = Prefs.of(context)
        val toggle = prefs.getInt(KEY_POLL_TOGGLE, 0)
        val id = if (toggle == 0) POLL_JOB_A else POLL_JOB_B
        val other = if (toggle == 0) POLL_JOB_B else POLL_JOB_A
        runCatching { scheduler.cancel(other) }
        val job = JobInfo.Builder(id, ComponentName(context, PollJobService::class.java))
            .setMinimumLatency(delayMs)
            // موعد أقصى: تُنفَّذ المهمة حتى لو لم يتحقق شرط الشحن، فيحسم الفحص الأمر
            .setOverrideDeadline(delayMs + POLL_DEADLINE_SLACK_MS)
            .apply { if (requireCharging) setRequiresCharging(true) }
            .build()
        val ok = runCatching { scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS }.getOrDefault(false)

        prefs.edit()
            .putInt(KEY_POLL_TOGGLE, 1 - toggle)
            .putLong(KEY_POLL_DUE, SystemClock.elapsedRealtime() + delayMs)
            .apply()
    }

    private fun cancelPollJobs(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        runCatching { scheduler.cancel(POLL_JOB_A) }
        runCatching { scheduler.cancel(POLL_JOB_B) }
        Prefs.of(context).edit().putLong(KEY_POLL_DUE, 0L).apply()
    }

    /** كم ثانية تأخرت مهمة الفحص عن موعدها (للتشخيص) */
    fun pollLateSeconds(context: Context): Long {
        val due = Prefs.of(context).getLong(KEY_POLL_DUE, 0L)
        return if (due == 0L) -1 else (SystemClock.elapsedRealtime() - due) / 1000
    }

    /** كم ثانية تأخر منبّه التحديث عن موعده (للتشخيص) */
    fun refreshLateSeconds(context: Context): Long {
        val due = Prefs.of(context).getLong(KEY_REFRESH_DUE, 0L)
        return if (due == 0L) -1 else (System.currentTimeMillis() - due) / 1000
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

    /**
     * Schedules the next 90 s refresh. Uses setAlarmClock(): setExactAndAllowWhileIdle() is
     * throttled by Doze (the log showed a 90 s refresh firing ~6 minutes late with the screen
     * off, so the threshold was only noticed after the screen was woken manually). Alarm-clock
     * alarms are exempt from Doze and do not need the SCHEDULE_EXACT_ALARM permission.
     */
    private fun scheduleRefreshAlarm(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val triggerAt = System.currentTimeMillis() + REFRESH_INTERVAL_MS
        Prefs.of(context).edit().putLong(KEY_REFRESH_DUE, triggerAt).apply()
        val showIntent = PendingIntent.getActivity(
            context,
            REFRESH_REQUEST,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val ok = runCatching {
            alarmManager.setAlarmClock(
                AlarmManager.AlarmClockInfo(triggerAt, showIntent),
                refreshIntent(context)
            )
        }.isSuccess
        if (!ok) {
            // Fallback: best-effort inexact alarm (no exact-alarm permission needed).
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, refreshIntent(context))
        }
        
    }

    private fun cancelRefreshAlarm(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(refreshIntent(context))
        Prefs.of(context).edit().putLong(KEY_REFRESH_DUE, 0L).apply()
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
            
            if (ok) return
        }

        val triggerAt = SystemClock.elapsedRealtime() + delay
        val exact = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S ||
            alarmManager.canScheduleExactAlarms()
        
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
