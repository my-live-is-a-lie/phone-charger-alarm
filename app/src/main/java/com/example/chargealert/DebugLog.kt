package com.example.chargealert

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * سجل تشخيص دائم على ملف (يبقى بعد موت العملية وإعادة التشغيل).
 *
 * صيغة السطر:  MM-dd HH:mm:ss.SSS <pid> <LEVEL>/<TAG> <message>
 *  - تغيّر رقم pid بين سطرين يعني أن العملية ماتت وأُعيد تشغيلها.
 *  - المستويات: D تفصيل، I معلومة، W تحذير، E خطأ.
 *  - الكتابة متزامنة عمداً حتى لا يضيع آخر سطر إذا قُتلت العملية والشاشة مغلقة.
 */
object DebugLog {
    private const val DIR = "diag"
    private const val CURRENT = "events.log"
    private const val OLD = "events.1.log"
    private const val MAX_FILE_BYTES = 300_000L
    private const val MAX_RING = 900

    private val lock = Any()
    private val ring = ArrayDeque<String>()
    private var ringLoaded = false
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val pid = android.os.Process.myPid()
    private var lastKey = ""
    private var lastAt = 0L

    private fun dir(context: Context): File =
        File(context.applicationContext.filesDir, DIR).apply { mkdirs() }

    fun logFile(context: Context): File = File(dir(context), CURRENT)

    // ---- API قديم متوافق مع بقية الكود ----
    fun add(context: Context, message: String) = log(context, 'I', "APP", message, null)

    // ---- API جديد ----
    fun d(context: Context, tag: String, message: String) = log(context, 'D', tag, message, null)
    fun i(context: Context, tag: String, message: String) = log(context, 'I', tag, message, null)
    fun w(context: Context, tag: String, message: String) = log(context, 'W', tag, message, null)
    fun e(context: Context, tag: String, message: String, error: Throwable? = null) =
        log(context, 'E', tag, message, error)

    private fun log(context: Context, level: Char, tag: String, message: String, error: Throwable?) {
        runCatching {
            val text = buildString {
                append(message.replace('\n', ' '))
                if (error != null) {
                    append(" | ").append(error.javaClass.simpleName)
                    error.message?.let { append(": ").append(it.replace('\n', ' ')) }
                    val frames = error.stackTrace.take(6).joinToString(" <- ") {
                        "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}"
                    }
                    if (frames.isNotEmpty()) append(" @ ").append(frames)
                }
            }
            synchronized(lock) {
                val nowMs = System.currentTimeMillis()
                val key = "$level/$tag/$text"
                // نمنع فقط العاصفة المتكررة خلال ثانية واحدة؛ التكرار البعيد زمنياً مفيد للتحليل
                if (key == lastKey && nowMs - lastAt < 1_000L) {
                    lastAt = nowMs
                } else {
                    lastKey = key
                    lastAt = nowMs
                    val line = "${stamp.format(Date(nowMs))} $pid $level/$tag $text"
                    ensureRing(context)
                    ring.addLast(line)
                    while (ring.size > MAX_RING) ring.removeFirst()
                    appendToFile(context, line)
                }
            }
        }
    }

    private fun appendToFile(context: Context, line: String) {
        val f = logFile(context)
        if (f.length() > MAX_FILE_BYTES) {
            val old = File(f.parentFile, OLD)
            old.delete()
            f.renameTo(old)
        }
        f.appendText(line + "\n", Charsets.UTF_8)
    }

    private fun ensureRing(context: Context) {
        if (ringLoaded) return
        ringLoaded = true
        val d = dir(context)
        for (f in listOf(File(d, OLD), File(d, CURRENT))) {
            if (!f.exists()) continue
            runCatching {
                f.useLines(Charsets.UTF_8) { seq ->
                    for (l in seq) {
                        ring.addLast(l)
                        while (ring.size > MAX_RING) ring.removeFirst()
                    }
                }
            }
        }
    }

    /** آخر [max] سطراً (الأقدم أولاً) */
    fun lines(context: Context, max: Int = 300): List<String> = synchronized(lock) {
        ensureRing(context)
        ring.toList().takeLast(max)
    }

    fun read(context: Context): String = lines(context, 200).joinToString("\n")

    fun clear(context: Context) {
        synchronized(lock) {
            ring.clear()
            ringLoaded = true
            val d = dir(context)
            File(d, CURRENT).delete()
            File(d, OLD).delete()
        }
    }

    /** مستوى السطر (D/I/W/E) أو ' ' إن لم يُعرف */
    fun levelOf(line: String): Char {
        // date time pid LEVEL/TAG message
        val parts = line.split(' ', limit = 5)
        return parts.getOrNull(3)?.firstOrNull() ?: ' '
    }

    /** وسم السطر (SVC/ALERT/...) */
    fun tagOf(line: String): String {
        val parts = line.split(' ', limit = 5)
        return parts.getOrNull(3)?.substringAfter('/', "") ?: ""
    }

    /** حالة الشاشة والنظام لحظة الحدث (سطر واحد مضغوط) */
    fun screenState(context: Context): String {
        val pm = context.getSystemService(android.os.PowerManager::class.java)
        val km = context.getSystemService(android.app.KeyguardManager::class.java)
        val bucket = runCatching {
            context.getSystemService(android.app.usage.UsageStatsManager::class.java).appStandbyBucket
        }.getOrNull()
        val restricted = runCatching {
            context.getSystemService(android.app.ActivityManager::class.java).isBackgroundRestricted
        }.getOrNull()
        val idle = runCatching { pm.isDeviceIdleMode }.getOrNull()
        val saver = runCatching { pm.isPowerSaveMode }.getOrNull()
        // bucket: 10 نشط، 20 مستخدم، 30 متكرر، 40 نادر، 45 مقيّد
        return "interactive=${pm.isInteractive} locked=${km.isKeyguardLocked} idle=$idle saver=$saver bucket=$bucket restricted=$restricted"
    }
}
