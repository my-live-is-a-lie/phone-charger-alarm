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
    private const val JOB_ID=1001
    private const val ALARM_REQUEST=2001
    private const val MINUTE=60_000L
    private const val MIN_RATE=15_000L
    private const val MAX_RATE=10*MINUTE
    private const val FIRST_MEASURE_MAX=15*MINUTE
    private const val EXACT_WINDOW=12*MINUTE
    private const val MIN_DELAY=30_000L
    private const val MAX_DELAY=45*MINUTE
    fun start(context: Context) {
        val info=BatteryUtils.read(context)
        if(info.plugged) beginSession(context,info,System.currentTimeMillis(),suppressAlert=info.percent>=Prefs.threshold(context))
        else endSession(context)
    }
    fun stop(context: Context) {
        context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
        cancelAlarm(context)
        Prefs.setSession(context,Prefs.Session.EMPTY)
        Prefs.setAlertedAt(context,0L)
        Notifications.cancelAlert(context)
    }
    fun onPlugged(context: Context) {
        if(!Prefs.isEnabled(context)) return
        val info=BatteryUtils.read(context)
        if(!info.plugged) { scheduleJob(context); return }
        beginSession(context,info,System.currentTimeMillis(),suppressAlert=false)
    }
    fun onThresholdChanged(context: Context) {
        if(!Prefs.isEnabled(context)) return
        val info=BatteryUtils.read(context)
        if(!info.plugged) return
        val session=Prefs.session(context)
        if(session.startTime==0L) return
        val now=System.currentTimeMillis()
        val above=info.percent>=Prefs.threshold(context)
        Prefs.setAlertedAt(context,if(above) now else 0L)
        process(context,info,session,now,updateLast=false)
    }
    fun check(context: Context) {
        if(!Prefs.isEnabled(context)) { stop(context); return }
        val info=BatteryUtils.read(context)
        val now=System.currentTimeMillis()
        if(!info.plugged) { endSession(context); return }
        val session=Prefs.session(context)
        if(session.startTime==0L || info.percent<session.lastPct-1) {
            beginSession(context,info,now,suppressAlert=false)
            return
        }
        process(context,info,session,now,updateLast=true)
    }
    private fun beginSession(context: Context, info: BatteryInfo, now: Long, suppressAlert: Boolean) {
        val session=Prefs.Session(now,info.percent,now,info.percent)
        Prefs.setSession(context,session)
        Prefs.setAlertedAt(context,if(suppressAlert) now else 0L)
        process(context,info,session,now,updateLast=false)
    }
    private fun endSession(context: Context) {
        Prefs.setSession(context,Prefs.Session.EMPTY)
        Prefs.setAlertedAt(context,0L)
        Notifications.cancelAlert(context)
        cancelAlarm(context)
        scheduleJob(context)
    }
    private fun process(context: Context, info: BatteryInfo, session: Prefs.Session, now: Long, updateLast: Boolean) {
        val threshold=Prefs.threshold(context)
        if(info.percent>=threshold) {
            if(Prefs.alertedAt(context)==0L) {
                Prefs.setAlertedAt(context,now)
                learnRate(context,info,session,now)
                Notifications.alert(context,info.percent)
            }
        } else if(Prefs.alertedAt(context)!=0L) Prefs.setAlertedAt(context,0L)
        if(updateLast) Prefs.setSession(context,session.copy(lastTime=now,lastPct=info.percent))
        val alertedAt=Prefs.alertedAt(context)
        if(alertedAt!=0L) scheduleAlarm(context,if(now-alertedAt<4*MINUTE) 2*MINUTE else 10*MINUTE)
        else scheduleAlarm(context,nextDelay(context,info,session,now,threshold))
    }
    private fun nextDelay(context: Context, info: BatteryInfo, session: Prefs.Session, now: Long, threshold: Int): Long {
        val remaining=(threshold-info.percent).coerceAtLeast(1)
        val recentDelta=info.percent-session.lastPct
        val recentTime=now-session.lastTime
        val sessionDelta=info.percent-session.startPct
        val sessionTime=now-session.startTime
        val prior=Prefs.learnedRate(context)
        val measured=recentDelta>=2 || sessionDelta>=2
        val rate=when {
            recentDelta>=2 -> recentTime/recentDelta
            sessionDelta>=2 -> sessionTime/sessionDelta
            sessionTime>10*MINUTE -> maxOf(prior,sessionTime/(sessionDelta.coerceAtLeast(0)+1))
            else -> prior
        }.coerceIn(MIN_RATE,MAX_RATE)
        val estimate=remaining*rate
        val delay=when {
            estimate<=EXACT_WINDOW -> estimate
            !measured -> minOf(FIRST_MEASURE_MAX,estimate/2)
            else -> estimate*8/10
        }
        return delay.coerceIn(MIN_DELAY,MAX_DELAY)
    }
    private fun learnRate(context: Context, info: BatteryInfo, session: Prefs.Session, now: Long) {
        val delta=info.percent-session.startPct
        if(delta<2) return
        val sample=((now-session.startTime)/delta).coerceIn(MIN_RATE,MAX_RATE)
        val old=Prefs.learnedRate(context)
        Prefs.setLearnedRate(context,(old+sample)/2)
    }
    private fun scheduleJob(context: Context) {
        val job=JobInfo.Builder(JOB_ID,ComponentName(context,PlugJobService::class.java)).setRequiresCharging(true).setPersisted(true).build()
        context.getSystemService(JobScheduler::class.java).schedule(job)
    }
    private fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(context,ALARM_REQUEST,Intent(context,CheckReceiver::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    private fun scheduleAlarm(context: Context, delayMs: Long) {
        context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,SystemClock.elapsedRealtime()+delayMs,alarmIntent(context))
    }
    private fun cancelAlarm(context: Context) { context.getSystemService(AlarmManager::class.java).cancel(alarmIntent(context)) }
}
