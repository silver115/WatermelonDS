package me.magnum.melonds.pokewalker

import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.ContentValues
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaActionSound
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.magnum.melonds.R
import me.magnum.melonds.ui.romlist.RomListActivity
import me.magnum.melonds.ui.settings.SettingsActivity
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The virtual Pokéwalker as its own screen (it has its own launcher icon), in a modern or
 * a classic look: the walker's picture, three buttons, Poké Radar, dowsing, trainer card,
 * Pokémon & items, settings. It is the same walker the emulated HeartGold/SoulSilver talks
 * to, so everything done here is there in the game, and the other way round.
 *
 * Behind it is the place in the game the walk's course looks like, read from the player's own
 * ROM, with the walking Pokémon exploring it; "explore" puts the walker away to watch it, send
 * it places with a tap, play games, take photos, or walk it about with the D-pad. On an AYN
 * Thor the D-pad (and stick) walk the Pokémon about whenever the map is there, and the walker's
 * buttons move to L1, R1 and A; a few seconds after letting go it roams on its own again.
 */
class PokewalkerDeviceActivity : AppCompatActivity() {

    companion object {
        /** Opens one of the walker's apps right away: 0 Poké Radar, 1 Dowsing, 3 Trainer Card... */
        const val EXTRA_OPEN_APP = "me.magnum.melonds.pokewalker.OPEN_APP"
        private const val KEY_PAD_INTRO = "pokewalker_pad_intro_shown"
        private const val MENU_STOP = 100
        private const val MENU_TOSS = 101
        private const val MENU_MUSIC = 201
        private const val MENU_FOLLOW = 202
        private const val MENU_REVEAL = 203
        private const val MENU_ZOOM = 204
        private const val MENU_PHOTO = 205
        private const val MENU_WALLPAPER = 206
    }

    private lateinit var content: FrameLayout
    private var rootLayout: View? = null
    private var bannerHolder: View? = null
    private var banner: LinearLayout? = null
    private var bannerText: TextView? = null
    private var bannerClose: ImageButton? = null
    private var dismissedNotice: String? = null
    private lateinit var scene: RouteSceneDrawable
    private var railChrome: Boolean? = null
    private var sceneDrawnAt = 0L
    private var walkerKey: String? = null
    private var lastVolume = 2
    private var skin: WalkerSkin? = null
    private val frame = ByteArray(WalkerDeviceView.LCD_WIDTH * WalkerDeviceView.LCD_HEIGHT)
    private var frameShade = 4
    private var haveFrame = false
    private var lastStatus: WalkerStatus? = null
    private var beeper: WalkerBeeper? = null
    private var running = false
    private var pendingApp = -1
    private var statusJob: Job? = null
    private var walkParty = WalkParty(null)
    private var exploring = false
    private var exploreButton: ImageButton? = null
    private var exploreCaption: TextView? = null
    private var gamesButton: ImageButton? = null
    private var cameraButton: ImageButton? = null
    private var moreButton: ImageButton? = null
    private var steeringChip: TextView? = null
    private var flash: View? = null
    private var captionAt = 0L
    // a press held on the map tosses a berry
    private var pressX = 0f
    private var pressY = 0f
    private var pressView: View? = null
    private var tossed = false
    // two taps close together get a closer look; two fingers pinch
    private var lastTapAt = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var pinching = false
    private lateinit var scaleDetector: ScaleGestureDetector
    // the AYN Thor's D-pad and stick walk the Pokémon about
    private val padSteers = Handheld.isAynThor
    private var stickDirection = -1
    private var steeringShown = false
    private var roamingUntil = 0L
    private var askingWalker = false
    private var shutter: MediaActionSound? = null
    private val longPress = Runnable {
        val map = scene.map
        val v = pressView
        if (exploring && map != null && v != null && !pinching) {
            tossed = true
            val (x, y) = mapPoint(v, pressX, pressY) ?: return@Runnable
            if (map.toss(x, y, SystemClock.uptimeMillis())) v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            scene.invalidateSelf()
        }
    }
    private val leaveExplore = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = setExploring(false)
    }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            tick()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private val prefs get() = PreferenceManager.getDefaultSharedPreferences(this)
    private val musicOn get() = prefs.getBoolean(RouteMaps.KEY_MUSIC, true)
    private val followOn get() = prefs.getBoolean(RouteMaps.KEY_FOLLOW, true)
    private val revealOn get() = prefs.getBoolean(RouteMaps.KEY_REVEAL, true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.pokewalker_device_label)
        // Opening this screen may be what started the app: make sure the walker is open.
        Pokewalker.applySettings(this)

        scene = RouteSceneDrawable(resources.displayMetrics.density)
        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val map = scene.map ?: return false
                pinching = true
                map.zoomTarget = map.zoomTarget * detector.scaleFactor
                return true
            }
        })
        // the landscape goes right to the edges, behind the status and navigation bars
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        buildChrome()
        applySkin(Pokewalker.skin(this))
        onBackPressedDispatcher.addCallback(this, leaveExplore)
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
        updateMusic()
    }

    override fun onPause() {
        running = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        statusJob?.cancel()
        statusJob = null
        beeper?.release()
        beeper = null
        PokemonCries.release()
        RouteMusicOut.stop()
        shutter?.release()
        shutter = null
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

        // the route's landscape behind everything, the bars included
        val root = LinearLayout(this).apply {
            orientation = if (rail) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            background = scene
        }
        scene.railSide = rail
        if (rail) {
            root.addView(buildRail(), LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.MATCH_PARENT))
        } else {
            root.addView(buildTopBar(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
        }
        // a note about the game above the walker when there is one, the walker below it
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(buildBanner(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // the walker, and over it while exploring, what the place is
        val stage = FrameLayout(this)
        content = FrameLayout(this)
        stage.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val caption = TextView(this).apply {
            setTextColor(WalkerTheme.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setLineSpacing(0f, 1.15f)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor((WalkerTheme.CARD and 0x00FFFFFF) or 0xD9000000.toInt())
            }
            visibility = View.GONE
        }
        stage.addView(caption, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { setMargins(dp(16), 0, dp(16), dp(16)) })
        exploreCaption = caption
        // while exploring, down the top corner: the games, a photo, the map's options
        gamesButton = roundButton(R.drawable.ic_pw_games, R.string.pokewalker_games) { showGames(it) }
        stage.addView(gamesButton, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(12), dp(12), 0) })
        cameraButton = roundButton(R.drawable.ic_pw_camera, R.string.pokewalker_photo) { takePhoto() }
        stage.addView(cameraButton, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(68), dp(12), 0) })
        moreButton = roundButton(R.drawable.ic_pw_more, R.string.pokewalker_map_options) { showOptions(it) }
        stage.addView(moreButton, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(124), dp(12), 0) })
        // who's walking the Pokémon about: a little label at the top while the D-pad does
        val chip = TextView(this).apply {
            setTextColor(WalkerTheme.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(12), dp(6), dp(14), dp(6))
            compoundDrawablePadding = dp(6)
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_pw_pad, 0, 0, 0)
            compoundDrawableTintList = ColorStateList.valueOf(WalkerTheme.GOLD)
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor((WalkerTheme.CARD and 0x00FFFFFF) or 0xE0000000.toInt())
                setStroke(dp(1), (WalkerTheme.GOLD and 0x00FFFFFF) or 0x80000000.toInt())
            }
            visibility = View.GONE
        }
        stage.addView(chip, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { setMargins(0, dp(14), 0, 0) })
        steeringChip = chip
        // the camera's flash
        val white = View(this).apply {
            setBackgroundColor(0xFFFFFFFF.toInt())
            alpha = 0f
            visibility = View.GONE
        }
        stage.addView(white, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        flash = white
        stage.setOnTouchListener { v, e -> exploreTouch(v, e) }
        column.addView(stage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(
            column,
            if (rail) LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        if (skinView != null) {
            content.addView(skinView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        rootLayout = root
        setContentView(root)
        if (exploring) showExploring()
        updateGamesButton()
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            scene.bottomInset = bars.bottom.toFloat()
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(root)
    }

    /** A round button over the map (shown while exploring). */
    private fun roundButton(icon: Int, description: Int, onClick: (View) -> Unit): ImageButton = ImageButton(this).apply {
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(WalkerTheme.TEXT)
        contentDescription = getString(description)
        TooltipCompat.setTooltipText(this, contentDescription)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor((WalkerTheme.CARD and 0x00FFFFFF) or 0xD9000000.toInt())
        }
        visibility = View.GONE
        setOnClickListener { onClick(it) }
    }

    /**
     * A card for what the player should know about the game: a transfer it still has to save
     * (until it does, closing or rewinding the game makes it forget), or what the walker did
     * when the game had lost one.
     */
    private fun buildBanner(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(4), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor((WalkerTheme.CARD and 0x00FFFFFF) or 0xF2000000.toInt())
                setStroke(dp(1), (WalkerTheme.GOLD and 0x00FFFFFF) or 0x8C000000.toInt())
            }
            visibility = View.GONE
        }
        val icon = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_status_warn)
            imageTintList = ColorStateList.valueOf(WalkerTheme.GOLD)
        }
        card.addView(icon, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(12) })
        val text = TextView(this).apply {
            setTextColor(WalkerTheme.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setLineSpacing(0f, 1.1f)
        }
        card.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val close = barButton(R.drawable.ic_clear, R.string.close) {
            dismissedNotice = bannerText?.text?.toString()
            banner?.visibility = View.GONE
        }.apply { imageTintList = ColorStateList.valueOf(WalkerTheme.TEXT_DIM) }
        card.addView(close, LinearLayout.LayoutParams(dp(40), dp(40)))
        val holder = FrameLayout(this).apply { setPadding(dp(10), dp(8), dp(10), 0) }
        holder.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        bannerHolder = holder
        banner = card
        bannerText = text
        bannerClose = close
        updateBanner(lastStatus)
        return holder
    }

    private fun updateBanner(status: WalkerStatus?) {
        val card = banner ?: return
        val unsaved = Pokewalker.unsavedTransferText(this, status)
        val notice = status?.notice?.takeIf { it.isNotBlank() && it != dismissedNotice }
        val text = unsaved ?: notice
        if (text == null) {
            card.visibility = View.GONE
            return
        }
        if (bannerText?.text?.toString() != text) bannerText?.text = text
        // the unsaved transfer stays until the game has saved it; a note can be put away
        bannerClose?.visibility = if (unsaved != null) View.GONE else View.VISIBLE
        card.visibility = View.VISIBLE
    }

    private fun buildRail(): View {
        val rail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(8), 0, dp(8))
        }
        rail.addView(barButton(R.drawable.ic_pw_back, R.string.pokewalker_device_back) { backToMelonDS() })
        rail.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        rail.addView(buildExploreButton())
        rail.addView(View(this), LinearLayout.LayoutParams(1, dp(8)))
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
            setTextColor(WalkerTheme.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(8), 0, 0, 0)
        }
        bar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(buildExploreButton())
        bar.addView(barButton(R.drawable.ic_pw_skin, R.string.pokewalker_device_switch_look) { switchSkin() })
        bar.addView(barButton(R.drawable.ic_settings, R.string.pokewalker_device_settings) { openSettings() })
        return bar
    }

    /** Explore the place the walk is in (only there when the place could be read from the game). */
    private fun buildExploreButton(): ImageButton = barButton(R.drawable.ic_pw_explore, R.string.pokewalker_explore) {
        setExploring(!exploring)
    }.also {
        it.visibility = if (scene.map != null) View.VISIBLE else View.GONE
        exploreButton = it
        updateExploreButton()
    }

    private fun barButton(icon: Int, description: Int, onClick: () -> Unit): ImageButton = ImageButton(this).apply {
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(WalkerTheme.TEXT)
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
            WalkerDeviceView(this, { press(it) }, { x, y -> touch(x, y) })
        } else {
            ModernWalkerView(this, { press(it) }, { action(it) }, { x, y -> touch(x, y) })
                .also { it.hasGame = Pokewalker.openGameIntent(this) != null }
        }
        skin = next
        scene.showWalker = name == Pokewalker.SKIN_CLASSIC
        next.view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateCovered() }
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

    /** A tap on the walker's screen, at walker pixel (x, y). */
    private fun touch(x: Int, y: Int) {
        PokewalkerNative.deviceTouch(x, y)
        tick()
    }

    private fun tick() {
        // the landscape's clouds, waves and weather move along at about 15 frames a second,
        // the exploring Pokémon at 30
        val now = SystemClock.uptimeMillis()
        if (now - sceneDrawnAt >= (if (scene.map != null) 33 else 66)) {
            sceneDrawnAt = now
            scene.invalidateSelf()
        }
        scene.map?.let { map ->
            // what's caught cries out; what a game has to say goes in the caption
            for (species in map.takeCries()) PokemonCries.play(this, species, lastVolume) {}
            // a catch or a find from the walker's own Poké Radar or dowsing: onto the walker
            for (r in map.takeRewards()) keep(r)
            if (now - captionAt >= 250) {
                captionAt = now
                if (exploring) updateCaption()
                updateSteering(map, now)
            }
        }
        val flags = PokewalkerNative.deviceFrame(frame)
        val shade = (flags shr 12) and 0xF
        val current = skin ?: return
        if (flags and 1 != 0 || shade != current.shade) {
            haveFrame = true
            frameShade = shade
            current.setFrame(frame, shade)
        }
        val sound = (flags shr 4) and 0xF
        val volume = (flags shr 8) and 0x3
        lastVolume = volume
        val cry = (flags ushr 16) and 0x3FF
        if (cry != 0) {
            // the Pokémon's own cry from the game; the walker's beep if it can't be read
            PokemonCries.play(this, cry, volume) { if (sound != 0) beeper?.play(sound, volume) }
        } else if (sound != 0) {
            beeper?.play(sound, volume)
        }
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
                val goal = Pokewalker.stepGoal(this@PokewalkerDeviceActivity)
                skin?.setStatus(status, goal)
                updateBanner(status)
                updateScene(status)
                celebrateGoal(status, goal)
                delay(1000)
            }
        }
    }

    /**
     * Where the walk is: the place in the game when the ROM can be read (with the walking
     * Pokémon's own overworld sprite exploring it), else the route's painted landscape, and the
     * walking Pokémon's small sprite for it.
     */
    private suspend fun updateScene(status: WalkerStatus?) {
        val walking = status != null && status.registered && status.hasPokemon
        if (walking) scene.scene.setRoute(status!!.routeImage, status.route) else scene.scene.setRoute(0, "")
        val key = if (walking) "${status!!.species}:${status.strollStartUnix}:${status.route}:${status.caught}" else ""
        if (key != walkerKey) {
            walkerKey = key
            val image = if (!walking) null else withContext(Dispatchers.Default) { PokewalkerNative.exportImage() }
            scene.walker = image?.let { RouteSceneDrawable.walkerSprite(it, LcdPalette.MODERN) }
            walkParty = WalkParty.fromImage(image)
        }
        val b = scene.bounds
        scene.map = if (!walking) null else RouteMaps.get(this, status!!.route, status.routeImage, walkParty, b.width(), b.height())
        scene.map?.let { map ->
            if (status != null) {
                map.setSteps(status.totalSteps, status.todaySteps, Pokewalker.stepGoal(this), SystemClock.uptimeMillis())
                map.reveal = if (revealOn) RouteMaps.revealed(this, status.strollStartUnix, status.totalSteps, status.todaySteps) else 1f
            }
            map.following = exploring && followOn
        }
        exploreButton?.visibility = if (scene.map != null) View.VISIBLE else View.GONE
        updateGamesButton()
        if (exploring && scene.map == null) setExploring(false)
        if (exploring) updateCaption()
        updateMusic()
    }

    // ---- exploring the place ----------------------------------------------------------------------

    private fun setExploring(on: Boolean) {
        if (on && scene.map == null) return
        if (on == exploring) return
        exploring = on
        if (on) showExploring() else {
            content.visibility = View.VISIBLE
            bannerHolder?.visibility = View.VISIBLE
            exploreCaption?.visibility = View.GONE
            scene.explore = false
            scene.map?.let { map ->
                map.release()
                map.stopGame(SystemClock.uptimeMillis())
                map.zoomTarget = 1f
            }
            updateBanner(lastStatus)
        }
        scene.map?.following = on && followOn
        updateGamesButton()
        leaveExplore.isEnabled = on
        updateExploreButton()
        updateCovered()
        updateMusic()
    }

    /** The walker put away: just the place, the Pokémon and what the place is. */
    private fun showExploring() {
        content.visibility = View.INVISIBLE
        bannerHolder?.visibility = View.GONE
        scene.explore = true
        updateCaption()
        exploreCaption?.visibility = View.VISIBLE
    }

    private fun updateExploreButton() {
        val b = exploreButton ?: return
        val description = getString(if (exploring) R.string.pokewalker_explore_close else R.string.pokewalker_explore)
        b.contentDescription = description
        TooltipCompat.setTooltipText(b, description)
        b.imageTintList = ColorStateList.valueOf(if (exploring) WalkerTheme.GOLD else WalkerTheme.TEXT)
    }

    /** The route's own music while exploring (when it's on and the game has it). */
    private fun updateMusic() {
        val map = scene.map
        if (running && exploring && map != null && musicOn) RouteMusicOut.play(this, map.scene.course) else RouteMusicOut.stop()
    }

    private fun pokemonName(): String {
        val status = lastStatus ?: return ""
        return status.pokemonName.ifBlank { status.speciesName }
    }

    private fun updateCaption() {
        val map = scene.map ?: return
        val status = lastStatus ?: return
        val name = pokemonName()
        val now = SystemClock.uptimeMillis()
        val line = map.gameLine(now)?.let { gameText(it, name) }
        // the time and the weather, as on a watch
        val clock = "${weatherGlyph(map)} ${DateFormat.getTimeFormat(this).format(Date())}"
        val text = clock + "  ·  " + getString(R.string.pokewalker_explore_caption, map.scene.course.name, map.scene.course.place, map.game) + "\n" +
            (line ?: getString(when {
                !map.hasPokemon -> R.string.pokewalker_explore_hint_no_sprite
                map.asleep -> R.string.pokewalker_explore_hint_asleep
                padSteers -> R.string.pokewalker_explore_hint_pad
                map.hasPeople -> R.string.pokewalker_explore_hint_people
                else -> R.string.pokewalker_explore_hint
            }, name))
        if (exploreCaption?.text?.toString() != text) exploreCaption?.text = text
    }

    private fun weatherGlyph(map: RouteMap): String {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return when (map.weather) {
            MapAmbience.Weather.RAIN -> "☂"
            MapAmbience.Weather.SNOW -> "❄"
            MapAmbience.Weather.MIST, MapAmbience.Weather.SAND -> "☁"
            else -> if (!map.scene.outdoor) "⛰" else if (hour < 6 || hour >= 19) "☾" else "☀"
        }
    }

    /** The label at the top while the D-pad walks the Pokémon about, and a moment after. */
    private fun updateSteering(map: RouteMap, now: Long) {
        val chip = steeringChip ?: return
        val name = pokemonName()
        if (map.steering(now)) {
            if (!steeringShown || chip.visibility != View.VISIBLE) {
                chip.text = getString(R.string.pokewalker_steering, name)
                chip.visibility = View.VISIBLE
            }
            steeringShown = true
        } else if (steeringShown) {
            steeringShown = false
            chip.text = getString(R.string.pokewalker_roaming, name)
            roamingUntil = now + 1_800
        } else if (now > roamingUntil && chip.visibility == View.VISIBLE) {
            chip.visibility = View.GONE
        }
    }

    private fun updateGamesButton() {
        val map = scene.map
        val show = exploring && map != null
        gamesButton?.visibility = if (show && (map!!.availableGames().isNotEmpty() || map.hasPokemon)) View.VISIBLE else View.GONE
        cameraButton?.visibility = if (show) View.VISIBLE else View.GONE
        moreButton?.visibility = if (show) View.VISIBLE else View.GONE
    }

    /** The games menu: the ones there are here, and a way to stop the one that's on. */
    private fun showGames(anchor: View) {
        val map = scene.map ?: return
        val menu = PopupMenu(this, anchor)
        val games = map.availableGames()
        for ((i, g) in games.withIndex()) menu.menu.add(0, i, i, getString(gameName(g)))
        if (map.hasPokemon && map.gameOn == null) menu.menu.add(0, MENU_TOSS, 99, getString(R.string.pokewalker_toss_berry))
        if (map.gameOn != null) menu.menu.add(0, MENU_STOP, 100, getString(R.string.pokewalker_game_stop))
        menu.setOnMenuItemClickListener { item ->
            val now = SystemClock.uptimeMillis()
            when (item.itemId) {
                MENU_STOP -> map.stopGame(now)
                MENU_TOSS -> map.tossNear(now)
                else -> when (val g = games.getOrNull(item.itemId)) {
                    MapGames.Game.RUSTLING -> askRadar()
                    MapGames.Game.DOWSING -> askDowsing()
                    null -> {}
                    else -> map.startGame(g, now)
                }
            }
            updateCaption()
            true
        }
        menu.show()
    }

    /**
     * Rustling grass is the walker's own Poké Radar: the walker picks the Pokémon (by today's
     * steps, as it does) and takes its 10 watts, then the grass rustles on the map.
     */
    private fun askRadar() {
        if (askingWalker) return
        askingWalker = true
        exploreCaption?.text = getString(R.string.pokewalker_rustle_asking)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { JSONObject(PokewalkerNative.radarEncounter()) }.getOrNull() }
            askingWalker = false
            val map = scene.map ?: return@launch
            val now = SystemClock.uptimeMillis()
            val error = result?.optString("error").orEmpty().ifEmpty { if (result == null) "?" else "" }
            if (error.isNotEmpty()) {
                map.announce(MapGames.Line(MapGames.What.WALKER_ERROR, word = error), now)
            } else {
                map.startRustling(MapPlay.Encounter(result!!.optInt("slot"), result.optInt("species"), result.optInt("level")), now)
            }
            updateCaption()
        }
    }

    /** Dowsing is the walker's own: the walker picks the item and takes its 3 watts, then it's buried on the map. */
    private fun askDowsing() {
        if (askingWalker) return
        askingWalker = true
        exploreCaption?.text = getString(R.string.pokewalker_dowse_asking)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { JSONObject(PokewalkerNative.dowsingStart()) }.getOrNull() }
            askingWalker = false
            val map = scene.map ?: return@launch
            val now = SystemClock.uptimeMillis()
            val error = result?.optString("error").orEmpty().ifEmpty { if (result == null) "?" else "" }
            if (error.isNotEmpty()) map.announce(MapGames.Line(MapGames.What.WALKER_ERROR, word = error), now)
            else map.startDowsing(result!!.optInt("item"), now)
            updateCaption()
        }
    }

    /** A catch or a find from the map's Poké Radar or dowsing goes onto the walker, as on its own screen. */
    private fun keep(r: MapGames.Reward) {
        lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) {
                runCatching {
                    when (r) {
                        is MapGames.Reward.Caught -> PokewalkerNative.storeCaught(r.slot)
                        is MapGames.Reward.Dowsed -> PokewalkerNative.storeDowsed(r.item)
                    }
                }.getOrElse { it.message ?: "?" }
            }
            val map = scene.map ?: return@launch
            val now = SystemClock.uptimeMillis()
            val line = when {
                error != null -> MapGames.Line(MapGames.What.WALKER_ERROR, word = error)
                r is MapGames.Reward.Caught -> MapGames.Line(MapGames.What.WALKER_CAUGHT, r.species)
                r is MapGames.Reward.Dowsed -> MapGames.Line(MapGames.What.WALKER_FOUND, 0, r.item)
                else -> null
            }
            if (line != null) map.announce(line, now + 2_500)
        }
    }

    private fun gameName(g: MapGames.Game): Int = when (g) {
        MapGames.Game.FISHING -> R.string.pokewalker_game_fishing
        MapGames.Game.TAG -> R.string.pokewalker_game_tag
        MapGames.Game.CONTEST -> R.string.pokewalker_game_contest
        MapGames.Game.UNOWN -> R.string.pokewalker_game_unown
        MapGames.Game.BERRY -> R.string.pokewalker_game_berry
        MapGames.Game.RUSTLING -> R.string.pokewalker_game_rustling
        MapGames.Game.DOWSING -> R.string.pokewalker_game_dowsing
        MapGames.Game.HIDE_SEEK -> R.string.pokewalker_game_hide
        MapGames.Game.HEADBUTT -> R.string.pokewalker_game_headbutt
        MapGames.Game.ROCK_SMASH -> R.string.pokewalker_game_smash
        MapGames.Game.CATCH -> R.string.pokewalker_game_catch
    }

    /** What a game has to say, in words. */
    private fun gameText(l: MapGames.Line, name: String): String {
        val species = if (l.species > 0) PokewalkerNative.speciesName(l.species).ifBlank { "#${l.species}" } else ""
        val berries = resources.getStringArray(R.array.pokewalker_berries)
        val berry = berries.getOrElse(l.n) { berries[0] }
        return when (l.what) {
            MapGames.What.FISH_WALK -> getString(R.string.pokewalker_fish_walk, name)
            MapGames.What.FISH_WAIT -> getString(R.string.pokewalker_fish_wait)
            MapGames.What.FISH_BITE -> getString(R.string.pokewalker_fish_bite)
            MapGames.What.FISH_CAUGHT -> getString(R.string.pokewalker_fish_caught, species, l.n)
            MapGames.What.FISH_AWAY -> getString(R.string.pokewalker_fish_away)
            MapGames.What.FISH_NOWHERE -> getString(R.string.pokewalker_fish_nowhere)
            MapGames.What.TAG_CHASE -> getString(R.string.pokewalker_tag_chase, species, l.n)
            MapGames.What.TAG_CAUGHT -> getString(R.string.pokewalker_tag_caught, species, l.n)
            MapGames.What.TAG_AWAY -> getString(R.string.pokewalker_tag_away, species)
            MapGames.What.TAG_NOBODY -> getString(R.string.pokewalker_tag_nobody)
            MapGames.What.CONTEST_ON -> getString(R.string.pokewalker_contest_on, l.n, l.m)
            MapGames.What.CONTEST_CAUGHT -> getString(R.string.pokewalker_contest_caught, species, l.n)
            MapGames.What.CONTEST_END -> getString(R.string.pokewalker_contest_end, species, l.n,
                resources.getStringArray(R.array.pokewalker_contest_places).getOrElse(l.m - 1) { "" })
            MapGames.What.CONTEST_NONE -> getString(R.string.pokewalker_contest_none)
            MapGames.What.UNOWN_ON -> getString(R.string.pokewalker_unown_on, l.word)
            MapGames.What.UNOWN_FOUND -> getString(R.string.pokewalker_unown_found, l.word)
            MapGames.What.UNOWN_DONE -> getString(R.string.pokewalker_unown_done, l.word)
            MapGames.What.BERRY_PLANTED -> getString(R.string.pokewalker_berry_planted, berry)
            MapGames.What.BERRY_GROWING -> getString(R.string.pokewalker_berry_growing, berry, l.m)
            MapGames.What.BERRY_PICKED -> getString(R.string.pokewalker_berry_picked, berry, l.m)
            MapGames.What.BERRY_NOWHERE -> getString(R.string.pokewalker_berry_nowhere, name)
            MapGames.What.ITEM_FOUND -> getString(R.string.pokewalker_item_found, name, itemText(l.n))
            MapGames.What.TREAT_EATEN -> getString(R.string.pokewalker_treat_eaten, name, berry)
            MapGames.What.TREAT_NOWHERE -> getString(R.string.pokewalker_treat_nowhere, name)
            MapGames.What.RUSTLE_ON -> getString(R.string.pokewalker_rustle_on)
            MapGames.What.RUSTLE_PICK -> getString(R.string.pokewalker_rustle_pick)
            MapGames.What.RUSTLE_WAIT -> getString(R.string.pokewalker_rustle_wait)
            MapGames.What.RUSTLE_WRONG -> getString(R.string.pokewalker_rustle_wrong, species)
            MapGames.What.RUSTLE_MISSED -> getString(R.string.pokewalker_rustle_missed, species)
            MapGames.What.RUSTLE_APPEARED -> getString(R.string.pokewalker_rustle_appeared, species, l.n)
            MapGames.What.RUSTLE_THROW -> getString(R.string.pokewalker_rustle_throw)
            MapGames.What.RUSTLE_CAUGHT -> getString(R.string.pokewalker_rustle_caught, species)
            MapGames.What.RUSTLE_BROKE_FREE -> getString(R.string.pokewalker_rustle_broke_free, species)
            MapGames.What.RUSTLE_FLED -> getString(R.string.pokewalker_rustle_fled, species)
            MapGames.What.RUSTLE_NOWHERE -> getString(R.string.pokewalker_rustle_nowhere, species)
            MapGames.What.DOWSE_ON -> getString(R.string.pokewalker_dowse_on, name, l.n)
            MapGames.What.DOWSE_WARMER -> getString(R.string.pokewalker_dowse_warmer, l.n)
            MapGames.What.DOWSE_COLDER -> getString(R.string.pokewalker_dowse_colder, l.n)
            MapGames.What.DOWSE_SAME -> getString(R.string.pokewalker_dowse_same, l.n)
            MapGames.What.DOWSE_HOT -> getString(R.string.pokewalker_dowse_hot, l.n)
            MapGames.What.DOWSE_DIG -> getString(R.string.pokewalker_dowse_dig, name)
            MapGames.What.DOWSE_FOUND -> getString(R.string.pokewalker_dowse_found, name, itemText(l.n))
            MapGames.What.DOWSE_NONE -> getString(R.string.pokewalker_dowse_none, itemName(l.n))
            MapGames.What.DOWSE_NOWHERE -> getString(R.string.pokewalker_dowse_nowhere)
            MapGames.What.HIDE_ON -> getString(R.string.pokewalker_hide_on, species, l.n)
            MapGames.What.HIDE_FOUND -> getString(R.string.pokewalker_hide_found, species, l.n)
            MapGames.What.HIDE_OVER -> getString(R.string.pokewalker_hide_over, species)
            MapGames.What.HIDE_NOWHERE -> getString(R.string.pokewalker_hide_nowhere)
            MapGames.What.HEADBUTT_POKEMON -> getString(R.string.pokewalker_headbutt_pokemon, species)
            MapGames.What.HEADBUTT_BERRY -> getString(R.string.pokewalker_headbutt_berry, withArticle("$berry Berry"))
            MapGames.What.HEADBUTT_NOTHING -> getString(R.string.pokewalker_headbutt_nothing)
            MapGames.What.HEADBUTT_NOWHERE -> getString(R.string.pokewalker_headbutt_nowhere, name)
            MapGames.What.SMASH_ITEM -> getString(R.string.pokewalker_smash_item, itemText(l.n))
            MapGames.What.SMASH_POKEMON -> getString(R.string.pokewalker_smash_pokemon, species)
            MapGames.What.SMASH_NOTHING -> getString(R.string.pokewalker_smash_nothing)
            MapGames.What.SMASH_NOWHERE -> getString(R.string.pokewalker_smash_nowhere, name)
            MapGames.What.CATCH_ON -> getString(R.string.pokewalker_catch_on, l.n, l.m)
            MapGames.What.CATCH_END -> if (l.word == "record") getString(R.string.pokewalker_catch_record, l.n) else getString(R.string.pokewalker_catch_end, l.n, l.m)
            MapGames.What.WALKER_CAUGHT -> getString(R.string.pokewalker_walker_caught, species)
            MapGames.What.WALKER_FOUND -> getString(R.string.pokewalker_walker_found, itemName(l.n))
            MapGames.What.WALKER_ERROR -> getString(R.string.pokewalker_walker_error, l.word)
            MapGames.What.VISITOR_HI -> getString(R.string.pokewalker_visitor_hi, species)
            MapGames.What.VISITOR_BYE -> getString(R.string.pokewalker_visitor_bye, species)
        }
    }

    /** "a Potion", "an Ether" ("an item" when it isn't known). */
    private fun itemText(item: Int): String {
        val name = PokewalkerNative.itemName(item).takeIf { it.isNotBlank() && it != "unknown" } ?: return getString(R.string.pokewalker_item_unknown)
        return withArticle(name)
    }

    /** "Potion" ("An item" when it isn't known). */
    private fun itemName(item: Int): String =
        PokewalkerNative.itemName(item).takeIf { it.isNotBlank() && it != "unknown" } ?: getString(R.string.pokewalker_item_unknown).replaceFirstChar { it.uppercase() }

    private fun withArticle(name: String) = (if (name.first().uppercaseChar() in "AEIOU") "an " else "a ") + name

    // ---- options, photos, the wallpaper ----------------------------------------------------------

    /** The map's options: music, following the Pokémon, uncovering the route, a closer look, a photo, the wallpaper. */
    private fun showOptions(anchor: View) {
        val map = scene.map ?: return
        val name = pokemonName()
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, MENU_MUSIC, 0, R.string.pokewalker_option_music).apply { isCheckable = true; isChecked = musicOn }
        menu.menu.add(0, MENU_FOLLOW, 1, getString(R.string.pokewalker_option_follow, name)).apply { isCheckable = true; isChecked = followOn }
        menu.menu.add(0, MENU_REVEAL, 2, R.string.pokewalker_option_reveal).apply { isCheckable = true; isChecked = revealOn }
        menu.menu.add(0, MENU_ZOOM, 3, if (map.zoomTarget > 1.2f) R.string.pokewalker_option_zoom_out else R.string.pokewalker_option_zoom_in)
        menu.menu.add(0, MENU_PHOTO, 4, R.string.pokewalker_photo)
        menu.menu.add(0, MENU_WALLPAPER, 5, R.string.pokewalker_option_wallpaper)
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_MUSIC -> {
                    prefs.edit().putBoolean(RouteMaps.KEY_MUSIC, !musicOn).apply()
                    updateMusic()
                }
                MENU_FOLLOW -> {
                    prefs.edit().putBoolean(RouteMaps.KEY_FOLLOW, !followOn).apply()
                    scene.map?.following = exploring && followOn
                }
                MENU_REVEAL -> {
                    prefs.edit().putBoolean(RouteMaps.KEY_REVEAL, !revealOn).apply()
                    val status = lastStatus
                    scene.map?.reveal = if (revealOn && status != null) {
                        RouteMaps.revealed(this, status.strollStartUnix, status.totalSteps, status.todaySteps)
                    } else 1f
                }
                MENU_ZOOM -> scene.map?.let { it.zoomTarget = if (it.zoomTarget > 1.2f) 1f else 2f }
                MENU_PHOTO -> takePhoto()
                MENU_WALLPAPER -> useAsWallpaper()
            }
            true
        }
        menu.show()
    }

    /** A photo of the route map as it is, with a little stamp, saved to the pictures. */
    private fun takePhoto() {
        val map = scene.map ?: return
        val root = rootLayout ?: return
        val w = scene.bounds.width().takeIf { it > 0 } ?: root.width
        val h = scene.bounds.height().takeIf { it > 0 } ?: root.height
        if (w <= 0 || h <= 0) return
        val photo = runCatching { map.capture(w, h, SystemClock.uptimeMillis()) }.getOrNull() ?: return
        stamp(photo, map)
        // click, and a flash
        val sound = shutter ?: MediaActionSound().also { shutter = it }
        runCatching { sound.play(MediaActionSound.SHUTTER_CLICK) }
        flash?.let { f ->
            f.animate().cancel()
            f.visibility = View.VISIBLE
            f.alpha = 0.85f
            f.animate().alpha(0f).setDuration(420).withEndAction { f.visibility = View.GONE }.start()
        }
        val app = applicationContext
        lifecycleScope.launch {
            val where = withContext(Dispatchers.IO) { runCatching { savePhoto(photo) }.getOrNull() }
            photo.recycle()
            val text = when {
                where == null -> getString(R.string.pokewalker_photo_failed)
                Build.VERSION.SDK_INT >= 29 -> getString(R.string.pokewalker_photo_saved)
                else -> getString(R.string.pokewalker_photo_saved_app, where)
            }
            Toast.makeText(app, text, Toast.LENGTH_SHORT).show()
        }
    }

    /** "Pikachu at Route 29 · 29 Oct 2026", small in the corner like a postcard's. */
    private fun stamp(photo: Bitmap, map: RouteMap) {
        val canvas = Canvas(photo)
        val text = getString(R.string.pokewalker_photo_stamp, pokemonName().ifBlank { getString(R.string.pokewalker_device_label) }, map.scene.course.place) +
            "  ·  " + DateFormat.getMediumDateFormat(this).format(Date())
        val size = (photo.width.coerceAtMost(photo.height) / 26f).coerceIn(dp(12).toFloat(), dp(22).toFloat())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = WalkerTheme.TEXT
            textSize = size
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val pad = size * 0.6f
        val tw = paint.measureText(text)
        val box = RectF(pad, photo.height - pad - size * 2f, pad + tw + size * 1.4f, photo.height - pad)
        val back = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = (WalkerTheme.CARD and 0x00FFFFFF) or 0xC8000000.toInt() }
        canvas.drawRoundRect(box, size, size, back)
        canvas.drawText(text, box.left + size * 0.7f, box.bottom - size * 0.62f, paint)
    }

    /** Saves [photo] (Pictures/Pokéwalker, or the app's own pictures before Android 10); where it went. */
    private fun savePhoto(photo: Bitmap): String? {
        val name = "Pokewalker_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".png"
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Pokéwalker")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            val ok = contentResolver.openOutputStream(uri)?.use { photo.compress(Bitmap.CompressFormat.PNG, 100, it) } ?: false
            if (!ok) {
                contentResolver.delete(uri, null, null)
                return null
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            return "Pictures/Pokéwalker"
        }
        val dir = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: filesDir, "Pokewalker").also { it.mkdirs() }
        val file = File(dir, name)
        FileOutputStream(file).use { if (!photo.compress(Bitmap.CompressFormat.PNG, 100, it)) return null }
        MediaScannerConnection.scanFile(this, arrayOf(file.path), arrayOf("image/png"), null)
        return file.path
    }

    /** Opens the system's live wallpaper preview with the route map. */
    private fun useAsWallpaper() {
        val component = ComponentName(this, RouteWallpaperService::class.java)
        try {
            startActivity(Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component))
        } catch (e: ActivityNotFoundException) {
            try {
                startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
            } catch (e2: ActivityNotFoundException) {
                Toast.makeText(this, R.string.pokewalker_wallpaper_none, Toast.LENGTH_LONG).show()
            }
        }
    }

    // ---- touching the map ---------------------------------------------------------------------

    /**
     * Taps while exploring: on the Pokémon it hops and cries, elsewhere it walks over. A press
     * held still tosses a berry there for it; two quick taps get a closer look (and back), two
     * fingers pinch in and out.
     */
    private fun exploreTouch(v: View, e: MotionEvent): Boolean {
        if (!exploring) return false
        scaleDetector.onTouchEvent(e)
        if (e.pointerCount > 1 || scaleDetector.isInProgress) {
            pinching = true
            v.removeCallbacks(longPress)
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tossed = false
                pinching = false
                pressX = e.x
                pressY = e.y
                pressView = v
                v.removeCallbacks(longPress)
                v.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                val slop = ViewConfiguration.get(this).scaledTouchSlop
                if (abs(e.x - pressX) > slop || abs(e.y - pressY) > slop) v.removeCallbacks(longPress)
            }
            MotionEvent.ACTION_CANCEL -> v.removeCallbacks(longPress)
            MotionEvent.ACTION_UP -> {
                v.removeCallbacks(longPress)
                if (tossed || pinching) {
                    pinching = false
                    return true
                }
                val map = scene.map ?: return true
                val now = SystemClock.uptimeMillis()
                // two taps close together: a closer look, or back out
                if (now - lastTapAt < ViewConfiguration.getDoubleTapTimeout() && hypot(e.x - lastTapX, e.y - lastTapY) < dp(48)) {
                    lastTapAt = 0L
                    map.zoomTarget = if (map.zoomTarget > 1.2f) 1f else 2f
                    return true
                }
                lastTapAt = now
                lastTapX = e.x
                lastTapY = e.y
                val (x, y) = mapPoint(v, e.x, e.y) ?: return true
                if (map.tap(x, y, now)) cry(map)
                scene.invalidateSelf()
                updateCaption()
            }
        }
        return true
    }

    /** A point in [v] as a point of the whole screen's box (where the map is drawn). */
    private fun mapPoint(v: View, x: Float, y: Float): Pair<Float, Float>? {
        val root = rootLayout ?: return null
        val at = IntArray(2)
        val rootAt = IntArray(2)
        v.getLocationInWindow(at)
        root.getLocationInWindow(rootAt)
        return (x + at[0] - rootAt[0]) to (y + at[1] - rootAt[1])
    }

    private fun cry(map: RouteMap) {
        if (map.species > 0) PokemonCries.play(this, map.species, lastVolume) {}
    }

    /** The cards on top cover the place: the Pokémon keeps to where it can be seen. */
    private fun updateCovered() {
        val s = skin
        val root = rootLayout
        if (s == null || root == null || exploring) {
            scene.setCovered(emptyList())
            return
        }
        val list = ArrayList<RectF>()
        s.coveredAreas(list)
        val at = IntArray(2)
        val rootAt = IntArray(2)
        s.view.getLocationInWindow(at)
        root.getLocationInWindow(rootAt)
        for (r in list) r.offset((at[0] - rootAt[0]).toFloat(), (at[1] - rootAt[1]).toFloat())
        scene.setCovered(list)
    }

    // ---- buttons and the D-pad --------------------------------------------------------------

    private fun direction(keyCode: Int): Int = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> FollowerSprites.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> FollowerSprites.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> FollowerSprites.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> FollowerSprites.RIGHT
        else -> -1
    }

    /**
     * The AYN Thor's D-pad walks the Pokémon about the map, exploring or not (true: taken); it
     * goes back to roaming on its own a few seconds after the last press.
     */
    private fun steerKey(keyCode: Int, event: KeyEvent, down: Boolean): Boolean {
        val map = scene.map ?: return false
        if (!map.hasPokemon) return false
        val d = direction(keyCode)
        if (d < 0) return false
        val now = SystemClock.uptimeMillis()
        if (down && event.repeatCount == 0) {
            map.walk(d, true, now)
            if (!exploring) padIntro()
        }
        if (!down) map.release()
        return true
    }

    /** The first time on a Thor: what the D-pad and the shoulder buttons do now. */
    private fun padIntro() {
        if (prefs.getBoolean(KEY_PAD_INTRO, false)) return
        prefs.edit().putBoolean(KEY_PAD_INTRO, true).apply()
        Toast.makeText(this, getString(R.string.pokewalker_pad_intro, pokemonName()), Toast.LENGTH_LONG).show()
    }

    /** D-pad walks the Pokémon about while exploring; A does what it's facing; B puts the map away. */
    private fun exploreKey(keyCode: Int, event: KeyEvent, down: Boolean): Boolean {
        val map = scene.map ?: return false
        val d = direction(keyCode)
        val now = SystemClock.uptimeMillis()
        if (d >= 0) {
            if (down && event.repeatCount == 0) map.walk(d, true, now)
            if (!down) map.release()
            return true
        }
        return when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SPACE -> {
                if (down && event.repeatCount == 0) {
                    if (map.action(now)) cry(map)
                    updateCaption()
                }
                true
            }
            KeyEvent.KEYCODE_BUTTON_B -> {
                if (!down) setExploring(false)
                true
            }
            KeyEvent.KEYCODE_BUTTON_Y -> {
                if (!down) scene.map?.let { it.zoomTarget = if (it.zoomTarget > 1.2f) 1f else 2f }
                true
            }
            else -> false
        }
    }

    /** The stick and the D-pad's hat walk the Pokémon about too (on a Thor, or while exploring). */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val map = scene.map
        if (map != null && map.hasPokemon && (padSteers || exploring) && event.actionMasked == MotionEvent.ACTION_MOVE &&
            (event.isFromSource(InputDevice.SOURCE_JOYSTICK) || event.isFromSource(InputDevice.SOURCE_GAMEPAD))) {
            var x = event.getAxisValue(MotionEvent.AXIS_HAT_X)
            var y = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
            if (x == 0f && y == 0f) {
                x = event.getAxisValue(MotionEvent.AXIS_X)
                y = event.getAxisValue(MotionEvent.AXIS_Y)
            }
            val d = PadInput.direction(x, y, 0.5f)
            if (d != stickDirection) {
                stickDirection = d
                val now = SystemClock.uptimeMillis()
                if (d >= 0) {
                    map.walk(d, true, now)
                    if (!exploring) padIntro()
                } else map.release()
            }
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    /** Confetti (and a happy cry) the first time today's steps reach the goal. */
    private fun celebrateGoal(status: WalkerStatus?, goal: Int) {
        if (status == null || !status.registered || goal <= 0 || status.todaySteps < goal) return
        val today = LocalDays.today()
        if (Pokewalker.celebratedDay(this) == today) return
        Pokewalker.setCelebratedDay(this, today)
        skin?.celebrate()
        scene.map?.celebrate(SystemClock.uptimeMillis())
        if (status.hasPokemon && status.species > 0) PokemonCries.play(this, status.species, lastVolume) {}
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

    /**
     * The walker's buttons: left, middle, right. On a Thor the D-pad walks the Pokémon about
     * instead, so the walker's left and right are on L1 and R1 (and L2, R2), its middle on A.
     */
    private fun buttonForKey(keyCode: Int): Int = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_L2 -> 0
        KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_R2 -> 2
        KeyEvent.KEYCODE_DPAD_LEFT -> if (padSteers && scene.map?.hasPokemon == true) -1 else 0
        KeyEvent.KEYCODE_DPAD_RIGHT -> if (padSteers && scene.map?.hasPokemon == true) -1 else 2
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_SPACE,
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y -> 1
        else -> -1
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // while exploring the walker is put away: its buttons do nothing
        if (exploring) return exploreKey(keyCode, event, true) || buttonForKey(keyCode) >= 0 || super.onKeyDown(keyCode, event)
        if (padSteers && steerKey(keyCode, event, true)) return true
        val button = buttonForKey(keyCode)
        if (button < 0) return super.onKeyDown(keyCode, event)
        if (event.repeatCount == 0) {
            skin?.showPressed(button, true)
            press(button)
        }
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (exploring) return exploreKey(keyCode, event, false) || buttonForKey(keyCode) >= 0 || super.onKeyUp(keyCode, event)
        if (padSteers && steerKey(keyCode, event, false)) return true
        val button = buttonForKey(keyCode)
        if (button < 0) return super.onKeyUp(keyCode, event)
        skin?.showPressed(button, false)
        return true
    }
}
