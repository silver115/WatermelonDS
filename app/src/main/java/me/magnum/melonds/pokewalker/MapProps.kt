package me.magnum.melonds.pokewalker

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The game's building animations, read from the player's ROM (pret/pokeheartgold
 * src/field/map_prop_animation.c):
 *  - each building model has an entry in `a/1/0/7` (outside) or `a/1/0/8` (inside), 0x18
 *    bytes: has animations, flags, bicycle slope, then four animation file numbers (s32, -1
 *    none) at +8, files of `a/1/0/6`;
 *  - flags 0: the animations always play, looping (windmills turning: joint animations, BCA0;
 *    water and lights: texture scrolls, BTA0; texture patterns, BTP0);
 *  - flags 8: one animation for each time of day (morning, day, evening, night), the night
 *    one lighting windows and lamps;
 *  - flags 1-3: played when something happens (doors opening and shutting as you go in).
 *
 * File formats as apicula (scurest/apicula, src/nitro) reads them: a BCA0's JNT0 has per node
 * translation, rotation and scale curves (a constant or one value every 1, 2 or 4 frames;
 * rotations as indices into pivot or basis matrix tables); a BTP0's PAT0 has per material
 * keyframes (frame, texture, palette). Pure Kotlin.
 */
object PropAnimations {
    const val FILES = "a/1/0/6"
    const val OUTDOOR_LISTS = "a/1/0/7"
    const val INDOOR_LISTS = "a/1/0/8"

    const val FLAG_TIME_OF_DAY = 8

    /** A building's animations: [flags] and the file numbers. */
    class Entry(val flags: Int, val ids: IntArray) {
        val loops get() = flags == 0
        val timeOfDay get() = flags == FLAG_TIME_OF_DAY
        /** Played when something happens (a door). */
        val oneShot get() = flags in 1..3
    }

    /** The animation entry of building [model] in [lists]; null when it has none. */
    fun entry(lists: Nitro.Narc, model: Int): Entry? {
        if (model < 0 || model >= lists.count) return null
        val b = runCatching { lists.read(model) }.getOrNull() ?: return null
        if (b.size < 0x18 || b[0].toInt() == 0) return null
        val ids = (0 until 4).map { Nitro.s32(b, 8 + it * 4) }.takeWhile { it >= 0 }.toIntArray()
        if (ids.isEmpty()) return null
        return Entry(Nitro.u8(b, 1), ids)
    }

    /** The game's time of day for its time-of-day animations: 0 morning (4-9), 1 day (10-16), 2 evening (17-19), 3 night. */
    fun timeOfDay(minute: Int): Int = when (Math.floorMod(minute, 24 * 60) / 60) {
        in 4..9 -> 0
        in 10..16 -> 1
        in 17..19 -> 2
        else -> 3
    }

    // ---- joint animations (BCA0) -------------------------------------------------------------------

    /** Values of one channel: a constant, or [values] sampled every 2^[rate] frames over [start, end). */
    class Curve(val constant: Double, val start: Int = 0, val end: Int = 0, val values: DoubleArray? = null) {
        fun at(frame: Int): Double {
            val v = values ?: return constant
            if (v.isEmpty()) return constant
            if (frame <= start) return v[0]
            if (frame >= end - 1) return v[v.size - 1]
            val lam = (frame - start).toDouble() / (end - 1 - start)
            val idx = lam * (v.size - 1)
            val lo = floor(idx).toInt().coerceIn(0, v.size - 1)
            val hi = (lo + 1).coerceAtMost(v.size - 1)
            val g = idx - lo
            return v[lo] * (1 - g) + v[hi] * g
        }
    }

    /** One node's animated transform: translation, rotation (3x3 rows, see [Mat4.fromRows3]) and scale. */
    class Trs(val trans: Array<Curve?>, val rotStart: Int, val rotEnd: Int, val rots: List<DoubleArray>?, val scale: Array<Curve?>) {
        fun at(frame: Int): DoubleArray {
            val tx = trans[0]?.at(frame) ?: 0.0
            val ty = trans[1]?.at(frame) ?: 0.0
            val tz = trans[2]?.at(frame) ?: 0.0
            val sx = scale[0]?.at(frame) ?: 1.0
            val sy = scale[1]?.at(frame) ?: 1.0
            val sz = scale[2]?.at(frame) ?: 1.0
            var m = Mat4.scale(sx, sy, sz)
            val r = rots
            if (r != null && r.isNotEmpty()) {
                val i = when {
                    r.size == 1 || frame <= rotStart -> 0
                    frame >= rotEnd - 1 -> r.size - 1
                    else -> ((frame - rotStart).toDouble() / (rotEnd - 1 - rotStart) * (r.size - 1) + 0.5).toInt().coerceIn(0, r.size - 1)
                }
                m = Mat4.mul(Mat4.fromRows3(r[i]), m)
            }
            return Mat4.mul(Mat4.translate(tx, ty, tz), m)
        }
    }

    /** A joint animation: [frames] long, a transform for the first nodes (null: identity). */
    class Joint(val frames: Int, val objects: Array<Trs?>) {
        /** The model's node matrices at [frame]: animated ones replaced, the rest as they are. */
        fun nodes(frame: Int, rest: List<DoubleArray>): List<DoubleArray> =
            rest.indices.map { i -> if (i < objects.size) objects[i]?.at(frame) ?: Mat4.identity() else rest[i] }
    }

    private fun section(b: ByteArray, stamp: String, section: String): Int? {
        if (b.size < 0x14 || String(b, 0, 4, Charsets.ISO_8859_1) != stamp) return null
        val n = Nitro.u16(b, 0x0E)
        for (i in 0 until n) {
            if (0x10 + i * 4 + 4 > b.size) break
            val off = Nitro.u32(b, 0x10 + i * 4).toInt()
            if (off >= 0 && off + 8 <= b.size && String(b, off, 4, Charsets.ISO_8859_1) == section) return off
        }
        return null
    }

    /** The first joint animation of a BCA0 file. */
    fun parseJoint(b: ByteArray): Joint? {
        val jnt = section(b, "BCA0", "JNT0") ?: return null
        val anims = runCatching { Nitro.dictEntries(b, jnt + 8, 4) }.getOrNull() ?: return null
        if (anims.isEmpty()) return null
        val a = jnt + Nitro.u32(b, anims[0].first).toInt()
        if (a + 20 > b.size || b[a].toInt() != 'J'.code || b[a + 2].toInt() != 'A'.code) return null
        val frames = Nitro.u16(b, a + 4)
        val count = Nitro.u16(b, a + 6)
        if (frames == 0 || count > 256 || a + 20 + count * 2 > b.size) return null
        val pivots = a + Nitro.u32(b, a + 12).toInt()
        val basis = a + Nitro.u32(b, a + 16).toInt()

        fun fx32(v: Int) = v / 4096.0
        fun fx16(v: Int) = v.toShort() / 4096.0
        fun info(x: Long): IntArray = intArrayOf((x and 0xFFFF).toInt(), ((x ushr 16) and 0xFFF).toInt(), ((x ushr 28) and 3).toInt(), ((x ushr 30) and 3).toInt())

        fun matrix(x: Int): DoubleArray? {
            val idx = x and 0x7FFF
            return if (x and 0x8000 != 0) {
                val p = pivots + idx * 6
                if (p + 6 > b.size) return null
                val selneg = Nitro.u16(b, p)
                Nitro.pivot(selneg and 15, (selneg ushr 4) and 15, fx16(Nitro.u16(b, p + 2)), fx16(Nitro.u16(b, p + 4)))
            } else {
                val p = basis + idx * 10
                if (p + 10 > b.size) return null
                val input = intArrayOf(Nitro.u16(b, p + 8), Nitro.u16(b, p), Nitro.u16(b, p + 2), Nitro.u16(b, p + 4), Nitro.u16(b, p + 6))
                val out = IntArray(6)
                for (i in 0 until 5) {
                    out[i] = input[i] ushr 3
                    out[5] = (out[5] shl 3) or (input[i] and 7)
                }
                fun f(v: Int): Double { val x13 = v and 0x1FFF; return (if (x13 and 0x1000 != 0) x13 - 0x2000 else x13) / 4096.0 }
                val ax = f(out[1]); val ay = f(out[2]); val az = f(out[3])
                val bx = f(out[4]); val by = f(out[0]); val bz = f(out[5])
                val cx = ay * bz - az * by; val cy = az * bx - ax * bz; val cz = ax * by - ay * bx
                // columns a, b, c (as fromRows3 takes them)
                doubleArrayOf(ax, ay, az, bx, by, bz, cx, cy, cz)
            }
        }

        val objects = Array<Trs?>(count) { k ->
            val o = a + Nitro.u16(b, a + 20 + k * 2)
            if (o + 4 > b.size) return@Array null
            val flags = Nitro.u16(b, o)
            if (flags and 1 != 0) return@Array null
            var p = o + 4
            fun need(n: Int) = p + n <= b.size
            val trans = arrayOfNulls<Curve>(3)
            if ((flags ushr 1) and 3 == 0) {
                for (i in 0 until 3) {
                    if ((flags ushr (3 + i)) and 1 != 0) {
                        if (!need(4)) return@Array null
                        trans[i] = Curve(fx32(Nitro.s32(b, p))); p += 4
                    } else {
                        if (!need(8)) return@Array null
                        val inf = info(Nitro.u32(b, p)); val off = a + Nitro.u32(b, p + 4).toInt(); p += 8
                        val n = ((inf[1] - inf[0]) ushr inf[3]).coerceAtLeast(1)
                        val half = inf[2] != 0
                        if (off + n * (if (half) 2 else 4) > b.size) return@Array null
                        trans[i] = Curve(0.0, inf[0], inf[1], DoubleArray(n) { j -> if (half) fx16(Nitro.u16(b, off + j * 2)) else fx32(Nitro.s32(b, off + j * 4)) })
                    }
                }
            }
            var rotStart = 0
            var rotEnd = 0
            var rots: List<DoubleArray>? = null
            if ((flags ushr 6) and 3 == 0) {
                if ((flags ushr 8) and 1 != 0) {
                    if (!need(4)) return@Array null
                    rots = listOfNotNull(matrix(Nitro.u16(b, p))); p += 4
                } else {
                    if (!need(8)) return@Array null
                    val inf = info(Nitro.u32(b, p)); val off = a + Nitro.u32(b, p + 4).toInt(); p += 8
                    val n = ((inf[1] - inf[0]) ushr inf[3]).coerceAtLeast(1)
                    if (off + n * 2 > b.size) return@Array null
                    rotStart = inf[0]; rotEnd = inf[1]
                    rots = (0 until n).mapNotNull { j -> matrix(Nitro.u16(b, off + j * 2)) }
                }
            }
            val scale = arrayOfNulls<Curve>(3)
            if ((flags ushr 9) and 3 == 0) {
                for (i in 0 until 3) {
                    if ((flags ushr (11 + i)) and 1 != 0) {
                        if (!need(8)) return@Array null
                        scale[i] = Curve(fx32(Nitro.s32(b, p))); p += 8
                    } else {
                        if (!need(8)) return@Array null
                        val inf = info(Nitro.u32(b, p)); val off = a + Nitro.u32(b, p + 4).toInt(); p += 8
                        val n = ((inf[1] - inf[0]) ushr inf[3]).coerceAtLeast(1)
                        val half = inf[2] != 0
                        if (off + n * (if (half) 4 else 8) > b.size) return@Array null
                        scale[i] = Curve(1.0, inf[0], inf[1], DoubleArray(n) { j -> if (half) fx16(Nitro.u16(b, off + j * 4)) else fx32(Nitro.s32(b, off + j * 8)) })
                    }
                }
            }
            Trs(trans, rotStart, rotEnd, rots, scale)
        }
        return Joint(frames, objects)
    }

    // ---- texture patterns (BTP0) -------------------------------------------------------------------

    /** A texture pattern: [frames] long; per material, keyframes (frame, texture, palette). */
    class Pattern(val frames: Int, val textures: List<String>, val palettes: List<String>, val tracks: Map<String, IntArray>) {
        /** Each material's texture and palette at [frame]. */
        fun at(frame: Int): Map<String, Pair<String, String?>> {
            val out = HashMap<String, Pair<String, String?>>()
            for ((mat, keys) in tracks) {
                var k = 0
                while (k + 3 < keys.size && keys[k + 3] <= frame) k += 3
                val tex = textures.getOrNull(keys[k + 1]) ?: continue
                out[mat] = tex to palettes.getOrNull(keys[k + 2])
            }
            return out
        }

        /** The frames at which something changes (sorted), for turning the pattern into a timeline. */
        val keyFrames: IntArray get() = tracks.values.flatMap { k -> (k.indices step 3).map { k[it] } }.distinct().sorted().toIntArray()
    }

    fun parsePattern(b: ByteArray): Pattern? {
        val pat = section(b, "BTP0", "PAT0") ?: return null
        val anims = runCatching { Nitro.dictEntries(b, pat + 8, 4) }.getOrNull() ?: return null
        if (anims.isEmpty()) return null
        val a = pat + Nitro.u32(b, anims[0].first).toInt()
        if (a + 12 > b.size) return null
        val frames = Nitro.u16(b, a + 4)
        val nTex = Nitro.u8(b, a + 6)
        val nPal = Nitro.u8(b, a + 7)
        val texAt = a + Nitro.u16(b, a + 8)
        val palAt = a + Nitro.u16(b, a + 10)
        if (texAt + nTex * 16 > b.size || palAt + nPal * 16 > b.size) return null
        fun name(o: Int): String { var n = 0; while (n < 16 && b[o + n].toInt() != 0) n++; return String(b, o, n, Charsets.ISO_8859_1) }
        val textures = List(nTex) { name(texAt + it * 16) }
        val palettes = List(nPal) { name(palAt + it * 16) }
        val tracks = HashMap<String, IntArray>()
        val dict = runCatching { Nitro.dictEntries(b, a + 12, 8) }.getOrNull() ?: return null
        for ((d, mat) in dict) {
            val n = Nitro.u16(b, d)
            val off = a + Nitro.u16(b, d + 6)
            if (n == 0 || off + n * 4 > b.size) continue
            tracks[mat] = IntArray(n * 3) { i ->
                val k = off + (i / 3) * 4
                when (i % 3) { 0 -> Nitro.u16(b, k); 1 -> Nitro.u8(b, k + 2); else -> Nitro.u8(b, k + 3) }
            }
        }
        return Pattern(frames.coerceAtLeast(1), textures, palettes, tracks)
    }
}

/**
 * Buildings whose shape moves (a windmill's sails, a door), drawn over the field picture: each
 * update draws them at their current frame, against the picture's depth without them, into
 * [overlay] (see-through where they aren't). A door's animation plays when [open] is called.
 * Pure Kotlin.
 */
class SceneProps internal constructor(
    private val width: Int,
    private val height: Int,
    viewProj: DoubleArray,
    /** Depth of the picture without these buildings. */
    private val baseDepth: FloatArray,
    val props: List<Prop>,
) {
    /** A moving building: its model and where it stands, how it's drawn, and its animations. */
    class Prop(
        val model: Nitro.Model,
        val place: DoubleArray,
        val texSets: List<Nitro.TexSet>,
        val light: Nitro.Lighting?,
        /** Always going round (a windmill), or played on [open] (a door: open, then shut). */
        val loop: PropAnimations.Joint?,
        val opening: PropAnimations.Joint? = null,
        val closing: PropAnimations.Joint? = null,
        /** Picture box it can cover: left, top, right, bottom. */
        val box: IntArray,
    ) {
        @Volatile internal var doorStart = Long.MIN_VALUE
        internal var lastFrame = Int.MIN_VALUE
        internal var lastPart = -1
        val isDoor get() = opening != null
    }

    /** ARGB over the picture: the moving buildings as they are now, 0 elsewhere. */
    val overlay = IntArray(width * height)

    private val r = SoftRenderer(width, height).also { it.viewProj = viewProj; it.frontIsClockwise = false }
    private val written = BooleanArray(width * height)
    private var image: IntArray = IntArray(0)
    private val box: IntArray = run {
        var l = width; var t = height; var rr = -1; var bb = -1
        for (p in props) { l = min(l, p.box[0]); t = min(t, p.box[1]); rr = max(rr, p.box[2]); bb = max(bb, p.box[3]) }
        intArrayOf(l.coerceAtLeast(0), t.coerceAtLeast(0), rr.coerceAtMost(width - 1), bb.coerceAtMost(height - 1))
    }

    companion object {
        /** How long a door stays open before it shuts (game frames, 30 a second). */
        const val DOOR_HOLD = 36
    }

    /** Plays door [prop]'s opening and shutting from [tick] (game frames, 30 a second). */
    fun open(prop: Prop, tick: Long) {
        if (!prop.isDoor) return
        val start = prop.doorStart
        val length = (prop.opening?.frames ?: 0) + DOOR_HOLD + (prop.closing?.frames ?: 0)
        if (start != Long.MIN_VALUE && tick - start < length) return
        prop.doorStart = tick
    }

    /** Whether door [prop] is open (or opening or shutting) at [tick]. */
    fun doorBusy(prop: Prop, tick: Long): Boolean {
        val start = prop.doorStart
        if (start == Long.MIN_VALUE || tick < start) return false
        val length = (prop.opening?.frames ?: 0) + DOOR_HOLD + (prop.closing?.frames ?: 0)
        return tick - start < length
    }

    /** Which animation and frame [p] shows at [tick]: part 0 looping / at rest, 1 opening, 2 open, 3 shutting. */
    private fun state(p: Prop, tick: Long): Pair<Int, Int> {
        p.loop?.let { return 0 to Math.floorMod(tick, it.frames.toLong()).toInt() }
        val start = p.doorStart
        val o = p.opening ?: return 0 to 0
        if (start == Long.MIN_VALUE || tick < start) return 0 to 0
        val t = (tick - start).toInt()
        if (t < o.frames) return 1 to t
        if (t < o.frames + DOOR_HOLD) return 2 to o.frames - 1
        val c = p.closing ?: return 0 to 0
        val u = t - o.frames - DOOR_HOLD
        if (u < c.frames) return 3 to u
        return 0 to 0
    }

    /**
     * Draws the buildings for [tick] over [picture] (the field picture as it is now, for what's
     * under see-through parts); true when [overlay] changed.
     */
    fun update(tick: Long, picture: IntArray): Boolean {
        if (props.isEmpty() || box[2] < box[0] || box[3] < box[1]) return false
        var changed = false
        val states = props.map { state(it, tick) }
        for ((i, p) in props.withIndex()) {
            val (part, frame) = states[i]
            if (part != p.lastPart || frame != p.lastFrame) changed = true
        }
        if (!changed) return false
        image = picture
        // the region they can cover: the picture and its depth there, nothing written yet
        for (y in box[1]..box[3]) {
            val o = y * width
            System.arraycopy(picture, o + box[0], r.color, o + box[0], box[2] - box[0] + 1)
            System.arraycopy(baseDepth, o + box[0], r.depth, o + box[0], box[2] - box[0] + 1)
            java.util.Arrays.fill(written, o + box[0], o + box[2] + 1, false)
        }
        r.written = written
        val batches = ArrayList<Nitro.Batch>()
        for ((i, p) in props.withIndex()) {
            val (part, frame) = states[i]
            p.lastPart = part
            p.lastFrame = frame
            val anim = when (part) {
                0 -> p.loop ?: p.opening
                1, 2 -> p.opening
                else -> p.closing
            }
            val nodes = anim?.nodes(frame, p.model.nodes)
            Nitro.buildMesh(p.model, p.place, p.texSets, p.light, batches, nodes)
        }
        r.draw(batches)
        r.written = null
        for (y in box[1]..box[3]) {
            val o = y * width
            for (x in box[0]..box[2]) {
                val i = o + x
                overlay[i] = if (written[i]) r.color[i] or (0xFF shl 24) else 0
            }
        }
        return true
    }

    /** The picture box of everything a prop's batches cover, a little enlarged. */
    internal object Boxes {
        fun of(batches: List<Nitro.Batch>, project: (Double, Double, Double) -> FloatArray?, width: Int, height: Int, margin: Int): IntArray {
            var l = Int.MAX_VALUE; var t = Int.MAX_VALUE; var rr = Int.MIN_VALUE; var bb = Int.MIN_VALUE
            for (b in batches) for (i in 0 until b.count) {
                val p = project(b.v[i * 8].toDouble(), b.v[i * 8 + 1].toDouble(), b.v[i * 8 + 2].toDouble()) ?: continue
                l = min(l, floor(p[0]).toInt()); t = min(t, floor(p[1]).toInt())
                rr = max(rr, p[0].toInt() + 1); bb = max(bb, p[1].toInt() + 1)
            }
            if (l == Int.MAX_VALUE) return intArrayOf(0, 0, -1, -1)
            return intArrayOf((l - margin).coerceAtLeast(0), (t - margin).coerceAtLeast(0), (rr + margin).coerceAtMost(width - 1), (bb + margin).coerceAtMost(height - 1))
        }
    }
}
