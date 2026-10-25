package me.magnum.melonds.pokewalker

import kotlin.math.floor

/**
 * The game's moving field textures, read from the player's ROM:
 *  - texture swaps (`a/1/3/9`, FieldTextureManager in overlay 1): member 0 is a table of
 *    u32 count, then 0x34-byte entries: the texture's name (16 bytes) and up to 18
 *    (frame, game frames to show it) pairs ending at 0xFF; member n + 1 is a texture set with
 *    entry n's frames ("sea_on.1"...) and either one palette or one per frame. The sea, the
 *    waves on the shore, rocks in the surf and flowers swap like this.
 *  - texture scrolls (`a/1/4/0`, an area's animation number): a BTA0 file whose SRT0 animation
 *    moves materials' textures (ponds, rivers, the sea): per material five tracks, scale S/T,
 *    rotation, move S/T, each a constant or one value per frame.
 */
object FieldAnimations {
    const val TEXTURE_SWAPS = "a/1/3/9"
    const val TEXTURE_SCROLLS = "a/1/4/0"

    /** A texture that swaps frames: [frames] decoded, and which frame shows at each game frame of the loop. */
    class Swap(val name: String, val frames: List<IntArray>, val width: Int, val height: Int, val timeline: IntArray)

    /** A material's texture moving by [s], [t] (in texture sizes) at each game frame of the loop. */
    class Scroll(val s: FloatArray?, val t: FloatArray?)

    fun readSwaps(src: CryArchive.Source): Map<String, Swap> {
        val narc = Nitro.narc(src, TEXTURE_SWAPS) ?: return emptyMap()
        if (narc.count < 1) return emptyMap()
        val table = narc.read(0)
        if (table.size < 4) return emptyMap()
        val count = Nitro.u32(table, 0).toInt().coerceIn(0, (table.size - 4) / 0x34).coerceAtMost(narc.count - 1)
        val out = HashMap<String, Swap>()
        for (i in 0 until count) {
            val e = 4 + i * 0x34
            var n = 0
            while (n < 16 && table[e + n].toInt() != 0) n++
            val name = String(table, e, n, Charsets.ISO_8859_1)
            val steps = ArrayList<Pair<Int, Int>>()
            for (k in 0 until 18) {
                val f = Nitro.u8(table, e + 16 + k * 2)
                val d = Nitro.u8(table, e + 17 + k * 2)
                if (f == 0xFF) break
                steps.add(f to d.coerceAtLeast(1))
            }
            if (steps.isEmpty()) continue
            val set = runCatching { Nitro.parse(narc.read(i + 1)).texSet }.getOrNull() ?: continue
            val textures = set.textures.values.toList()
            val palettes = set.palettes.values.toList()
            if (textures.isEmpty() || palettes.isEmpty()) continue
            val w = textures[0].width
            val h = textures[0].height
            val frames = textures.mapIndexedNotNull { k, t ->
                if (t.width != w || t.height != h) null
                else Nitro.decode(t, if (palettes.size == textures.size) palettes[k] else palettes[0])
            }
            if (frames.size != textures.size) continue
            val timeline = IntArray(steps.sumOf { it.second })
            var at = 0
            for ((f, d) in steps) for (k in 0 until d) timeline[at++] = f.coerceIn(0, frames.size - 1)
            out[name] = Swap(name, frames, w, h, timeline)
        }
        return out
    }

    /** The scrolling materials of area animation [index] (an area's u16 at +4; 0xFFFF none). */
    fun readScrolls(src: CryArchive.Source, index: Int): Map<String, Scroll> {
        if (index < 0 || index == 0xFFFF) return emptyMap()
        val narc = Nitro.narc(src, TEXTURE_SCROLLS) ?: return emptyMap()
        if (index >= narc.count) return emptyMap()
        return runCatching { parseScrolls(narc.read(index)) }.getOrDefault(emptyMap())
    }

    fun parseScrolls(b: ByteArray): Map<String, Scroll> {
        if (b.size < 0x14 || String(b, 0, 4, Charsets.ISO_8859_1) != "BTA0") return emptyMap()
        val block = Nitro.u32(b, 0x10).toInt()
        if (block + 8 > b.size || String(b, block, 4, Charsets.ISO_8859_1) != "SRT0") return emptyMap()
        val anims = Nitro.dictEntries(b, block + 8, 4)
        if (anims.isEmpty()) return emptyMap()
        val a = block + Nitro.u32(b, anims[0].first).toInt()
        if (a + 8 > b.size || b[a].toInt() != 'M'.code) return emptyMap()
        val frames = Nitro.u16(b, a + 4).coerceIn(1, 4096)
        val out = HashMap<String, Scroll>()
        for ((d, name) in Nitro.dictEntries(b, a + 8, 40)) {
            fun track(k: Int): FloatArray? {
                val info = Nitro.u32(b, d + k * 8)
                val value = Nitro.u32(b, d + k * 8 + 4)
                if (info and 0x20000000L != 0L) {
                    val c = value.toInt() / 4096f
                    return if (c == 0f) null else FloatArray(frames) { c }
                }
                val half = info and 0x10000000L != 0L // fx16 values
                val step = when ((info ushr 30) and 3) { 1L -> 2; 2L -> 4; else -> 1 }
                val count = (frames + step - 1) / step
                val at = a + value.toInt()
                val size = if (half) 2 else 4
                if (at < 0 || at + count * size > b.size) return null
                return FloatArray(frames) { f ->
                    val i = (f / step).coerceAtMost(count - 1)
                    (if (half) Nitro.s16(b, at + i * 2) else Nitro.s32(b, at + i * 4)) / 4096f
                }
            }
            val s = track(3)
            val t = track(4)
            if (s != null || t != null) out[name] = Scroll(s, t)
        }
        return out
    }
}

/**
 * The moving parts of a drawn field picture: the pixels of the layers whose texture swaps or
 * scrolls (up to [DEPTH] layers deep, like the sea's highlights over its water), with what's
 * needed to colour them again (texture position, vertex colour, what's under them),
 * recoloured in [image] whenever one of their textures moves on.
 */
class SceneAnimation internal constructor(private val width: Int, private val height: Int, private val image: IntArray) : SoftRenderer.Capture {

    /** A layer: its texture (and how it repeats), its swaps and scroll, how it's drawn. */
    class Slot(
        val base: Nitro.Image,
        val swap: FieldAnimations.Swap?,
        val scroll: FieldAnimations.Scroll?,
        val alpha: Float,
        val decal: Boolean,
        val blend: Boolean,
    )

    internal val slots = ArrayList<Slot>()
    private val n = width * height
    private val count = ByteArray(n)
    private val lSlot = ShortArray(n * DEPTH)
    private val lU = FloatArray(n * DEPTH)
    private val lV = FloatArray(n * DEPTH)
    private val lTint = IntArray(n * DEPTH)
    private val lDepth = FloatArray(n * DEPTH)
    private val under = IntArray(n)
    private var pixels = Array(0) { IntArray(0) }
    private var state = LongArray(0)
    // per update: each slot's texture and offset
    private var curTex = Array(0) { IntArray(0) }
    private var curDu = IntArray(0)
    private var curDv = IntArray(0)
    private var stamp = IntArray(0)
    private var updates = 0

    /** Pixels that move. */
    var size = 0
        private set

    override fun pixel(idx: Int, slot: Int, u: Float, v: Float, r: Float, g: Float, b: Float, iw: Float) {
        var c = count[idx].toInt()
        if (c == 0) under[idx] = image[idx]
        // kept farthest first; when full, the farthest goes
        val base = idx * DEPTH
        if (c == DEPTH) {
            if (iw <= lDepth[base]) return
            for (k in 1 until DEPTH) copy(base + k, base + k - 1)
            c--
        }
        var at = c
        while (at > 0 && lDepth[base + at - 1] > iw) { copy(base + at - 1, base + at); at-- }
        val o = base + at
        lSlot[o] = slot.toShort()
        lU[o] = u
        lV[o] = v
        lTint[o] = (c8(r) shl 16) or (c8(g) shl 8) or c8(b)
        lDepth[o] = iw
        count[idx] = (c + 1).toByte()
    }

    private fun copy(from: Int, to: Int) {
        lSlot[to] = lSlot[from]; lU[to] = lU[from]; lV[to] = lV[from]; lTint[to] = lTint[from]; lDepth[to] = lDepth[from]
    }

    private fun c8(v: Float) = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()

    /** After capturing: lists the pixels by layer, colours them for game frame 0, and puts the solid ones in [depth]. */
    internal fun finish(depth: FloatArray) {
        val lists = Array(slots.size) { ArrayList<Int>() }
        for (i in 0 until n) {
            val c = count[i].toInt()
            if (c == 0) continue
            size++
            for (k in 0 until c) {
                val list = lists[lSlot[i * DEPTH + k].toInt()]
                if (list.isEmpty() || list[list.size - 1] != i) list.add(i)
            }
        }
        pixels = Array(slots.size) { lists[it].toIntArray() }
        state = LongArray(slots.size) { Long.MIN_VALUE }
        curTex = Array(slots.size) { slots[it].base.argb }
        curDu = IntArray(slots.size)
        curDv = IntArray(slots.size)
        stamp = IntArray(n)
        update(0)
        for (i in 0 until n) {
            val c = count[i].toInt()
            if (c == 0) continue
            for (k in 0 until c) if (!slots[lSlot[i * DEPTH + k].toInt()].blend) depth[i] = maxOf(depth[i], lDepth[i * DEPTH + k])
        }
    }

    /** Recolours what moved by game frame [tick] (30 a second); true when [image] changed. */
    fun update(tick: Long): Boolean {
        val changed = ArrayList<Int>()
        for (s in slots.indices) {
            val slot = slots[s]
            if (pixels[s].isEmpty()) continue
            val swap = slot.swap
            val frame = if (swap != null) swap.timeline[Math.floorMod(tick, swap.timeline.size.toLong()).toInt()] else 0
            var du = 0
            var dv = 0
            slot.scroll?.let { sc ->
                sc.s?.let { du = floor(it[Math.floorMod(tick, it.size.toLong()).toInt()] * slot.base.width).toInt() }
                sc.t?.let { dv = floor(it[Math.floorMod(tick, it.size.toLong()).toInt()] * slot.base.height).toInt() }
            }
            val key = (frame.toLong() shl 40) or ((du.toLong() and 0xFFFFF) shl 20) or (dv.toLong() and 0xFFFFF)
            if (key == state[s]) continue
            state[s] = key
            curTex[s] = swap?.frames?.get(frame) ?: slot.base.argb
            curDu[s] = du
            curDv[s] = dv
            changed.add(s)
        }
        if (changed.isEmpty()) return false
        updates++
        for (s in changed) for (i in pixels[s]) {
            if (stamp[i] == updates) continue
            stamp[i] = updates
            var color = under[i]
            val c = count[i].toInt()
            for (k in 0 until c) {
                val o = i * DEPTH + k
                val sl = lSlot[o].toInt()
                val slot = slots[sl]
                val img = slot.base
                val tx = wrapTexel(floor(lU[o]).toInt() + curDu[sl], img.width, img.repeatS, img.mirrorS)
                val ty = wrapTexel(floor(lV[o]).toInt() + curDv[sl], img.height, img.repeatT, img.mirrorT)
                color = shade(curTex[sl][ty * img.width + tx], lTint[o], color, slot)
            }
            image[i] = color
        }
        return true
    }

    private fun shade(texel: Int, tint: Int, under: Int, slot: Slot): Int {
        val ta = (texel ushr 24) / 255f
        if (ta <= 0f) return under
        val vr = ((tint ushr 16) and 0xFF) / 255f
        val vg = ((tint ushr 8) and 0xFF) / 255f
        val vb = (tint and 0xFF) / 255f
        val tr = ((texel ushr 16) and 0xFF) / 255f
        val tg = ((texel ushr 8) and 0xFF) / 255f
        val tb = (texel and 0xFF) / 255f
        val r: Float
        val g: Float
        val b: Float
        var alpha = slot.alpha
        if (slot.decal) {
            r = vr * (1 - ta) + tr * ta; g = vg * (1 - ta) + tg * ta; b = vb * (1 - ta) + tb * ta
        } else {
            r = vr * tr; g = vg * tg; b = vb * tb
            alpha *= ta
        }
        if (!slot.blend) return if (alpha < 0.5f) under else pack(r, g, b)
        if (alpha <= 0.01f) return under
        val ur = ((under ushr 16) and 0xFF) / 255f
        val ug = ((under ushr 8) and 0xFF) / 255f
        val ub = (under and 0xFF) / 255f
        return pack(r * alpha + ur * (1 - alpha), g * alpha + ug * (1 - alpha), b * alpha + ub * (1 - alpha))
    }

    private fun pack(r: Float, g: Float, b: Float) = (0xFF shl 24) or (c8(r) shl 16) or (c8(g) shl 8) or c8(b)

    companion object {
        /** Moving layers a pixel keeps (the sea is two: water and highlights). */
        const val DEPTH = 3

        /**
         * Draws [layers] (the batches whose texture moves) into [r] as captured layers over what's
         * already drawn; null when there are none.
         */
        internal fun capture(r: SoftRenderer, layers: List<Pair<Nitro.Batch, Slot>>): SceneAnimation? {
            if (layers.isEmpty()) return null
            val anim = SceneAnimation(r.width, r.height, r.color)
            val ids = HashMap<Slot, Int>()
            // solid layers first, then see-through ones over them, as drawn
            val ordered = layers.sortedBy { if (it.second.blend) 1 else 0 }
            val numbered = ordered.map { (b, slot) -> b to ids.getOrPut(slot) { anim.slots.add(slot); anim.slots.size - 1 } }
            r.drawCaptured(numbered, anim)
            anim.finish(r.depth)
            return if (anim.size == 0) null else anim
        }
    }
}
