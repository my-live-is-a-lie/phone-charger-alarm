package com.example.chargealert

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Foreground service that stays alive while monitoring is enabled.
 *
 * طبقات الكشف (كلها تنتهي بـ ChargeScheduler.check):
 *  1) نبضة دورية كل 10 ثوانٍ على خيط خاص. أثناء الشحن وقبل بلوغ النسبة نمسك WakeLock جزئياً
 *     كي لا يدخل المعالج في السكون والشاشة مغلقة — هذا هو إصلاح "التنبيه لا يعمل إلا عند إيقاظ
 *     الشاشة": بدون القفل كان النظام يوقف المؤقتات والبثّ حتى تُضاء الشاشة.
 *  2) بثّ BATTERY_CHANGED (يُعالَج عبر goAsync على خيط خلفي).
 *  3) بثّ الشاشة/الطاقة (SCREEN_ON/OFF, POWER_CONNECTED/DISCONNECTED, Doze, توفير الطاقة).
 *  4) منبّه ساعة كل 90 ثانية يعيد تشغيل الخدمة إن قُتلت (يُجدوَل من ChargeScheduler).
 *
 * كل شيء يُسجَّل في DebugLog: فجوات النبض الكبيرة تعني أن المعالج أو العملية كانا متوقفين.
 */
class BatteryMonitorService : Service() {
    private var receiver: BroadcastReceiver? = null
    private var systemReceiver: BroadcastReceiver? = null
    private var lastLogged = ""
    private var shownPercent = -1
    private val handler = Handler(Looper.getMainLooper())
    private val batteryExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var beatThread: HandlerThread? = null
    @Volatile private var beatHandler: Handler? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastBeat = 0L
    private var lastBeatArmed = false
    private var lastSummaryAt = 0L
    private var beatsAtSummary = 0L

    override fun onCreate() {
        super.onCreate()
        running = true
        starting = false
        DiagStats.serviceStartedAt = SystemClock.elapsedRealtime()
        createMonitorChannel()

        val info = BatteryUtils.read(this)
        DebugLog.i(
            this, "SVC",
            "created pct=${info.percent} plugged=${info.plugged} enabled=${Prefs.isEnabled(this)} ${DebugLog.screenState(this)}"
        )
        try {
            startAsForeground(buildNotification(info.percent))
        } catch (t: Throwable) {
            DebugLog.e(this, "SVC", "startForeground FAILED", t)
            running = false
            stopSelf()
            return
        }
        logNotificationState("service created")
        handler.postDelayed({ logNotificationState("1.5s later") }, 1500)

        // Nothing to watch: monitoring is off.
        if (!Prefs.isEnabled(this)) {
            DebugLog.w(this, "SVC", "monitoring disabled -> stopSelf")
            stopSelf()
            return
        }

        registerBatteryReceiver()
        registerSystemReceiver()
        startHeartbeat()
        ChargeScheduler.check(this)
    }

    // ------------------------------------------------------------------ heartbeat

    private fun startHeartbeat() {
        if (beatThread != null) return
        val thread = HandlerThread("chargealert-beat").also { it.start() }
        beatThread = thread
        val h = Handler(thread.looper)
        beatHandler = h
        h.post(beatRunnable)
        DebugLog.i(this, "BEAT", "heartbeat started every ${HEARTBEAT_MS / 1000}s")
    }

    private val beatRunnable = object : Runnable {
        override fun run() {
            try {
                beat()
            } catch (t: Throwable) {
                DebugLog.e(this@BatteryMonitorService, "BEAT", "beat failed", t)
            }
            beatHandler?.postDelayed(this, HEARTBEAT_MS)
        }
    }

    private fun beat() {
        val now = SystemClock.elapsedRealtime()
        val gap = if (lastBeat == 0L) 0L else now - lastBeat
        // الفجوة الكبيرة لا تُسجَّل إلا إذا كنا "مسلَّحين" (الشاحن موصول وبانتظار النسبة) والمعالج
        // يُفترض أنه مستيقظ بفضل القفل؛ حينها تعني أن النظام جمّد العملية أو علّق المعالج.
        if (lastBeatArmed && gap > HEARTBEAT_MS * 3) {
            DiagStats.bigGaps++
            DebugLog.w(
                this, "BEAT",
                "GAP ${gap / 1000}s (expected ${HEARTBEAT_MS / 1000}s): CPU suspended or process frozen. ${DebugLog.screenState(this)}"
            )
        }
        if (gap > DiagStats.maxGapMs) DiagStats.maxGapMs = gap
        lastBeat = now
        DiagStats.lastBeatAt = now
        DiagStats.beats++

        val enabled = Prefs.isEnabled(this)
        val info = BatteryUtils.read(this)
        val armed = enabled && info.plugged && Prefs.alertedAt(this) == 0L
        lastBeatArmed = armed
        syncWakeLock(armed)

        ChargeScheduler.check(this)
        Diagnostics.noteBeatWall(this)

        if (now - lastSummaryAt >= SUMMARY_MS) {
            if (lastSummaryAt != 0L) {
                DebugLog.d(
                    this, "BEAT",
                    "summary beats=${DiagStats.beats - beatsAtSummary} maxGap=${DiagStats.maxGapMs / 1000}s pct=${info.percent} plugged=${info.plugged} armed=$armed wl=${DiagStats.wakeLockHeld}"
                )
            }
            lastSummaryAt = now
            beatsAtSummary = DiagStats.beats
        }
    }

    /** يمسك/يحرر قفل الاستيقاظ الجزئي. المهلة تحمي من بقاء القفل إذا تعطلت النبضات. */
    private fun syncWakeLock(armed: Boolean) {
        try {
            val lock = wakeLock ?: getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "chargealert:monitor")
                .also {
                    it.setReferenceCounted(false)
                    wakeLock = it
                }
            if (armed) {
                val wasHeld = lock.isHeld
                lock.acquire(WAKELOCK_TIMEOUT_MS)
                if (!wasHeld) DebugLog.i(this, "WAKELOCK", "ACQUIRED (plugged, waiting for threshold) ${DebugLog.screenState(this)}")
            } else if (lock.isHeld) {
                lock.release()
                DebugLog.i(this, "WAKELOCK", "released (not armed)")
            }
            DiagStats.wakeLockHeld = lock.isHeld
        } catch (t: Throwable) {
            DebugLog.e(this, "WAKELOCK", "sync failed", t)
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.let { if (it.isHeld) it.release() } }
        wakeLock = null
        DiagStats.wakeLockHeld = false
    }

    // ------------------------------------------------------------------ receivers

    private fun registerBatteryReceiver() {
        receiver?.let { runCatching { unregisterReceiver(it) } }
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != Intent.ACTION_BATTERY_CHANGED) return
                // Keep the broadcast wake lock held while the check runs on a background
                // thread (goAsync + background task).
                val pending = goAsync()
                batteryExecutor.execute {
                    try {
                        val current = BatteryUtils.parse(intent)
                        DiagStats.lastBatteryEventAt = SystemClock.elapsedRealtime()
                        DiagStats.batteryEvents++
                        val tag = "${current.percent}/${current.plugged}"
                        if (tag != lastLogged) {
                            lastLogged = tag
                            DebugLog.i(
                                context, "BATT",
                                "event pct=${current.percent} plugged=${current.plugged} status=${BatteryUtils.statusName(current.status)} plug=${BatteryUtils.plugName(current.plugType)} temp=${current.tempC}C ${DebugLog.screenState(context)}"
                            )
                        }
                        ensureNotificationShown(current.percent)
                        updateNotification(current.percent)
                        if (current.plugged) {
                            ChargeScheduler.check(context)
                        } else {
                            // الشاحن مفصول: ننهي الجلسة بعد التأكد، والخدمة تبقى تعمل بانتظار التوصيل التالي
                            if (ChargeScheduler.isUnplugConfirmed(context)) ChargeScheduler.onUnplugged(context)
                        }
                    } catch (t: Throwable) {
                        DebugLog.e(context, "BATT", "event handling failed", t)
                    } finally {
                        pending.finish()
                    }
                }
            }
        }.also {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED).apply { priority = 100 }
            ContextCompat.registerReceiver(this, it, filter, ContextCompat.RECEIVER_EXPORTED)
        }
    }

    /** بثّ الشاشة والطاقة: للتسجيل التشخيصي، ولفحص فوري عند التوصيل أو إضاءة الشاشة */
    private fun registerSystemReceiver() {
        if (systemReceiver != null) return
        systemReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val name = intent.action?.substringAfterLast('.') ?: return
                DiagStats.lastScreenEvent = "$name @ " +
                    java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
                DebugLog.i(
                    context, "SYS",
                    "$name beatAge=${DiagStats.ageSeconds(DiagStats.lastBeatAt)}s wl=${DiagStats.wakeLockHeld} ${DebugLog.screenState(context)}"
                )
                val app = context.applicationContext
                val job = Runnable {
                    try {
                        when (intent.action) {
                            Intent.ACTION_POWER_CONNECTED -> ChargeScheduler.onPlugged(app)
                            Intent.ACTION_POWER_DISCONNECTED -> ChargeScheduler.check(app)
                            Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> ChargeScheduler.check(app)
                        }
                    } catch (t: Throwable) {
                        DebugLog.e(app, "SYS", "handling $name failed", t)
                    }
                }
                val h = beatHandler
                if (h != null) h.post(job) else batteryExecutor.execute(job)
            }
        }.also {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
                addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
                addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            }
            ContextCompat.registerReceiver(this, it, filter, ContextCompat.RECEIVER_EXPORTED)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val refresh = intent?.getBooleanExtra(EXTRA_REFRESH, false) == true
        DebugLog.d(this, "SVC", "onStartCommand refresh=$refresh systemRestart=${intent == null} startId=$startId")
        if (!running) return START_NOT_STICKY // onCreate failed or was cancelled
        if (Prefs.isEnabled(this)) {
            // Refresh heartbeat: re-registering the receiver makes the system deliver the
            // current sticky BATTERY_CHANGED intent immediately. The direct check() is a safety net.
            if (refresh) {
                registerBatteryReceiver()
                DebugLog.i(this, "SVC", "refresh: receiver re-registered late=${ChargeScheduler.refreshLateSeconds(this)}s")
            }
            registerSystemReceiver()
            startHeartbeat()
            val h = beatHandler
            val job = Runnable { ChargeScheduler.check(applicationContext) }
            if (h != null) h.post(job) else batteryExecutor.execute(job)
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        DebugLog.w(this, "SVC", "task removed from recents (service keeps running) ${DebugLog.screenState(this)}")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        running = false
        DebugLog.w(this, "SVC", "destroyed ${DebugLog.screenState(this)}")
        handler.removeCallbacksAndMessages(null)
        beatHandler?.removeCallbacksAndMessages(null)
        beatHandler = null
        beatThread?.quitSafely()
        beatThread = null
        releaseWakeLock()
        batteryExecutor.shutdownNow()
        receiver?.let { runCatching { unregisterReceiver(it) } }
        receiver = null
        systemReceiver?.let { runCatching { unregisterReceiver(it) } }
        systemReceiver = null
        // Make sure the status-bar notification disappears together with the service.
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------------ notification

    /** إشعار دائم يعرض النسبة الحية: يوضّح أن الخدمة تعمل، وبالضغط عليه تُفتح الشاشة الرئيسية */
    private fun buildNotification(percent: Int): Notification {
        val open = PendingIntent.getActivity(
            this,
            3,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_battery)
            .setContentTitle(getString(R.string.monitor_notification_title))
            .setContentText(
                getString(
                    R.string.monitor_notification_progress,
                    percent.coerceIn(0, 100),
                    Prefs.threshold(this)
                )
            )
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .apply {
                if (Build.VERSION.SDK_INT >= 31) {
                    setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                }
            }
            .build()
    }

    private fun startAsForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /** يسجّل هل الإشعار الدائم ظاهر فعلاً وحالة قناته */
    private fun logNotificationState(tag: String) {
        val nm = getSystemService(NotificationManager::class.java)
        val importance = nm.getNotificationChannel(CHANNEL_ID)?.importance
        val shown = nm.activeNotifications.any { it.id == NOTIFICATION_ID }
        DebugLog.i(
            this, "SVC",
            "$tag: monitor notification shown=$shown channelImportance=$importance appNotifsEnabled=${nm.areNotificationsEnabled()}"
        )
    }

    /** إذا أزال المستخدم الإشعار الدائم (أندرويد 13+ يسمح بذلك) نعيد إظهاره */
    private fun ensureNotificationShown(percent: Int) {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.activeNotifications.none { it.id == NOTIFICATION_ID }) {
            runCatching { startAsForeground(buildNotification(percent)) }
            DebugLog.i(this, "SVC", "monitor notification was missing -> re-posted")
        }
    }

    /** يحدّث نص الإشعار بالنسبة الحية عند تغيّرها (notify بدل startForeground المتكرر) */
    private fun updateNotification(percent: Int) {
        if (percent == shownPercent) return
        shownPercent = percent
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(percent))
        }
    }

    private fun createMonitorChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel(OLD_CHANNEL_ID)
        nm.deleteNotificationChannel(PREVIOUS_CHANNEL_ID)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.monitor_notification_channel),
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        }
        nm.createNotificationChannel(channel)
    }

    companion object {
        /** Extra set on the refresh alarm's intent: re-register the battery receiver on start. */
        const val EXTRA_REFRESH = "refresh"
        private const val OLD_CHANNEL_ID = "charge_monitor"
        private const val PREVIOUS_CHANNEL_ID = "charge_monitor_v2"
        private const val CHANNEL_ID = "charge_monitor_v3"
        private const val NOTIFICATION_ID = 3

        private const val HEARTBEAT_MS = 10_000L
        private const val WAKELOCK_TIMEOUT_MS = 45_000L
        private const val SUMMARY_MS = 5 * 60_000L
        private const val START_PENDING_MS = 8_000L

        @Volatile private var running = false
        @Volatile private var starting = false
        @Volatile private var startRequestedAt = 0L

        val isRunning: Boolean get() = running

        /**
         * Starts the service if it is not already running. Returns false when Android refuses to
         * start a foreground service from the background (Android 12+); the caller then retries
         * from an alarm-clock alarm, which is an allowed context.
         */
        fun ensureRunning(context: Context): Boolean {
            if (running) return true
            val nowE = SystemClock.elapsedRealtime()
            if (starting) {
                if (nowE - startRequestedAt < START_PENDING_MS) return true
                // الطلب السابق لم ينتج خدمة (رفض صامت أو قتل): نعيد المحاولة بدل الانتظار للأبد
                DebugLog.w(context, "SVC", "FGS start was pending ${(nowE - startRequestedAt) / 1000}s without service -> retrying")
            }
            val app = context.applicationContext
            return try {
                starting = true
                startRequestedAt = nowE
                ContextCompat.startForegroundService(app, Intent(app, BatteryMonitorService::class.java))
                DebugLog.i(app, "SVC", "FGS start requested ${DebugLog.screenState(app)}")
                true
            } catch (e: RuntimeException) {
                starting = false
                DiagStats.fgsRefusals++
                DebugLog.e(app, "SVC", "FGS start REFUSED", e)
                false
            }
        }

        fun stop(context: Context) {
            // If a start is still pending, onCreate() re-checks the state and stops itself;
            // stopping it before startForeground() is called would crash the app.
            if (!running) return
            val app = context.applicationContext
            runCatching { app.stopService(Intent(app, BatteryMonitorService::class.java)) }
        }
    }
}
