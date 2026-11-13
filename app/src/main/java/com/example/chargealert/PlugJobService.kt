package com.example.chargealert
import android.app.job.JobParameters
import android.app.job.JobService
class PlugJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        DebugLog.add(this, "job fired (charging started)")
        ChargeScheduler.onPlugged(this)
        return false
    }
    override fun onStopJob(params: JobParameters): Boolean = false
}
