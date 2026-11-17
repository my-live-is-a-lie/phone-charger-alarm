package com.example.chargealert

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.job.JobScheduler
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** عدّادات حيّة داخل العملية (تُصفَّر عند موت العملية؛ وموتها يظهر في السجل كسطر PROC جديد) */
object DiagStats {
    @Volatile var processStartedAt = SystemClock.elapsedRealtime()
    @Volatile var serviceStartedAt = 0L
    @Volatile var lastBeatAt = 0L
    @Volatile var beats = 0L
    @Volatile var maxGapMs = 0L
    @Volatile var bigGaps = 0
    @Volatile var lastBatteryEventAt = 0L
    @Volatile var batteryEvents = 0L
    @Volatile var lastAlertAtWall = 0L
    @Volatile var wakeLockHeld = false
    @Volatile var fgsRefusals = 0
    @Volatile var lastScreenEvent = "-"

    fun ageSeconds(at: Long): Long = if (at == 0L) -1 else (SystemClock.elapsedRealtime() - at) / 1000
}

enum class CheckStatus { OK, WARN, BAD, INFO }

enum class Fix { BATTERY_OPT, OVERLAY, FULL_SCREEN, NOTIFICATIONS, EXACT_ALARM, APP_DETAILS }

data class Check(
    val title: String,
    val status: CheckStatus,
    val detail: String,
    val fix: Fix? = null
)

object Diagnostics {
    private const val KEY_LAST_BEAT_WALL = "diag_last_beat_wall"
    private const val KEY_LAST_EXIT_TS = "diag_last_exit_ts"
    private val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    private fun fmt(ms: Long): String = synchronized(timeFmt) { timeFmt.format(Date(ms)) }

    // ------------------------------------------------------------------ process start

    /** يُستدعى من Application.onCreate: يسجّل بداية العملية وسبب موت العملية السابقة */
    fun onProcessStart(context: Context) {
        val app = context.applicationContext
        DebugLog.i(
            app, "PROC",
            "process START pid=${android.os.Process.myPid()} app=${appVersion(app)} " +
                "device=${Build.MANUFACTURER} ${Build.MODEL} android=${Build.VERSION.RELEASE}(${Build.VERSION.SDK_INT})"
        )
        val lastBeat = Prefs.of(app).getLong(KEY_LAST_BEAT_WALL, 0L)
        if (lastBeat != 0L) {
            val ago = (System.currentTimeMillis() - lastBeat) / 1000
            DebugLog.i(app, "PROC", "last heartbeat of previous process was at ${fmt(lastBeat)} (${ago}s ago)")
        }
        logNewExitReasons(app)
    }

    fun noteBeatWall(context: Context) {
        Prefs.of(context).edit().putLong(KEY_LAST_BEAT_WALL, System.currentTimeMillis()).apply()
    }

    private fun logNewExitReasons(app: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        runCatching {
            val am = app.getSystemService(ActivityManager::class.java)
            val seen = Prefs.of(app).getLong(KEY_LAST_EXIT_TS, 0L)
            var newest = seen
            val infos = am.getHistoricalProcessExitReasons(app.packageName, 0, 10)
                .sortedBy { it.timestamp }
            for (info in infos) {
                if (info.timestamp <= seen) continue
                val killedByOs = info.reason !in intArrayOf(1, 10, 11, 16)
                val line = "previous process DIED: reason=${exitReasonName(info.reason)} status=${info.status} " +
                    "importance=${info.importance} at=${fmt(info.timestamp)} desc=${info.description}"
                if (killedByOs) DebugLog.w(app, "EXIT", line) else DebugLog.i(app, "EXIT", line)
                if (info.timestamp > newest) newest = info.timestamp
            }
            if (newest != seen) Prefs.of(app).edit().putLong(KEY_LAST_EXIT_TS, newest).apply()
        }.onFailure { DebugLog.e(app, "EXIT", "reading exit reasons failed", it) }
    }

    private fun exitReasonName(reason: Int): String = when (reason) {
        1 -> "EXIT_SELF"
        2 -> "SIGNALED"
        3 -> "LOW_MEMORY"
        4 -> "CRASH"
        5 -> "CRASH_NATIVE"
        6 -> "ANR"
        7 -> "INITIALIZATION_FAILURE"
        8 -> "PERMISSION_CHANGE"
        9 -> "EXCESSIVE_RESOURCE_USAGE"
        10 -> "USER_REQUESTED"
        11 -> "USER_STOPPED"
        12 -> "DEPENDENCY_DIED"
        13 -> "OTHER"
        14 -> "FREEZER"
        15 -> "PACKAGE_STATE_CHANGE"
        16 -> "PACKAGE_UPDATED"
        else -> "UNKNOWN($reason)"
    }

    private fun appVersion(context: Context): String = runCatching {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        "${pi.versionName}(${pi.longVersionCode})"
    }.getOrDefault("?")

    // ------------------------------------------------------------------ one-line snapshot

    /** سطر واحد مضغوط لحالة النظام، يُكتب في السجل عند الأحداث المهمة */
    fun oneLine(context: Context): String {
        val b = BatteryUtils.read(context)
        return "pct=${b.percent} plugged=${b.plugged}(${BatteryUtils.statusName(b.status)},${BatteryUtils.plugName(b.plugType)},src=${b.source}) " +
            "svc=${BatteryMonitorService.isRunning} wl=${DiagStats.wakeLockHeld} " +
            "beatAge=${DiagStats.ageSeconds(DiagStats.lastBeatAt)}s ${DebugLog.screenState(context)}"
    }

    // ------------------------------------------------------------------ live checks (dashboard)

    fun checks(context: Context): List<Check> {
        val out = ArrayList<Check>()
        val pm = context.getSystemService(PowerManager::class.java)
        val nm = context.getSystemService(NotificationManager::class.java)
        val am = context.getSystemService(AudioManager::class.java)

        val enabled = Prefs.isEnabled(context)
        out += Check(
            "المراقبة", if (enabled) CheckStatus.OK else CheckStatus.BAD,
            if (enabled) "مفعّلة (النسبة ${Prefs.threshold(context)}%)" else "متوقفة — فعّلها من الشاشة الرئيسية أو من البلاطة"
        )

        val running = BatteryMonitorService.isRunning
        out += Check(
            "خدمة المراقبة (Foreground)",
            if (!enabled) CheckStatus.INFO else if (running) CheckStatus.OK else CheckStatus.BAD,
            if (running) {
                "تعمل منذ ${DiagStats.ageSeconds(DiagStats.serviceStartedAt)} ثانية"
            } else {
                "لا تعمل" + (if (DiagStats.fgsRefusals > 0) " — رفض النظام تشغيلها ${DiagStats.fgsRefusals} مرة" else "")
            }
        )

        if (enabled && running) {
            val age = DiagStats.ageSeconds(DiagStats.lastBeatAt)
            val st = when {
                age < 0 -> CheckStatus.WARN
                age <= 30 -> CheckStatus.OK
                else -> CheckStatus.BAD
            }
            out += Check(
                "نبض الفحص الدوري",
                st,
                "آخر نبضة قبل ${age} ث • عدد النبضات ${DiagStats.beats} • أكبر فجوة ${DiagStats.maxGapMs / 1000} ث • فجوات كبيرة ${DiagStats.bigGaps}"
            )
        }

        val wl = DiagStats.wakeLockHeld
        out += Check(
            "إبقاء المعالج مستيقظاً أثناء الانتظار",
            CheckStatus.INFO,
            if (wl) "نشط الآن (الشاحن موصول وبانتظار النسبة)" else "غير نشط (طبيعي إن لم يكن الشاحن موصولاً أو بعد إرسال التنبيه)"
        )

        val notifOn = nm.areNotificationsEnabled()
        out += Check(
            "إذن الإشعارات",
            if (notifOn) CheckStatus.OK else CheckStatus.BAD,
            if (notifOn) "مسموح" else "الإشعارات محظورة: لن يظهر التنبيه",
            if (notifOn) null else Fix.NOTIFICATIONS
        )

        val alertCh = nm.getNotificationChannel("alert_silent_v1")
        val chImp = alertCh?.importance
        out += Check(
            "قناة التنبيه",
            when {
                alertCh == null -> CheckStatus.INFO
                chImp == NotificationManager.IMPORTANCE_NONE -> CheckStatus.BAD
                chImp != null && chImp < NotificationManager.IMPORTANCE_HIGH -> CheckStatus.WARN
                else -> CheckStatus.OK
            },
            if (alertCh == null) "لم تُنشأ بعد (تُنشأ عند أول تنبيه)" else "الأهمية = $chImp (المطلوب 4 فأعلى لظهور الشاشة الكاملة)",
            if (alertCh != null && (chImp ?: 4) < NotificationManager.IMPORTANCE_HIGH) Fix.NOTIFICATIONS else null
        )

        if (Build.VERSION.SDK_INT >= 31) {
            val canExact = Permissions.canScheduleExactAlarms(context)
            out += Check(
                "إذن المنبهات والتذكيرات (Exact Alarms)",
                if (canExact) CheckStatus.OK else CheckStatus.BAD,
                if (canExact) "مسموح — يتيح إيقاظ الشاشة وإطلاق التنبيه بدقة أثناء الشحن والشاشة مغلقة"
                else "غير مسموح: لن يتمكن التطبيق من إيقاظ الشاشة بالموعد الدقيق أثناء الشحن",
                if (canExact) null else Fix.EXACT_ALARM
            )
        }

        if (Build.VERSION.SDK_INT >= 34) {
            val fsi = nm.canUseFullScreenIntent()
            out += Check(
                "عرض التنبيه على شاشة مقفلة (Full-screen intent)",
                if (fsi) CheckStatus.OK else CheckStatus.BAD,
                if (fsi) "مسموح" else "غير مسموح: لن تُضاء الشاشة تلقائياً عند التنبيه",
                if (fsi) null else Fix.FULL_SCREEN
            )
        }

        val overlay = Settings.canDrawOverlays(context)
        out += Check(
            "الظهور فوق التطبيقات (يسمح أيضاً بتشغيل الخدمة من الخلفية)",
            if (overlay) CheckStatus.OK else CheckStatus.WARN,
            if (overlay) "مسموح" else "غير مسموح — يُنصح به",
            if (overlay) null else Fix.OVERLAY
        )

        val ignoring = pm.isIgnoringBatteryOptimizations(context.packageName)
        out += Check(
            "استثناء من تحسين البطارية",
            if (ignoring) CheckStatus.OK else CheckStatus.BAD,
            if (ignoring) "مستثنى" else "غير مستثنى: النظام قد يجمّد التطبيق والشاشة مغلقة",
            if (ignoring) null else Fix.BATTERY_OPT
        )

        val restricted = runCatching {
            context.getSystemService(ActivityManager::class.java).isBackgroundRestricted
        }.getOrNull()
        out += Check(
            "تقييد الخلفية (من إعدادات التطبيق)",
            if (restricted == true) CheckStatus.BAD else CheckStatus.OK,
            if (restricted == true) "التطبيق مقيّد في الخلفية — اجعل البطارية \"بدون قيود\"" else "غير مقيّد",
            if (restricted == true) Fix.APP_DETAILS else null
        )

        val bucket = runCatching {
            context.getSystemService(UsageStatsManager::class.java).appStandbyBucket
        }.getOrNull()
        out += Check(
            "فئة الاستعداد (Standby bucket)",
            when {
                bucket == null -> CheckStatus.INFO
                bucket >= 40 -> CheckStatus.WARN
                else -> CheckStatus.OK
            },
            "${bucketName(bucket)} ($bucket)"
        )

        val saver = pm.isPowerSaveMode
        out += Check(
            "وضع توفير الطاقة",
            if (saver) CheckStatus.WARN else CheckStatus.OK,
            if (saver) "مفعّل: قد يؤخر المهام الخلفية" else "غير مفعّل"
        )

        val alarmVol = am.getStreamVolume(AudioManager.STREAM_ALARM)
        val alarmMax = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        out += Check(
            "صوت المنبّه",
            if (alarmVol == 0) CheckStatus.BAD else CheckStatus.OK,
            "$alarmVol من $alarmMax" + if (alarmVol == 0) " — لن تسمع شيئاً" else ""
        )

        val dnd = nm.currentInterruptionFilter
        out += Check(
            "عدم الإزعاج (DND)",
            if (dnd == NotificationManager.INTERRUPTION_FILTER_NONE) CheckStatus.BAD else CheckStatus.OK,
            dndName(dnd) + if (dnd == NotificationManager.INTERRUPTION_FILTER_NONE) " — يمنع المنبّهات" else ""
        )

        val js = context.getSystemService(JobScheduler::class.java)
        val jobs = runCatching { js.allPendingJobs }.getOrDefault(emptyList())
        out += Check(
            "مهام JobScheduler المعلّقة",
            CheckStatus.INFO,
            if (jobs.isEmpty()) "لا شيء" else jobs.joinToString(" • ") { "#${it.id}${if (it.isRequireCharging) " (يتطلب شحناً)" else ""}" }
        )

        val next = context.getSystemService(AlarmManager::class.java).nextAlarmClock
        out += Check(
            "أقرب منبّه ساعة مسجّل",
            CheckStatus.INFO,
            if (next == null) "لا يوجد" else "بعد ${(next.triggerTime - System.currentTimeMillis()) / 1000} ث"
        )

        val b = BatteryUtils.read(context)
        out += Check(
            "حالة البطارية",
            CheckStatus.INFO,
            "${b.percent}% • ${BatteryUtils.statusName(b.status)} • ${BatteryUtils.plugName(b.plugType)} • " +
                "plugged=${b.plugged} (${b.source})"
        )

        val m = Build.MANUFACTURER.lowercase(Locale.US)
        if (m.contains("infinix") || m.contains("tecno") || m.contains("itel") || m.contains("transsion")) {
            out += Check(
                "ملاحظة لهواتف Infinix/Tecno (XOS/HiOS)",
                CheckStatus.INFO,
                "هذه الأنظمة تجمّد التطبيقات الخلفية بقوة. من إعدادات النظام: البطارية/إدارة الطاقة ← " +
                    "تنبيه الشحن ← \"بدون قيود\"، وفعّل التشغيل التلقائي (Autostart) إن وُجد. التسميات تختلف بين الإصدارات."
            )
        }
        return out
    }

    private fun bucketName(b: Int?): String = when (b) {
        10 -> "ACTIVE"
        20 -> "WORKING_SET"
        30 -> "FREQUENT"
        40 -> "RARE"
        45 -> "RESTRICTED"
        50 -> "NEVER"
        null -> "unknown"
        else -> "?"
    }

    private fun dndName(f: Int): String = when (f) {
        NotificationManager.INTERRUPTION_FILTER_ALL -> "مغلق (كل الإشعارات مسموحة)"
        NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "أولوية فقط (المنبّهات تعمل)"
        NotificationManager.INTERRUPTION_FILTER_ALARMS -> "المنبّهات فقط"
        NotificationManager.INTERRUPTION_FILTER_NONE -> "صمت تام"
        else -> "غير معروف ($f)"
    }

    // ------------------------------------------------------------------ full report

    /** تقرير كامل نصّي: معلومات الجهاز + الحالة + المجدولات + أسباب موت العملية + آخر السجل */
    fun fullReport(context: Context, logLines: Int = 600): String {
        val app = context.applicationContext
        val sb = StringBuilder()
        sb.appendLine("===== Charge Alert diagnostic report =====")
        sb.appendLine("generated: ${fmt(System.currentTimeMillis())}")
        sb.appendLine()

        section(sb, "DEVICE") {
            appendLine("app: ${appVersion(app)}  pkg=${app.packageName}")
            appendLine("device: ${Build.MANUFACTURER} ${Build.BRAND} ${Build.MODEL} (${Build.DEVICE})")
            appendLine("android: ${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT} patch=${Build.VERSION.SECURITY_PATCH}")
            appendLine("rom: ${Build.DISPLAY}")
            appendLine("fingerprint: ${Build.FINGERPRINT}")
        }

        section(sb, "SETTINGS") {
            val s = Prefs.session(app)
            appendLine("enabled=${Prefs.isEnabled(app)} threshold=${Prefs.threshold(app)} alertedAt=${Prefs.alertedAt(app)}")
            appendLine("session: start=${fmtOrDash(s.startTime)} startPct=${s.startPct} last=${fmtOrDash(s.lastTime)} lastPct=${s.lastPct}")
        }

        section(sb, "CHECKS") {
            for (c in checks(app)) {
                val mark = when (c.status) {
                    CheckStatus.OK -> "[OK]  "
                    CheckStatus.WARN -> "[WARN]"
                    CheckStatus.BAD -> "[BAD] "
                    CheckStatus.INFO -> "[i]   "
                }
                appendLine("$mark ${c.title}: ${c.detail}")
            }
        }

        section(sb, "BATTERY (raw)") {
            val bm = app.getSystemService(BatteryManager::class.java)
            val b = BatteryUtils.read(app)
            appendLine("read(): pct=${b.percent} plugged=${b.plugged} status=${BatteryUtils.statusName(b.status)} plug=${BatteryUtils.plugName(b.plugType)} temp=${b.tempC}C volt=${b.voltageMv}mV src=${b.source}")
            appendLine("bm.isCharging=${bm.isCharging} currentNow=${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)}uA currentAvg=${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)}uA chargeCounter=${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)}uAh")
        }

        section(sb, "POWER / SCREEN") {
            val pm = app.getSystemService(PowerManager::class.java)
            val km = app.getSystemService(KeyguardManager::class.java)
            appendLine("interactive=${pm.isInteractive} keyguardLocked=${km.isKeyguardLocked} deviceLocked=${km.isDeviceLocked}")
            appendLine("deviceIdle(doze)=${pm.isDeviceIdleMode} powerSave=${pm.isPowerSaveMode}")
            appendLine("ignoringBatteryOpt=${pm.isIgnoringBatteryOptimizations(app.packageName)} thermalStatus=${pm.currentThermalStatus}")
            appendLine("last screen event: ${DiagStats.lastScreenEvent}")
        }

        section(sb, "SERVICE / HEARTBEAT") {
            appendLine("serviceRunning=${BatteryMonitorService.isRunning} startedAgo=${DiagStats.ageSeconds(DiagStats.serviceStartedAt)}s processAge=${DiagStats.ageSeconds(DiagStats.processStartedAt)}s")
            appendLine("beats=${DiagStats.beats} lastBeatAgo=${DiagStats.ageSeconds(DiagStats.lastBeatAt)}s maxGap=${DiagStats.maxGapMs / 1000}s bigGaps=${DiagStats.bigGaps}")
            appendLine("batteryEvents=${DiagStats.batteryEvents} lastBatteryEventAgo=${DiagStats.ageSeconds(DiagStats.lastBatteryEventAt)}s")
            appendLine("wakeLockHeld=${DiagStats.wakeLockHeld} fgsRefusals=${DiagStats.fgsRefusals}")
            appendLine("lastAlert=${fmtOrDash(DiagStats.lastAlertAtWall)} alarmPlaying=${AlarmPlayer.isPlaying}")
            appendLine("pollLate=${ChargeScheduler.pollLateSeconds(app)}s refreshLate=${ChargeScheduler.refreshLateSeconds(app)}s")
        }

        section(sb, "SCHEDULERS") {
            val js = app.getSystemService(JobScheduler::class.java)
            val jobs = js.allPendingJobs
            appendLine("pending jobs: ${jobs.size}")
            for (j in jobs) {
                appendLine("  job #${j.id} ${j.service.shortClassName} requiresCharging=${j.isRequireCharging} persisted=${j.isPersisted} minLatency=${j.minLatencyMillis}ms deadline=${j.maxExecutionDelayMillis}ms")
            }
            val alarms = app.getSystemService(AlarmManager::class.java)
            val next = alarms.nextAlarmClock
            appendLine("nextAlarmClock: ${if (next == null) "none" else "in ${(next.triggerTime - System.currentTimeMillis()) / 1000}s"}")
            appendLine("canScheduleExactAlarms=${if (Build.VERSION.SDK_INT >= 31) alarms.canScheduleExactAlarms() else "n/a"}")
        }

        section(sb, "NOTIFICATIONS") {
            val nm = app.getSystemService(NotificationManager::class.java)
            appendLine("enabled=${nm.areNotificationsEnabled()} dnd=${dndName(nm.currentInterruptionFilter)}")
            for (id in listOf("charge_monitor_v3", "alert_silent_v1")) {
                val ch = nm.getNotificationChannel(id)
                appendLine("channel $id: ${if (ch == null) "missing" else "importance=${ch.importance} lockscreen=${ch.lockscreenVisibility}"}")
            }
            appendLine("active: ${nm.activeNotifications.joinToString { "#${it.id}" }.ifEmpty { "none" }}")
        }

        section(sb, "PROCESS EXIT HISTORY (Android 11+)") {
            if (Build.VERSION.SDK_INT >= 30) {
                val am = app.getSystemService(ActivityManager::class.java)
                val infos = am.getHistoricalProcessExitReasons(app.packageName, 0, 8)
                if (infos.isEmpty()) appendLine("none recorded")
                for (i in infos) {
                    appendLine("${fmt(i.timestamp)} reason=${exitReasonName(i.reason)} status=${i.status} importance=${i.importance} desc=${i.description}")
                }
            } else appendLine("n/a")
        }

        sb.appendLine()
        sb.appendLine("===== LOG (last $logLines lines; format: time pid LEVEL/TAG message) =====")
        for (l in DebugLog.lines(app, logLines)) sb.appendLine(l)
        return sb.toString()
    }

    private fun fmtOrDash(ms: Long): String = if (ms == 0L) "-" else fmt(ms)

    private inline fun section(sb: StringBuilder, title: String, block: StringBuilder.() -> Unit) {
        sb.appendLine("--- $title ---")
        try {
            sb.block()
        } catch (t: Throwable) {
            sb.appendLine("!! section failed: ${t.javaClass.simpleName}: ${t.message}")
        }
        sb.appendLine()
    }

    // ------------------------------------------------------------------ actions

    fun openFix(context: Context, fix: Fix) {
        if (fix == Fix.EXACT_ALARM) {
            Permissions.requestExactAlarm(context)
            return
        }
        val pkg = Uri.parse("package:${context.packageName}")
        val intent = when (fix) {
            Fix.BATTERY_OPT -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg)
            Fix.OVERLAY -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg)
            Fix.FULL_SCREEN ->
                if (Build.VERSION.SDK_INT >= 34) Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg)
                else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
            Fix.NOTIFICATIONS ->
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            Fix.EXACT_ALARM -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
            Fix.APP_DETAILS -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { DebugLog.w(context, "UI", "openFix($fix) failed: ${it.javaClass.simpleName}") }
    }

    /** يكتب التقرير في ملف ويفتح قائمة المشاركة */
    fun shareReport(context: Context) {
        runCatching {
            val dir = File(context.filesDir, "diag").apply { mkdirs() }
            val file = File(dir, "charge-alert-report.txt")
            file.writeText(fullReport(context, 900), Charsets.UTF_8)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.diagfiles", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Charge Alert diagnostic report")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, "مشاركة تقرير التشخيص").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { DebugLog.e(context, "UI", "share report failed", it) }
    }

    /** إعادة تشغيل المراقبة بالكامل (إيقاف الخدمة ثم تشغيلها) */
    fun restartMonitor(context: Context) {
        val app = context.applicationContext
        DebugLog.i(app, "UI", "manual restart of monitor requested")
        BatteryMonitorService.stop(app)
        Handler(Looper.getMainLooper()).postDelayed({ ChargeScheduler.start(app) }, 800L)
    }
}
