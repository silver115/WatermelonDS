package me.magnum.melonds.pokewalker

import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import me.magnum.melonds.R

/**
 * Shown by Health Connect when the user asks why the app wants step data.
 * Health Connect requires an app to provide this screen before it lets it
 * request permissions.
 */
class HealthPermissionsRationaleActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.pokewalker_health_privacy_title)
        val padding = (20 * resources.displayMetrics.density).toInt()
        val text = TextView(this).apply {
            setText(R.string.pokewalker_health_privacy_text)
            textSize = 16f
            setPadding(padding, padding, padding, padding)
        }
        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            addView(text)
        })
    }
}
