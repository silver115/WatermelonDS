package me.magnum.melonds.pokewalker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.util.Calendar
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/** The walking Pokémon as the game hands it to the walker: species, form, female, shiny. */
data class WalkingPokemon(val species: Int, val form: Int, val female: Boolean, val shiny: Boolean) {
    companion object {
        /**
         * From the walker's memory: the route info at 0x8F00 starts with the Pokémon's species,
         * then at +0x0D its form (bits 0-4) and gender (bits 5-6, 1 = female), at +0x0E bit 1
         * shiny (how HG/SS fills it in overlay 112).
         */
        fun fromImage(image: ByteArray?): WalkingPokemon? {
            val o = 0x8F00
            if (image == null || image.size < o + 0x10) return null
            val species = (image[o].toInt() and 0xFF) or ((image[o + 1].toInt() and 0xFF) shl 8)
            if (species !in 1..493) return null
            val v = image[o + 0x0D].toInt() and 0xFF
            val f = image[o + 0x0E].toInt() and 0xFF
            return WalkingPokemon(species, v and 0x1F, (v shr 5) and 3 == 1, f and 0x02 != 0)
        }
    }
}

/**
 * The real place of the walk, from the player's own HeartGold/SoulSilver ROM ([FieldMap]), with
 * the walking Pokémon exploring it ([MapWanderer], [MapSpritePainter]). It is drawn in the DS's
 * own pixels, made [scale] times bigger to fill the screen.
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
) {
    private val picture: Bitmap = Bitmap.createBitmap(scene.image, scene.width, scene.height, Bitmap.Config.ARGB_8888)
    private val wanderer: MapWanderer? = if (scene.tiles.isEmpty()) null else MapWanderer(scene).also { w ->
        // the same place drawn again (new light): it carries on from where it was
        previous?.wanderer?.at?.let { at -> scene.tile(at.gx, at.gy)?.let { w.placeAt(it) } }
    }
    private val painter = if (sprite != null && wanderer != null) MapSpritePainter(scene, sprite) else null
    private val spriteBitmap = painter?.let { Bitmap.createBitmap(it.bufferWidth, it.bufferHeight, Bitmap.Config.ARGB_8888) }
    private val paint = Paint().apply {
        isFilterBitmap = false
        isDither = false
    }
    private var offsetX = 0f
    private var offsetY = 0f
    private var covered: List<RectF> = emptyList()

    /** Whether the Pokémon is drawn walking around. */
    val hasPokemon get() = painter != null

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
        canvas.drawBitmap(picture, 0f, 0f, paint)
        val wd = wanderer
        val p = painter
        val b = spriteBitmap
        if (wd != null && p != null && b != null) {
            wd.update(now)
            if (p.paint(wd.pose(now))) {
                b.setPixels(p.buffer, 0, p.bufferWidth, 0, 0, p.bufferWidth, p.bufferHeight)
                canvas.drawBitmap(b, p.left.toFloat(), p.top.toFloat(), paint)
            }
        }
        canvas.restore()
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
    private var failedRom: String? = null
    private var failedKey: String? = null

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
        if (!sameScene && key == failedKey) return null
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
                    if (sameScene == null) {
                        PokewalkerNative.note("Route map: ${course.place} from $game for ${course.name}, " +
                            "${scene.tiles.size} tiles to explore (${(System.nanoTime() - t0) / 1_000_000} ms)")
                    }
                    RouteMap(scene, sprite, game, size[2], key, pokemon, previous)
                }
            }
        } catch (e: FieldMap.MapException) {
            if (e.wholeRom) failedRom = uri else failedKey = key
            Log.w(TAG, "No route map: ${e.message}")
            PokewalkerNative.note("Route map not available: ${e.message}")
            null
        } catch (e: Nitro.FormatException) {
            failedKey = key
            Log.w(TAG, "No route map: ${e.message}")
            PokewalkerNative.note("Route map not available: ${e.message}")
            null
        } catch (e: Exception) {
            Log.w(TAG, "Could not draw the route map", e)
            null
        } catch (e: OutOfMemoryError) {
            failedKey = key
            Log.w(TAG, "Not enough memory for the route map", e)
            null
        }
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
