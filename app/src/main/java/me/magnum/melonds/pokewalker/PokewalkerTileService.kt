package me.magnum.melonds.pokewalker

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import me.magnum.melonds.R
import java.text.NumberFormat

/** Quick Settings tile: today's steps and watts at a glance; tap to open the walker. */
class PokewalkerTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        val status = runCatching { Pokewalker.status() }.getOrNull()
        val numbers = NumberFormat.getIntegerInstance()
        tile.label = getString(R.string.pokewalker_device_label)
        val detail = if (status != null && status.registered) {
            getString(R.string.pokewalker_tile_subtitle, numbers.format(status.todaySteps), numbers.format(status.watts))
        } else {
            getString(R.string.pokewalker_modern_not_paired)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = detail
        } else {
            tile.label = "${getString(R.string.pokewalker_device_label)} · $detail"
        }
        tile.state = if (status?.hasPokemon == true) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, PokewalkerDeviceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        /** Asks the system to show fresh numbers next time the tile is visible. */
        fun refresh(context: Context) {
            try {
                requestListeningState(context.applicationContext, ComponentName(context.applicationContext, PokewalkerTileService::class.java))
            } catch (e: Exception) {
                Log.d("PokewalkerTile", "Tile not added or not refreshable: ${e.message}")
            }
        }
    }
}
