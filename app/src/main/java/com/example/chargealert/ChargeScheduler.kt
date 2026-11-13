package com.example.chargealert

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
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
        } else {
            endSession(context)
        }
    }

    fun stop(context: Context) {
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
            endSession(context)
            return
        }
        val session = Prefs.session(context)
        if (session.startTime == 0L) {
            beginSession(context, info, System.currentTimeMillis())
        } else {
            check(context)
        }
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
            endSession(context)
            return
        }
        val session = Prefs.session(context)
        if (session.startTime == 0L || info.percent < session.lastPct - 1) {
            beginSession(context, info, now)
            return
        }
        process(context, info, session, now, updateLast = true)
    }

    private fun beginSession(context: Context, info: BatteryInfo, now: Long) {
        val session = Prefs.Session(now, info.percent, now, info.percent)
        Prefs.setSession(context, session)
        Prefs.setAlertedAt(context, 0L)
        process(context, info, session, now, updateLast = false)
    }

    private fun endSession(context: Context) {
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
        scheduleAlarm(context, delay)
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
            remaining <= 5 -> MINUTE
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

    private fun scheduleAlarm(context: Context, delayMs: Long) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val triggerAt = SystemClock.elapsedRealtime() + delayMs.coerceAtLeast(MIN_DELAY)
        val pendingIntent = alarmIntent(context)
        // On Android 12+, inexact alarms may be deferred substantially, which can
        // miss a rapidly crossed charge threshold. Use exact while-idle alarms when
        // the user has granted the system's exact-alarm access; retain a safe fallback.
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S ||
            alarmManager.canScheduleExactAlarms()
        ) {
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
