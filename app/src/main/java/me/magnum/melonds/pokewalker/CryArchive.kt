package me.magnum.melonds.pokewalker

import kotlin.math.pow

/**
 * Reads a Pokémon's cry from the player's own HeartGold/SoulSilver ROM. The game keeps its
 * sounds in a Nitro sound archive (`data/sound/gs_sound_data.sdat`) and plays a cry as the
 * sequence SEQ_PV with the Pokémon's own bank (bank number = national dex number). So:
 * species -> bank -> the instrument SEQ_PV plays -> its wave in the bank's wave archive,
 * decoded to 16-bit samples.
 *
 * Nothing from the game ships with the app: this only reads the ROM the player runs, the same
 * way the emulator does. Pure Kotlin (no Android) so it can be tested on its own.
 */
object CryArchive {
    const val SDAT_PATH = "data/sound/gs_sound_data.sdat"
    private const val SEQ_PV = 2
    private const val GROUP_SEQ = 0
    private const val GROUP_BANK = 2
    private const val GROUP_WAVEARC = 3
    private const val MAX_BLOCK = 8 shl 20

    /** Random access to the ROM file. */
    interface Source {
        val size: Long
        fun read(pos: Long, len: Int): ByteArray
    }

    class Cry(val samples: ShortArray, val sampleRate: Int)

    /** What didn't fit; [wholeRom] when no cry at all can come from this ROM. */
    class FormatException(message: String, val wholeRom: Boolean = false) : Exception(message)

    /** The cry of national dex [species] (1-493), or an exception saying what didn't fit. */
    fun load(src: Source, species: Int): Cry {
        if (species !in 1..493) throw FormatException("species $species")
        val (start, end) = findFile(src, SDAT_PATH) ?: throw FormatException("no $SDAT_PATH in the ROM", wholeRom = true)
        val sdat = Sdat(src, start, end - start)

        val bank = sdat.entry(GROUP_BANK, species, 12) ?: throw FormatException("no bank $species")
        val sbnk = sdat.file(sdat.u16(bank))
        val waveArcs = IntArray(4) { sdat.u16(bank + 4 + it * 2) }

        // what SEQ_PV plays: program and note (and any transpose / pitch bend)
        val play = sdat.entry(GROUP_SEQ, SEQ_PV, 12)?.let { runCatching { firstNote(sdat.file(sdat.u16(it))) }.getOrNull() }
        val region = instrument(sbnk, play?.program ?: 0, play?.note ?: 60)
            ?: instrument(sbnk, 0, play?.note ?: 60)
            ?: throw FormatException("no wave instrument in bank $species")
        val arcIndex = waveArcs.getOrNull(region.swar)?.takeIf { it != 0xFFFF } ?: throw FormatException("bank $species: no wave archive ${region.swar}")
        val arc = sdat.entry(GROUP_WAVEARC, arcIndex, 4) ?: throw FormatException("no wave archive $arcIndex")
        val (swarPos, swarSize) = sdat.fileRange(sdat.u16(arc))
        val wave = readWave(src, swarPos, swarSize, region.swav)

        val semitones = if (play != null) play.note - region.baseNote + play.bend else 0f
        val rate = (wave.sampleRate * 2.0.pow(semitones / 12.0)).toInt()
        return Cry(wave.samples, rate.coerceIn(2000, 96000))
    }

    // ---- the ROM's file system --------------------------------------------------------------

    /** Start and end of a file in an NDS ROM, found through its file name table. */
    fun findFile(src: Source, path: String): Pair<Long, Long>? {
        val h = src.read(0, 0x50)
        val fntOff = u32(h, 0x40)
        val fntSize = u32(h, 0x44)
        val fatOff = u32(h, 0x48)
        val fatSize = u32(h, 0x4C)
        if (fntSize <= 8 || fntSize > MAX_BLOCK || fatSize > MAX_BLOCK || fntOff + fntSize > src.size || fatOff + fatSize > src.size) return null
        val fnt = src.read(fntOff, fntSize.toInt())
        val parts = path.split('/')
        var dir = 0
        for ((i, part) in parts.withIndex()) {
            val last = i == parts.size - 1
            if (dir * 8 + 8 > fnt.size) return null
            var p = u32(fnt, dir * 8).toInt()
            var fileId = u16(fnt, dir * 8 + 4)
            var next = -1
            while (p < fnt.size) {
                val b = fnt[p++].toInt() and 0xFF
                if (b == 0) break
                val len = b and 0x7F
                if (p + len > fnt.size) return null
                val name = String(fnt, p, len, Charsets.ISO_8859_1)
                p += len
                if (b and 0x80 != 0) {
                    val id = u16(fnt, p) and 0x0FFF
                    p += 2
                    if (!last && name == part) { next = id; break }
                } else {
                    if (last && name == part) {
                        if ((fileId + 1) * 8L > fatSize) return null
                        val e = src.read(fatOff + fileId * 8L, 8)
                        val start = u32(e, 0)
                        val end = u32(e, 4)
                        return if (end > start && end <= src.size) start to end else null
                    }
                    fileId++
                }
            }
            if (next < 0) return null
            dir = next
        }
        return null
    }

    // ---- the sound archive --------------------------------------------------------------------

    private class Sdat(private val src: Source, private val base: Long, length: Long) {
        private val info: ByteArray
        private val fat: ByteArray

        init {
            val h = src.read(base, 0x40)
            if (tag(h, 0) != "SDAT") throw FormatException("not a sound archive", wholeRom = true)
            val infoOff = u32(h, 0x18)
            val infoSize = u32(h, 0x1C)
            val fatOff = u32(h, 0x20)
            val fatSize = u32(h, 0x24)
            if (infoSize !in 0x40..MAX_BLOCK || fatSize !in 12..MAX_BLOCK || infoOff + infoSize > length || fatOff + fatSize > length)
                throw FormatException("sound archive blocks", wholeRom = true)
            info = src.read(base + infoOff, infoSize.toInt())
            fat = src.read(base + fatOff, fatSize.toInt())
            if (tag(info, 0) != "INFO" || tag(fat, 0) != "FAT ") throw FormatException("sound archive tags", wholeRom = true)
        }

        fun u16(infoPos: Int) = CryArchive.u16(info, infoPos)

        /** Offset in the INFO block of entry [index] of [group] ([size] bytes), or null if there is none. */
        fun entry(group: Int, index: Int, size: Int): Int? {
            val rec = u32(info, 8 + group * 4).toInt()
            if (rec <= 0 || rec + 4 > info.size) return null
            val count = u32(info, rec)
            if (index < 0 || index >= count || rec + 8 + index * 4 > info.size) return null
            val off = u32(info, rec + 4 + index * 4).toInt()
            return if (off <= 0 || off + size > info.size) null else off
        }

        fun fileRange(fileId: Int): Pair<Long, Int> {
            val count = u32(fat, 8)
            if (fileId < 0 || fileId >= count || 12 + (fileId + 1) * 16 > fat.size) throw FormatException("file $fileId")
            val off = u32(fat, 12 + fileId * 16)
            val size = u32(fat, 12 + fileId * 16 + 4)
            if (size <= 0 || size > MAX_BLOCK) throw FormatException("file $fileId size $size")
            return (base + off) to size.toInt()
        }

        fun file(fileId: Int): ByteArray {
            val (pos, size) = fileRange(fileId)
            return src.read(pos, size)
        }
    }

    // ---- what the cry sequence plays ---------------------------------------------------------------

    class Play(val program: Int, val note: Int, val bend: Float)

    /** The first note of a sequence (SSEQ) with the program, transpose and pitch bend before it. */
    fun firstNote(sseq: ByteArray): Play? {
        if (tag(sseq, 0) != "SSEQ" || tag(sseq, 0x10) != "DATA") return null
        val start = u32(sseq, 0x18).toInt()
        var pos = start
        var program = 0
        var transpose = 0
        var bend = 0
        var bendRange = 2
        val tracks = ArrayDeque<Int>()
        var steps = 0
        fun varlen(): Int {
            var v = 0
            for (i in 0 until 4) {
                val b = sseq[pos++].toInt() and 0xFF
                v = (v shl 7) or (b and 0x7F)
                if (b and 0x80 == 0) break
            }
            return v
        }
        fun u24(): Int {
            val v = (sseq[pos].toInt() and 0xFF) or ((sseq[pos + 1].toInt() and 0xFF) shl 8) or ((sseq[pos + 2].toInt() and 0xFF) shl 16)
            pos += 3
            return v
        }
        try {
            while (steps++ < 4096 && pos < sseq.size) {
                val cmd = sseq[pos++].toInt() and 0xFF
                when {
                    cmd < 0x80 -> return Play(program, (cmd + transpose).coerceIn(0, 127), bend * bendRange / 128f)
                    cmd == 0x80 -> varlen()
                    cmd == 0x81 -> program = varlen()
                    cmd == 0x93 -> { pos++; tracks.addLast(start + u24()) }
                    cmd == 0x94 || cmd == 0x95 -> pos = start + u24()
                    cmd == 0xFE -> pos += 2
                    cmd in 0xB0..0xBD -> pos += 3
                    cmd == 0xC3 -> transpose = sseq[pos++].toInt()
                    cmd == 0xC4 -> bend = sseq[pos++].toInt()
                    cmd == 0xC5 -> bendRange = sseq[pos++].toInt() and 0xFF
                    cmd in 0xC0..0xD7 -> pos += 1
                    cmd in 0xE0..0xE3 -> pos += 2
                    cmd == 0xA2 || cmd == 0xFC || cmd == 0xFD -> Unit
                    cmd == 0xFF -> {
                        // end of this track: look in the next one it opened
                        pos = tracks.removeFirstOrNull() ?: return null
                        program = 0
                        transpose = 0
                        bend = 0
                    }
                    else -> return null // random / variable commands: not in a cry
                }
            }
        } catch (e: IndexOutOfBoundsException) {
            return null
        }
        return null
    }

    // ---- the bank's instrument ---------------------------------------------------------------------

    class Region(val swav: Int, val swar: Int, val baseNote: Int)

    /** The wave played for [note] by instrument [program] of a bank (SBNK), if it is a sampled one. */
    fun instrument(sbnk: ByteArray, program: Int, note: Int): Region? {
        if (tag(sbnk, 0) != "SBNK" || tag(sbnk, 0x10) != "DATA" || sbnk.size < 0x3C) return null
        val count = u32(sbnk, 0x38)
        if (program < 0 || program >= count || 0x3C + program * 4 + 4 > sbnk.size) return null
        val rec = 0x3C + program * 4
        val type = sbnk[rec].toInt() and 0xFF
        val off = u16(sbnk, rec + 1)
        fun region(at: Int, kind: Int): Region? {
            if (at + 5 > sbnk.size || (kind != 1 && kind != 4)) return null // PSG and noise have no wave
            return Region(u16(sbnk, at), u16(sbnk, at + 2), sbnk[at + 4].toInt() and 0xFF)
        }
        return when {
            type == 0 -> null
            type < 16 -> region(off, type)
            type == 16 -> {
                // drum set: one region per key from low to high
                if (off + 2 > sbnk.size) return null
                val low = sbnk[off].toInt() and 0xFF
                val high = sbnk[off + 1].toInt() and 0xFF
                if (high < low) return null
                val k = note.coerceIn(low, high) - low
                val e = off + 2 + k * 12
                if (e + 12 > sbnk.size) null else region(e + 2, u16(sbnk, e))
            }
            type == 17 -> {
                // key split: up to 8 regions, each up to a key (0 = unused)
                if (off + 8 > sbnk.size) return null
                var used = 0
                while (used < 8 && sbnk[off + used].toInt() != 0) used++
                if (used == 0) return null
                var i = 0
                while (i < used - 1 && note > (sbnk[off + i].toInt() and 0xFF)) i++
                val e = off + 8 + i * 12
                if (e + 12 > sbnk.size) null else region(e + 2, u16(sbnk, e))
            }
            else -> null
        }
    }

    // ---- the wave -------------------------------------------------------------------------------

    class Wave(val samples: ShortArray, val sampleRate: Int)

    /** Wave [index] of a wave archive (SWAR) at [pos] in the ROM, decoded. */
    fun readWave(src: Source, pos: Long, size: Int, index: Int): Wave {
        val head = src.read(pos, 0x3C)
        if (tag(head, 0) != "SWAR" || tag(head, 0x10) != "DATA") throw FormatException("not a wave archive")
        val count = u32(head, 0x38)
        if (index < 0 || index >= count || count > 4096) throw FormatException("wave $index of $count")
        val offsets = src.read(pos + 0x3C, (count * 4).toInt())
        val at = u32(offsets, index * 4)
        if (at + 12 > size) throw FormatException("wave offset")
        val info = src.read(pos + at, 12)
        val type = info[0].toInt() and 0xFF
        val rate = u16(info, 2)
        val words = u16(info, 6).toLong() + u32(info, 8)
        val bytes = (words * 4).coerceAtMost(size - at - 12)
        if (bytes <= 4 || rate <= 0) throw FormatException("empty wave")
        return Wave(decode(type, src.read(pos + at + 12, bytes.toInt())), rate)
    }

    private val STEP = intArrayOf(
        7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45, 50, 55, 60, 66, 73, 80, 88, 97,
        107, 118, 130, 143, 157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408, 449, 494, 544, 598, 658, 724, 796,
        876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871,
        5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899, 15289, 16818, 18500, 20350, 22385, 24623,
        27086, 29794, 32767,
    )
    private val INDEX = intArrayOf(-1, -1, -1, -1, 2, 4, 6, 8)

    /** 0 = signed 8-bit, 1 = signed 16-bit little endian, 2 = IMA-ADPCM (DS flavour). */
    fun decode(type: Int, data: ByteArray): ShortArray = when (type) {
        0 -> ShortArray(data.size) { (data[it].toInt() shl 8).toShort() }
        1 -> ShortArray(data.size / 2) { ((data[it * 2].toInt() and 0xFF) or (data[it * 2 + 1].toInt() shl 8)).toShort() }
        2 -> {
            var predictor = ((data[0].toInt() and 0xFF) or (data[1].toInt() shl 8)).toShort().toInt()
            var index = (data[2].toInt() and 0xFF).coerceIn(0, 88)
            val out = ShortArray((data.size - 4) * 2)
            var o = 0
            for (i in 4 until data.size) {
                val b = data[i].toInt() and 0xFF
                for (n in intArrayOf(b and 0xF, b shr 4)) {
                    val step = STEP[index]
                    var diff = step shr 3
                    if (n and 1 != 0) diff += step shr 2
                    if (n and 2 != 0) diff += step shr 1
                    if (n and 4 != 0) diff += step
                    predictor = if (n and 8 != 0) maxOf(predictor - diff, -0x7FFF) else minOf(predictor + diff, 0x7FFF)
                    index = (index + INDEX[n and 7]).coerceIn(0, 88)
                    out[o++] = predictor.toShort()
                }
            }
            out
        }
        else -> throw FormatException("wave type $type")
    }

    // ---- little-endian helpers ----------------------------------------------------------------------

    internal fun u16(b: ByteArray, at: Int): Int = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
    internal fun u32(b: ByteArray, at: Int): Long = (u16(b, at).toLong()) or (u16(b, at + 2).toLong() shl 16)
    private fun tag(b: ByteArray, at: Int): String = if (at + 4 <= b.size) String(b, at, 4, Charsets.ISO_8859_1) else ""
}
