package com.example.chargealert
import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService
object MonitorController {
    fun setEnabled(context: Context, enabled: Boolean) {
        val app=context.applicationContext
        Prefs.setEnabled(app,enabled)
        if(enabled) ChargeScheduler.start(app) else ChargeScheduler.stop(app)
        refreshTile(app)
    }
    fun onThresholdChanged(context: Context) {
        val app=context.applicationContext
        ChargeScheduler.onThresholdChanged(app)
        refreshTile(app)
    }
    fun refreshTile(context: Context) {
        TileService.requestListeningState(context,ComponentName(context,ChargeTileService::class.java))
    }
}
