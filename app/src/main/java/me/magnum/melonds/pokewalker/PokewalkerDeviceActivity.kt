package me.magnum.melonds.pokewalker

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.magnum.melonds.R
import me.magnum.melonds.ui.romlist.RomListActivity
import me.magnum.melonds.ui.settings.SettingsActivity

/**
 * The virtual Pokéwalker as its own screen (it has its own launcher icon), in a modern or
 * a classic look: the walker's picture, three buttons, Poké Radar, dowsing, trainer card,
 * Pokémon & items, settings. It is the same walker the emulated HeartGold/SoulSilver talks
 * to, so everything done here is there in the game, and the other way round.
 */
class PokewalkerDeviceActivity : AppCompatActivity() {

    companion object {
        /** Opens one of the walker's apps right away: 0 Poké Radar, 1 Dowsing, 3 Trainer Card... */
        const val EXTRA_OPEN_APP = "me.magnum.melonds.pokewalker.OPEN_APP"
    }

    private lateinit var content: FrameLayout
    private var railChrome: Boolean? = null
    private var skin: WalkerSkin? = null
    private val frame = ByteArray(WalkerDeviceView.LCD_WIDTH * WalkerDeviceView.LCD_HEIGHT)
    private var frameShade = 4
    private var haveFrame = false
    private var lastStatus: WalkerStatus? = null
    private var beeper: WalkerBeeper? = null
    private var running = false
    private var pendingApp = -1
    private var statusJob: Job? = null

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            tick()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.pokewalker_device_label)
        // Opening this screen may be what started the app: make sure the walker is open.
        Pokewalker.applySettings(this)

        buildChrome()
        applySkin(Pokewalker.skin(this))
        pendingApp = intent?.getIntExtra(EXTRA_OPEN_APP, -1) ?: -1
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val app = intent.getIntExtra(EXTRA_OPEN_APP, -1)
        if (app >= 0) {
            if (running) {
                PokewalkerNative.deviceOpenApp(app)
                tick()
            } else {
                pendingApp = app
            }
        }
    }

    override fun onResume() {
        super.onResume()
        beeper = WalkerBeeper()
        PokewalkerNative.deviceReset()
        if (pendingApp >= 0) {
            PokewalkerNative.deviceOpenApp(pendingApp)
            pendingApp = -1
        }
        running = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
        (skin as? ModernWalkerView)?.hasGame = Pokewalker.openGameIntent(this) != null
        startStatusUpdates()
        if (Pokewalker.autoSyncEnabled(this)) syncSteps(showResult = false)
    }

    override fun onPause() {
        running = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        statusJob?.cancel()
        statusJob = null
        beeper?.release()
        beeper = null
        PokewalkerWidget.updateAll(this)
        PokewalkerTileService.refresh(this)
        super.onPause()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        buildChrome()
    }

    // ---- top bar / side rail -------------------------------------------------------------

    /**
     * Back, look and settings buttons: a bar along the top when the window is upright, a rail
     * down the left side when it is wider than tall (landscape, like the AYN Thor's main
     * screen), where height is what the walker needs most.
     */
    private fun buildChrome() {
        val config = resources.configuration
        val rail = config.screenWidthDp > config.screenHeightDp
        if (rail == railChrome) return
        railChrome = rail
        val skinView = skin?.view
        (skinView?.parent as? ViewGroup)?.removeView(skinView)

        val root = LinearLayout(this).apply {
            orientation = if (rail) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            setBackgroundColor(0xFF0E1322.toInt())
        }
        if (rail) {
            root.addView(buildRail(), LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.MATCH_PARENT))
        } else {
            root.addView(buildTopBar(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
        }
        content = FrameLayout(this)
        root.addView(
            content,
            if (rail) LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        if (skinView != null) {
            content.addView(skinView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun buildRail(): View {
        val rail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(8), 0, dp(8))
        }
        rail.addView(barButton(R.drawable.ic_pw_back, R.string.pokewalker_device_back) { backToMelonDS() })
        rail.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        rail.addView(barButton(R.drawable.ic_pw_skin, R.string.pokewalker_device_switch_look) { switchSkin() })
        rail.addView(View(this), LinearLayout.LayoutParams(1, dp(8)))
        rail.addView(barButton(R.drawable.ic_settings, R.string.pokewalker_device_settings) { openSettings() })
        return rail
    }

    private fun buildTopBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
        }
        bar.addView(barButton(R.drawable.ic_pw_back, R.string.pokewalker_device_back) { backToMelonDS() })
        val title = TextView(this).apply {
            text = getString(R.string.pokewalker_device_label)
            setTextColor(0xFFF2F5FA.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(8), 0, 0, 0)
        }
        bar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(barButton(R.drawable.ic_pw_skin, R.string.pokewalker_device_switch_look) { switchSkin() })
        bar.addView(barButton(R.drawable.ic_settings, R.string.pokewalker_device_settings) { openSettings() })
        return bar
    }

    private fun barButton(icon: Int, description: Int, onClick: () -> Unit): ImageButton = ImageButton(this).apply {
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(0xFFF2F5FA.toInt())
        contentDescription = getString(description)
        val ripple = TypedValue()
        if (theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)) setBackgroundResource(ripple.resourceId)
        else background = null
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        TooltipCompat.setTooltipText(this, contentDescription)
        setOnClickListener { onClick() }
    }

    /** Back to melonDS where it was (game, ROM list or settings). */
    private fun backToMelonDS() {
        try {
            startActivity(Intent(this, RomListActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            // nothing else to go back to
        }
        finish()
    }

    private fun openSettings() {
        val intent = Intent(this, SettingsActivity::class.java)
            .putExtra(SettingsActivity.KEY_ENTRY_POINT, SettingsActivity.POKEWALKER_ENTRY_POINT)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // settings are always there; ignore
        }
    }

    // ---- looks -----------------------------------------------------------------------------

    private fun applySkin(name: String) {
        content.removeAllViews()
        val next: WalkerSkin = if (name == Pokewalker.SKIN_CLASSIC) {
            WalkerDeviceView(this) { press(it) }
        } else {
            ModernWalkerView(this, { press(it) }, { action(it) }).also { it.hasGame = Pokewalker.openGameIntent(this) != null }
        }
        skin = next
        content.addView(next.view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (haveFrame) next.setFrame(frame, frameShade)
        next.setStatus(lastStatus, Pokewalker.stepGoal(this))
    }

    private fun switchSkin() {
        val next = if (Pokewalker.skin(this) == Pokewalker.SKIN_CLASSIC) Pokewalker.SKIN_MODERN else Pokewalker.SKIN_CLASSIC
        Pokewalker.setSkin(this, next)
        applySkin(next)
        val name = if (next == Pokewalker.SKIN_CLASSIC) R.string.pokewalker_device_look_classic else R.string.pokewalker_device_look_modern
        Toast.makeText(this, name, Toast.LENGTH_SHORT).show()
    }

    // ---- the walker ---------------------------------------------------------------------------

    private fun press(button: Int) {
        PokewalkerNative.devicePress(button)
        tick()
    }

    private fun tick() {
        val flags = PokewalkerNative.deviceFrame(frame)
        val shade = (flags shr 12) and 0xF
        val current = skin ?: return
        if (flags and 1 != 0 || shade != current.shade) {
            haveFrame = true
            frameShade = shade
            current.setFrame(frame, shade)
        }
        val sound = (flags shr 4) and 0xF
        if (sound != 0) beeper?.play(sound, (flags shr 8) and 0x3)
    }

    private fun action(which: Int) {
        when (which) {
            ModernWalkerView.ACTION_SYNC -> syncSteps(showResult = true)
            ModernWalkerView.ACTION_RADAR -> {
                PokewalkerNative.deviceOpenApp(0)
                tick()
            }
            ModernWalkerView.ACTION_DOWSING -> {
                PokewalkerNative.deviceOpenApp(1)
                tick()
            }
            ModernWalkerView.ACTION_PLAY -> openGame()
        }
    }

    private fun openGame() {
        val intent = Pokewalker.openGameIntent(this)
        if (intent == null) {
            Toast.makeText(this, R.string.pokewalker_action_play_none, Toast.LENGTH_LONG).show()
            return
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.pokewalker_action_play_none, Toast.LENGTH_LONG).show()
        }
    }

    private fun startStatusUpdates() {
        statusJob?.cancel()
        statusJob = lifecycleScope.launch {
            while (isActive) {
                val status = withContext(Dispatchers.Default) { runCatching { Pokewalker.status() }.getOrNull() }
                lastStatus = status
                skin?.setStatus(status, Pokewalker.stepGoal(this@PokewalkerDeviceActivity))
                delay(1000)
            }
        }
    }

    /**
     * Like carrying the walker: new Health Connect steps are added when the screen opens
     * (quietly) or when Sync is tapped (with the result).
     */
    private fun syncSteps(showResult: Boolean) {
        val app = applicationContext
        lifecycleScope.launch {
            val result = WalkerSync.fromHealthConnect(app)
            if ((showResult || result is WalkerSync.Result.Added) && !isFinishing) {
                Toast.makeText(this@PokewalkerDeviceActivity, WalkerSync.describe(app, result), Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---- keys ---------------------------------------------------------------------------------

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
            skin?.showPressed(button, true)
            press(button)
        }
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val button = buttonForKey(keyCode)
        if (button < 0) return super.onKeyUp(keyCode, event)
        skin?.showPressed(button, false)
        return true
    }
}
