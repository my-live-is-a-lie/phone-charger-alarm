package com.example.chargealert

import android.app.job.JobParameters
import android.app.job.JobService

/**
 * فحص دوري عبر JobScheduler أثناء الشحن. مؤقتات JobScheduler يملكها النظام نفسه، فلا يؤخرها
 * نظام الهاتف كما يؤخر مؤقتات التطبيق (AlarmManager) والشاشة مغلقة.
 */
class PollJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        ChargeScheduler.check(this)
        return false
    }

    override fun onStopJob(params: JobParameters): Boolean = false
}
