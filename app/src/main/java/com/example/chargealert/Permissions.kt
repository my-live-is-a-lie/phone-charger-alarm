package com.example.chargealert

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

object Permissions {
    fun canScheduleExactAlarms(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() ?: false
        } else {
            true
        }

    fun canUseFullScreenIntent(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 34) {
            context.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() ?: false
        } else {
            true
        }

    fun canDrawOverlays(context: Context): Boolean =
        Settings.canDrawOverlays(context)

    fun isIgnoringBatteryOptimizations(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) ?: false

    /**
     * يطلب إذن "المنبهات والتذكيرات" (Alarms & Reminders) من إعدادات الهاتف.
     * هذا الإذن ضروري لجدولة المنبهات الدقيقة وإيقاظ الشاشة أثناء الشحن والشاشة مغلقة.
     */
    fun requestExactAlarm(context: Context) {
        if (Build.VERSION.SDK_INT < 31) return
        val am = context.getSystemService(AlarmManager::class.java)
        if (am?.canScheduleExactAlarms() == true) return
        val pkgUri = Uri.parse("package:${context.packageName}")
        val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkgUri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching {
            context.startActivity(intent)
        }.onFailure {
            // بعض الواجهات المخصصة قد لا تدعم ACTION_REQUEST_SCHEDULE_EXACT_ALARM فنفتح صفحة تفاصيل التطبيق
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    fun requestIgnoreBatteryOptimizations(context: Context) {
        val pm = context.getSystemService(PowerManager::class.java)
        if (pm?.isIgnoringBatteryOptimizations(context.packageName) == true) return
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    fun requestOverlay(context: Context) {
        if (Settings.canDrawOverlays(context)) return
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    fun requestFullScreenIntent(context: Context) {
        if (Build.VERSION.SDK_INT < 34) return
        val intent = Intent(
            Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }
}
