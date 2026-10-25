package me.magnum.melonds.pokewalker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The real place of the walk, from the player's own HeartGold/SoulSilver ROM ([FieldMap]), with
 * the walking Pokémon exploring it ([MapWanderer], [MapSpritePainter]) among the townsfolk and
 * trainers who live there ([FieldPeople], [MapCrowd]). The game's own moving
 * textures play on it (the sea, waves on the shore, rivers, flowers: [SceneAnimation]) and little
 * things move over it ([MapAmbience]). It is drawn in the DS's own pixels, made [scale] times
 * bigger to fill the screen.
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
    /** Sprites of the people in [scene] by their overworld sprite number; people without one aren't shown. */
    internal val peopleSprites: Map<Int, FollowerSprites.Sprite> = emptyMap(),
) {
    private val picture: Bitmap = Bitmap.createBitmap(scene.image, scene.width, scene.height, Bitmap.Config.ARGB_8888)
    private val crowd: MapCrowd? = scene.people.filter { it.model in peopleSprites }.takeIf { it.isNotEmpty() }?.let { MapCrowd(scene, it) }
    private val people: List<Figure> = crowd?.walkers?.map { w ->
        val p = MapSpritePainter(scene, peopleSprites.getValue(w.person.model)).apply { shadow = !w.person.swimming }
        Figure(p, Bitmap.createBitmap(p.bufferWidth, p.bufferHeight, Bitmap.Config.ARGB_8888), w)
    } ?: emptyList()
    private val wanderer: MapWanderer? = if (scene.tiles.isEmpty()) null else MapWanderer(scene).also { w ->
        // the same place drawn again (new light): it carries on from where it was
        previous?.wanderer?.at?.let { at -> scene.tile(at.gx, at.gy)?.let { w.placeAt(it) } }
        crowd?.let { c ->
            w.blocked = { gx, gy -> c.occupied(gx, gy) }
            // not on top of someone
            if (c.occupied(w.at.gx, w.at.gy)) {
                scene.tiles.values.filter { !c.occupied(it.gx, it.gy) }
                    .minByOrNull { abs(it.gx - w.at.gx) + abs(it.gy - w.at.gy) }
                    ?.let { w.placeAt(it) }
            }
        }
    }
    private val painter = if (sprite != null && wanderer != null) MapSpritePainter(scene, sprite) else null
    private val spriteBitmap = painter?.let { Bitmap.createBitmap(it.bufferWidth, it.bufferHeight, Bitmap.Config.ARGB_8888) }

    /** Someone drawn in the picture: how to paint them, their picture, and (for people) who they are. */
    private class Figure(val painter: MapSpritePainter, val bitmap: Bitmap, val walker: MapCrowd.Walker?) {
        var pose: MapWanderer.Pose? = null
        var feetY = 0f
        var lastTile: FieldMap.Tile? = null
    }
    private val pokemonFigure = if (painter != null && spriteBitmap != null) Figure(painter, spriteBitmap, null) else null
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
    private var lastTick = Long.MIN_VALUE
    private val animFrame = IntArray(scene.width * scene.height)
    private val animBusy = AtomicBoolean(false)
    @Volatile private var animReady = false
    private var minute = 0
    private var minuteAt = Long.MIN_VALUE
    private var lastTile: FieldMap.Tile? = null

    /** Whether the Pokémon is drawn walking around. */
    val hasPokemon get() = painter != null

    /** Whether any of the place's people are about. */
    val hasPeople get() = people.isNotEmpty()

    val species get() = pokemon?.species ?: 0

    /** Whether the Pokémon is walking (draw often then). */
    fun moving(now: Long) = wanderer?.pose(now)?.moving == true

    /** Draws the place and the Pokémon into a [w] x [h] box, the picture in the middle. */
    fun draw(canvas: Canvas, w: Float, h: Float, now: Long) {
        offsetX = ((w - scene.width * scale) / 2f).roundToInt().toFloat()
        offsetY = ((h - scene.height * scale) / 2f).roundToInt().toFloat()
        canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.scale(scale.toFloat(), scale.toFloat())
        // the game's moving textures, at 10 steps a second of its 30, coloured in the background
        val anim = scene.animation
        val tick = now * 30 / 1000 / 3 * 3
        if (anim != null && tick != lastTick && animBusy.compareAndSet(false, true)) {
            lastTick = tick
            animator.execute {
                try {
                    if (anim.update(tick)) {
                        synchronized(animFrame) { System.arraycopy(scene.image, 0, animFrame, 0, animFrame.size) }
                        animReady = true
                    }
                } catch (e: Exception) {
                    Log.w("RouteMap", "Texture animation stopped", e)
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
        val wd = wanderer
        drawOrder.clear()
        if (wd != null) {
            wd.update(now)
            val pose = wd.pose(now)
            pokemonFigure?.let { f ->
                f.pose = pose
                f.feetY = scene.project(pose.x, pose.y, pose.z)?.get(1) ?: 0f
                drawOrder.add(f)
            }
            // stepping into tall grass shakes it
            val at = wd.at
            if (at !== lastTile) {
                if (lastTile != null && at.tallGrass) scene.project(at.x, at.y, at.z)?.let { ambience.rustle(it[0], it[1], now) }
                lastTile = at
            }
        }
        crowd?.let { c ->
            c.update(now, wd?.at, wd?.next, wd?.facing ?: 0, wd != null && wd.next == null)
            for (f in people) {
                val w = f.walker ?: continue
                val pose = w.pose(now)
                f.pose = pose
                f.feetY = scene.project(pose.x, pose.y, pose.z)?.get(1) ?: continue
                drawOrder.add(f)
                if (w.at !== f.lastTile) {
                    if (f.lastTile != null && w.at.tallGrass) scene.project(w.at.x, w.at.y, w.at.z)?.let { ambience.rustle(it[0], it[1], now) }
                    f.lastTile = w.at
                }
            }
        }
        // further back first, so whoever is nearer is drawn over them
        drawOrder.sortBy { it.feetY }
        for (f in drawOrder) {
            val p = f.painter
            val pose = f.pose ?: continue
            if (p.paint(pose)) {
                f.bitmap.setPixels(p.buffer, 0, p.bufferWidth, 0, 0, p.bufferWidth, p.bufferHeight)
                canvas.drawBitmap(f.bitmap, p.left.toFloat(), p.top.toFloat(), paint)
            }
        }
        if (minuteAt == Long.MIN_VALUE || now - minuteAt > 10_000) {
            minuteAt = now
            minute = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }
        }
        ambience.frame(now, minute, dots, clouds)
        for (c in clouds) {
            cloudRect.set(c.x - c.rx, c.y - c.ry, c.x + c.rx, c.y + c.ry)
            cloudPaint.alpha = (c.alpha * 255).toInt().coerceIn(0, 255)
            canvas.drawBitmap(cloudShadow, null, cloudRect, cloudPaint)
        }
        for (d in dots) {
            dotPaint.color = d.color
            val x = d.x.roundToInt().toFloat()
            val y = d.y.roundToInt().toFloat()
            canvas.drawRect(x, y, x + d.size, y + d.size, dotPaint)
        }
        canvas.restore()
    }

    private companion object {
        /** Recolours the moving textures off the main thread. */
        val animator: ExecutorService by lazy {
            Executors.newSingleThreadExecutor { r -> Thread(r, "RouteMapAnimation").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 } }
        }

        /** A soft round shadow, stretched over each passing cloud. */
        val cloudShadow: Bitmap by lazy {
            val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.shader = RadialGradient(32f, 32f, 32f, intArrayOf(0xFF000000.toInt(), 0xB0000000.toInt(), 0x00000000),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            Canvas(bmp).drawCircle(32f, 32f, 32f, p)
            bmp
        }
    }

    /**
     * A tap at ([x], [y]) in the box: on the Pokémon it hops and true comes back (the caller
     * plays its cry); anywhere else it walks over there if it can.
     */
    fun tap(x: Float, y: Float, now: Long): Boolean {
        val wd = wanderer ?: return false
        val px = (x - offsetX) / scale
        val py = (y - offsetY) / scale
        if (painter?.hit(px, py) == true) {
            wd.hop(now)
            return true
        }
        // on someone: it goes up to them and looks at them, and they turn to it
        people.filter { it.painter.hit(px, py) }.maxByOrNull { it.feetY }?.walker?.let { w ->
            val t = w.next ?: w.at
            val from = wd.next ?: wd.at
            val spots = intArrayOf(0, 1, 0, -1, 1, 0, -1, 0)
            val besides = (0 until 4).mapNotNull { i -> scene.tile(t.gx + spots[i * 2], t.gy + spots[i * 2 + 1]) }
                .sortedBy { abs(it.gx - from.gx) + abs(it.gy - from.gy) }
            for (b in besides) if (wd.goTo(b, now, t)) return false
            return false
        }
        scene.tileNear(px, py, scene.tilePixels * 2f)?.let { wd.goTo(it, now) }
        return false
    }

    /** Walks the Pokémon about by hand (D-pad): see [MapWanderer.walk]. */
    fun walk(direction: Int, held: Boolean, now: Long) {
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
     * stays where it can be seen.
     */
    fun setCovered(rects: List<RectF>) {
        covered = rects.map { RectF(it) }
        val wd = wanderer ?: return
        wd.avoid = if (covered.isEmpty()) null else { t ->
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

    private var cached: RouteMap? = null
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
    fun current(routeName: String, routeImage: Int, pokemon: WalkingPokemon?, w: Int, h: Int, context: Context): RouteMap? {
        val c = cached ?: return null
        val uri = Pokewalker.lastGameUri(context) ?: return null
        return if (c.key == sceneKey(uri, routeName, routeImage, w, h) && c.pokemon == pokemon && lightOk(c)) c else null
    }

    private fun sceneKey(uri: String, routeName: String, routeImage: Int, w: Int, h: Int): String {
        val course = FieldMap.courseFor(routeName, routeImage)
        val size = pictureSize(w, h)
        return "$uri|${course.name}|${size[0]}x${size[1]}x${size[2]}"
    }

    private fun lightOk(m: RouteMap) = m.scene.lightKey < 0 || m.scene.lightKey == FieldMap.lightIndex(minuteNow())

    /**
     * The map for the walk on [routeName] in a [w] x [h] view, made (in the background) only when
     * something changed; null when it can't be made.
     */
    suspend fun get(context: Context, routeName: String, routeImage: Int, pokemon: WalkingPokemon?, w: Int, h: Int): RouteMap? {
        if (w <= 0 || h <= 0) return null
        val uri = Pokewalker.lastGameUri(context) ?: return null
        if (uri == failedRom) return null
        val key = sceneKey(uri, routeName, routeImage, w, h)
        val c = cached
        val sameScene = c != null && c.key == key && lightOk(c)
        if (sameScene && c!!.pokemon == pokemon) return c
        if (!sameScene && key == failedKey && SystemClock.elapsedRealtime() < failedUntil) return null
        val app = context.applicationContext
        val made = withContext(Dispatchers.IO) { make(app, uri, key, routeName, routeImage, pokemon, w, h, if (sameScene) c else null, c) }
        if (made != null) cached = made
        return made
    }

    private fun make(
        context: Context, uri: String, key: String, routeName: String, routeImage: Int, pokemon: WalkingPokemon?,
        w: Int, h: Int, sameScene: RouteMap?, previous: RouteMap?,
    ): RouteMap? {
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
                    val peopleSprites = sameScene?.peopleSprites ?: HashMap<Int, FollowerSprites.Sprite>().also { m ->
                        for (model in scene.people.map { it.model }.distinct()) {
                            runCatching { FollowerSprites.loadModel(src, model) }.getOrNull()?.let { m[model] = it }
                        }
                    }
                    if (sameScene == null) {
                        PokewalkerNative.note("Route map: ${course.place} from $game for ${course.name}, " +
                            "${scene.tiles.size} tiles to explore, ${scene.people.count { it.model in peopleSprites }} people " +
                            "(${(System.nanoTime() - t0) / 1_000_000} ms)")
                    }
                    RouteMap(scene, sprite, game, size[2], key, pokemon, previous, peopleSprites)
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
