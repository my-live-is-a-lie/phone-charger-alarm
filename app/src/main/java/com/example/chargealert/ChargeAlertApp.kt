package com.example.chargealert

import android.app.Application

/**
 * يسجّل بداية كل عملية جديدة وسبب موت العملية السابقة (قتلها النظام؟ جُمّدت؟ انهارت؟)،
 * ويلتقط أي استثناء غير معالج في السجل قبل أن ينهار التطبيق.
 */
class ChargeAlertApp : Application() {
    override fun onCreate() {
        super.onCreate()
        installCrashLogger()
        Diagnostics.onProcessStart(this)
    }

    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { DebugLog.e(this, "CRASH", "uncaught exception in thread '${thread.name}'", error) }
            previous?.uncaughtException(thread, error)
        }
    }
}
