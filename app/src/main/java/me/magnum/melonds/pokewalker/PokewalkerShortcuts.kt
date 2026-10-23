package me.magnum.melonds.pokewalker

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import me.magnum.melonds.R

/** Long-press shortcuts on the Pokéwalker icon: Poké Radar, Dowsing, Trainer card, Sync steps. */
object PokewalkerShortcuts {
    private const val TAG = "PokewalkerShortcuts"

    fun publish(context: Context) {
        val app = context.applicationContext
        try {
            val walker = ComponentName(app, PokewalkerDeviceActivity::class.java)
            fun openApp(id: String, label: Int, icon: Int, index: Int): ShortcutInfoCompat =
                ShortcutInfoCompat.Builder(app, id)
                    .setShortLabel(app.getString(label))
                    .setIcon(IconCompat.createWithResource(app, icon))
                    .setActivity(walker)
                    .setIntent(
                        Intent(app, PokewalkerDeviceActivity::class.java)
                            .setAction(Intent.ACTION_VIEW)
                            .putExtra(PokewalkerDeviceActivity.EXTRA_OPEN_APP, index)
                    )
                    .build()
            val sync = ShortcutInfoCompat.Builder(app, "pokewalker_sync")
                .setShortLabel(app.getString(R.string.pokewalker_shortcut_sync))
                .setIcon(IconCompat.createWithResource(app, R.drawable.ic_pw_shortcut_sync))
                .setActivity(walker)
                .setIntent(Intent(app, PokewalkerSyncActivity::class.java).setAction(Intent.ACTION_VIEW))
                .build()
            val shortcuts = listOf(
                openApp("pokewalker_radar", R.string.pokewalker_shortcut_radar, R.drawable.ic_pw_shortcut_radar, 0),
                openApp("pokewalker_dowsing", R.string.pokewalker_shortcut_dowsing, R.drawable.ic_pw_shortcut_dowsing, 1),
                openApp("pokewalker_card", R.string.pokewalker_shortcut_card, R.drawable.ic_pw_shortcut_card, 3),
                sync,
            )
            val max = ShortcutManagerCompat.getMaxShortcutCountPerActivity(app).coerceAtLeast(1)
            ShortcutManagerCompat.setDynamicShortcuts(app, shortcuts.take(max))
        } catch (e: Exception) {
            Log.w(TAG, "Could not publish the Pokéwalker shortcuts", e)
        }
    }
}
