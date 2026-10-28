package me.magnum.melonds.pokewalker

import java.util.IdentityHashMap
import kotlin.random.Random

/** The game's emote bubbles (fldeff `fuki_*`), by what they say. */
enum class Emote(val art: String) {
    HEART("fuki_heart"),
    /** Eyes shut, a little smile. */
    SMILE("fuki_kao1"),
    /** Eyes shut, mouth open: delighted. */
    HAPPY("fuki_kao2"),
    ANGRY("fuki_kao3"),
    /** Eyes open, a big smile. */
    GLAD("fuki_kao4"),
    SAD("fuki_kao5"),
    NOTE("fuki_onpu"),
    QUESTION("fuki_hatena"),
    EXCLAIM("fuki_bikkuri"),
    SWEAT("fuki_ase"),
    TROUBLED("fuki_komari"),
    /** "..." */
    SILENT("fuki_chinmoku"),
    /** "Zz" */
    SLEEP("fuki_pikon"),
}

/**
 * The game's own little field effects, read from the player's ROM (`a/1/0/3`,
 * data/fielddata/fldeff): the emote bubbles people show (`fuki_*`, two frames each), a splash
 * (`shibuki`, three frames), dust (`hero_kemu`, four), rings on water (`ripple`, four; brown
 * `n_ripple` in mud), a sparkle (`seed_kira`, two), the item ball (`monstarball`), and
 * footprints left in sand (`f_mark_u/d/l/r`) and snow (`nf_mark_*`). Pure Kotlin.
 */
class FieldEffectArt internal constructor(private val images: Map<String, Image>) {

    /** An ARGB picture, [width] x [height], and the rows its first and last drawn pixels are on. */
    class Image(val width: Int, val height: Int, val pixels: IntArray) {
        val top: Int = (0 until height).firstOrNull { y -> (0 until width).any { pixels[y * width + it] ushr 24 != 0 } } ?: 0
        val bottom: Int = (height - 1 downTo 0).firstOrNull { y -> (0 until width).any { pixels[y * width + it] ushr 24 != 0 } } ?: (height - 1)
    }

    /** The frames called [name] (".1", ".2" ... in order), else the one picture called [name]. */
    fun frames(name: String): List<Image> {
        val out = ArrayList<Image>()
        var i = 1
        while (true) {
            out.add(images["$name.$i"] ?: break)
            i++
        }
        if (out.isEmpty()) images[name]?.let { out.add(it) }
        return out
    }

    fun emote(e: Emote): List<Image> = frames(e.art)

    /** A footprint left walking [direction] (FollowerSprites.UP ...) in sand, or in snow. */
    fun footprint(direction: Int, snow: Boolean): Image? =
        images[(if (snow) "nf_mark_" else "f_mark_") + "udlr"[direction.coerceIn(0, 3)]]

    /** The names of the pictures there are. */
    val names: Set<String> get() = images.keys

    companion object {
        const val ARCHIVE = "a/1/0/3"

        private val WANTED = arrayOf("fuki_", "shibuki", "hero_kemu", "seed_kira", "monstarball", "ripple", "n_ripple", "f_mark_", "nf_mark_")

        /** Where pret/pokeheartgold has them (data/fielddata/fldeff), looked at first; the rest only if some are missing. */
        private val KNOWN = (2..15).toList() + listOf(18, 20, 26, 27, 29, 30) + (74..77).toList() + (90..93).toList()

        private val NEEDED = Emote.values().map { "${it.art}.1" } +
            listOf("shibuki.1", "hero_kemu.1", "seed_kira.1", "monstarball", "ripple.1", "n_ripple.1", "f_mark_u", "nf_mark_u")

        /** Reads the effects from the ROM; null when it has none of them. */
        fun load(src: CryArchive.Source): FieldEffectArt? {
            val narc = Nitro.narc(src, ARCHIVE) ?: return null
            val images = HashMap<String, Image>()
            val order = (KNOWN.filter { it < narc.count } + (0 until narc.count)).distinct()
            for (i in order) {
                if (NEEDED.all { it in images }) break
                // a member that isn't a model or texture file is skipped
                val set = runCatching { Nitro.parse(narc.read(i)).texSet }.getOrNull() ?: continue
                for ((name, tex) in set.textures) {
                    if (name in images || WANTED.none { name.startsWith(it) }) continue
                    // its own palette (shibuki.1_pl for all three shibuki frames), else the file's first
                    val base = name.substringBefore('.')
                    val pal = set.palettes.values.firstOrNull { it.name.startsWith(base) } ?: set.palettes.values.firstOrNull()
                    val px = Nitro.decode(tex, pal) ?: continue
                    images[name] = Image(tex.width, tex.height, px)
                }
            }
            return if (images.isEmpty()) null else FieldEffectArt(images)
        }
    }
}

/**
 * The game's little effects going on in a [FieldMap.Scene], drawn with [art]: footprints left in
 * sand and snow that slowly fade, rings spreading on water and in mud ([ground]), and splashes,
 * puffs of dust and sparkles ([air]). Where they are is the world's; what to draw comes back in
 * picture pixels. Pure Kotlin.
 */
class FieldEffects(private val scene: FieldMap.Scene, val art: FieldEffectArt?, private val random: Random = Random.Default) {

    enum class Kind { FOOTPRINT, RIPPLE, MUD_RIPPLE, SPLASH, DUST, SPARKLE }

    /**
     * One effect: what it is, where (world), since when and for how long; a footprint's
     * [direction] and whether it's in [snow]; [dx], [dy] picture pixels from where it is.
     */
    class Effect(
        val kind: Kind, val x: Double, val y: Double, val z: Double, val start: Long, val life: Long,
        val direction: Int = 0, val snow: Boolean = false, val dx: Float = 0f, val dy: Float = 0f,
    )

    /** One picture to draw: [image] stretched over the box from ([left], [top]), [alpha] solid. */
    class Draw(val image: FieldEffectArt.Image, val left: Float, val top: Float, val width: Float, val height: Float, val alpha: Float)

    companion object {
        /** How long a footprint stays (fading over the last [FADE_MS]), and how many there can be. */
        const val FOOTPRINT_MS = 8_000L
        const val FADE_MS = 3_000L
        const val MAX_FOOTPRINTS = 40
        const val RIPPLE_MS = 640L
        const val SPLASH_MS = 360L
        const val DUST_MS = 400L
        const val SPARKLE_MS = 480L
        private const val MAX_EFFECTS = 160

        /** Tile behaviours (include/constants/metatile_behavior.h). */
        const val PUDDLE = 0x16
        const val SHALLOW_WATER = 0x17
        const val ICE = 0x20
        const val SAND = 0x21
        const val MUD = 0xA4
        const val SNOW = 0xA8
    }

    private val effects = ArrayList<Effect>()

    /** In the Ice Path the floor that isn't ice is snow. */
    private val snowyFloor = scene.course.kind == FieldMap.Kind.ICE

    /** The effects going on (for tests). */
    val all: List<Effect> get() = effects

    private fun add(e: Effect) {
        if (effects.size >= MAX_EFFECTS) effects.removeAt(0)
        effects.add(e)
    }

    /** Someone stepped onto [tile] walking [direction]: a footprint in sand or snow, a ring in mud, a splash in a puddle. */
    fun stepped(tile: FieldMap.Tile, direction: Int, now: Long) {
        when (tile.behavior) {
            SAND -> footprint(tile, direction, false, now)
            SNOW -> footprint(tile, direction, true, now)
            MUD -> add(Effect(Kind.MUD_RIPPLE, tile.x, tile.y, tile.z, now, RIPPLE_MS))
            PUDDLE, SHALLOW_WATER -> splash(tile.x, tile.y, tile.z, now)
            ICE -> {}
            else -> if (snowyFloor) footprint(tile, direction, true, now)
        }
    }

    private fun footprint(tile: FieldMap.Tile, direction: Int, snow: Boolean, now: Long) {
        // a footprint already there is trodden over
        effects.removeAll { it.kind == Kind.FOOTPRINT && it.x == tile.x && it.z == tile.z }
        if (effects.count { it.kind == Kind.FOOTPRINT } >= MAX_FOOTPRINTS) {
            effects.firstOrNull { it.kind == Kind.FOOTPRINT }?.let { effects.remove(it) }
        }
        add(Effect(Kind.FOOTPRINT, tile.x, tile.y, tile.z, now, FOOTPRINT_MS, direction, snow))
    }

    /** A splash at ([x], [y], [z]) (someone stepped into water, or something leapt out), and a ring spreading. */
    fun splash(x: Double, y: Double, z: Double, now: Long) {
        add(Effect(Kind.SPLASH, x, y, z, now, SPLASH_MS))
        ripple(x, y, z, now)
    }

    /** A ring spreading on the water at ([x], [y], [z]). */
    fun ripple(x: Double, y: Double, z: Double, now: Long) {
        add(Effect(Kind.RIPPLE, x, y, z, now, RIPPLE_MS))
    }

    /** Puffs of dust at someone's feet (landing from a jump, popping out of the ground). */
    fun dust(x: Double, y: Double, z: Double, now: Long) {
        add(Effect(Kind.DUST, x, y, z, now, DUST_MS, dx = -5f))
        add(Effect(Kind.DUST, x, y, z, now + 60, DUST_MS, dx = 5f))
    }

    /** A sparkle [dx], [dy] picture pixels from world point ([x], [y], [z]), starting at [now]. */
    fun sparkle(x: Double, y: Double, z: Double, now: Long, dx: Float = 0f, dy: Float = 0f) {
        add(Effect(Kind.SPARKLE, x, y, z, now, SPARKLE_MS, dx = dx, dy = dy))
    }

    /** A few sparkles around something [size] pixels big standing at ([x], [y], [z]) (a shiny Pokémon). */
    fun sparkles(x: Double, y: Double, z: Double, size: Int, now: Long) {
        repeat(3) { k ->
            val dx = (random.nextFloat() - 0.5f) * size * 0.8f
            val dy = -size * (0.2f + random.nextFloat() * 0.6f)
            sparkle(x, y, z, now + k * 140L, dx, dy)
        }
    }

    /** Footprints, rings: flat on the ground, under everyone, at [now] (finished ones go). */
    fun ground(now: Long, out: MutableList<Draw>) {
        out.clear()
        effects.removeAll { now - it.start >= it.life }
        val a = art ?: return
        for (e in effects) {
            if (now < e.start) continue
            val u = (now - e.start).toFloat() / e.life
            val image: FieldEffectArt.Image
            var alpha = 1f
            when (e.kind) {
                Kind.FOOTPRINT -> {
                    image = a.footprint(e.direction, e.snow) ?: continue
                    val left = e.life - (now - e.start)
                    if (left < FADE_MS) alpha = left.toFloat() / FADE_MS
                }
                Kind.RIPPLE, Kind.MUD_RIPPLE -> {
                    val frames = a.frames(if (e.kind == Kind.RIPPLE) "ripple" else "n_ripple")
                    if (frames.isEmpty()) continue
                    image = frames[(u * frames.size).toInt().coerceIn(0, frames.size - 1)]
                    if (u > 0.75f) alpha = (1f - u) * 4f
                }
                else -> continue
            }
            // the tile's square of ground as the camera sees it: as wide as its near edge, from its far edge down
            val far = scene.project(e.x, e.y, e.z - 8.0) ?: continue
            val nearLeft = scene.project(e.x - 8.0, e.y, e.z + 8.0) ?: continue
            val nearRight = scene.project(e.x + 8.0, e.y, e.z + 8.0) ?: continue
            val mid = scene.project(e.x, e.y, e.z) ?: continue
            // hidden behind something nearer
            val px = mid[0].toInt()
            val py = mid[1].toInt()
            if (px !in 0 until scene.width || py !in 0 until scene.height) continue
            if (scene.depth[py * scene.width + px] > mid[2] * 1.004f) continue
            out.add(Draw(image, nearLeft[0], far[1], nearRight[0] - nearLeft[0], nearLeft[1] - far[1], alpha))
        }
    }

    /** Splashes, dust, sparkles: standing up, over everyone, at [now]. */
    fun air(now: Long, out: MutableList<Draw>) {
        out.clear()
        val a = art ?: return
        for (e in effects) {
            if (now < e.start || now - e.start >= e.life) continue
            val frames = when (e.kind) {
                Kind.SPLASH -> a.frames("shibuki")
                Kind.DUST -> a.frames("hero_kemu")
                Kind.SPARKLE -> a.frames("seed_kira")
                else -> continue
            }
            if (frames.isEmpty()) continue
            val u = (now - e.start).toFloat() / e.life
            val image = frames[(u * frames.size).toInt().coerceIn(0, frames.size - 1)]
            val p = scene.project(e.x, e.y, e.z) ?: continue
            val cx = p[0] + e.dx
            val cy = p[1] + e.dy
            when (e.kind) {
                // a splash rises around the feet; dust sits on the ground
                Kind.SPLASH -> out.add(Draw(image, cx - image.width / 2f, cy - image.bottom - 1f + 4f, image.width.toFloat(), image.height.toFloat(), 1f))
                Kind.DUST -> out.add(Draw(image, cx - image.width / 2f, cy - image.bottom - 1f + 2f, image.width.toFloat(), image.height.toFloat(),
                    if (u > 0.6f) (1f - u) * 2.5f else 1f))
                // a sparkle is centred where it is
                else -> out.add(Draw(image, cx - image.width / 2f, cy - image.height / 2f, image.width.toFloat(), image.height.toFloat(), 1f))
            }
        }
    }
}

/**
 * Who shows which emote bubble (the walking Pokémon, the one following it, someone in the
 * crowd: any object, by identity), and until when.
 */
class Emotes {
    class Bubble(val emote: Emote, val start: Long, val until: Long)

    private val bubbles = IdentityHashMap<Any, Bubble>()

    /** [who] shows [emote] from [now] for [ms]. */
    fun show(who: Any, emote: Emote, now: Long, ms: Long = 1_500) {
        bubbles[who] = Bubble(emote, now, now + ms)
    }

    /** The bubble [who] shows at [now], if any. */
    fun at(who: Any, now: Long): Bubble? = bubbles[who]?.takeIf { now >= it.start && now < it.until }

    /** Whether [who] shows a bubble at [now] or has one coming. */
    fun pending(who: Any, now: Long): Boolean = bubbles[who]?.let { now < it.until } == true

    fun clear(who: Any) {
        bubbles.remove(who)
    }

    /** Forgets the bubbles that are over. */
    fun prune(now: Long) {
        bubbles.values.removeAll { now >= it.until }
    }

    companion object {
        /** Which of a bubble's two pictures shows [ms] after it popped up (they take turns, as in the game). */
        fun frame(ms: Long, frames: Int): Int = if (frames <= 1) 0 else ((ms / 320) % frames).toInt()

        /** How far below its place it still is, popping up (pixels). */
        fun rise(ms: Long): Float = if (ms < 120) (1f - ms / 120f) * 4f else 0f
    }
}
