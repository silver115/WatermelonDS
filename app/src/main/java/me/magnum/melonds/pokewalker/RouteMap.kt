package me.magnum.melonds.pokewalker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.util.Calendar
import java.util.IdentityHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** What else the place has, read from the ROM and the walker along with it. */
class MapExtras(
    /** The walking Pokémon's types ([PokemonTypes]). */
    val types: Set<Int> = emptySet(),
    /** A Pokémon caught on the walk that follows the walking one about, and its sprite. */
    val buddy: WalkingPokemon? = null,
    val buddySprite: FollowerSprites.Sprite? = null,
    /** The course's own Pokémon, coming out of the grass now and then. */
    val wild: List<MapCrowd.WildKind> = emptyList(),
    /** Fish to catch here, bugs for the contest, Unown A to Z (sprites among the people's). */
    val fish: List<MapCrowd.WildKind> = emptyList(),
    val bugs: List<MapCrowd.WildKind> = emptyList(),
    val unown: IntArray = IntArray(0),
    /** Where this course's berry patch keeps its state (null: no patch). */
    val berryStore: MapGames.BerryStore? = null,
    /** The game's field effects (emote bubbles, splashes, footprints, sparkles, the item ball). */
    val art: FieldEffectArt? = null,
    /** The walk's own items, for item balls. */
    val items: IntArray = IntArray(0),
    /** Bird Pokémon flying over, and the Magikarp leaping out of the water (sprites among the people's). */
    val birds: List<MapCrowd.WildKind> = emptyList(),
    val magikarp: MapCrowd.WildKind? = null,
    /** Today's visitor (a sprite among the people's). */
    val visitor: MapCrowd.WildKind? = null,
    /** Pokémon that drop out of trees, and that turn up under rocks. */
    val headbutt: List<MapCrowd.WildKind> = emptyList(),
    val smash: List<MapCrowd.WildKind> = emptyList(),
    /** The game's cracked rock. */
    val rockSprite: FollowerSprites.Sprite? = null,
    /** Where the catching game's best score is kept. */
    val scores: MapGames.ScoreStore? = null,
)

/**
 * The real place of the walk, from the player's own HeartGold/SoulSilver ROM ([FieldMap]), with
 * the walking Pokémon exploring it ([MapWanderer], [MapSpritePainter]) among the townsfolk and
 * trainers who live there ([FieldPeople], [MapCrowd]), the course's wild Pokémon coming out of
 * the grass, a Pokémon caught on the walk following along ([MapBuddy]), today's visitor
 * ([MapVisitor]), and games ([MapGames], [MapPlay]). The game's own moving textures play on it
 * (the sea, waves on the shore, rivers, flowers: [SceneAnimation]), its windmills turn and doors
 * open ([SceneProps]), windows and lamps glow after dark, little things and the weather move over
 * it ([MapAmbience], [MapCritters]), and the game's own effects ([FieldEffects]) and life
 * ([MapLife]: item balls, a tossed berry, birds flying over, sleep, moods, emote bubbles) go on.
 *
 * The picture is bigger than the view: the view can follow the Pokémon about ([following]), get
 * closer ([zoomTarget]), and what the walk hasn't uncovered yet can lie under cloud ([reveal]).
 * It is drawn in the DS's own pixels, made [scale] times bigger (more when closer) to fill the screen.
 */
class RouteMap internal constructor(
    val scene: FieldMap.Scene,
    internal val sprite: FollowerSprites.Sprite?,
    /** "HeartGold" or "SoulSilver": the game the place was read from. */
    val game: String,
    val scale: Int,
    internal val key: String,
    internal val pokemon: WalkingPokemon?,
    previous: RouteMap?,
    /** Sprites by overworld sprite number: the people's, and the wild Pokémon's, fish, bugs and Unown. */
    internal val peopleSprites: Map<Int, FollowerSprites.Sprite> = emptyMap(),
    internal val extras: MapExtras = MapExtras(),
    /** Who the map was made for. */
    internal val party: WalkParty = WalkParty(pokemon),
) {
    private val picture: Bitmap = Bitmap.createBitmap(scene.image, scene.width, scene.height, Bitmap.Config.ARGB_8888)
    private val crowd: MapCrowd = MapCrowd(scene, scene.people.filter { it.model in peopleSprites }).also { c ->
        c.wildKinds = extras.wild.filter { it.model in peopleSprites }
    }
    private val wanderer: MapWanderer? = if (scene.tiles.isEmpty()) null else MapWanderer(scene).also { w ->
        // the same place drawn again (new light): it carries on from where it was
        previous?.wanderer?.at?.let { at -> scene.tile(at.gx, at.gy)?.let { w.placeAt(it) } }
        w.swims = PokemonTypes.WATER in extras.types && scene.water.isNotEmpty()
        w.flies = PokemonTypes.FLYING in extras.types
        w.fades = PokemonTypes.GHOST in extras.types
    }

    /** The berry patch: open ground near the middle of the view, the same spot each time. */
    private val berryTile: FieldMap.Tile? = if (!scene.outdoor || extras.berryStore == null || wanderer == null) null else {
        val homes = scene.people.map { it.home }
        val v = scene.view
        val vw = v[2] - v[0]
        val vh = v[3] - v[1]
        scene.tiles.values.filter { t ->
            val p = scene.project(t.x, t.y, t.z)
            !t.tallGrass && p != null && p[0] in v[0] + vw * 0.2f..v[0] + vw * 0.8f && p[1] in v[1] + vh * 0.3f..v[1] + vh * 0.85f &&
                listOf(0 to 1, 0 to -1, 1 to 0, -1 to 0).all { (dx, dy) -> scene.tile(t.gx + dx, t.gy + dy)?.let { n -> FieldMap.stepOk(t, n) } == true } &&
                homes.none { abs(it.gx - t.gx) + abs(it.gy - t.gy) <= 1 } &&
                (scene.start == null || abs(scene.start.gx - t.gx) + abs(scene.start.gy - t.gy) >= 2)
        }.minByOrNull { Math.floorMod((scene.course.name.hashCode() * 31 + it.gx) * 7919 + it.gy * 104729, 1_000_003) }
    }

    private val buddy: MapBuddy? = wanderer?.let { w ->
        if (extras.buddySprite == null) return@let null
        val start = listOf(0 to 1, 1 to 0, -1 to 0, 0 to -1).mapNotNull { (dx, dy) -> scene.tile(w.at.gx + dx, w.at.gy + dy) }
            .firstOrNull { !crowd.occupied(it.gx, it.gy) && (berryTile == null || it !== berryTile) } ?: return@let null
        MapBuddy(scene, start)
    }

    private val critters = MapCritters(scene)

    /** Someone or something on tile ([gx], [gy]): people, the berry patch, an item ball, a berry thrown there, a rock, a pumpkin, the visitor. */
    private fun taken(gx: Int, gy: Int): Boolean {
        val berry = berryTile
        val now = SystemClock.uptimeMillis()
        return crowd.occupied(gx, gy) || (berry != null && berry.gx == gx && berry.gy == gy) || life?.blocks(gx, gy) == true ||
            games?.play?.blocks(gx, gy, now) == true || critters.blocks(gx, gy) ||
            visitor?.tiles()?.any { it.gx == gx && it.gy == gy } == true
    }

    init {
        wanderer?.let { w ->
            val berry = berryTile
            w.blocked = { gx, gy -> taken(gx, gy) || fogged(gx, gy) }
            // not on top of someone
            if (crowd.occupied(w.at.gx, w.at.gy) || w.at === berry) {
                scene.tiles.values.filter { !crowd.occupied(it.gx, it.gy) && it !== berry }
                    .minByOrNull { abs(it.gx - w.at.gx) + abs(it.gy - w.at.gy) }
                    ?.let { w.placeAt(it) }
            }
        }
    }

    private val games: MapGames? = wanderer?.let { w ->
        MapGames(scene, w, crowd).also { g ->
            g.fishKinds = extras.fish.filter { it.model in peopleSprites }
            g.bugKinds = extras.bugs.filter { it.model in peopleSprites }
            g.unownModels = if (extras.unown.size >= 26 && extras.unown.all { it in peopleSprites }) extras.unown else IntArray(0)
            g.berryTile = berryTile
            g.berryStore = extras.berryStore
            g.play.wildKinds = extras.wild.filter { it.model in peopleSprites }
            g.play.headbuttKinds = extras.headbutt.filter { it.model in peopleSprites }
            g.play.smashKinds = extras.smash.filter { it.model in peopleSprites }
            g.play.bestScore = extras.scores
            g.play.inSight = { t -> !outOfSight(t) }
            g.play.free = { t -> !taken(t.gx, t.gy) && !fogged(t.gx, t.gy) }
            g.play.placeRocks()
        }
    }

    private val effects = FieldEffects(scene, extras.art)

    /** Life on the map: item balls, a tossed berry, birds and Magikarp, sleep, moods, emotes. */
    private val life: MapLife? = wanderer?.let { w ->
        MapLife(scene, w, crowd, effects).also { l ->
            l.items = extras.items
            l.birds = extras.birds.filter { it.model in peopleSprites }
            l.magikarp = extras.magikarp?.takeIf { it.model in peopleSprites }
            l.shiny = pokemon?.shiny == true && sprite != null
            l.buddy = buddy
            l.buddyShiny = extras.buddy?.shiny == true && buddy != null
            l.games = games
            l.pokemonSize = sprite?.size ?: 32
            l.buddySize = extras.buddySprite?.size ?: 32
            l.free = { t ->
                val bd = buddy
                !crowd.occupied(t.gx, t.gy) && (berryTile == null || berryTile.gx != t.gx || berryTile.gy != t.gy) &&
                    (bd == null || ((bd.at.gx != t.gx || bd.at.gy != t.gy) && bd.next?.let { it.gx == t.gx && it.gy == t.gy } != true)) &&
                    games?.play?.blocks(t.gx, t.gy, SystemClock.uptimeMillis()) != true && !critters.blocks(t.gx, t.gy) && !fogged(t.gx, t.gy) &&
                    visitor?.tiles()?.none { it.gx == t.gx && it.gy == t.gy } != false
            }
            l.hidden = { t -> outOfSight(t) }
            // the same walk drawn again: it carries on as it was (asleep, say)
            previous?.life?.let { l.carryOn(it) }
        }
    }

    /** Today's visitor, now and then. */
    private val visitor: MapVisitor? = run {
        val w = wanderer ?: return@run null
        val l = life ?: return@run null
        val kind = extras.visitor?.takeIf { it.model in peopleSprites } ?: return@run null
        MapVisitor(scene, kind, w, l.emotes).also { v ->
            v.inSight = { t -> !outOfSight(t) }
            v.blocked = { gx, gy ->
                val berry = berryTile
                crowd.occupied(gx, gy) || (berry != null && berry.gx == gx && berry.gy == gy) || l.blocks(gx, gy) ||
                    games?.play?.blocks(gx, gy, SystemClock.uptimeMillis()) == true || critters.blocks(gx, gy) ||
                    buddy?.let { b -> (b.at.gx == gx && b.at.gy == gy) || b.next?.let { it.gx == gx && it.gy == gy } == true } == true
            }
        }
    }

    init {
        games?.play?.effects = effects
        games?.play?.onBerry = { tile, kind, now -> life?.drop(tile, kind, now) }
    }

    private val painter = if (sprite != null && wanderer != null) MapSpritePainter(scene, sprite) else null
    private val spriteBitmap = painter?.let { Bitmap.createBitmap(it.bufferWidth, it.bufferHeight, Bitmap.Config.ARGB_8888) }

    /** Someone drawn in the picture: how to paint them, their picture, and (for people) who they are. */
    private class Figure(val painter: MapSpritePainter, val bitmap: Bitmap, val walker: MapCrowd.Walker?) {
        var pose: MapWanderer.Pose? = null
        var feetY = 0f
        var lastTile: FieldMap.Tile? = null
        /** Drawn this frame (where [painter] says). */
        var painted = false
    }
    private fun figure(sprite: FollowerSprites.Sprite, walker: MapCrowd.Walker? = null, shadow: Boolean = true): Figure {
        val p = MapSpritePainter(scene, sprite).apply { this.shadow = shadow }
        return Figure(p, Bitmap.createBitmap(p.bufferWidth, p.bufferHeight, Bitmap.Config.ARGB_8888), walker)
    }
    private val pokemonFigure = if (painter != null && spriteBitmap != null) Figure(painter, spriteBitmap, null) else null
    private val buddyFigure = if (buddy != null) figure(extras.buddySprite!!) else null
    private val visitorFigure = visitor?.let { v -> peopleSprites[v.kind.model]?.let { figure(it) } }
    private val people = HashMap<MapCrowd.Walker, Figure>()
    private val berryFigures = HashMap<Int, Figure>()
    private val leapFigures = HashMap<Int, Figure>()
    private val flyerFigures = HashMap<Int, Figure>()
    private val hiderFigures = HashMap<Int, Figure>()
    private val rockFigures = IdentityHashMap<MapPlay.Rock, Figure>()
    private val patchFigures = IdentityHashMap<MapPlay.Patch, Figure>()
    private val ballSprite: FollowerSprites.Sprite? = extras.art?.frames("monstarball")?.firstOrNull()?.let { img ->
        FollowerSprites.Sprite(arrayOf(img.pixels), img.width, 1).takeIf { img.width == img.height }
    }
    private val ballFigure = ballSprite?.let { figure(it) }
    private val ballBitmap: Bitmap? = ballSprite?.let { Bitmap.createBitmap(it.frames[0], it.size, it.size, Bitmap.Config.ARGB_8888) }
    private val rockSprite: FollowerSprites.Sprite = extras.rockSprite ?: OWN_ROCK
    /** The game's grass shaking, as a sprite: four steps of one frame each. */
    private val grassSprite: FollowerSprites.Sprite? = extras.art?.frames("kusaeff")?.takeIf { f -> f.size >= 2 && f.all { it.width == f[0].width && it.width == it.height } }
        ?.let { f -> FollowerSprites.Sprite(Array(f.size) { f[it].pixels }, f[0].width, f.size) }
    private val treatFigures = HashMap<Int, Figure>()
    private val drawOrder = ArrayList<Figure>()
    private val paint = Paint().apply {
        isFilterBitmap = false
        isDither = false
    }
    private var offsetX = 0f
    private var offsetY = 0f
    private var covered: List<RectF> = emptyList()
    private val ambience = MapAmbience(scene)
    private val dots = ArrayList<MapAmbience.Dot>()
    private val clouds = ArrayList<MapAmbience.Cloud>()
    private val critterDots = ArrayList<MapAmbience.Dot>()
    private val critterPics = ArrayList<MapCritters.Pic>()
    private val dotPaint = Paint()
    private val cloudPaint = Paint().apply { isFilterBitmap = true }
    private val cloudRect = RectF()
    private val linePaint = Paint().apply { color = 0xC8F0F0F0.toInt(); strokeWidth = 0.6f; isAntiAlias = true }
    private val tintPaint = Paint()
    private val darkPaint = Paint()
    private val effectPaint = Paint().apply { isFilterBitmap = false }
    private val effectRect = RectF()
    private val picPaint = Paint().apply { isFilterBitmap = false }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fogPaint = Paint().apply { isFilterBitmap = true }
    private val groundDraws = ArrayList<FieldEffects.Draw>()
    private val airDraws = ArrayList<FieldEffects.Draw>()
    private val glowPaint = Paint().apply {
        isFilterBitmap = true
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
    }
    private val glowRect = RectF()
    /** The buildings that move (windmills, doors) over the picture. */
    private val props = scene.props
    private val propsBitmap: Bitmap? = props?.let { p ->
        Bitmap.createBitmap(scene.width, scene.height, Bitmap.Config.ARGB_8888).also { it.setPixels(p.overlay, 0, scene.width, 0, 0, scene.width, scene.height) }
    }
    private val propsFrame = IntArray(if (props != null) scene.width * scene.height else 0)
    @Volatile private var propsReady = false
    private var doorOpened: Pair<FieldMap.Tile, SceneProps.Prop>? = null
    private var weatherNow = MapAmbience.Weather.CLEAR
    private var occasions: Set<SpecialDates.Occasion> = emptySet()
    private var lastTick = Long.MIN_VALUE
    private val animFrame = IntArray(scene.width * scene.height)
    private val animBusy = AtomicBoolean(false)
    @Volatile private var animReady = false
    private var minute = 0
    private var minuteAt = Long.MIN_VALUE
    private var lastTile: FieldMap.Tile? = null
    private var lastGameLeap: MapGames.Leap? = null
    private var sparkAt = 0L
    private var lightX = scene.width / 2f
    private var lightY = scene.height / 2f
    private var homeAt = 0L
    private var shookTree: FieldMap.Tree? = null
    private var treeCrop: Bitmap? = null
    private val bladesAt = IdentityHashMap<Any, Long>()

    // ---- the view: where it looks, how close ----

    /** The view follows the Pokémon about the bigger picture (else it stays where the place looks its best). */
    var following = false

    /** How close to get (1: as it is, up to 3); the view eases there. */
    var zoomTarget = 1f
        set(v) { field = v.coerceIn(1f, MAX_ZOOM) }

    /** How close the view is now. */
    var zoom = 1f
        private set

    /**
     * Where across the route the view rests when it isn't following the Pokémon (0 the left
     * end of the picture, 1 the right; null: where the place looks its best), as a wallpaper
     * pans with the home screens.
     */
    var pan: Float? = null

    private var camX = (scene.view[0] + scene.view[2]) / 2f
    private var camY = (scene.view[1] + scene.view[3]) / 2f
    private var camAt = Long.MIN_VALUE
    private var drawScale = scale
    /** The part of the picture in view now (left, top, right, bottom). */
    private val inView = scene.view.copyOf()

    // ---- what the walk has uncovered ----

    /**
     * How much of the route the walk's steps have uncovered: 0 just the middle of the view, up to
     * 1 all of it (the rest lies under cloud, and the Pokémon can't go there). 1: none of it is covered.
     */
    var reveal = 1f
        set(v) { field = v.coerceIn(0f, 1f) }
    private var fogK = -1f
    private var fogBitmap: Bitmap? = null
    private val fogRect = RectF(0f, 0f, scene.width.toFloat(), scene.height.toFloat())

    /** How big the uncovered part is: its half width and height over the view's. */
    private fun fogScale(): Float {
        val vw = scene.view[2] - scene.view[0]
        val vh = scene.view[3] - scene.view[1]
        val most = sqrt((scene.width / vw) * (scene.width / vw) + (scene.height / vh) * (scene.height / vh)) * 1.02f
        return FOG_START + (most - FOG_START) * reveal
    }

    /** Whether picture point ([px], [py]) is still under cloud. */
    private fun foggedAt(px: Float, py: Float): Boolean {
        if (reveal >= 1f) return false
        val k = fogScale()
        val ax = (scene.view[2] - scene.view[0]) / 2f * k
        val ay = (scene.view[3] - scene.view[1]) / 2f * k
        val dx = (px - (scene.view[0] + scene.view[2]) / 2f) / ax
        val dy = (py - (scene.view[1] + scene.view[3]) / 2f) / ay
        return dx * dx + dy * dy > 1f
    }

    private fun fogged(gx: Int, gy: Int): Boolean {
        if (reveal >= 1f) return false
        val t = scene.tile(gx, gy) ?: scene.water[FieldMap.Scene.key(gx, gy)] ?: return false
        val p = scene.project(t.x, t.y, t.z) ?: return true
        return foggedAt(p[0], p[1])
    }

    /** Whether the Pokémon is drawn walking around. */
    val hasPokemon get() = painter != null

    /** Whether any of the place's people are about. */
    val hasPeople get() = scene.people.any { it.model in peopleSprites }

    val species get() = pokemon?.species ?: 0

    /** The walker's steps (the berry patch grows with them). */
    var steps: Long = 0
        set(value) { field = value; games?.steps = value }

    /** The walker's steps, today's and the day's goal: its mood follows them, and new steps wake it up. */
    fun setSteps(total: Long, today: Long, goal: Int, now: Long) {
        steps = total
        life?.setSteps(total, today, goal, now)
    }

    /** Whether the Pokémon is asleep. */
    val asleep get() = life?.asleep == true

    /** The weather on the map now. */
    val weather get() = weatherNow

    /** Whether it's being walked about by hand right now (it goes back to roaming a few seconds after). */
    fun steering(now: Long) = wanderer?.steered(now) == true

    /** Today's visitor's species (0: nobody's coming). */
    val visitorSpecies get() = visitor?.kind?.species ?: 0

    /** Today's goal reached: it's delighted. */
    fun celebrate(now: Long) {
        life?.celebrate(now)
    }

    /** Throws a berry towards box point ([x], [y]) for the Pokémon to eat; false when it can't be. */
    fun toss(x: Float, y: Float, now: Long): Boolean {
        val l = life ?: return false
        val (px, py) = toPicture(x, y)
        val tile = scene.tileNear(px, py, scene.tilePixels * 2.5f)?.takeIf { !fogged(it.gx, it.gy) } ?: return false.also { l.interacted(now) }
        return l.toss(tile, (inView[0] + inView[2]) / 2f, inView[3] + 8f, now)
    }

    /** Throws a berry a few steps from the Pokémon. */
    fun tossNear(now: Long): Boolean = life?.tossNear((inView[0] + inView[2]) / 2f, inView[3] + 8f, now) == true

    /** Whether the Pokémon is walking (draw often then). */
    fun moving(now: Long) = wanderer?.pose(now)?.moving == true

    /** The games there are here, which one is on, and what to tell the player about it. */
    fun availableGames(now: Long = SystemClock.uptimeMillis()): List<MapGames.Game> = games?.available(now) ?: emptyList()
    val gameOn: MapGames.Game? get() = games?.game
    fun gameLine(now: Long): MapGames.Line? = games?.lineAt(now)
    fun startGame(g: MapGames.Game, now: Long): Boolean {
        life?.interacted(now)
        return games?.start(g, now) == true
    }
    fun stopGame(now: Long) { games?.stop(now) }

    /** Rustling grass for the walker's Poké Radar encounter ([MapPlay.Encounter]). */
    fun startRustling(e: MapPlay.Encounter, now: Long): Boolean {
        life?.interacted(now)
        return games?.startRustling(e, now) == true
    }

    /** Dowsing for the walker's [item]. */
    fun startDowsing(item: Int, now: Long): Boolean {
        life?.interacted(now)
        return games?.startDowsing(item, now) == true
    }

    /** Shows what the walker said about a reward (or anything else to tell). */
    fun announce(l: MapGames.Line, now: Long) {
        games?.announce(l, now, 7_000)
    }

    /** What the walker should keep (a catch, a find), since last taken. */
    fun takeRewards(): List<MapGames.Reward> {
        val g = games ?: return emptyList()
        val out = ArrayList(g.rewards)
        g.rewards.clear()
        return out
    }

    /** Species whose cry should play now (something was caught, a berry eaten, the visitor said hello). */
    fun takeCries(): List<Int> {
        val out = ArrayList<Int>()
        games?.let { out.addAll(it.cries); it.cries.clear() }
        life?.let { l ->
            for (c in l.cries) if (c == MapLife.OWN_CRY) { if (species > 0) out.add(species) } else out.add(c)
            l.cries.clear()
        }
        visitor?.let { v -> if (v.cry) { v.cry = false; out.add(v.kind.species) } }
        return out
    }

    /** A picture point from a point of the box it's drawn in. */
    private fun toPicture(x: Float, y: Float): Pair<Float, Float> = ((x - offsetX) / drawScale) to ((y - offsetY) / drawScale)

    /** Where the view looks and how close, eased towards the Pokémon (or the middle) for a [w] x [h] box. */
    private fun updateCamera(w: Float, h: Float, now: Long, pose: MapWanderer.Pose?) {
        val dt = if (camAt == Long.MIN_VALUE) 10_000L else (now - camAt).coerceIn(0, 1_000)
        camAt = now
        // easing in and out
        val k = 1f - exp(-dt / 180f)
        zoom += (zoomTarget - zoom) * k
        if (abs(zoomTarget - zoom) < 0.01f) zoom = zoomTarget
        drawScale = max(scale, (scale * zoom).roundToInt())
        val vw = w / drawScale
        val vh = h / drawScale
        val follow = following || zoomTarget > 1.01f || (wanderer?.steered(now) == true)
        var tx = (scene.view[0] + scene.view[2]) / 2f
        var ty = (scene.view[1] + scene.view[3]) / 2f
        pan?.let { p -> if (vw < scene.width) tx = vw / 2f + (scene.width - vw) * p.coerceIn(0f, 1f) }
        if (follow && pose != null) {
            scene.project(pose.x, pose.y, pose.z)?.let { p -> tx = p[0]; ty = p[1] - 10f }
        }
        val ck = 1f - exp(-dt / 260f)
        camX += (tx - camX) * ck
        camY += (ty - camY) * ck
        // kept inside the picture
        camX = if (vw >= scene.width) scene.width / 2f else camX.coerceIn(vw / 2f, scene.width - vw / 2f)
        camY = if (vh >= scene.height) scene.height / 2f else camY.coerceIn(vh / 2f, scene.height - vh / 2f)
        offsetX = (w / 2f - camX * drawScale).roundToInt().toFloat()
        offsetY = (h / 2f - camY * drawScale).roundToInt().toFloat()
        inView[0] = -offsetX / drawScale
        inView[1] = -offsetY / drawScale
        inView[2] = inView[0] + vw
        inView[3] = inView[1] + vh
    }

    /**
     * Whether a tile is out of sight: out of the view (with room for someone standing there),
     * under cloud, or behind the cards on the screen.
     */
    private fun outOfSight(t: FieldMap.Tile): Boolean {
        val p = scene.project(t.x, t.y, t.z) ?: return true
        if (p[0] < inView[0] + 8f || p[0] > inView[2] - 8f || p[1] < inView[1] + 26f || p[1] > inView[3] - 3f) return true
        if (foggedAt(p[0], p[1])) return true
        return coveredAt(p)
    }

    /** Whether the cards on the screen cover a tile drawn at picture point [p] (its feet or its head). */
    private fun coveredAt(p: FloatArray): Boolean {
        if (covered.isEmpty()) return false
        val vx = offsetX + p[0] * drawScale
        val vy = offsetY + p[1] * drawScale
        return covered.any { it.contains(vx, vy) || it.contains(vx, vy - scene.tilePixels * 1.5f * drawScale) }
    }

    /** Draws the place and the Pokémon into a [w] x [h] box. */
    fun draw(canvas: Canvas, w: Float, h: Float, now: Long) {
        val wd = wanderer
        updateCamera(w, h, now, wd?.pose(now))
        val s = drawScale.toFloat()
        canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.scale(s, s)
        // the game's moving textures and buildings, at 10 steps a second of its 30, done in the background
        val anim = scene.animation
        val pr = props
        val tick = now * 30 / 1000 / 3 * 3
        if ((anim != null || pr != null) && tick != lastTick && animBusy.compareAndSet(false, true)) {
            lastTick = tick
            animator.execute {
                try {
                    if (anim != null && anim.update(tick)) {
                        synchronized(animFrame) { System.arraycopy(scene.image, 0, animFrame, 0, animFrame.size) }
                        animReady = true
                    }
                    if (pr != null && pr.update(tick, scene.image)) {
                        synchronized(propsFrame) { System.arraycopy(pr.overlay, 0, propsFrame, 0, propsFrame.size) }
                        propsReady = true
                    }
                } catch (e: Exception) {
                    Log.w("RouteMap", "Animation stopped", e)
                } finally {
                    animBusy.set(false)
                }
            }
        }
        if (animReady) {
            animReady = false
            synchronized(animFrame) { picture.setPixels(animFrame, 0, scene.width, 0, 0, scene.width, scene.height) }
        }
        canvas.drawBitmap(picture, 0f, 0f, paint)
        // a tree being headbutted shakes
        drawTreeShake(canvas, now)
        propsBitmap?.let { b ->
            if (propsReady) {
                propsReady = false
                synchronized(propsFrame) { b.setPixels(propsFrame, 0, scene.width, 0, 0, scene.width, scene.height) }
            }
            canvas.drawBitmap(b, 0f, 0f, paint)
        }
        if (minuteAt == Long.MIN_VALUE || now - minuteAt > 10_000) {
            minuteAt = now
            val cal = Calendar.getInstance()
            minute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
            ambience.weather = MapAmbience.weatherFor(scene.course, cal.get(Calendar.DAY_OF_YEAR), minute)
            weatherNow = ambience.weather
            occasions = SpecialDates.on(cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH), minute)
            // snow at Christmas
            if (SpecialDates.Occasion.CHRISTMAS in occasions && scene.outdoor && weatherNow == MapAmbience.Weather.CLEAR &&
                scene.course.kind != FieldMap.Kind.BEACH && scene.course.kind != FieldMap.Kind.VOLCANO) {
                ambience.weather = MapAmbience.Weather.SNOW
                weatherNow = MapAmbience.Weather.SNOW
            }
        }
        ambience.view = inView
        critters.view = inView
        critters.occasions = occasions
        critters.weather = weatherNow
        life?.view = inView
        // lit windows and lamps after dark
        drawGlows(canvas, now)

        drawOrder.clear()
        pokemonFigure?.painted = false
        buddyFigure?.painted = false
        visitorFigure?.painted = false
        for (f in people.values) f.painted = false
        var pokemonPoint: FloatArray? = null
        if (wd != null) {
            wd.update(now)
            val pose = wd.pose(now)
            pokemonFigure?.let { f ->
                f.pose = pose
                f.feetY = scene.project(pose.x, pose.y, pose.z)?.get(1) ?: 0f
                drawOrder.add(f)
            }
            scene.project(pose.x, pose.y, pose.z)?.let { lightX = it[0]; lightY = it[1] - 10f; pokemonPoint = it }
            // stepping into tall grass shakes it; into the water, a splash; sand and snow keep its footprints
            val at = wd.at
            val was = lastTile
            if (at !== was) {
                if (was != null) {
                    val wet = scene.tile(at.gx, at.gy) == null
                    val wasWet = scene.tile(was.gx, was.gy) == null
                    when {
                        wet && !wasWet -> splash(at, now)
                        wet -> effects.ripple(at.x, at.y, at.z, now)
                        wasWet -> splash(at, now)
                        else -> stepped(at, wd.facing, now)
                    }
                    scene.project(at.x, at.y, at.z)?.let { critters.disturb(it[0], it[1], now) }
                }
                lastTile = at
            }
            if (PokemonTypes.FIRE in extras.types && pose.moving && now - sparkAt > 110) {
                sparkAt = now
                scene.project(pose.x, pose.y, pose.z)?.let { ambience.sparks(it[0], it[1] - 2f, now) }
            }
            // running: little puffs of dust behind it
            if (pose.moving && wd.move == MapWanderer.Move.WALK && !pose.inWater && wd.pace <= 240L && now - sparkAt > 260 && !at.tallGrass) {
                sparkAt = now
                if (extras.art != null) effects.dust(pose.x, pose.y, pose.z, now)
            }
            comeBackIntoView(wd, now)
        }
        // the one following along
        val bd = buddy
        if (bd != null && wd != null) {
            bd.update(now, wd) { gx, gy -> taken(gx, gy) }
            buddyFigure?.let { f ->
                val pose = bd.pose(now)
                f.pose = pose
                f.feetY = scene.project(pose.x, pose.y, pose.z)?.get(1) ?: 0f
                drawOrder.add(f)
                if (bd.at !== f.lastTile) {
                    if (f.lastTile != null) stepped(bd.at, bd.facing, now)
                    f.lastTile = bd.at
                }
            }
        }
        // the people and wild Pokémon, the games, and life about the place
        val g = games
        val l = life
        val v = visitor
        if (v != null) {
            v.quiet = g?.game != null || l?.asleep == true
            v.update(now)
            if (v.lines.isNotEmpty()) {
                for (line in v.lines) g?.announce(line, now)
                v.lines.clear()
            }
            visitorFigure?.let { f ->
                val pose = v.pose(now)
                if (pose != null) {
                    f.pose = pose
                    f.feetY = scene.project(pose.x, pose.y, pose.z)?.get(1) ?: 0f
                    drawOrder.add(f)
                    val vw = v.walker
                    if (vw != null && vw.at !== f.lastTile) {
                        if (f.lastTile != null) stepped(vw.at, vw.facing, now)
                        f.lastTile = vw.at
                    }
                }
            }
        }
        val others = ArrayList<FieldMap.Tile>(8)
        bd?.let { others.add(it.at); it.next?.let { n -> others.add(n) } }
        berryTile?.let { others.add(it) }
        l?.let { others.addAll(it.tiles()) }
        v?.let { others.addAll(it.tiles()) }
        g?.play?.let { p -> for (r in p.rocks) if (r.standing(now)) others.add(r.tile) }
        if (SpecialDates.Occasion.HALLOWEEN in occasions) others.addAll(critters.pumpkins)
        crowd.update(now, wd?.at, wd?.next, wd?.facing ?: 0, wd != null && wd.next == null, others)
        g?.update(now)
        l?.update(now, minute, weatherNow)
        if (l != null && l.lines.isNotEmpty()) {
            for (line in l.lines) g?.announce(line, now)
            l.lines.clear()
        }
        for ((t, _) in crowd.popped) {
            when {
                scene.tile(t.gx, t.gy) == null -> splash(t, now)
                t.tallGrass -> rustle(t, now)
                extras.art != null -> effects.dust(t.x, t.y, t.z, now)
                else -> scene.project(t.x, t.y, t.z)?.let { ambience.puff(it[0], it[1], now) }
            }
        }
        crowd.popped.clear()
        // a door it walks up to and looks at opens, and shuts again
        if (pr != null && wd != null && l != null) {
            val door = if (wd.idle && wd.facing == FollowerSprites.UP && !l.asleep) {
                scene.doors.firstOrNull { it.first.gx == wd.at.gx && it.first.gy == wd.at.gy - 1 }
            } else null
            if (door != null && door !== doorOpened) {
                pr.open(door.second, now * 30 / 1000)
                l.atDoor(now)
            }
            doorOpened = door
        }
        people.keys.retainAll(crowd.walkers.toSet())
        val play = g?.play
        for (wk in crowd.walkers) {
            val sp = peopleSprites[wk.person.model] ?: continue
            // in a Poké Ball just now
            if (play != null && play.inBall(wk, now)) continue
            val f = people.getOrPut(wk) { figure(sp, wk, shadow = !wk.person.swimming) }
            val pose = wk.pose(now)
            f.pose = pose
            f.feetY = scene.project(pose.x, pose.y, pose.z)?.get(1) ?: continue
            drawOrder.add(f)
            if (wk.at !== f.lastTile) {
                if (f.lastTile != null && !wk.person.swimming) {
                    stepped(wk.at, wk.facing, now)
                    scene.project(wk.at.x, wk.at.y, wk.at.z)?.let { critters.disturb(it[0], it[1], now) }
                }
                f.lastTile = wk.at
            }
        }
        // the berry patch
        berryTile?.let { b ->
            val stage = g?.berryStage() ?: -1
            val kind = g?.berryKind() ?: 0
            val f = berryFigures.getOrPut(stage * 8 + kind) { figure(BerryArt.sprite(stage, kind), shadow = false) }
            f.pose = MapWanderer.Pose(b.x, b.y, b.z, 0, 0, 0.0, false)
            f.feetY = scene.project(b.x, b.y, b.z)?.get(1) ?: 0f
            drawOrder.add(f)
        }
        // a fish leaping out (one caught, or a Magikarp jumping)
        g?.leap?.let { leap ->
            if (leap !== lastGameLeap) {
                lastGameLeap = leap
                splash(leap.tile, now)
            }
            addLeap(leap, now)
        }
        l?.leap?.let { addLeap(it, now) }
        // an item ball on the ground
        val ball = l?.ball
        if (ball != null && ballFigure != null) {
            ballFigure.pose = MapWanderer.Pose(ball.tile.x, ball.tile.y, ball.tile.z, 0, 0, 0.0, false)
            ballFigure.feetY = scene.project(ball.tile.x, ball.tile.y, ball.tile.z)?.get(1) ?: 0f
            drawOrder.add(ballFigure)
        }
        // a berry thrown, on the ground (being eaten: gone before the last bites)
        val treat = l?.treat
        if (treat != null && now >= treat.landsAt && (treat.eatenAt == Long.MIN_VALUE || now - treat.eatenAt < MapLife.EAT_MS * 7 / 10)) {
            val f = treatFigures.getOrPut(treat.kind) { figure(BerryArt.treat(treat.kind)) }
            f.pose = MapWanderer.Pose(treat.tile.x, treat.tile.y, treat.tile.z, 0, 0, 0.0, false)
            f.feetY = scene.project(treat.tile.x, treat.tile.y, treat.tile.z)?.get(1) ?: 0f
            drawOrder.add(f)
        }
        // the games' things: rocks, the grass rustling, someone hiding
        if (play != null) addPlay(play, now)
        // birds flying over, on top of everything
        if (l != null) for (fl in l.flyers) {
            val sp = peopleSprites[fl.kind.model] ?: continue
            val pose = fl.pose(now) ?: continue
            val f = flyerFigures.getOrPut(fl.kind.model) { figure(sp) }
            f.pose = pose
            f.feetY = Float.MAX_VALUE
            drawOrder.add(f)
        }
        // a shooting star: it looks up
        critters.frame(now, minute, pokemonPoint, critterDots, critterPics)
        if (critters.shootingStars > 0) {
            critters.shootingStars = 0
            l?.lookUp(now)
        }

        // footprints and rings under everyone, and little things on the ground
        effects.ground(now, groundDraws)
        drawEffects(canvas, groundDraws)
        drawPics(canvas, critterPics, ground = true)
        // further back first, so whoever is nearer is drawn over them
        drawOrder.sortBy { it.feetY }
        for (f in drawOrder) {
            val p = f.painter
            val pose = f.pose ?: continue
            f.painted = p.paint(pose)
            if (f.painted) {
                f.bitmap.setPixels(p.buffer, 0, p.bufferWidth, 0, 0, p.bufferWidth, p.bufferHeight)
                canvas.drawBitmap(f.bitmap, p.left.toFloat(), p.top.toFloat(), paint)
            }
        }
        // splashes, dust and sparkles over them
        effects.air(now, airDraws)
        drawEffects(canvas, airDraws)
        // things falling, a Poké Ball thrown, bits of rock flying
        if (play != null) drawPlayAir(canvas, play, now)
        // a berry in the air
        if (treat != null && now < treat.landsAt) {
            scene.project(treat.tile.x, treat.tile.y, treat.tile.z)?.let { end ->
                val u = ((now - treat.thrownAt).toFloat() / MapLife.TOSS_MS).coerceIn(0f, 1f)
                val x = treat.fromX + (end[0] - treat.fromX) * u
                val y = treat.fromY + (end[1] - treat.fromY) * u - sin(Math.PI * u).toFloat() * 46f
                val b = treatBitmap(treat.kind)
                canvas.drawBitmap(b, (x - b.width / 2f).roundToInt().toFloat(), (y - b.height + 3f).roundToInt().toFloat(), paint)
            }
        }

        // fishing: the line and the float (and "!" when something bites)
        if (g != null && wd != null) {
            g.bobber?.let { b -> scene.project(b.x, b.y, b.z) }?.let { bp ->
                val bob = if (g.biting) 1.5f + sin(now / 60.0).toFloat() * 1.2f else sin(now / 400.0).toFloat() * 0.6f
                val pp = painter
                if (pp != null) {
                    val rx = if (wd.facing == FollowerSprites.LEFT) pp.spriteLeft + 8f else if (wd.facing == FollowerSprites.RIGHT) pp.spriteLeft + pp.size - 8f else pp.spriteLeft + pp.size / 2f
                    canvas.drawLine(rx, pp.spriteTop + pp.headSpace + 4f, bp[0], bp[1] - 2f + bob, linePaint)
                }
                dotPaint.color = 0xFFE83838.toInt()
                canvas.drawRect(bp[0] - 1.5f, bp[1] - 3.5f + bob, bp[0] + 1.5f, bp[1] - 1.5f + bob, dotPaint)
                dotPaint.color = 0xFFFFFFFF.toInt()
                canvas.drawRect(bp[0] - 1.5f, bp[1] - 1.5f + bob, bp[0] + 1.5f, bp[1] + 0.5f + bob, dotPaint)
            }
            val pp = painter
            if (g.biting && pp != null && extras.art == null) bubble(canvas, pp.spriteLeft + pp.size / 2f, (pp.spriteTop + pp.headSpace).toFloat() - 3f)
        }
        drawPics(canvas, critterPics, ground = false)

        ambience.frame(now, minute, dots, clouds)
        for (c in clouds) {
            cloudRect.set(c.x - c.rx, c.y - c.ry, c.x + c.rx, c.y + c.ry)
            cloudPaint.alpha = (c.alpha * 255).toInt().coerceIn(0, 255)
            canvas.drawBitmap(if (c.mist) mistBank else cloudShadow, null, cloudRect, cloudPaint)
        }
        drawRainbow(canvas)
        if (ambience.tint != 0) {
            tintPaint.color = ambience.tint
            canvas.drawRect(inView[0] - 2f, inView[1] - 2f, inView[2] + 2f, inView[3] + 2f, tintPaint)
        }
        drawDots(canvas, dots)
        drawDots(canvas, critterDots)
        if (ambience.darkness > 0f) {
            // a dark cave: only what's near the Pokémon shows
            val a = (ambience.darkness * 255).toInt().coerceIn(0, 255)
            darkPaint.shader = RadialGradient(lightX, lightY, 78f, intArrayOf(0x00000000, (a / 3) shl 24, a shl 24),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            canvas.drawRect(inView[0] - 2f, inView[1] - 2f, inView[2] + 2f, inView[3] + 2f, darkPaint)
        }
        // what the walk hasn't uncovered yet
        drawFog(canvas)
        // emote bubbles, over it all
        if (l != null) {
            for ((wk, f) in people) if (f.painted) drawEmote(canvas, wk, f.painter, now)
            if (bd != null) buddyFigure?.takeIf { it.painted }?.let { drawEmote(canvas, bd, it.painter, now) }
            v?.walker?.let { vw -> visitorFigure?.takeIf { it.painted }?.let { drawEmote(canvas, vw, it.painter, now) } }
            if (wd != null) pokemonFigure?.takeIf { it.painted }?.let { drawEmote(canvas, wd, it.painter, now) }
            // "!" over the grass that's rustling
            play?.let { p ->
                for (patch in p.patches) {
                    if (!p.alert(patch)) continue
                    val pt = scene.project(patch.tile.x, patch.tile.y, patch.tile.z) ?: continue
                    drawEmoteAt(canvas, Emote.EXCLAIM, 0L, pt[0], pt[1] - 22f, now)
                }
            }
        }
        canvas.restore()
    }

    /** Out of view, and not being followed: it walks back to where it can be seen. */
    private fun comeBackIntoView(wd: MapWanderer, now: Long) {
        val follow = following || zoomTarget > 1.01f || wd.steered(now)
        if (follow || !wd.idle || wd.held || asleep || now - homeAt < 3_000) return
        val p = scene.project(wd.at.x, wd.at.y, wd.at.z) ?: return
        if (p[0] >= inView[0] + 6f && p[0] <= inView[2] - 6f && p[1] >= inView[1] + 20f && p[1] <= inView[3] - 4f) return
        homeAt = now
        val back = scene.tiles.values.filter { t -> !outOfSight(t) }
            .sortedBy { abs(it.gx - wd.at.gx) + abs(it.gy - wd.at.gy) }
            .take(12)
        for (t in back) if (wd.goTo(t, now)) break
    }

    /** The rocks, the patches of grass rustling, someone hiding: drawn among everyone else. */
    private fun addPlay(play: MapPlay, now: Long) {
        for (r in play.rocks) {
            if (!r.standing(now)) continue
            val f = rockFigures.getOrPut(r) { figure(rockSprite) }
            val t = r.tile
            f.pose = MapWanderer.Pose(t.x, t.y, t.z, 0, 0, 0.0, false, alpha = r.alpha(now), dx = r.dx(now))
            f.feetY = scene.project(t.x, t.y, t.z)?.get(1) ?: continue
            drawOrder.add(f)
        }
        patchFigures.keys.retainAll(play.patches.toSet())
        for (p in play.patches) {
            val amount = play.rustle(p, now)
            val t = p.tile
            if (grassSprite != null) {
                if (amount <= 0.05f) continue
                val f = patchFigures.getOrPut(p) { figure(grassSprite, shadow = false) }
                val step = ((now / (if (amount > 0.6f) 70 else 120)) % grassSprite.perDirection).toInt()
                f.pose = MapWanderer.Pose(t.x, t.y, t.z + 1.0, 0, step, 0.0, false, dx = if (amount > 0.6f && (now / 50) % 2 == 0L) 1 else 0)
                f.feetY = (scene.project(t.x, t.y, t.z)?.get(1) ?: continue) + 0.5f
                drawOrder.add(f)
            } else if (amount > 0.3f) {
                rustle(t, now, p)
            }
        }
        val pose = play.hiderPose(now)
        val h = play.hider
        if (pose != null && h != null) {
            peopleSprites[h.kind.model]?.let { sp ->
                val f = hiderFigures.getOrPut(h.kind.model) { figure(sp) }
                f.pose = pose
                f.feetY = scene.project(pose.x, pose.y, pose.z)?.get(1) ?: 0f
                drawOrder.add(f)
            }
        }
        if (play.hiderRustles(now) && h != null) rustle(h.tile, now, h)
    }

    /** Grass shaking at [t] (no more than every 300 ms for [who]). */
    private fun rustle(t: FieldMap.Tile, now: Long, who: Any = t) {
        if (now - (bladesAt[who] ?: Long.MIN_VALUE) < 300) return
        bladesAt[who] = now
        if (bladesAt.size > 64) bladesAt.clear()
        scene.project(t.x, t.y, t.z)?.let { ambience.rustle(it[0], it[1], now) }
    }

    /** What falls (out of a tree, or in the catching game) with its shadow, the Poké Ball, bits of rock. */
    private fun drawPlayAir(canvas: Canvas, play: MapPlay, now: Long) {
        for (d in play.drops) {
            val at = play.dropAt(d, now) ?: continue
            val lift = at[2]
            // its shadow, bigger and darker the lower it is
            val k = (1f - lift / d.height.coerceAtLeast(1f)).coerceIn(0.2f, 1f)
            shadowPaint.color = ((0x50 * k).toInt() shl 24)
            val r = 2.5f + 3.5f * k
            canvas.drawOval(at[0] - r, at[1] - r * 0.4f, at[0] + r, at[1] + r * 0.4f, shadowPaint)
            val b = when (d.kind) {
                MapPlay.DROP_POKEMON -> pokemonStill(d.model, now)
                MapPlay.DROP_BERRY, MapPlay.FALL_BERRY -> treatBitmap(d.berry)
                MapPlay.FALL_GOLDEN -> goldenBitmap()
                else -> bitmapOf(FALLING_LEAF)
            } ?: continue
            val bottom = if (d.kind == MapPlay.DROP_POKEMON) stillBottom(d.model) else b.height
            canvas.drawBitmap(b, (at[0] - b.width / 2f).roundToInt().toFloat(), (at[1] - bottom - lift + 1f).roundToInt().toFloat(), paint)
        }
        // the Poké Ball, thrown and rocking
        play.ballAt(now)?.let { b ->
            val x = b[0]
            val y = b[1] - b[2]
            val bmp = ballBitmap
            if (bmp != null) {
                canvas.save()
                canvas.rotate(b[3] * 24f, x, y)
                canvas.drawBitmap(bmp, (x - bmp.width / 2f).roundToInt().toFloat(), (y - bmp.height + 2f).roundToInt().toFloat(), paint)
                canvas.restore()
            } else {
                dotPaint.color = 0xFFE83838.toInt()
                canvas.drawRect(x - 3f, y - 6f, x + 3f, y - 3f, dotPaint)
                dotPaint.color = 0xFFFFFFFF.toInt()
                canvas.drawRect(x - 3f, y - 3f, x + 3f, y, dotPaint)
            }
        }
        // bits of a broken rock
        for (c in play.chips) {
            val t = (now - c.start) / 1000f
            if (t < 0f) continue
            val u = (now - c.start).toFloat() / MapPlay.CRUMBLE_MS
            val x = c.x + c.vx * t
            val y = c.y + c.vy * t + 130f * t * t
            dotPaint.color = (((1f - u).coerceIn(0f, 1f) * 255).toInt() shl 24) or (c.color and 0xFFFFFF)
            canvas.drawRect(x.roundToInt().toFloat(), y.roundToInt().toFloat(), x.roundToInt() + 2f, y.roundToInt() + 2f, dotPaint)
        }
    }

    /** The tree being headbutted: its picture shaken side to side, and leaves coming down. */
    private fun drawTreeShake(canvas: Canvas, now: Long) {
        val play = games?.play ?: return
        val dx = play.treeShake(now)
        val t = play.tree
        if (dx == null || t == null) {
            if (dx == null && shookTree != null && play.tree == null) { shookTree = null; treeCrop = null }
            return
        }
        if (t !== shookTree) {
            shookTree = t
            val w = (t.right - t.left).coerceAtLeast(1)
            val h = (t.bottom - t.top).coerceAtLeast(1)
            treeCrop = runCatching { Bitmap.createBitmap(picture, t.left, t.top, w, h) }.getOrNull()
            critters.leafBurst(t.left, t.top, t.right, t.bottom, now)
        }
        treeCrop?.let { canvas.drawBitmap(it, (t.left + dx).toFloat(), t.top.toFloat(), paint) }
    }

    private fun drawPics(canvas: Canvas, list: List<MapCritters.Pic>, ground: Boolean) {
        for (p in list) {
            if (p.ground != ground) continue
            picPaint.alpha = (p.alpha * 255).toInt().coerceIn(0, 255)
            val b = bitmapOf(p.art)
            canvas.drawBitmap(b, (p.x - b.width / 2f).roundToInt().toFloat(), (p.y - b.height).roundToInt().toFloat(), picPaint)
        }
    }

    private fun drawDots(canvas: Canvas, list: List<MapAmbience.Dot>) {
        for (d in list) {
            dotPaint.color = d.color
            val x = d.x.roundToInt().toFloat()
            val y = d.y.roundToInt().toFloat()
            canvas.drawRect(x, y, x + d.size, y + d.tall, dotPaint)
        }
    }

    /** A rainbow after the rain. */
    private fun drawRainbow(canvas: Canvas) {
        val a = critters.rainbow
        if (a <= 0f) return
        val r = critters.rainbowR
        arcPaint.strokeWidth = 3f
        for ((i, c) in RAINBOW.withIndex()) {
            arcPaint.color = c
            arcPaint.alpha = (a * 255).toInt().coerceIn(0, 255)
            val rr = r - i * 3f
            cloudRect.set(critters.rainbowX - rr, critters.rainbowY - rr, critters.rainbowX + rr, critters.rainbowY + rr)
            canvas.drawArc(cloudRect, 180f, 180f, false, arcPaint)
        }
    }

    /** The cloud over what the walk hasn't uncovered yet: soft-edged and puffy. */
    private fun drawFog(canvas: Canvas) {
        if (reveal >= 1f) { fogBitmap = null; fogK = -1f; return }
        val k = fogScale()
        if (fogBitmap == null || abs(k - fogK) > 0.01f) {
            fogK = k
            fogBitmap = makeFog(k)
        }
        fogBitmap?.let { canvas.drawBitmap(it, null, fogRect, fogPaint) }
    }

    private fun makeFog(k: Float): Bitmap {
        val q = 4
        val fw = (scene.width + q - 1) / q
        val fh = (scene.height + q - 1) / q
        val px = IntArray(fw * fh)
        val cx = (scene.view[0] + scene.view[2]) / 2f
        val cy = (scene.view[1] + scene.view[3]) / 2f
        val ax = (scene.view[2] - scene.view[0]) / 2f * k
        val ay = (scene.view[3] - scene.view[1]) / 2f * k
        val soft = 26f
        for (y in 0 until fh) for (x in 0 until fw) {
            val wx = x * q + q / 2f
            val wy = y * q + q / 2f
            val dx = (wx - cx) / ax
            val dy = (wy - cy) / ay
            val e = sqrt(dx * dx + dy * dy)
            // how far out of the uncovered part, in pixels
            val out = (e - 1f) * min(ax, ay)
            if (out <= -soft * 0.2f) continue
            val edge = ((out + soft * 0.2f) / soft).coerceIn(0f, 1f)
            // puffs of cloud
            val n = 0.5f + 0.25f * sin(wx * 0.071f + sin(wy * 0.043f) * 2f) + 0.25f * sin(wy * 0.089f + wx * 0.031f)
            val a = (edge * edge * (3 - 2 * edge)) * (0.86f + 0.12f * n)
            val shade = (232 + 18 * n).toInt().coerceIn(0, 255)
            px[y * fw + x] = ((a * 240).toInt().coerceIn(0, 255) shl 24) or ((shade - 10) shl 16) or ((shade - 4) shl 8) or 0xFF
        }
        return Bitmap.createBitmap(px, fw, fh, Bitmap.Config.ARGB_8888)
    }

    /** Someone stepped onto [t]: footprints in sand and snow, a splash in a puddle (or in the rain), grass shaken up. */
    private fun stepped(t: FieldMap.Tile, facing: Int, now: Long) {
        when {
            t.tallGrass -> rustle(t, now)
            weatherNow == MapAmbience.Weather.RAIN && scene.outdoor && t.behavior != FieldEffects.SAND -> {
                effects.stepped(t, facing, now)
                if (extras.art != null) effects.ripple(t.x, t.y, t.z, now)
            }
            weatherNow == MapAmbience.Weather.SNOW && scene.outdoor && t.behavior != FieldEffects.SAND -> effects.footprintIn(t, facing, snow = true, now = now)
            else -> effects.stepped(t, facing, now)
        }
    }

    /** A splash on the water at [t]: the game's, else our own. */
    private fun splash(t: FieldMap.Tile, now: Long) {
        if (extras.art != null) effects.splash(t.x, t.y, t.z, now)
        else scene.project(t.x, t.y, t.z)?.let { ambience.splash(it[0], it[1], now) }
    }

    /** A fish leaping out of the water and back in, to draw. */
    private fun addLeap(leap: MapGames.Leap, now: Long) {
        val sp = peopleSprites[leap.model] ?: return
        val f = leapFigures.getOrPut(leap.model) { figure(sp, shadow = false) }
        val u = ((now - leap.start).toDouble() / MapGames.LEAP_MS).coerceIn(0.0, 1.0)
        f.pose = MapWanderer.Pose(leap.tile.x, leap.tile.y, leap.tile.z, FollowerSprites.DOWN, ((now / 120) % 2).toInt(),
            sin(Math.PI * u) * 34.0, false, inWater = u < 0.12 || u > 0.88, aloft = u in 0.12..0.88)
        f.feetY = Float.MAX_VALUE
        drawOrder.add(f)
    }

    private fun drawEffects(canvas: Canvas, list: List<FieldEffects.Draw>) {
        for (d in list) {
            effectPaint.alpha = (d.alpha * 255).toInt().coerceIn(0, 255)
            effectRect.set(d.left, d.top, d.left + d.width, d.top + d.height)
            canvas.drawBitmap(bitmapOf(d.image), null, effectRect, effectPaint)
        }
    }

    /** Warm glows round the lit windows (steady) and lamps (flickering). */
    private fun drawGlows(canvas: Canvas, now: Long) {
        val g = scene.glows
        var i = 0
        var k = 0
        while (i + 3 < g.size) {
            val r = g[i + 2]
            if (g[i] + r < inView[0] || g[i] - r > inView[2] || g[i + 1] + r < inView[1] || g[i + 1] - r > inView[3]) { i += 4; k++; continue }
            val lamp = g[i + 3] >= FieldMap.LAMP
            val flicker = if (lamp) 0.8f + 0.12f * sin(now / 140.0 + k * 1.9).toFloat() + 0.08f * sin(now / 57.0 + k * 0.7).toFloat()
                else 0.88f + 0.12f * sin(now / 1_300.0 + k * 2.3).toFloat()
            glowPaint.alpha = ((if (lamp) 170 else 140) * flicker).toInt().coerceIn(0, 255)
            glowRect.set(g[i] - r, g[i + 1] - r * 0.85f, g[i] + r, g[i + 1] + r * 0.85f)
            canvas.drawBitmap(glowBitmap, null, glowRect, glowPaint)
            i += 4
            k++
        }
    }

    /** [who]'s emote bubble over its sprite as [p] last painted it. */
    private fun drawEmote(canvas: Canvas, who: Any, p: MapSpritePainter, now: Long) {
        val b = life?.emotes?.at(who, now) ?: return
        val cx = p.spriteLeft + p.size / 2f + if (b.emote == Emote.SLEEP) 6f else 0f
        val head = (p.spriteTop + p.headSpace).toFloat()
        drawEmoteAt(canvas, b.emote, b.start, cx, head, now)
    }

    /** An emote bubble with its point at ([cx], [head]) (picture pixels), up since [start]. */
    private fun drawEmoteAt(canvas: Canvas, emote: Emote, start: Long, cx: Float, head: Float, now: Long) {
        val frames = extras.art?.emote(emote) ?: emptyList()
        if (frames.isEmpty()) {
            if (emote == Emote.EXCLAIM) bubble(canvas, cx, head - 3f)
            return
        }
        val age = (now - start).coerceAtLeast(0)
        val img = frames[Emotes.frame(age, frames.size)]
        val left = (cx - img.width / 2f).roundToInt()
        var top = (head - 1f - img.bottom + Emotes.rise(age)).roundToInt()
        if (top + img.top < inView[1]) top = (inView[1] - img.top).roundToInt()
        canvas.drawBitmap(bitmapOf(img), left.toFloat(), top.toFloat(), paint)
    }

    private val treatBitmaps = HashMap<Int, Bitmap>()
    private fun treatBitmap(kind: Int): Bitmap = treatBitmaps.getOrPut(kind) {
        val sp = BerryArt.treat(kind)
        Bitmap.createBitmap(sp.frames[0], sp.size, sp.size, Bitmap.Config.ARGB_8888)
    }

    private var golden: Bitmap? = null
    /** A golden berry (the catching game's best). */
    private fun goldenBitmap(): Bitmap = golden ?: run {
        val sp = BerryArt.treat(3)
        val px = IntArray(sp.frames[0].size) { i ->
            val c = sp.frames[0][i]
            if (c ushr 24 == 0) 0 else {
                val l = (((c shr 16) and 0xFF) * 3 + ((c shr 8) and 0xFF) * 4 + (c and 0xFF)) / 8
                (c and 0xFF000000.toInt()) or ((minOf(255, l + 140)) shl 16) or ((minOf(255, l + 100)) shl 8) or (l / 3)
            }
        }
        Bitmap.createBitmap(px, sp.size, sp.size, Bitmap.Config.ARGB_8888).also { golden = it }
    }

    private val stills = HashMap<Int, Bitmap>()
    private val stillBottoms = HashMap<Int, Int>()
    /** A Pokémon's front picture (by its sprite number), for when it drops. */
    private fun pokemonStill(model: Int, now: Long): Bitmap? {
        val sp = peopleSprites[model] ?: return null
        val step = ((now / 120) % 2).toInt()
        return stills.getOrPut(model * 2 + step) { Bitmap.createBitmap(sp.frame(FollowerSprites.DOWN, step), sp.size, sp.size, Bitmap.Config.ARGB_8888) }
    }

    /** The row under a Pokémon's feet in its picture (from the top), so it lands on them. */
    private fun stillBottom(model: Int): Int = stillBottoms.getOrPut(model) {
        val sp = peopleSprites[model] ?: return@getOrPut 32
        val f = sp.frame(FollowerSprites.DOWN, 0)
        var lowest = 0
        for (y in sp.size - 1 downTo 0) if ((0 until sp.size).any { f[y * sp.size + it] ushr 24 != 0 }) { lowest = y; break }
        lowest + 1
    }

    /** A little "!" speech bubble with its point at ([x], [y]) (when the ROM's own bubbles can't be read). */
    private fun bubble(canvas: Canvas, x: Float, y: Float) {
        val left = x.roundToInt() - 4f
        val top = y.roundToInt() - 13f
        dotPaint.color = 0xFF303030.toInt()
        canvas.drawRect(left - 1, top - 1, left + 10, top + 11, dotPaint)
        canvas.drawRect(left + 3, top + 11, left + 6, top + 13, dotPaint)
        dotPaint.color = 0xFFFFFFFF.toInt()
        canvas.drawRect(left, top, left + 9, top + 10, dotPaint)
        canvas.drawRect(left + 4, top + 10, left + 5, top + 12, dotPaint)
        dotPaint.color = 0xFFE03030.toInt()
        canvas.drawRect(left + 4, top + 2, left + 5, top + 6, dotPaint)
        canvas.drawRect(left + 4, top + 7, left + 5, top + 8, dotPaint)
    }

    private companion object {
        /** How close the view can get. */
        const val MAX_ZOOM = 3f
        /** How much of the view is uncovered when a walk starts (its half size over the view's). */
        const val FOG_START = 0.75f

        val RAINBOW = intArrayOf(0xFFFF5050.toInt(), 0xFFFFA040.toInt(), 0xFFFFE860.toInt(), 0xFF70D870.toInt(), 0xFF60A8FF.toInt(), 0xFFA070E0.toInt())

        /** A rock of our own (when the ROM's can't be read): 16x16. */
        val OWN_ROCK: FollowerSprites.Sprite by lazy {
            val art = CritterArt.of(
                mapOf('k' to 0xFF3A342C.toInt(), 'd' to 0xFF6E665C.toInt(), 'm' to 0xFF8C8478.toInt(), 'l' to 0xFFB0A898.toInt(), 'c' to 0xFF4A4238.toInt()),
                "................", "................", "................", "................",
                "................", ".....kkkkkk.....", "....kllmmmdk....", "...klmmmcmmdk...",
                "..klmmmcmmmddk..", "..kmmmcmmmmmdk..", "..kmmcccmmmddk..", "..kdmmmmcmmddk..",
                "...kddmmmcddk...", "....kkdddddk....", ".....kkkkkk.....", "................",
            )
            FollowerSprites.Sprite(arrayOf(art.pixels), 16, 1)
        }

        /** A leaf in the catching game. */
        val FALLING_LEAF: CritterArt = CritterArt.of(
            mapOf('l' to 0xFF5AB040.toInt(), 'L' to 0xFF3C8A2C.toInt(), 'v' to 0xFF2A5A1E.toInt()),
            "...ll", "..lLl", ".lLvl", "lLvl.", "Lvl..", "v....",
        )

        /** Recolours the moving textures off the main thread. */
        val animator: ExecutorService by lazy {
            Executors.newSingleThreadExecutor { r -> Thread(r, "RouteMapAnimation").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 } }
        }

        /** A soft round shadow, stretched over each passing cloud. */
        val cloudShadow: Bitmap by lazy { softBlob(0xFF000000.toInt(), 0xB0000000.toInt()) }

        /** A soft white bank of mist. */
        val mistBank: Bitmap by lazy { softBlob(0xFFF4F6F8.toInt(), 0xA0F0F4F8.toInt()) }

        /** A warm soft glow, stretched over each lit window and lamp. */
        val glowBitmap: Bitmap by lazy { softBlob(0xFFFFE6A8.toInt(), 0x90FFC060.toInt()) }

        /** The game's effect pictures and our own little ones as bitmaps, made once each. */
        private val bitmaps = IdentityHashMap<Any, Bitmap>()

        fun bitmapOf(img: FieldEffectArt.Image): Bitmap = synchronized(bitmaps) {
            bitmaps.getOrPut(img) { Bitmap.createBitmap(img.pixels, img.width, img.height, Bitmap.Config.ARGB_8888) }
        }

        fun bitmapOf(art: CritterArt): Bitmap = synchronized(bitmaps) {
            bitmaps.getOrPut(art) { Bitmap.createBitmap(art.pixels, art.width, art.height, Bitmap.Config.ARGB_8888) }
        }

        private fun softBlob(inner: Int, mid: Int): Bitmap {
            val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.shader = RadialGradient(32f, 32f, 32f, intArrayOf(inner, mid, inner and 0x00FFFFFF),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            Canvas(bmp).drawCircle(32f, 32f, 32f, p)
            return bmp
        }
    }

    // ---- touching it, and its buttons ----

    /**
     * A tap at ([x], [y]) in the box: on the Pokémon it hops and true comes back (the caller
     * plays its cry); on someone it goes to meet them; on today's visitor, it's pleased; on a
     * rock or a tree, it goes to smash or headbutt it; anywhere else it walks over there if it
     * can. A game that's on (or a tap that starts one: a wild Pokémon, the water, the berry
     * patch) may take it instead.
     */
    fun tap(x: Float, y: Float, now: Long): Boolean {
        val wd = wanderer ?: return false
        val (px, py) = toPicture(x, y)
        val l = life
        val g = games
        if (painter?.hit(px, py) == true && g?.game != MapGames.Game.FISHING && g?.game !in MapGames.PLAYED) {
            wd.hop(now)
            l?.patted(now)
            return true
        }
        l?.interacted(now)
        // today's visitor
        val v = visitor
        if (v != null && visitorFigure?.let { it.painted && it.painter.hit(px, py) } == true) {
            v.patted(now)
            return false
        }
        val hit = people.values.filter { it.walker != null && it.painted && it.painter.hit(px, py) }.maxByOrNull { it.feetY }?.walker
        val onBerry = berryTile?.let { b -> berryFigures.values.any { it.painter.hit(px, py) } && scene.project(b.x, b.y, b.z) != null } == true
        val ground = scene.tileNear(px, py, scene.tilePixels * 2f)?.takeIf { !fogged(it.gx, it.gy) }
        val water = if (ground != null && scene.tileNear(px, py, scene.tilePixels * 0.9f) != null) null else waterNear(px, py)
        if (g?.tap(hit, water, onBerry, now, ground) == true) return false
        // on someone: it goes up to them and looks at them, and they turn to it
        hit?.let { w ->
            val t = w.next ?: w.at
            val from = wd.next ?: wd.at
            val spots = intArrayOf(0, 1, 0, -1, 1, 0, -1, 0)
            val besides = (0 until 4).mapNotNull { i -> scene.tile(t.gx + spots[i * 2], t.gy + spots[i * 2 + 1]) }
                .sortedBy { abs(it.gx - from.gx) + abs(it.gy - from.gy) }
            for (b in besides) if (wd.goTo(b, now, t)) return false
            return false
        }
        // on the item ball: it goes and picks it up
        if (l?.ball != null && ballFigure?.let { it.painted && it.painter.hit(px, py) } == true) {
            l.fetchBall(now)
            return false
        }
        val play = g?.play
        if (g != null && play != null && g.game == null) {
            // on a rock: it goes to smash it
            rockFigures.entries.firstOrNull { (r, f) -> r.standing(now) && f.painted && f.painter.hit(px, py) }?.let { (r, _) ->
                if (g.smash(r, now)) return false
            }
            // on a tree's leaves (not the ground in front of it): it goes to headbutt it
            if (scene.tileNear(px, py, scene.tilePixels * 0.6f) == null) {
                scene.trees.firstOrNull { t -> px >= t.left + 3 && px <= t.right - 3 && py >= t.top && py <= t.top + (t.bottom - t.top) * 0.7f && !outOfSight(t.front) }
                    ?.let { t -> if (g.headbutt(t, now)) return false }
            }
        }
        // on a door: it goes up to it (and the door opens)
        doorNear(px, py)?.let { door ->
            scene.tile(door.gx, door.gy + 1)?.let { front -> if (wd.goTo(front, now, door)) return false }
        }
        ground?.let { wd.goTo(it, now) }
        return false
    }

    /** The door drawn at picture position ([px], [py]), if any. */
    private fun doorNear(px: Float, py: Float): FieldMap.Tile? = scene.doors.map { it.first }.firstOrNull { d ->
        val front = scene.tile(d.gx, d.gy + 1)
        val p = if (front == null) null else scene.project(d.x, front.y, d.z)
        p != null && px in p[0] - 10f..p[0] + 10f && py in p[1] - 28f..p[1] + 6f
    }

    /** The water tile drawn nearest to picture position ([px], [py]), within a tile and a half. */
    private fun waterNear(px: Float, py: Float): FieldMap.Tile? {
        var best: FieldMap.Tile? = null
        var bestD = (scene.tilePixels * 1.5f).let { it * it }
        for (t in scene.water.values) {
            val p = scene.project(t.x, t.y, t.z) ?: continue
            val d = (p[0] - px) * (p[0] - px) + (p[1] - py) * (p[1] - py)
            if (d < bestD) { bestD = d; best = t }
        }
        return best
    }

    /** The tree whose tile is ([gx], [gy]) that can be headbutted from [from]. */
    private fun treeAt(gx: Int, gy: Int, from: FieldMap.Tile): FieldMap.Tree? =
        scene.trees.firstOrNull { it.tile.gx == gx && it.tile.gy == gy && it.front.gx == from.gx && it.front.gy == from.gy }
            ?: scene.trees.firstOrNull { it.tile.gx == gx && it.tile.gy == gy }?.let { t -> FieldMap.Tree(t.tile, from, t.left, t.top, t.right, t.bottom) }

    /**
     * The A button: whatever the Pokémon is facing. A game takes it first (reeling in a bite,
     * throwing a ball); then someone there is greeted, today's visitor patted, a tree headbutted,
     * a rock smashed, the item ball picked up, a sign read, the water fished; else a happy hop
     * (true: play its cry).
     */
    fun action(now: Long): Boolean {
        val g = games
        if (g?.action(now) == true) return false
        val wd = wanderer ?: return false
        val l = life
        if (l?.asleep == true) { l.patted(now); return false }
        l?.interacted(now)
        if (!wd.idle || g?.game != null) {
            wd.hop(now)
            l?.patted(now)
            return true
        }
        val (fx, fy) = wd.facingTile()
        // someone there: they turn to it and say hi
        crowd.at(fx, fy)?.let { w ->
            if (w.wild && g != null && g.tap(w, null, false, now)) return false
            l?.emotes?.show(wd, Emote.EXCLAIM, now, 900)
            if (!w.wild) l?.emotes?.show(w, if (w.person.trainer) Emote.EXCLAIM else Emote.SMILE, now + 300, 1_400)
            wd.hop(now)
            return true
        }
        visitor?.let { v -> if (v.tiles().any { it.gx == fx && it.gy == fy }) { v.patted(now); return false } }
        if (g != null) {
            treeAt(fx, fy, wd.at)?.let { t -> if (g.headbutt(t, now)) return false }
            g.play.rockAt(fx, fy, now)?.let { r -> if (g.smash(r, now)) return false }
        }
        l?.ball?.let { b -> if (b.tile.gx == fx && b.tile.gy == fy) { l.fetchBall(now); return false } }
        if (scene.signs.any { it.gx == fx && it.gy == fy }) {
            l?.emotes?.show(wd, Emote.SILENT, now, 1_800)
            wd.pauseUntil(now + 2_200)
            return false
        }
        val berry = berryTile
        if (berry != null && berry.gx == fx && berry.gy == fy && g != null && g.start(MapGames.Game.BERRY, now)) return false
        if (scene.tile(fx, fy) == null) scene.water[FieldMap.Scene.key(fx, fy)]?.let { water ->
            if (g != null && g.fishHere(water, now)) return false
        }
        wd.hop(now)
        l?.patted(now)
        return true
    }

    /**
     * Walks the Pokémon about by hand (D-pad, stick): see [MapWanderer.walk]. Pressing on into a
     * tree or a rock it's facing headbutts or smashes it.
     */
    fun walk(direction: Int, held: Boolean, now: Long) {
        life?.interacted(now)
        val g = games
        if (g?.game == MapGames.Game.FISHING) g.stop(now)
        val wd = wanderer ?: return
        if (wd.idle && wd.facing == direction && g != null && g.game == null) {
            val (fx, fy) = wd.facingTile()
            if (scene.tile(fx, fy) == null) {
                treeAt(fx, fy, wd.at)?.let { t -> if (g.headbutt(t, now)) return }
            }
            g.play.rockAt(fx, fy, now)?.let { r -> if (g.smash(r, now)) return }
        }
        wd.walk(direction, held, now)
    }

    fun release() {
        wanderer?.release()
    }

    fun hop(now: Long) {
        wanderer?.hop(now)
    }

    /** Back to roaming on its own straight away (the player let go of the controls for good). */
    fun letGo(now: Long) {
        wanderer?.letGo(now)
    }

    /**
     * Parts of the box the cards on top cover: the Pokémon would rather not head there, so it
     * stays where it can be seen (and wild Pokémon don't come out there).
     */
    fun setCovered(rects: List<RectF>) {
        covered = rects.map { RectF(it) }
        updateSight()
    }

    /** Where the Pokémon would rather not go: out of sight, unless the view follows it about. */
    private fun updateSight() {
        wanderer?.avoid = { t ->
            val follow = following || zoomTarget > 1.01f
            if (follow) scene.project(t.x, t.y, t.z)?.let { coveredAt(it) || foggedAt(it[0], it[1]) } ?: true else outOfSight(t)
        }
        crowd.spawnOk = { t -> !outOfSight(t) }
    }

    init {
        updateSight()
    }

    /** A picture of the place right now, [w] x [h] (as it's drawn in the box). */
    fun capture(w: Int, h: Int, now: Long): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        draw(Canvas(b), w.toFloat(), h.toFloat(), now)
        return b
    }
}

/**
 * Makes [RouteMap]s: reads the ROM last started in melonDS (as [PokemonCries] does), draws the
 * place for the walk's course once, and keeps it until the course, the light of the day, the
 * screen size or the Pokémon change (one for the walker screen, one for the wallpaper). When the
 * ROM can't be read (never started, moved, zipped) there's no map and the painted landscape stays.
 */
object RouteMaps {
    private const val TAG = "RouteMaps"
    private const val PREFS = "pokewalker_route_map"
    /** Who a map is drawn for: the walker screen, the live wallpaper. */
    const val SCREEN = "screen"
    const val WALLPAPER = "wallpaper"

    /** Fish you can catch, by the kind of place. */
    private fun fishFor(kind: FieldMap.Kind): IntArray = when (kind) {
        FieldMap.Kind.LAKE -> intArrayOf(129, 118, 60, 119)
        FieldMap.Kind.SEA, FieldMap.Kind.BEACH -> intArrayOf(72, 129, 98, 116, 222)
        FieldMap.Kind.CAVE, FieldMap.Kind.ICE -> intArrayOf(129, 118, 194)
        else -> intArrayOf(129, 60, 118)
    }

    /** The berry patch's state for [course], kept with the app's settings. */
    private class PrefsBerryStore(context: Context, course: String) : MapGames.BerryStore {
        private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        private val key = "berry_" + course.lowercase().filter { it.isLetterOrDigit() }
        override var planted: Long
            get() = prefs.getLong("${key}_planted", -1L)
            set(v) { prefs.edit().putLong("${key}_planted", v).apply() }
        override var kind: Int
            get() = prefs.getInt("${key}_kind", 0)
            set(v) { prefs.edit().putInt("${key}_kind", v).apply() }
        override var picked: Int
            get() = prefs.getInt("berries_picked", 0)
            set(v) { prefs.edit().putInt("berries_picked", v).apply() }
    }

    /** The catching game's best score, kept with the app's settings. */
    private class PrefsScoreStore(context: Context) : MapGames.ScoreStore {
        private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        override var best: Int
            get() = prefs.getInt("catch_best", 0)
            set(v) { prefs.edit().putInt("catch_best", v).apply() }
    }

    private val cached = HashMap<String, RouteMap>()
    /** The game's field effects, read once for the ROM they're from. */
    @Volatile private var art: Pair<String, FieldEffectArt?>? = null
    @Volatile private var failedRom: String? = null
    @Volatile private var failedKey: String? = null
    /** Until when not to try [failedKey] again (elapsed realtime; Long.MAX_VALUE: not this session). */
    @Volatile private var failedUntil = 0L

    /**
     * For a [w] x [h] view: the view's size in picture pixels (about 22 tiles across when wide,
     * 13 when tall), how many screen pixels a picture pixel is, and the whole picture's size (twice
     * the view each way, to pan across, within a million pixels).
     */
    fun pictureSize(w: Int, h: Int): IntArray {
        val across = if (w >= h) 22f else 13f
        val scale = max(2, (w / (across * FieldMap.TILE.toFloat())).roundToInt())
        val vw = ceil(w / scale.toFloat()).toInt()
        val vh = ceil(h / scale.toFloat()).toInt()
        var pw = vw * 2
        var ph = vh * 2
        val budget = 1_000_000.0 / (pw.toDouble() * ph)
        if (budget < 1.0) {
            val k = sqrt(budget)
            pw = max(vw, (pw * k).toInt())
            ph = max(vh, (ph * k).toInt())
        }
        pw = min(pw, 2048).coerceAtLeast(vw)
        ph = min(ph, 2048).coerceAtLeast(vh)
        return intArrayOf(vw, vh, scale, pw, ph)
    }

    private fun minuteNow(): Int = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }

    /** The map that's ready for this walk right now, without making one. */
    fun current(routeName: String, routeImage: Int, party: WalkParty, w: Int, h: Int, context: Context, client: String = SCREEN): RouteMap? {
        val c = synchronized(cached) { cached[client] } ?: return null
        val uri = Pokewalker.lastGameUri(context) ?: return null
        return if (c.key == sceneKey(uri, routeName, routeImage, w, h) && c.party == party && lightOk(c)) c else null
    }

    private fun sceneKey(uri: String, routeName: String, routeImage: Int, w: Int, h: Int): String {
        val course = FieldMap.courseFor(routeName, routeImage)
        val size = pictureSize(w, h)
        return "$uri|${course.name}|${size.joinToString("x")}|${LocalDays.today()}"
    }

    private fun lightOk(m: RouteMap) = m.scene.lightKey < 0 || m.scene.lightKey == FieldMap.dayKey(minuteNow())

    /**
     * The map for the walk on [routeName] in a [w] x [h] view for [client], made (in the
     * background) only when something changed; null when it can't be made.
     */
    suspend fun get(context: Context, routeName: String, routeImage: Int, party: WalkParty, w: Int, h: Int, client: String = SCREEN): RouteMap? {
        if (w <= 0 || h <= 0) return null
        val uri = Pokewalker.lastGameUri(context) ?: return null
        if (uri == failedRom) return null
        val key = sceneKey(uri, routeName, routeImage, w, h)
        val c = synchronized(cached) { cached[client] }
        val sameScene = c != null && c.key == key && lightOk(c)
        if (sameScene && c!!.party == party) return c
        if (!sameScene && key == failedKey && SystemClock.elapsedRealtime() < failedUntil) return null
        val app = context.applicationContext
        // the other one may have just this picture already
        val shared = if (sameScene) c else synchronized(cached) { cached.values.firstOrNull { it.key == key && lightOk(it) } }
        val made = withContext(Dispatchers.IO) { make(app, uri, key, routeName, routeImage, party, w, h, shared, c) }
        if (made != null) synchronized(cached) { cached[client] = made }
        return made
    }

    /** Lets go of [client]'s map (the wallpaper went away). */
    fun forget(client: String) {
        synchronized(cached) { cached.remove(client) }
    }

    private fun make(
        context: Context, uri: String, key: String, routeName: String, routeImage: Int, party: WalkParty,
        w: Int, h: Int, sameScene: RouteMap?, previous: RouteMap?,
    ): RouteMap? {
        val pokemon = party.walking
        val course = FieldMap.courseFor(routeName, routeImage)
        val size = pictureSize(w, h)
        return try {
            context.contentResolver.openFileDescriptor(Uri.parse(uri), "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).channel.use { channel ->
                    val src = object : CryArchive.Source {
                        override val size = channel.size()
                        override fun read(pos: Long, len: Int): ByteArray {
                            val buf = ByteBuffer.allocate(len)
                            var at = pos
                            while (buf.hasRemaining()) {
                                val n = channel.read(buf, at)
                                if (n < 0) throw Nitro.FormatException("short read")
                                at += n
                            }
                            return buf.array()
                        }
                    }
                    val t0 = System.nanoTime()
                    val scene = sameScene?.scene ?: FieldMap.render(src, course, size[3], size[4], minuteNow(), size[0], size[1]) { Log.i(TAG, it) }
                    val sprite = pokemon?.let { p ->
                        if (sameScene != null && sameScene.pokemon == p) sameScene.sprite
                        else runCatching { FollowerSprites.load(src, p.species, p.form, p.female, p.shiny) }.getOrNull()
                    }
                    val game = gameName(src)
                    // the people's sprites, once per sprite
                    val peopleSprites = HashMap<Int, FollowerSprites.Sprite>(sameScene?.peopleSprites ?: emptyMap())
                    for (model in scene.people.map { it.model }.distinct()) {
                        if (model !in peopleSprites) runCatching { FollowerSprites.loadModel(src, model) }.getOrNull()?.let { peopleSprites[model] = it }
                    }
                    // Pokémon sprites (by their overworld sprite number, like the people's)
                    fun kind(p: WalkingPokemon, swims: Boolean): MapCrowd.WildKind? {
                        val model = FollowerSprites.spriteFor(p.species, p.form, p.female)
                        if (model !in peopleSprites) {
                            runCatching { FollowerSprites.load(src, p.species, p.form, p.female, p.shiny) }.getOrNull()?.let { peopleSprites[model] = it } ?: return null
                        }
                        return MapCrowd.WildKind(model, p.species, swims)
                    }
                    fun plain(species: Int, swims: Boolean = false) = kind(WalkingPokemon(species, 0, false, false), swims)
                    fun types(species: Int) = runCatching { PokemonTypes.of(src, species) }.getOrDefault(emptySet())
                    val hasWater = scene.water.isNotEmpty()
                    val grassy = scene.tiles.values.count { it.tallGrass } >= 8
                    // the course's Pokémon come out of the grass (Water types out of the water), or about a cave
                    val wild = if (grassy || !scene.outdoor || hasWater) party.route.mapNotNull { p ->
                        val water = PokemonTypes.WATER in types(p.species) && hasWater
                        if (!water && !grassy && scene.outdoor) null else kind(p, water)
                    } else emptyList()
                    // the route's Pokémon on land too (for hide and seek and the Poké Radar)
                    val landWild = party.route.mapNotNull { p -> if (wild.any { it.species == p.species }) null else kind(p, false) }
                    val fish = if (hasWater) fishFor(course.kind).toList().mapNotNull { plain(it, true) } else emptyList()
                    val bugs = if (grassy && scene.outdoor) MapGames.BUGS.toList().mapNotNull { plain(it) } else emptyList()
                    val unown = if (course.kind == FieldMap.Kind.CAVE || course.kind == FieldMap.Kind.ICE) {
                        (0 until 26).mapNotNull { kind(WalkingPokemon(201, it, false, false), false)?.model }.toIntArray()
                    } else IntArray(0)
                    val buddy = party.caught.firstOrNull()
                    val buddySprite = buddy?.let { b -> runCatching { FollowerSprites.load(src, b.species, b.form, b.female, b.shiny) }.getOrNull() }
                    // birds flying over outside (owls at night), and a Magikarp in the water
                    val birds = if (!scene.outdoor) emptyList() else (if (scene.timeOfDay == 3) MapLife.NIGHT_BIRDS else MapLife.DAY_BIRDS)
                        .toList().mapNotNull { plain(it) }
                    val magikarp = if (scene.water.size >= 6 && course.kind != FieldMap.Kind.ICE && course.kind != FieldMap.Kind.VOLCANO) {
                        plain(MapLife.MAGIKARP, true)
                    } else null
                    // today's visitor, and who drops out of trees and is found under rocks (a few of them, the same each time)
                    val cal = Calendar.getInstance()
                    val occasions = SpecialDates.on(cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH), minuteNow())
                    val visitor = if (pokemon != null) plain(DailyVisitor.species(LocalDays.today(), occasions)) else null
                    val pick = java.util.Random(course.name.hashCode().toLong())
                    val headbutt = if (scene.trees.isEmpty()) emptyList() else
                        MapPlay.HEADBUTT_SPECIES.toList().shuffled(pick).take(4).mapNotNull { plain(it) }
                    val smash = if (course.kind == FieldMap.Kind.TOWN || course.kind == FieldMap.Kind.SEA) emptyList() else
                        MapPlay.SMASH_SPECIES.toList().mapNotNull { plain(it) }
                    val rockSprite = runCatching { FollowerSprites.loadStill(src, FieldPeople.modelFor(FieldPeople.BREAKROCK_SPRITE)) }.getOrNull()
                    val effectArt = artFor(uri, src)
                    val extras = MapExtras(
                        types = pokemon?.let { types(it.species) } ?: emptySet(),
                        buddy = buddy, buddySprite = buddySprite,
                        wild = wild + landWild, fish = fish, bugs = bugs, unown = unown,
                        berryStore = if (scene.outdoor) PrefsBerryStore(context, course.name) else null,
                        art = effectArt, items = party.items.toIntArray(), birds = birds, magikarp = magikarp,
                        visitor = visitor, headbutt = headbutt, smash = smash, rockSprite = rockSprite,
                        scores = PrefsScoreStore(context),
                    )
                    if (sameScene == null) {
                        PokewalkerNative.note("Route map: ${course.place} from $game for ${course.name}, " +
                            "${scene.tiles.size} tiles to explore, ${scene.people.count { it.model in peopleSprites }} people, " +
                            "${wild.size} wild Pokémon, ${scene.props?.props?.size ?: 0} moving buildings (${scene.doors.size} doors), " +
                            "${scene.glows.size / 4} lights, ${scene.trees.size} trees, ${scene.rocks.size} rocks, ${scene.hiding.size} places to hide, " +
                            "${if (effectArt != null) "the game's effects" else "no effects"}, " +
                            "visitor ${visitor?.species ?: 0} (${(System.nanoTime() - t0) / 1_000_000} ms)")
                    }
                    RouteMap(scene, sprite, game, size[2], key, pokemon, previous, peopleSprites, extras, party)
                }
            }
        } catch (e: FieldMap.MapException) {
            if (e.wholeRom) failedRom = uri else fail(key, Long.MAX_VALUE)
            Log.w(TAG, "No route map: ${e.message}")
            PokewalkerNative.note("Route map not available: ${e.message}")
            null
        } catch (e: Nitro.FormatException) {
            fail(key, Long.MAX_VALUE)
            Log.w(TAG, "No route map: ${e.message}")
            PokewalkerNative.note("Route map not available: ${e.message}")
            null
        } catch (e: Exception) {
            // the ROM can't be opened right now (moved, no permission, card not mounted): try again in a minute
            if (key != failedKey) PokewalkerNative.note("Route map: can't read the game (${e.javaClass.simpleName}: ${e.message})")
            fail(key, SystemClock.elapsedRealtime() + 60_000)
            Log.w(TAG, "Could not draw the route map", e)
            null
        } catch (e: OutOfMemoryError) {
            fail(key, Long.MAX_VALUE)
            Log.w(TAG, "Not enough memory for the route map", e)
            null
        }
    }

    private fun artFor(uri: String, src: CryArchive.Source): FieldEffectArt? {
        art?.let { if (it.first == uri) return it.second }
        val a = runCatching { FieldEffectArt.load(src) }.getOrNull()
        art = uri to a
        return a
    }

    private fun fail(key: String, until: Long) {
        failedKey = key
        failedUntil = until
    }

    /** HeartGold or SoulSilver, from the ROM's game code (IPK... / IPG...). */
    private fun gameName(src: CryArchive.Source): String {
        val code = runCatching { String(src.read(0x0C, 4), Charsets.ISO_8859_1) }.getOrDefault("")
        return when {
            code.startsWith("IPK") -> "HeartGold"
            code.startsWith("IPG") -> "SoulSilver"
            else -> "HeartGold/SoulSilver"
        }
    }

    /**
     * How much of the route the walk has uncovered: by the steps walked on this stroll, all of it
     * at [REVEAL_STEPS]. The walker doesn't keep a stroll's own steps, so the count starts from
     * the day's first step when the stroll is first seen here (today's steps count already).
     */
    fun revealed(context: Context, strollStartUnix: Long, totalSteps: Long, todaySteps: Long): Float {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getLong("reveal_stroll", Long.MIN_VALUE) != strollStartUnix) {
            prefs.edit().putLong("reveal_stroll", strollStartUnix).putLong("reveal_from", (totalSteps - todaySteps).coerceAtLeast(0)).apply()
        }
        val from = prefs.getLong("reveal_from", totalSteps)
        return ((totalSteps - from).coerceAtLeast(0) / REVEAL_STEPS.toFloat()).coerceIn(0f, 1f)
    }

    const val REVEAL_STEPS = 5_000

    /** The route map's settings: route music, the view following the Pokémon, uncovering the route as you walk. */
    const val KEY_MUSIC = "pokewalker_route_music"
    const val KEY_FOLLOW = "pokewalker_follow_camera"
    const val KEY_REVEAL = "pokewalker_reveal_map"
}
