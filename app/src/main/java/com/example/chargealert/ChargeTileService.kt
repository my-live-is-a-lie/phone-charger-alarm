package com.example.chargealert

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class ChargeTileService : TileService() {

    override fun onTileAdded() {
        Prefs.setTileAdded(this, true)
    }

    override fun onTileRemoved() {
        Prefs.setTileAdded(this, false)
    }

    override fun onStartListening() {
        Prefs.setTileAdded(this, true)
        refresh()
    }

    override fun onClick() {
        if (Prefs.isEnabled(this)) {
            MonitorController.setEnabled(this, false)
            refresh()
            return
        }
        val needsPermission = Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            openSheet()
            return
        }
        MonitorController.setEnabled(this, true)
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val enabled = Prefs.isEnabled(this)
        tile.state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        tile.subtitle = if (enabled) {
            getString(R.string.tile_subtitle_on, Prefs.threshold(this))
        } else {
            getString(R.string.tile_subtitle_off)
        }
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openSheet() {
        val intent = Intent(this, QuickSheetActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
