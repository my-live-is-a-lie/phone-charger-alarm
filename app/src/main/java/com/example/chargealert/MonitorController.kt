package com.example.chargealert

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat

object MonitorController {

    fun setEnabled(context: Context, enabled: Boolean) {
        val app = context.applicationContext
        Prefs.setEnabled(app, enabled)
        val intent = Intent(app, ChargeMonitorService::class.java)
        if (enabled) {
            try {
                ContextCompat.startForegroundService(app, intent)
            } catch (e: Exception) {
                Prefs.setEnabled(app, false)
            }
        } else {
            app.stopService(intent)
            Notifications.cancelAlert(app)
        }
        refreshTile(app)
    }

    fun refreshTile(context: Context) {
        TileService.requestListeningState(
            context,
            ComponentName(context, ChargeTileService::class.java)
        )
    }
}
