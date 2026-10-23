package me.magnum.melonds.pokewalker

import android.os.Build
import android.os.Bundle
import android.view.Choreographer
import android.view.KeyEvent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.magnum.melonds.R
import java.text.NumberFormat

/**
 * The virtual Pokéwalker as its own screen (it has its own launcher icon): the walker's
 * picture, three buttons, Poké Radar, dowsing, trainer card, Pokémon & items, settings.
 * It is the same walker the emulated HeartGold/SoulSilver talks to, so everything done
 * here is there in the game, and the other way round.
 */
class PokewalkerDeviceActivity : AppCompatActivity() {

    private lateinit var screen: WalkerDeviceView
    private val frame = ByteArray(WalkerDeviceView.LCD_WIDTH * WalkerDeviceView.LCD_HEIGHT)
    private var beeper: WalkerBeeper? = null
    private var running = false

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            tick()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.pokewalker_device_label)
        // Opening this screen may be what started the app: make sure the walker is open.
        Pokewalker.applySettings(this)
        screen = WalkerDeviceView(this) { button -> press(button) }
        setContentView(screen)
    }

    override fun onResume() {
        super.onResume()
        beeper = WalkerBeeper()
        PokewalkerNative.deviceReset()
        running = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
        syncStepsQuietly()
    }

    override fun onPause() {
        running = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        beeper?.release()
        beeper = null
        super.onPause()
    }

    private fun press(button: Int) {
        PokewalkerNative.devicePress(button)
        tick()
    }

    private fun tick() {
        val flags = PokewalkerNative.deviceFrame(frame)
        val shade = (flags shr 12) and 0xF
        if (flags and 1 != 0 || shade != screen.shade) screen.setFrame(frame, shade)
        val sound = (flags shr 4) and 0xF
        if (sound != 0) beeper?.play(sound, (flags shr 8) and 0x3)
    }

    private fun buttonForKey(keyCode: Int): Int = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_L2 -> 0
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_R2 -> 2
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_SPACE,
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y -> 1
        else -> -1
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val button = buttonForKey(keyCode)
        if (button < 0) return super.onKeyDown(keyCode, event)
        if (event.repeatCount == 0) {
            screen.showPressed(button, true)
            press(button)
        }
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val button = buttonForKey(keyCode)
        if (button < 0) return super.onKeyUp(keyCode, event)
        screen.showPressed(button, false)
        return true
    }

    /**
     * Like carrying the walker: when its screen opens, new steps from Health Connect are
     * added, if that was already set up in Settings → Pokéwalker (no prompts here).
     */
    private fun syncStepsQuietly() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val app = applicationContext
        lifecycleScope.launch {
            val added = withContext(Dispatchers.IO) {
                runCatching {
                    val health = HealthConnectSteps(app)
                    if (health.availability() != HealthConnectSteps.Availability.AVAILABLE || !health.hasPermission()) {
                        return@runCatching 0L
                    }
                    val status = Pokewalker.status()
                    if (!status.hasPokemon || status.strollStartUnix <= 0 || status.sessionActive) return@runCatching 0L
                    val now = System.currentTimeMillis()
                    val from = maxOf(status.strollStartUnix * 1000, now - 30 * LocalDays.DAY_MS)
                    when (val result = StepLedger(app).credit(health.stepsByDay(from, now))) {
                        is CreditResult.Added -> result.steps
                        else -> 0L
                    }
                }.getOrDefault(0L)
            }
            if (added > 0 && !isFinishing) {
                val text = getString(R.string.pokewalker_device_synced, NumberFormat.getIntegerInstance().format(added))
                Toast.makeText(this@PokewalkerDeviceActivity, text, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
