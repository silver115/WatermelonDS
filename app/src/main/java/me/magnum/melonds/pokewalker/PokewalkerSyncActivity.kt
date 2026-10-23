package me.magnum.melonds.pokewalker

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Invisible screen behind "Sync steps" (widget button, launcher shortcut): being on screen
 * for a moment lets Health Connect hand over steps even without background access.
 */
class PokewalkerSyncActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = applicationContext
        lifecycleScope.launch {
            val result = WalkerSync.fromHealthConnect(app)
            Toast.makeText(app, WalkerSync.describe(app, result), Toast.LENGTH_SHORT).show()
            PokewalkerWidget.updateAll(app)
            PokewalkerTileService.refresh(app)
            finish()
        }
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
