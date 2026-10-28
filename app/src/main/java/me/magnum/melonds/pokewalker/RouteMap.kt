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
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

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
)

/**
 * The real place of the walk, from the player's own HeartGold/SoulSilver ROM ([FieldMap]), with
 * the walking Pokémon exploring it ([MapWanderer], [MapSpritePainter]) among the townsfolk and
 * trainers who live there ([FieldPeople], [MapCrowd]), the course's wild Pokémon coming out of
 * the grass, a Pokémon caught on the walk following along ([MapBuddy]), and little games
 * ([MapGames]). The game's own moving textures play on it (the sea, waves on the shore, rivers,
 * flowers: [SceneAnimation]), its windmills turn and doors open ([SceneProps]), windows and
 * lamps glow after dark, little things and the weather move over it ([MapAmbience]), and the
 * game's own effects ([FieldEffects]) and life ([MapLife]: item balls, a tossed berry, birds
 * flying over, sleep, moods, emote bubbles) go on. It is drawn in the DS's own pixels, made
 * [scale] times bigger to fill the screen.
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

    /** The berry patch: open ground near the middle of the picture, the same spot each time. */
    private val berryTile: FieldMap.Tile? = if (!scene.outdoor || extras.berryStore == null || wanderer == null) null else {
        val homes = scene.people.map { it.home }
        scene.tiles.values.filter { t ->
            val p = scene.project(t.x, t.y, t.z)
            !t.tallGrass && p != null && p[0] in scene.width * 0.2f..scene.width * 0.8f && p[1] in scene.height * 0.3f..scene.height * 0.85f &&
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

    /** Someone or something on tile ([gx], [gy]): people, the berry patch, an item ball, a berry thrown there. */
    private fun taken(gx: Int, gy: Int): Boolean {
        val berry = berryTile
        return crowd.occupied(gx, gy) || (berry != null && berry.gx == gx && berry.gy == gy) || life?.blocks(gx, gy) == true
    }

    init {
        wanderer?.let { w ->
            val berry = berryTile
            w.blocked = { gx, gy -> taken(gx, gy) }
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
                    (bd == null || ((bd.at.gx != t.gx || bd.at.gy != t.gy) && bd.next?.let { it.gx == t.gx && it.gy == t.gy } != true))
            }
            // the same walk drawn again: it carries on as it was (asleep, say)
            previous?.life?.let { l.carryOn(it) }
        }
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
    private val people = HashMap<MapCrowd.Walker, Figure>()
    private val berryFigures = HashMap<Int, Figure>()
    private val leapFigures = HashMap<Int, Figure>()
    private val flyerFigures = HashMap<Int, Figure>()
    private val ballSprite: FollowerSprites.Sprite? = extras.art?.frames("monstarball")?.firstOrNull()?.let { img ->
        FollowerSprites.Sprite(arrayOf(img.pixels), img.width, 1).takeIf { img.width == img.height }
    }
    private val ballFigure = ballSprite?.let { figure(it) }
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
    private val dotPaint = Paint()
    private val cloudPaint = Paint().apply { isFilterBitmap = true }
    private val cloudRect = RectF()
    private val linePaint = Paint().apply { color = 0xC8F0F0F0.toInt(); strokeWidth = 0.6f; isAntiAlias = true }
    private val tintPaint = Paint()
    private val darkPaint = Paint()
    private val effectPaint = Paint().apply { isFilterBitmap = false }
    private val effectRect = RectF()
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

    /** Today's steps reached the goal: it's delighted. */
    fun celebrate(now: Long) {
        life?.celebrate(now)
    }

    /** Throws a berry towards box point ([x], [y]) for the Pokémon to eat; false when it can't be. */
    fun toss(x: Float, y: Float, now: Long): Boolean {
        val l = life ?: return false
        val px = (x - offsetX) / scale
        val py = (y - offsetY) / scale
        val tile = scene.tileNear(px, py, scene.tilePixels * 2.5f) ?: return false.also { l.interacted(now) }
        return l.toss(tile, scene.width / 2f, scene.height + 8f, now)
    }

    /** Throws a berry a few steps from the Pokémon. */
    fun tossNear(now: Long): Boolean = life?.tossNear(scene.width / 2f, scene.height + 8f, now) == true

    /** Whether the Pokémon is walking (draw often then). */
    fun moving(now: Long) = wanderer?.pose(now)?.moving == true

    /** The games there are here, which one is on, and what to tell the player about it. */
    fun availableGames(): List<MapGames.Game> = games?.available() ?: emptyList()
    val gameOn: MapGames.Game? get() = games?.game
    fun gameLine(now: Long): MapGames.Line? = games?.lineAt(now)
    fun startGame(g: MapGames.Game, now: Long): Boolean {
        life?.interacted(now)
        return games?.start(g, now) == true
    }
    fun stopGame(now: Long) { games?.stop(now) }

    /** Species whose cry should play now (something was caught, a berry eaten). */
    fun takeCries(): List<Int> {
        val out = ArrayList<Int>()
        games?.let { out.addAll(it.cries); it.cries.clear() }
        life?.let { l ->
            for (c in l.cries) if (c == MapLife.OWN_CRY) { if (species > 0) out.add(species) } else out.add(c)
            l.cries.clear()
        }
        return out
    }

    /** Draws the place and the Pokémon into a [w] x [h] box, the picture in the middle. */
    fun draw(canvas: Canvas, w: Float, h: Float, now: Long) {
        offsetX = ((w - scene.width * scale) / 2f).roundToInt().toFloat()
        offsetY = ((h - scene.height * scale) / 2f).roundToInt().toFloat()
        canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.scale(scale.toFloat(), scale.toFloat())
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
        }
        // lit windows and lamps after dark
        drawGlows(canvas, now)

        val wd = wanderer
        drawOrder.clear()
        pokemonFigure?.painted = false
        buddyFigure?.painted = false
        for (f in people.values) f.painted = false
        if (wd != null) {
            wd.update(now)
            val pose = wd.pose(now)
            pokemonFigure?.let { f ->
                f.pose = pose
                f.feetY = scene.project(pose.x, pose.y, pose.z)?.get(1) ?: 0f
                drawOrder.add(f)
            }
            scene.project(pose.x, pose.y, pose.z)?.let { lightX = it[0]; lightY = it[1] - 10f }
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
                        at.tallGrass -> scene.project(at.x, at.y, at.z)?.let { ambience.rustle(it[0], it[1], now) }
                        else -> effects.stepped(at, wd.facing, now)
                    }
                }
                lastTile = at
            }
            if (PokemonTypes.FIRE in extras.types && pose.moving && now - sparkAt > 110) {
                sparkAt = now
                scene.project(pose.x, pose.y, pose.z)?.let { ambience.sparks(it[0], it[1] - 2f, now) }
            }
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
        val others = ArrayList<FieldMap.Tile>(4)
        bd?.let { others.add(it.at); it.next?.let { n -> others.add(n) } }
        berryTile?.let { others.add(it) }
        life?.let { others.addAll(it.tiles()) }
        crowd.update(now, wd?.at, wd?.next, wd?.facing ?: 0, wd != null && wd.next == null, others)
        games?.update(now)
        val l = life
        l?.update(now, minute, weatherNow)
        if (l != null && l.lines.isNotEmpty()) {
            for (line in l.lines) games?.announce(line, now)
            l.lines.clear()
        }
        for ((t, _) in crowd.popped) {
            when {
                scene.tile(t.gx, t.gy) == null -> splash(t, now)
                t.tallGrass -> scene.project(t.x, t.y, t.z)?.let { ambience.rustle(it[0], it[1], now) }
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
        for (wk in crowd.walkers) {
            val sp = peopleSprites[wk.person.model] ?: continue
            val f = people.getOrPut(wk) { figure(sp, wk, shadow = !wk.person.swimming) }
            val pose = wk.pose(now)
            f.pose = pose
            f.feetY = scene.project(pose.x, pose.y, pose.z)?.get(1) ?: continue
            drawOrder.add(f)
            if (wk.at !== f.lastTile) {
                if (f.lastTile != null && !wk.person.swimming) stepped(wk.at, wk.facing, now)
                f.lastTile = wk.at
            }
        }
        // the berry patch
        val g = games
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
        // birds flying over, on top of everything
        if (l != null) for (fl in l.flyers) {
            val sp = peopleSprites[fl.kind.model] ?: continue
            val pose = fl.pose(now) ?: continue
            val f = flyerFigures.getOrPut(fl.kind.model) { figure(sp) }
            f.pose = pose
            f.feetY = Float.MAX_VALUE
            drawOrder.add(f)
        }

        // footprints and rings under everyone
        effects.ground(now, groundDraws)
        drawEffects(canvas, groundDraws)
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

        ambience.frame(now, minute, dots, clouds)
        for (c in clouds) {
            cloudRect.set(c.x - c.rx, c.y - c.ry, c.x + c.rx, c.y + c.ry)
            cloudPaint.alpha = (c.alpha * 255).toInt().coerceIn(0, 255)
            canvas.drawBitmap(if (c.mist) mistBank else cloudShadow, null, cloudRect, cloudPaint)
        }
        if (ambience.tint != 0) {
            tintPaint.color = ambience.tint
            canvas.drawRect(0f, 0f, scene.width.toFloat(), scene.height.toFloat(), tintPaint)
        }
        for (d in dots) {
            dotPaint.color = d.color
            val x = d.x.roundToInt().toFloat()
            val y = d.y.roundToInt().toFloat()
            canvas.drawRect(x, y, x + d.size, y + d.tall, dotPaint)
        }
        if (ambience.darkness > 0f) {
            // a dark cave: only what's near the Pokémon shows
            val a = (ambience.darkness * 255).toInt().coerceIn(0, 255)
            darkPaint.shader = RadialGradient(lightX, lightY, 78f, intArrayOf(0x00000000, (a / 3) shl 24, a shl 24),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, scene.width.toFloat(), scene.height.toFloat(), darkPaint)
        }
        // emote bubbles, over it all
        if (l != null) {
            for ((wk, f) in people) if (f.painted) drawEmote(canvas, wk, f.painter, now)
            if (bd != null) buddyFigure?.takeIf { it.painted }?.let { drawEmote(canvas, bd, it.painter, now) }
            if (wd != null) pokemonFigure?.takeIf { it.painted }?.let { drawEmote(canvas, wd, it.painter, now) }
        }
        canvas.restore()
    }

    /** Someone stepped onto [t]: footprints in sand and snow, a splash in a puddle, grass shaken up. */
    private fun stepped(t: FieldMap.Tile, facing: Int, now: Long) {
        if (t.tallGrass) scene.project(t.x, t.y, t.z)?.let { ambience.rustle(it[0], it[1], now) } else effects.stepped(t, facing, now)
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
        val frames = extras.art?.emote(b.emote) ?: emptyList()
        val cx = p.spriteLeft + p.size / 2f
        val head = (p.spriteTop + p.headSpace).toFloat()
        if (frames.isEmpty()) {
            if (b.emote == Emote.EXCLAIM) bubble(canvas, cx, head - 3f)
            return
        }
        val age = now - b.start
        val img = frames[Emotes.frame(age, frames.size)]
        // a sleeper's "Zz" floats a little to the side
        val left = (cx - img.width / 2f + if (b.emote == Emote.SLEEP) 6f else 0f).roundToInt()
        var top = (head - 1f - img.bottom + Emotes.rise(age)).roundToInt()
        if (top + img.top < 0) top = -img.top
        canvas.drawBitmap(bitmapOf(img), left.toFloat(), top.toFloat(), paint)
    }

    private val treatBitmaps = HashMap<Int, Bitmap>()
    private fun treatBitmap(kind: Int): Bitmap = treatBitmaps.getOrPut(kind) {
        val sp = BerryArt.treat(kind)
        Bitmap.createBitmap(sp.frames[0], sp.size, sp.size, Bitmap.Config.ARGB_8888)
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

        /** The game's effect pictures as bitmaps, made once each. */
        private val bitmaps = IdentityHashMap<FieldEffectArt.Image, Bitmap>()

        fun bitmapOf(img: FieldEffectArt.Image): Bitmap = synchronized(bitmaps) {
            bitmaps.getOrPut(img) { Bitmap.createBitmap(img.pixels, img.width, img.height, Bitmap.Config.ARGB_8888) }
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

    /**
     * A tap at ([x], [y]) in the box: on the Pokémon it hops and true comes back (the caller
     * plays its cry); on someone it goes to meet them; anywhere else it walks over there if it
     * can. A game that's on (or a tap that starts one: a wild Pokémon, the water, the berry
     * patch) may take it instead.
     */
    fun tap(x: Float, y: Float, now: Long): Boolean {
        val wd = wanderer ?: return false
        val px = (x - offsetX) / scale
        val py = (y - offsetY) / scale
        val l = life
        if (painter?.hit(px, py) == true && games?.game != MapGames.Game.FISHING) {
            wd.hop(now)
            l?.patted(now)
            return true
        }
        l?.interacted(now)
        val hit = people.values.filter { it.walker != null && it.painter.hit(px, py) }.maxByOrNull { it.feetY }?.walker
        val onBerry = berryTile?.let { b -> berryFigures.values.any { it.painter.hit(px, py) } && scene.project(b.x, b.y, b.z) != null } == true
        val ground = scene.tileNear(px, py, scene.tilePixels * 2f)
        val water = if (ground != null && scene.tileNear(px, py, scene.tilePixels * 0.9f) != null) null else waterNear(px, py)
        if (games?.tap(hit, water, onBerry, now) == true) return false
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

    /** The A button: reels in a bite when fishing; otherwise a hop (true: play its cry). */
    fun action(now: Long): Boolean {
        if (games?.action(now) == true) return false
        wanderer?.hop(now)
        life?.patted(now)
        return true
    }

    /** Walks the Pokémon about by hand (D-pad): see [MapWanderer.walk]. */
    fun walk(direction: Int, held: Boolean, now: Long) {
        life?.interacted(now)
        if (games?.game == MapGames.Game.FISHING) games.stop(now)
        wanderer?.walk(direction, held, now)
    }

    fun release() {
        wanderer?.release()
    }

    fun hop(now: Long) {
        wanderer?.hop(now)
    }

    /**
     * Parts of the box the cards on top cover: the Pokémon would rather not head there, so it
     * stays where it can be seen (and wild Pokémon don't come out there).
     */
    fun setCovered(rects: List<RectF>) {
        covered = rects.map { RectF(it) }
        val hidden: ((FieldMap.Tile) -> Boolean)? = if (covered.isEmpty()) null else { t ->
            val pr = scene.project(t.x, t.y, t.z)
            if (pr == null) {
                true
            } else {
                val vx = offsetX + pr[0] * scale
                val vy = offsetY + pr[1] * scale
                // its feet and its head
                covered.any { it.contains(vx, vy) || it.contains(vx, vy - scene.tilePixels * 1.5f * scale) }
            }
        }
        wanderer?.avoid = hidden
        life?.hidden = hidden
        crowd.spawnOk = hidden?.let { h -> { t: FieldMap.Tile -> !h(t) } }
    }
}

/**
 * Makes [RouteMap]s: reads the ROM last started in melonDS (as [PokemonCries] does), draws the
 * place for the walk's course once, and keeps it until the course, the light of the day, the
 * screen size or the Pokémon change. When the ROM can't be read (never started, moved, zipped)
 * there's no map and the painted landscape stays.
 */
object RouteMaps {
    private const val TAG = "RouteMaps"
    private const val PREFS = "pokewalker_route_map"

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

    private var cached: RouteMap? = null
    /** The game's field effects, read once for the ROM they're from. */
    @Volatile private var art: Pair<String, FieldEffectArt?>? = null
    @Volatile private var failedRom: String? = null
    @Volatile private var failedKey: String? = null
    /** Until when not to try [failedKey] again (elapsed realtime; Long.MAX_VALUE: not this session). */
    @Volatile private var failedUntil = 0L

    /** Picture size and scale for a [w] x [h] view: about 22 tiles across when wide (13 when tall), scaled by whole pixels. */
    fun pictureSize(w: Int, h: Int): IntArray {
        val across = if (w >= h) 22f else 13f
        val scale = max(2, (w / (across * FieldMap.TILE.toFloat())).roundToInt())
        return intArrayOf(ceil(w / scale.toFloat()).toInt(), ceil(h / scale.toFloat()).toInt(), scale)
    }

    private fun minuteNow(): Int = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }

    /** The map that's ready for this walk right now, without making one. */
    fun current(routeName: String, routeImage: Int, party: WalkParty, w: Int, h: Int, context: Context): RouteMap? {
        val c = cached ?: return null
        val uri = Pokewalker.lastGameUri(context) ?: return null
        return if (c.key == sceneKey(uri, routeName, routeImage, w, h) && c.party == party && lightOk(c)) c else null
    }

    private fun sceneKey(uri: String, routeName: String, routeImage: Int, w: Int, h: Int): String {
        val course = FieldMap.courseFor(routeName, routeImage)
        val size = pictureSize(w, h)
        return "$uri|${course.name}|${size[0]}x${size[1]}x${size[2]}"
    }

    private fun lightOk(m: RouteMap) = m.scene.lightKey < 0 || m.scene.lightKey == FieldMap.dayKey(minuteNow())

    /**
     * The map for the walk on [routeName] in a [w] x [h] view, made (in the background) only when
     * something changed; null when it can't be made.
     */
    suspend fun get(context: Context, routeName: String, routeImage: Int, party: WalkParty, w: Int, h: Int): RouteMap? {
        if (w <= 0 || h <= 0) return null
        val uri = Pokewalker.lastGameUri(context) ?: return null
        if (uri == failedRom) return null
        val key = sceneKey(uri, routeName, routeImage, w, h)
        val c = cached
        val sameScene = c != null && c.key == key && lightOk(c)
        if (sameScene && c!!.party == party) return c
        if (!sameScene && key == failedKey && SystemClock.elapsedRealtime() < failedUntil) return null
        val app = context.applicationContext
        val made = withContext(Dispatchers.IO) { make(app, uri, key, routeName, routeImage, party, w, h, if (sameScene) c else null, c) }
        if (made != null) cached = made
        return made
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
                    val scene = sameScene?.scene ?: FieldMap.render(src, course, size[0], size[1], minuteNow()) { Log.i(TAG, it) }
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
                    fun types(species: Int) = runCatching { PokemonTypes.of(src, species) }.getOrDefault(emptySet())
                    val hasWater = scene.water.isNotEmpty()
                    val grassy = scene.tiles.values.count { it.tallGrass } >= 8
                    // the course's Pokémon come out of the grass (Water types out of the water), or about a cave
                    val wild = if (grassy || !scene.outdoor || hasWater) party.route.mapNotNull { p ->
                        val water = PokemonTypes.WATER in types(p.species) && hasWater
                        if (!water && !grassy && scene.outdoor) null else kind(p, water)
                    } else emptyList()
                    val fish = if (hasWater) fishFor(course.kind).toList().mapNotNull { kind(WalkingPokemon(it, 0, false, false), true) } else emptyList()
                    val bugs = if (grassy && scene.outdoor) MapGames.BUGS.toList().mapNotNull { kind(WalkingPokemon(it, 0, false, false), false) } else emptyList()
                    val unown = if (course.kind == FieldMap.Kind.CAVE || course.kind == FieldMap.Kind.ICE) {
                        (0 until 26).mapNotNull { kind(WalkingPokemon(201, it, false, false), false)?.model }.toIntArray()
                    } else IntArray(0)
                    val buddy = party.caught.firstOrNull()
                    val buddySprite = buddy?.let { b -> runCatching { FollowerSprites.load(src, b.species, b.form, b.female, b.shiny) }.getOrNull() }
                    // birds flying over outside (owls at night), and a Magikarp in the water
                    val birds = if (!scene.outdoor) emptyList() else (if (scene.timeOfDay == 3) MapLife.NIGHT_BIRDS else MapLife.DAY_BIRDS)
                        .toList().mapNotNull { kind(WalkingPokemon(it, 0, false, false), false) }
                    val magikarp = if (scene.water.size >= 6 && course.kind != FieldMap.Kind.ICE && course.kind != FieldMap.Kind.VOLCANO) {
                        kind(WalkingPokemon(MapLife.MAGIKARP, 0, false, false), true)
                    } else null
                    val effectArt = artFor(uri, src)
                    val extras = MapExtras(
                        types = pokemon?.let { types(it.species) } ?: emptySet(),
                        buddy = buddy, buddySprite = buddySprite,
                        wild = wild, fish = fish, bugs = bugs, unown = unown,
                        berryStore = if (scene.outdoor) PrefsBerryStore(context, course.name) else null,
                        art = effectArt, items = party.items.toIntArray(), birds = birds, magikarp = magikarp,
                    )
                    if (sameScene == null) {
                        PokewalkerNative.note("Route map: ${course.place} from $game for ${course.name}, " +
                            "${scene.tiles.size} tiles to explore, ${scene.people.count { it.model in peopleSprites }} people, " +
                            "${wild.size} wild Pokémon, ${scene.props?.props?.size ?: 0} moving buildings (${scene.doors.size} doors), " +
                            "${scene.glows.size / 4} lights, ${if (effectArt != null) "the game's effects" else "no effects"} " +
                            "(${(System.nanoTime() - t0) / 1_000_000} ms)")
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
}
