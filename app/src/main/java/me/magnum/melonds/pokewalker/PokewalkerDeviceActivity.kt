package me.magnum.melonds.pokewalker

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
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
 *
 * Behind it is the place in the game the walk's course looks like, read from the player's own
 * ROM, with the walking Pokémon exploring it; "explore" puts the walker away to watch it, send
 * it places with a tap or walk it about with the D-pad.
 */
class PokewalkerDeviceActivity : AppCompatActivity() {

    companion object {
        /** Opens one of the walker's apps right away: 0 Poké Radar, 1 Dowsing, 3 Trainer Card... */
        const val EXTRA_OPEN_APP = "me.magnum.melonds.pokewalker.OPEN_APP"
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
    private var captionAt = 0L
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.pokewalker_device_label)
        // Opening this screen may be what started the app: make sure the walker is open.
        Pokewalker.applySettings(this)

        scene = RouteSceneDrawable(resources.displayMetrics.density)
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
    }

    override fun onPause() {
        running = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        statusJob?.cancel()
        statusJob = null
        beeper?.release()
        beeper = null
        PokemonCries.release()
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
        // the games to play on the map, in the top corner while exploring
        val games = ImageButton(this).apply {
            setImageResource(R.drawable.ic_pw_games)
            imageTintList = ColorStateList.valueOf(WalkerTheme.TEXT)
            contentDescription = getString(R.string.pokewalker_games)
            TooltipCompat.setTooltipText(this, contentDescription)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor((WalkerTheme.CARD and 0x00FFFFFF) or 0xD9000000.toInt())
            }
            visibility = View.GONE
            setOnClickListener { showGames(it) }
        }
        stage.addView(games, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(12), dp(12), 0) })
        gamesButton = games
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
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            scene.bottomInset = bars.bottom.toFloat()
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(root)
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
            if (exploring && now - captionAt >= 250) {
                captionAt = now
                updateCaption()
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
        scene.map?.steps = status?.totalSteps ?: 0
        exploreButton?.visibility = if (scene.map != null) View.VISIBLE else View.GONE
        updateGamesButton()
        if (exploring && scene.map == null) setExploring(false)
        if (exploring) updateCaption()
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
            scene.map?.release()
            scene.map?.stopGame(SystemClock.uptimeMillis())
            updateBanner(lastStatus)
        }
        updateGamesButton()
        leaveExplore.isEnabled = on
        updateExploreButton()
        updateCovered()
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

    private fun updateCaption() {
        val map = scene.map ?: return
        val status = lastStatus ?: return
        val name = status.pokemonName.ifBlank { status.speciesName }
        val line = map.gameLine(SystemClock.uptimeMillis())?.let { gameText(it, name) }
        val text = getString(R.string.pokewalker_explore_caption, map.scene.course.name, map.scene.course.place, map.game) + "\n" +
            (line ?: getString(when {
                !map.hasPokemon -> R.string.pokewalker_explore_hint_no_sprite
                map.hasPeople -> R.string.pokewalker_explore_hint_people
                else -> R.string.pokewalker_explore_hint
            }, name))
        if (exploreCaption?.text?.toString() != text) exploreCaption?.text = text
    }

    private fun updateGamesButton() {
        val b = gamesButton ?: return
        val map = scene.map
        b.visibility = if (exploring && map != null && map.availableGames().isNotEmpty()) View.VISIBLE else View.GONE
    }

    /** The games menu: the ones there are here, and a way to stop the one that's on. */
    private fun showGames(anchor: View) {
        val map = scene.map ?: return
        val menu = PopupMenu(this, anchor)
        val games = map.availableGames()
        for ((i, g) in games.withIndex()) menu.menu.add(0, i, i, getString(gameName(g)))
        if (map.gameOn != null) menu.menu.add(0, 100, 100, getString(R.string.pokewalker_game_stop))
        menu.setOnMenuItemClickListener { item ->
            val now = SystemClock.uptimeMillis()
            if (item.itemId == 100) map.stopGame(now) else games.getOrNull(item.itemId)?.let { map.startGame(it, now) }
            updateCaption()
            true
        }
        menu.show()
    }

    private fun gameName(g: MapGames.Game): Int = when (g) {
        MapGames.Game.FISHING -> R.string.pokewalker_game_fishing
        MapGames.Game.TAG -> R.string.pokewalker_game_tag
        MapGames.Game.CONTEST -> R.string.pokewalker_game_contest
        MapGames.Game.UNOWN -> R.string.pokewalker_game_unown
        MapGames.Game.BERRY -> R.string.pokewalker_game_berry
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
        }
    }

    /** Taps while exploring: on the Pokémon it hops and cries, elsewhere it walks over. */
    private fun exploreTouch(v: View, e: MotionEvent): Boolean {
        if (!exploring) return false
        if (e.actionMasked == MotionEvent.ACTION_UP) {
            val map = scene.map ?: return true
            val root = rootLayout ?: return true
            val at = IntArray(2)
            val rootAt = IntArray(2)
            v.getLocationInWindow(at)
            root.getLocationInWindow(rootAt)
            if (map.tap(e.x + at[0] - rootAt[0], e.y + at[1] - rootAt[1], SystemClock.uptimeMillis())) cry(map)
            scene.invalidateSelf()
            updateCaption()
        }
        return true
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

    /** D-pad walks the Pokémon about while exploring; A says hi. */
    private fun exploreKey(keyCode: Int, event: KeyEvent, down: Boolean): Boolean {
        val map = scene.map ?: return false
        val direction = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> FollowerSprites.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> FollowerSprites.DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> FollowerSprites.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> FollowerSprites.RIGHT
            else -> -1
        }
        val now = SystemClock.uptimeMillis()
        if (direction >= 0) {
            if (down && event.repeatCount == 0) map.walk(direction, true, now)
            if (!down) map.release()
            return true
        }
        return when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SPACE -> {
                if (down && event.repeatCount == 0) {
                    if (map.action(now)) cry(map)
                }
                true
            }
            KeyEvent.KEYCODE_BUTTON_B -> {
                if (!down) setExploring(false)
                true
            }
            else -> false
        }
    }

    /** Confetti (and a happy cry) the first time today's steps reach the goal. */
    private fun celebrateGoal(status: WalkerStatus?, goal: Int) {
        if (status == null || !status.registered || goal <= 0 || status.todaySteps < goal) return
        val today = LocalDays.today()
        if (Pokewalker.celebratedDay(this) == today) return
        Pokewalker.setCelebratedDay(this, today)
        skin?.celebrate()
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

    private fun buttonForKey(keyCode: Int): Int = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_L2 -> 0
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_R2 -> 2
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_SPACE,
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y -> 1
        else -> -1
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // while exploring the walker is put away: its buttons do nothing
        if (exploring) return exploreKey(keyCode, event, true) || buttonForKey(keyCode) >= 0 || super.onKeyDown(keyCode, event)
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
        val button = buttonForKey(keyCode)
        if (button < 0) return super.onKeyUp(keyCode, event)
        skin?.showPressed(button, false)
        return true
    }
}
