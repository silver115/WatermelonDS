package me.magnum.melonds.pokewalker

import kotlin.math.sqrt

/**
 * Nintendo's DS file formats the walker screen reads from the player's own HeartGold/SoulSilver
 * ROM: NARC archives and Nitro 3D files (BMD0 models with MDL0/TEX0 blocks, BTX0 texture sets).
 * Pure Kotlin, no Android, so it can be tested on its own. Nothing from the game ships with the
 * app; this only reads the ROM the player runs, as the emulator does.
 *
 * The layouts follow the NitroSystem formats as documented by the DS homebrew community
 * (GBATEK for the GPU commands, apicula / DSPRE for the containers).
 */
object Nitro {
    class FormatException(message: String) : Exception(message)

    internal fun u8(b: ByteArray, o: Int): Int = b[o].toInt() and 0xFF
    internal fun u16(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    internal fun s16(b: ByteArray, o: Int): Int = u16(b, o).toShort().toInt()
    internal fun s32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or ((b[o + 2].toInt() and 0xFF) shl 16) or (b[o + 3].toInt() shl 24)
    internal fun u32(b: ByteArray, o: Int): Long = s32(b, o).toLong() and 0xFFFFFFFFL
    internal fun fx32(b: ByteArray, o: Int): Double = s32(b, o) / 4096.0
    internal fun fx16(v: Int): Double = v.toShort() / 4096.0
    private fun need(cond: Boolean, what: String) { if (!cond) throw FormatException(what) }

    // ---- NARC archives -------------------------------------------------------------------------

    /** A NARC archive (an "a/0/4/1"-style file of the ROM): numbered members, read on demand. */
    class Narc(private val src: CryArchive.Source, private val base: Long, size: Long) {
        private val starts: LongArray
        private val ends: LongArray
        private val data: Long
        val count: Int get() = starts.size

        init {
            val h = src.read(base, 0x1C)
            need(tag(h, 0) == "NARC", "not a NARC archive")
            need(tag(h, 0x10) == "BTAF", "NARC without a file table")
            val fatSize = u32(h, 0x14)
            val n = u16(h, 0x18)
            need(fatSize in 12..(8L shl 20) && 12 + n * 8L <= fatSize, "NARC file table")
            val fat = src.read(base + 0x1C, n * 8)
            starts = LongArray(n) { u32(fat, it * 8) }
            ends = LongArray(n) { u32(fat, it * 8 + 4) }
            // then the name table (BTNF), then the data (GMIF)
            val fntPos = base + 0x10 + fatSize
            val fnt = src.read(fntPos, 8)
            need(tag(fnt, 0) == "BTNF", "NARC without a name table")
            val gmifPos = fntPos + u32(fnt, 4)
            need(tag(src.read(gmifPos, 4), 0) == "GMIF", "NARC without data")
            data = gmifPos + 8
            need(data <= base + size, "NARC size")
            for (i in 0 until n) need(ends[i] >= starts[i] && data + ends[i] <= base + size, "NARC member $i")
        }

        fun size(i: Int): Int = (ends[i] - starts[i]).toInt()

        fun read(i: Int): ByteArray {
            if (i < 0 || i >= count) throw FormatException("NARC member $i of $count")
            return src.read(data + starts[i], size(i))
        }
    }

    /** The NARC at [path] in the ROM ("a/0/4/1"), or null when there is none. */
    fun narc(src: CryArchive.Source, path: String): Narc? {
        val (start, end) = CryArchive.findFile(src, path) ?: return null
        return Narc(src, start, end - start)
    }

    private fun tag(b: ByteArray, o: Int) = String(b, o, 4, Charsets.ISO_8859_1)

    // ---- info blocks ("dictionaries") -----------------------------------------------------------

    /** (datum offset, name) pairs of the dictionary at [o]. */
    private fun dict(b: ByteArray, o: Int, datumSize: Int): List<Pair<Int, String>> {
        need(o >= 0 && o + 4 <= b.size && u8(b, o) == 0, "dictionary at $o")
        val count = u8(b, o + 1)
        val p = o + 4 + 8 + 4 * count
        need(p + 4 <= b.size, "dictionary at $o")
        need(u16(b, p) == datumSize, "dictionary at $o: entry size ${u16(b, p)}")
        val names = p + 4 + count * datumSize
        need(names + count * 16 <= b.size, "dictionary at $o: names")
        return List(count) { i -> (p + 4 + i * datumSize) to name(b, names + i * 16) }
    }

    /** (datum offset, name) pairs of the dictionary at [o] (throws [FormatException] when it isn't one). */
    internal fun dictEntries(b: ByteArray, o: Int, datumSize: Int) = dict(b, o, datumSize)

    private fun name(b: ByteArray, o: Int): String {
        var n = 0
        while (n < 16 && b[o + n].toInt() != 0) n++
        return String(b, o, n, Charsets.ISO_8859_1)
    }

    // ---- textures (TEX0) -------------------------------------------------------------------------

    class Texture(val name: String, val params: Int, val data: ByteArray, val data2: ByteArray?) {
        val format get() = (params ushr 26) and 7
        val width get() = 8 shl ((params ushr 20) and 7)
        val height get() = 8 shl ((params ushr 23) and 7)
        val color0Transparent get() = (params ushr 29) and 1 != 0
    }

    class Palette(val name: String, private val block: ByteArray, private val offset: Int) {
        /** Colour [i] as RGB555 (0 past the end of the palette block). */
        fun color(i: Int): Int {
            val o = offset + i * 2
            return if (o >= 0 && o + 2 <= block.size) u16(block, o) else 0
        }
    }

    /** A decoded texture: ARGB pixels, its size, how it repeats, and whether it has see-through texels. */
    class Image(val argb: IntArray, val width: Int, val height: Int, val repeatS: Boolean, val repeatT: Boolean,
                val mirrorS: Boolean, val mirrorT: Boolean, val translucent: Boolean)

    class TexSet(val textures: Map<String, Texture>, val palettes: Map<String, Palette>) {
        private val cache = HashMap<String, Image?>()

        /** The texture/palette pair decoded with a material's repeat bits ([teximage]: bits 16-19). */
        fun image(texName: String?, palName: String?, teximage: Int): Image? {
            val tex = texName?.let { textures[it] } ?: return null
            val key = "$texName/$palName/${(teximage ushr 16) and 15}"
            return cache.getOrPut(key) {
                val pal = palName?.let { palettes[it] }
                val px = decode(tex, pal) ?: return@getOrPut null
                Image(px, tex.width, tex.height,
                    repeatS = (teximage ushr 16) and 1 != 0, repeatT = (teximage ushr 17) and 1 != 0,
                    mirrorS = (teximage ushr 18) and 1 != 0, mirrorT = (teximage ushr 19) and 1 != 0,
                    translucent = tex.format == 1 || tex.format == 6)
            }
        }
    }

    private fun readTex0(b: ByteArray, o: Int): TexSet {
        need(tag(b, o) == "TEX0", "TEX0")
        val texDict = o + u16(b, o + 0x0E)
        val texData = o + u32(b, o + 0x14).toInt()
        val cmp1 = o + u32(b, o + 0x24).toInt()
        val cmp2 = o + u32(b, o + 0x28).toInt()
        val palLen = u16(b, o + 0x30) shl 3
        val palDict = o + u32(b, o + 0x34).toInt()
        val palData = o + u32(b, o + 0x38).toInt()
        val textures = LinkedHashMap<String, Texture>()
        for ((d, n) in dict(b, texDict, 8)) {
            val params = s32(b, d)
            val fmt = (params ushr 26) and 7
            if (fmt == 0) continue
            val w = 8 shl ((params ushr 20) and 7)
            val h = 8 shl ((params ushr 23) and 7)
            val bpp = intArrayOf(0, 8, 2, 4, 8, 2, 8, 16)[fmt]
            val len = w * h * bpp / 8
            val off = (params and 0xFFFF) shl 3
            if (fmt == 5) {
                if (cmp1 + off + len > b.size || cmp2 + off / 2 + len / 2 > b.size) continue
                textures[n] = Texture(n, params, b.copyOfRange(cmp1 + off, cmp1 + off + len), b.copyOfRange(cmp2 + off / 2, cmp2 + off / 2 + len / 2))
            } else {
                if (texData + off + len > b.size) continue
                textures[n] = Texture(n, params, b.copyOfRange(texData + off, texData + off + len), null)
            }
        }
        val block = if (palData in 0..b.size) b.copyOfRange(palData, minOf(b.size, palData + palLen)) else ByteArray(0)
        val palettes = LinkedHashMap<String, Palette>()
        for ((d, n) in dict(b, palDict, 4)) palettes[n] = Palette(n, block, u16(b, d) shl 3)
        return TexSet(textures, palettes)
    }

    private fun c5to8(c: Int) = (c shl 3) or (c ushr 2)

    /** RGB555 + 5-bit alpha to ARGB. */
    private fun argb(rgb: Int, a5: Int): Int {
        val r = c5to8(rgb and 31)
        val g = c5to8((rgb ushr 5) and 31)
        val bl = c5to8((rgb ushr 10) and 31)
        return (c5to8(a5) shl 24) or (r shl 16) or (g shl 8) or bl
    }

    /** Decodes a texture to ARGB (GBATEK "DS 3D Texture Formats"); null when it needs a missing palette. */
    fun decode(t: Texture, pal: Palette?): IntArray? {
        val w = t.width
        val h = t.height
        val out = IntArray(w * h)
        val d = t.data
        val c0 = t.color0Transparent
        if (t.format != 7 && pal == null) return null
        when (t.format) {
            1 -> for (i in 0 until w * h) { // A3I5
                val v = u8(d, i)
                val a3 = v ushr 5
                out[i] = argb(pal!!.color(v and 31), (a3 shl 2) or (a3 ushr 1))
            }
            2 -> for (i in 0 until w * h) { // 4 colours
                val idx = (u8(d, i ushr 2) ushr ((i and 3) * 2)) and 3
                out[i] = if (idx == 0 && c0) 0 else argb(pal!!.color(idx), 31)
            }
            3 -> for (i in 0 until w * h) { // 16 colours
                val idx = (u8(d, i ushr 1) ushr ((i and 1) * 4)) and 15
                out[i] = if (idx == 0 && c0) 0 else argb(pal!!.color(idx), 31)
            }
            4 -> for (i in 0 until w * h) { // 256 colours
                val idx = u8(d, i)
                out[i] = if (idx == 0 && c0) 0 else argb(pal!!.color(idx), 31)
            }
            5 -> { // 4x4 texel blocks
                val d2 = t.data2 ?: return null
                val bw = w / 4
                for (y in 0 until h) for (x in 0 until w) {
                    val blk = (y / 4) * bw + x / 4
                    if (blk * 4 + 4 > d.size || blk * 2 + 2 > d2.size) continue
                    val bits = s32(d, blk * 4)
                    val texel = (bits ushr (2 * (4 * (y % 4) + x % 4))) and 3
                    val extra = u16(d2, blk * 2)
                    val mode = extra ushr 14
                    val base = (extra and 0x3FFF) shl 1
                    fun col(n: Int) = argb(pal!!.color(base + n), 31)
                    out[y * w + x] = when (mode) {
                        0 -> if (texel == 3) 0 else col(texel)
                        1 -> when (texel) { 0 -> col(0); 1 -> col(1); 2 -> mix(col(0), col(1), 1, 1); else -> 0 }
                        2 -> col(texel)
                        else -> when (texel) { 0 -> col(0); 1 -> col(1); 2 -> mix(col(0), col(1), 5, 3); else -> mix(col(0), col(1), 3, 5) }
                    }
                }
            }
            6 -> for (i in 0 until w * h) { // A5I3
                val v = u8(d, i)
                out[i] = argb(pal!!.color(v and 7), v ushr 3)
            }
            7 -> for (i in 0 until w * h) { // direct colour
                val v = u16(d, i * 2)
                out[i] = if (v and 0x8000 == 0) 0 else argb(v, 31)
            }
            else -> return null
        }
        return out
    }

    /** (a*wa + b*wb) / (wa + wb), per channel. */
    private fun mix(a: Int, b: Int, wa: Int, wb: Int): Int {
        var out = 0
        for (s in intArrayOf(24, 16, 8, 0)) {
            val v = (((a ushr s) and 0xFF) * wa + ((b ushr s) and 0xFF) * wb) / (wa + wb)
            out = out or (v shl s)
        }
        return out
    }

    // ---- models (MDL0) ----------------------------------------------------------------------------

    class Material(
        val name: String,
        val difAmb: Int,
        val speEmi: Int,
        val polyAttr: Int,
        val teximage: Int,
        val width: Int,
        val height: Int,
        val texScale: DoubleArray?, // s, t scale from the texture matrix, when there is one
    ) {
        var texName: String? = null
        var palName: String? = null
        val alpha get() = (polyAttr ushr 16) and 31
        val lights get() = polyAttr and 15
        val mode get() = (polyAttr ushr 4) and 3
        val drawBack get() = (polyAttr ushr 6) and 1 != 0
        val drawFront get() = (polyAttr ushr 7) and 1 != 0
    }

    class Piece(val name: String, val cmds: ByteArray)

    /** Render command, reduced to what draws the model (see [Model.ops]). */
    class Op(val kind: Int, val a: Int = 0, val b: Int = 0, val terms: IntArray? = null) {
        companion object {
            const val LOAD = 0
            const val STORE = 1
            const val MUL_NODE = 2
            const val SCALE_UP = 3
            const val SCALE_DOWN = 4
            const val MATERIAL = 5
            const val DRAW = 6
            const val SKIN = 7
        }
    }

    class Model(
        val name: String,
        val materials: List<Material>,
        val pieces: List<Piece>,
        val nodes: List<DoubleArray>,
        val invBinds: List<DoubleArray>,
        val ops: List<Op>,
        val upScale: Double,
        val downScale: Double,
    )

    class Container(val models: List<Model>, val texSet: TexSet?)

    /** Reads a BMD0 (model) or BTX0 (texture set) file. */
    fun parse(b: ByteArray): Container {
        need(b.size >= 0x14, "Nitro file too short")
        val stamp = tag(b, 0)
        need(stamp == "BMD0" || stamp == "BTX0", "not a Nitro model or texture file ($stamp)")
        val sections = u16(b, 0x0E)
        val models = ArrayList<Model>()
        var texSet: TexSet? = null
        for (i in 0 until sections) {
            if (0x10 + i * 4 + 4 > b.size) break
            val off = u32(b, 0x10 + i * 4).toInt()
            if (off < 0 || off + 8 > b.size) continue
            when (tag(b, off)) {
                "MDL0" -> for ((d, n) in dict(b, off + 8, 4)) runCatching { models.add(readModel(b, off + u32(b, d).toInt(), n)) }
                "TEX0" -> texSet = readTex0(b, off)
            }
        }
        return Container(models, texSet)
    }

    private fun readModel(b: ByteArray, o: Int, name: String): Model {
        val cmdsOff = o + u32(b, o + 4).toInt()
        val matsOff = o + u32(b, o + 8).toInt()
        val piecesOff = o + u32(b, o + 0x0C).toInt()
        val invOff = o + u32(b, o + 0x10).toInt()
        val numNodes = u8(b, o + 0x17)
        val upScale = fx32(b, o + 0x1C)
        val downScale = fx32(b, o + 0x20)

        val nodesDict = o + 0x40
        val nodes = dict(b, nodesDict, 4).map { (d, _) -> readNode(b, nodesDict + u32(b, d).toInt()) }
        val pieces = dict(b, piecesOff, 4).map { (d, n) ->
            val p = piecesOff + u32(b, d).toInt()
            val cmds = p + u32(b, p + 8).toInt()
            val len = u32(b, p + 12).toInt()
            need(cmds >= 0 && len >= 0 && cmds + len <= b.size, "piece $n")
            Piece(n, b.copyOfRange(cmds, cmds + len))
        }
        val materials = dict(b, matsOff + 4, 4).map { (d, n) -> readMaterial(b, matsOff + u32(b, d).toInt(), n) }
        for ((pairs, isTex) in listOf(matsOff + u16(b, matsOff) to true, matsOff + u16(b, matsOff + 2) to false)) {
            for ((d, n) in dict(b, pairs, 4)) {
                val at = matsOff + u16(b, d)
                val num = u8(b, d + 2)
                for (k in 0 until num) {
                    val m = u8(b, at + k)
                    if (m < materials.size) if (isTex) materials[m].texName = n else materials[m].palName = n
                }
            }
        }
        val invBinds = ArrayList<DoubleArray>()
        for (k in 0 until numNodes) {
            val p = invOff + k * (12 + 9) * 4
            if (p + 48 > b.size || invOff <= o) break
            val m = Mat4.identity()
            for (r in 0 until 4) for (c in 0 until 3) {
                // stored as 4 rows of 3 (row vectors); as column-vector matrix: M[c][r]
                m[c * 4 + r] = fx32(b, p + (r * 3 + c) * 4)
            }
            invBinds.add(m)
        }
        return Model(name, materials, pieces, nodes, invBinds, readOps(b, cmdsOff), upScale, downScale)
    }

    private fun readNode(b: ByteArray, o: Int): DoubleArray {
        var p = o
        val flags = u16(b, p)
        val m0 = s16(b, p + 2)
        p += 4
        var m = Mat4.identity()
        var t: DoubleArray? = null
        var r: DoubleArray? = null
        var s: DoubleArray? = null
        if (flags and 1 == 0) {
            t = doubleArrayOf(fx32(b, p), fx32(b, p + 4), fx32(b, p + 8)); p += 12
        }
        if ((flags shr 3) and 1 == 1) {
            val a = fx16(u16(b, p)); val bb = fx16(u16(b, p + 2)); p += 4
            r = pivot((flags shr 4) and 15, (flags shr 8) and 15, a, bb)
        } else if ((flags shr 1) and 1 == 0) {
            val v = DoubleArray(9)
            v[0] = fx16(m0)
            for (k in 1 until 9) v[k] = fx16(u16(b, p + (k - 1) * 2))
            p += 16
            r = v
        }
        if ((flags shr 2) and 1 == 0) {
            s = doubleArrayOf(fx32(b, p), fx32(b, p + 4), fx32(b, p + 8)); p += 12
        }
        if (s != null) m = Mat4.scale(s[0], s[1], s[2])
        if (r != null) m = Mat4.mul(Mat4.fromRows3(r), m)
        if (t != null) m = Mat4.mul(Mat4.translate(t[0], t[1], t[2]), m)
        return m
    }

    /** Rows of a 3x3 matrix given as "pivot + two values" (apicula's nitro/rotation.rs). */
    private fun pivot(select: Int, neg: Int, a: Double, b: Double): DoubleArray {
        val o = if (neg and 1 == 0) 1.0 else -1.0
        val c = if (neg and 2 == 0) b else -b
        val d = if (neg and 4 == 0) a else -a
        return when (select) {
            0 -> doubleArrayOf(o, 0.0, 0.0, 0.0, a, b, 0.0, c, d)
            1 -> doubleArrayOf(0.0, o, 0.0, a, 0.0, b, c, 0.0, d)
            2 -> doubleArrayOf(0.0, 0.0, o, a, b, 0.0, c, d, 0.0)
            3 -> doubleArrayOf(0.0, a, b, o, 0.0, 0.0, 0.0, c, d)
            4 -> doubleArrayOf(a, 0.0, b, 0.0, o, 0.0, c, 0.0, d)
            5 -> doubleArrayOf(a, b, 0.0, 0.0, 0.0, o, c, d, 0.0)
            6 -> doubleArrayOf(0.0, a, b, 0.0, c, d, o, 0.0, 0.0)
            7 -> doubleArrayOf(a, 0.0, b, c, 0.0, d, 0.0, o, 0.0)
            8 -> doubleArrayOf(a, b, 0.0, c, d, 0.0, 0.0, 0.0, o)
            else -> doubleArrayOf(-a, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        }
    }

    private fun readMaterial(b: ByteArray, o: Int, name: String): Material {
        val misc = u16(b, o + 0x1E)
        var texScale: DoubleArray? = null
        if (misc and 1 != 0 && misc and 2 == 0 && o + 0x34 <= b.size) {
            texScale = doubleArrayOf(fx32(b, o + 0x2C), fx32(b, o + 0x30))
        }
        return Material(
            name,
            difAmb = s32(b, o + 4),
            speEmi = s32(b, o + 8),
            polyAttr = s32(b, o + 0x0C),
            teximage = s32(b, o + 0x14),
            width = u16(b, o + 0x20),
            height = u16(b, o + 0x22),
            texScale = texScale,
        )
    }

    private fun readOps(b: ByteArray, start: Int): List<Op> {
        val ops = ArrayList<Op>()
        var p = start
        while (p < b.size && ops.size < 100_000) {
            val op = u8(b, p++)
            fun arg(i: Int) = if (p + i < b.size) u8(b, p + i) else 0
            val len = when (op) {
                0x00 -> 0
                0x01 -> return ops
                0x02 -> 2
                0x03 -> { ops.add(Op(Op.LOAD, arg(0))); 1 }
                0x04, 0x24, 0x44 -> { ops.add(Op(Op.MATERIAL, arg(0))); 1 }
                0x05 -> { ops.add(Op(Op.DRAW, arg(0))); 1 }
                0x06 -> { ops.add(Op(Op.MUL_NODE, arg(0))); 3 }
                0x26 -> { ops.add(Op(Op.MUL_NODE, arg(0))); ops.add(Op(Op.STORE, arg(3))); 4 }
                0x46 -> { ops.add(Op(Op.LOAD, arg(3))); ops.add(Op(Op.MUL_NODE, arg(0))); 4 }
                0x66 -> { ops.add(Op(Op.LOAD, arg(4))); ops.add(Op(Op.MUL_NODE, arg(0))); ops.add(Op(Op.STORE, arg(3))); 5 }
                0x07, 0x08 -> 1
                0x09 -> {
                    val n = arg(1)
                    val terms = IntArray(n * 3) { arg(2 + it) }
                    ops.add(Op(Op.SKIN, terms = terms))
                    ops.add(Op(Op.STORE, arg(0)))
                    2 + 3 * n
                }
                0x0B -> { ops.add(Op(Op.SCALE_UP)); 0 }
                0x2B -> { ops.add(Op(Op.SCALE_DOWN)); 0 }
                0x0C, 0x0D -> 2
                0x40, 0x80 -> 0
                0x47 -> 2
                else -> return ops
            }
            p += len
        }
        return ops
    }

    // ---- turning a model into triangles -----------------------------------------------------------

    /** Lights and colours the way the DS works them out for a NORMAL command (GBATEK "DS 3D Lights"). */
    class Lighting(
        val lightDirs: Array<DoubleArray?>, // world-space direction each light shines in (normalised), null = off
        val lightColors: Array<DoubleArray>,
        /** Replaces lit materials' diffuse/ambient/specular/emission (HG/SS's area lights do). */
        val materialOverride: Array<DoubleArray>?,
    )

    /** Triangles sharing a texture and drawing state, in world space. */
    class Batch(val image: Image?, val alpha: Int, val drawBack: Boolean, val drawFront: Boolean, val mode: Int) {
        /** The material and texture it was made from (texture animations go by these names). */
        var materialName: String? = null
        var textureName: String? = null
        /** Per vertex: x, y, z, u, v (in texels), r, g, b (0..1). */
        var v = FloatArray(8 * 3 * 16)
        var count = 0 // vertices
        fun add(x: Double, y: Double, z: Double, s: Double, t: Double, c: DoubleArray) {
            if ((count + 1) * 8 > v.size) v = v.copyOf(v.size * 2)
            val o = count * 8
            v[o] = x.toFloat(); v[o + 1] = y.toFloat(); v[o + 2] = z.toFloat()
            v[o + 3] = s.toFloat(); v[o + 4] = t.toFloat()
            v[o + 5] = c[0].toFloat(); v[o + 6] = c[1].toFloat(); v[o + 7] = c[2].toFloat()
            count++
        }
        val triangles get() = count / 3
    }

    /**
     * The triangles of [model] placed by [place] (world = place x model). Textures come from
     * [texSets] (the first that has the material's texture); a vertex-coloured or lit model gets
     * its colours from [lighting].
     */
    fun buildMesh(model: Model, place: DoubleArray, texSets: List<TexSet>, lighting: Lighting?, out: MutableList<Batch>) {
        val stack = Array(32) { Mat4.identity() }
        var cur = Mat4.identity()
        var mat: Material? = null
        var scaled = false
        for (op in model.ops) {
            when (op.kind) {
                Op.LOAD -> cur = stack[op.a and 31].copyOf()
                Op.STORE -> stack[op.a and 31] = cur.copyOf()
                Op.MUL_NODE -> model.nodes.getOrNull(op.a)?.let { cur = Mat4.mul(cur, it) }
                Op.SCALE_UP -> { cur = Mat4.mul(cur, Mat4.scale(model.upScale, model.upScale, model.upScale)); scaled = true }
                Op.SCALE_DOWN -> cur = Mat4.mul(cur, Mat4.scale(model.downScale, model.downScale, model.downScale))
                Op.SKIN -> {
                    val t = op.terms ?: continue
                    val m = DoubleArray(16)
                    var k = 0
                    while (k + 2 < t.size) {
                        val inv = model.invBinds.getOrNull(t[k + 1]) ?: Mat4.identity()
                        val term = Mat4.mul(stack[t[k] and 31], inv)
                        val w = t[k + 2] / 256.0
                        for (i in 0 until 16) m[i] += term[i] * w
                        k += 3
                    }
                    cur = m
                }
                Op.MATERIAL -> mat = model.materials.getOrNull(op.a)
                Op.DRAW -> {
                    val piece = model.pieces.getOrNull(op.a) ?: continue
                    val m = mat ?: model.materials.firstOrNull() ?: continue
                    if (m.mode == 3) continue // shadow volume polygons draw nothing themselves
                    // a model that never scales itself up still has its vertices in posScale units
                    val world = if (!scaled && model.upScale != 1.0) Mat4.mul(place, Mat4.mul(cur, Mat4.scale(model.upScale, model.upScale, model.upScale)))
                                else Mat4.mul(place, cur)
                    runPiece(piece.cmds, world, stack, place, m, texSets, lighting, out)
                }
            }
        }
    }

    private fun rgb(c: Int) = doubleArrayOf((c and 31) / 31.0, ((c ushr 5) and 31) / 31.0, ((c ushr 10) and 31) / 31.0)

    private fun runPiece(cmds: ByteArray, startWorld: DoubleArray, stack: Array<DoubleArray>, place: DoubleArray, mat: Material,
                         texSets: List<TexSet>, lighting: Lighting?, out: MutableList<Batch>) {
        val image = texSets.firstNotNullOfOrNull { it.image(mat.texName, mat.palName, mat.teximage) }
        val batch = Batch(image, mat.alpha, mat.drawBack, mat.drawFront, mat.mode)
        batch.materialName = mat.name
        batch.textureName = mat.texName
        var world = startWorld
        val tw = (image?.width ?: mat.width).coerceAtLeast(1)
        val th = (image?.height ?: mat.height).coerceAtLeast(1)
        val ts = mat.texScale

        // material colours, possibly replaced by the area light
        val lit = mat.lights != 0 && lighting != null
        val ov = if (lit) lighting!!.materialOverride else null
        var dif = ov?.get(0) ?: rgb(mat.difAmb)
        var amb = ov?.get(1) ?: rgb(mat.difAmb ushr 16)
        var spe = ov?.get(2) ?: rgb(mat.speEmi)
        var emi = ov?.get(3) ?: rgb(mat.speEmi ushr 16)
        var color = if (mat.difAmb and 0x8000 != 0 || ov != null) dif.copyOf() else doubleArrayOf(1.0, 1.0, 1.0)

        var s = 0.0
        var t = 0.0
        var vx = 0.0; var vy = 0.0; var vz = 0.0
        var prim = 0
        val strip = ArrayList<DoubleArray>() // vertices of the current primitive: x,y,z,s,t,r,g,b

        fun emitTri(a: DoubleArray, b: DoubleArray, c: DoubleArray) {
            for (q in arrayOf(a, b, c)) batch.add(q[0], q[1], q[2], q[3], q[4], doubleArrayOf(q[5], q[6], q[7]))
        }

        fun flush() {
            val n = strip.size
            when (prim) {
                0 -> { var i = 0; while (i + 2 < n) { emitTri(strip[i], strip[i + 1], strip[i + 2]); i += 3 } }
                1 -> { var i = 0; while (i + 3 < n) { emitTri(strip[i], strip[i + 1], strip[i + 2]); emitTri(strip[i], strip[i + 2], strip[i + 3]); i += 4 } }
                2 -> { var i = 0; while (i + 2 < n) { if (i % 2 == 0) emitTri(strip[i], strip[i + 1], strip[i + 2]) else emitTri(strip[i + 1], strip[i], strip[i + 2]); i++ } }
                3 -> { var i = 0; while (i + 3 < n) { emitTri(strip[i], strip[i + 1], strip[i + 3]); emitTri(strip[i], strip[i + 3], strip[i + 2]); i += 2 } }
            }
            strip.clear()
        }

        fun vertex() {
            val p = Mat4.apply(world, vx, vy, vz)
            var ss = s
            var tt = t
            if (ts != null) { ss *= ts[0]; tt *= ts[1] }
            strip.add(doubleArrayOf(p[0], p[1], p[2], ss / tw, tt / th, color[0], color[1], color[2]))
        }

        var p = 0
        var ops = 0
        val nParams = PARAMS
        loop@ while (p + 4 <= cmds.size) {
            val packed = s32(cmds, p)
            p += 4
            for (k in 0 until 4) {
                val op = (packed ushr (8 * k)) and 0xFF
                if (op == 0 && k > 0) continue
                val n = if (op < nParams.size) nParams[op] else -1
                if (n < 0) break@loop
                if (p + n * 4 > cmds.size) break@loop
                fun par(i: Int) = s32(cmds, p + i * 4)
                when (op) {
                    0x14 -> world = Mat4.mul(place, stack[par(0) and 31])
                    0x1B -> world = Mat4.mul(world, Mat4.scale(par(0) / 4096.0, par(1) / 4096.0, par(2) / 4096.0))
                    0x1C -> world = Mat4.mul(world, Mat4.translate(par(0) / 4096.0, par(1) / 4096.0, par(2) / 4096.0))
                    0x20 -> color = rgb(par(0))
                    0x21 -> if (lighting != null && mat.lights != 0) {
                        val q = par(0)
                        val nx = sx10(q) / 512.0
                        val ny = sx10(q ushr 10) / 512.0
                        val nz = sx10(q ushr 20) / 512.0
                        val w = Mat4.applyDir(world, nx, ny, nz)
                        val len = sqrt(w[0] * w[0] + w[1] * w[1] + w[2] * w[2]).takeIf { it > 1e-9 } ?: 1.0
                        color = light(w[0] / len, w[1] / len, w[2] / len, mat.lights, lighting, dif, amb, spe, emi)
                    }
                    0x22 -> {
                        val q = par(0)
                        s = (q shl 16 shr 16) / 16.0
                        t = (q shr 16) / 16.0
                    }
                    0x23 -> {
                        vx = fx16(par(0)); vy = fx16(par(0) ushr 16); vz = fx16(par(1)); vertex()
                    }
                    0x24 -> {
                        val q = par(0)
                        vx = sx10(q) / 64.0; vy = sx10(q ushr 10) / 64.0; vz = sx10(q ushr 20) / 64.0; vertex()
                    }
                    0x25 -> { vx = fx16(par(0)); vy = fx16(par(0) ushr 16); vertex() }
                    0x26 -> { vx = fx16(par(0)); vz = fx16(par(0) ushr 16); vertex() }
                    0x27 -> { vy = fx16(par(0)); vz = fx16(par(0) ushr 16); vertex() }
                    0x28 -> {
                        val q = par(0)
                        vx += sx10(q) / 4096.0; vy += sx10(q ushr 10) / 4096.0; vz += sx10(q ushr 20) / 4096.0; vertex()
                    }
                    0x30 -> if (ov == null) {
                        val q = par(0)
                        dif = rgb(q); amb = rgb(q ushr 16)
                        if (q and 0x8000 != 0) color = dif.copyOf()
                    }
                    0x31 -> if (ov == null) { val q = par(0); spe = rgb(q); emi = rgb(q ushr 16) }
                    0x40 -> { flush(); prim = par(0) and 3 }
                    0x41 -> flush()
                }
                p += n * 4
                if (++ops > 400_000) break@loop
            }
        }
        flush()
        if (batch.count > 0) out.add(batch)
    }

    private fun sx10(v: Int) = (v and 0x3FF) shl 22 shr 22

    /** Vertex colour for a normal (world space, unit length). */
    private fun light(nx: Double, ny: Double, nz: Double, enabled: Int, l: Lighting,
                      dif: DoubleArray, amb: DoubleArray, spe: DoubleArray, emi: DoubleArray): DoubleArray {
        val c = emi.copyOf()
        for (i in 0 until 4) {
            if (enabled and (1 shl i) == 0) continue
            val dir = l.lightDirs.getOrNull(i) ?: continue
            val lc = l.lightColors[i]
            val d = maxOf(0.0, -(dir[0] * nx + dir[1] * ny + dir[2] * nz))
            // half vector with the line of sight, which for the field camera is about straight down-forward
            val hx = dir[0] + 0.0; val hy = dir[1] - 0.75; val hz = dir[2] - 0.66
            val hl = sqrt(hx * hx + hy * hy + hz * hz).takeIf { it > 1e-9 } ?: 1.0
            val sh = maxOf(0.0, -(hx * nx + hy * ny + hz * nz) / hl)
            val shine = sh * sh
            for (k in 0 until 3) c[k] += spe[k] * lc[k] * shine + dif[k] * lc[k] * d + amb[k] * lc[k]
        }
        for (k in 0 until 3) c[k] = c[k].coerceIn(0.0, 1.0)
        return c
    }

    /** Parameter words each GPU command takes (GBATEK "DS 3D Geometry Commands"); -1 unknown. */
    private val PARAMS = IntArray(0x80) { -1 }.also {
        it[0x00] = 0
        it[0x10] = 1; it[0x11] = 0; it[0x12] = 1; it[0x13] = 1; it[0x14] = 1; it[0x15] = 0
        it[0x16] = 16; it[0x17] = 12; it[0x18] = 16; it[0x19] = 12; it[0x1A] = 9; it[0x1B] = 3; it[0x1C] = 3
        for (op in 0x20..0x2B) it[op] = 1
        it[0x23] = 2
        it[0x30] = 1; it[0x31] = 1; it[0x32] = 1; it[0x33] = 1; it[0x34] = 32
        it[0x40] = 1; it[0x41] = 0
        it[0x50] = 1; it[0x60] = 1; it[0x70] = 3; it[0x71] = 2; it[0x72] = 1
    }
}

/** 4x4 matrices for column vectors (v' = M v), stored row-major in a DoubleArray(16). */
object Mat4 {
    fun identity() = DoubleArray(16).also { it[0] = 1.0; it[5] = 1.0; it[10] = 1.0; it[15] = 1.0 }

    fun mul(a: DoubleArray, b: DoubleArray): DoubleArray {
        val r = DoubleArray(16)
        for (i in 0 until 4) for (j in 0 until 4) {
            var s = 0.0
            for (k in 0 until 4) s += a[i * 4 + k] * b[k * 4 + j]
            r[i * 4 + j] = s
        }
        return r
    }

    fun scale(x: Double, y: Double, z: Double) = identity().also { it[0] = x; it[5] = y; it[10] = z }
    fun translate(x: Double, y: Double, z: Double) = identity().also { it[3] = x; it[7] = y; it[11] = z }

    /** A 3x3 given as three rows applied to row vectors (v M), as a column-vector matrix. */
    fun fromRows3(r: DoubleArray) = identity().also {
        for (i in 0 until 3) for (j in 0 until 3) it[j * 4 + i] = r[i * 3 + j]
    }

    fun rotX(a: Double) = identity().also { val c = kotlin.math.cos(a); val s = kotlin.math.sin(a); it[5] = c; it[6] = -s; it[9] = s; it[10] = c }
    fun rotY(a: Double) = identity().also { val c = kotlin.math.cos(a); val s = kotlin.math.sin(a); it[0] = c; it[2] = s; it[8] = -s; it[10] = c }
    fun rotZ(a: Double) = identity().also { val c = kotlin.math.cos(a); val s = kotlin.math.sin(a); it[0] = c; it[1] = -s; it[4] = s; it[5] = c }

    fun apply(m: DoubleArray, x: Double, y: Double, z: Double) = doubleArrayOf(
        m[0] * x + m[1] * y + m[2] * z + m[3],
        m[4] * x + m[5] * y + m[6] * z + m[7],
        m[8] * x + m[9] * y + m[10] * z + m[11],
    )

    fun applyDir(m: DoubleArray, x: Double, y: Double, z: Double) = doubleArrayOf(
        m[0] * x + m[1] * y + m[2] * z,
        m[4] * x + m[5] * y + m[6] * z,
        m[8] * x + m[9] * y + m[10] * z,
    )
}
