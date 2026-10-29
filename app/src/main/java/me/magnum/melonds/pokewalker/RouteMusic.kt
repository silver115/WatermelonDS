package me.magnum.melonds.pokewalker

import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The route's own music, from the player's HeartGold/SoulSilver ROM: the sequence the course's
 * map plays (its map header's music), with the sequence's instrument bank and wave archives
 * from the game's sound archive (`data/sound/gs_sound_data.sdat`), played by [SequencePlayer].
 * Nothing from the game ships with the app: this only reads the ROM the player runs. Pure Kotlin.
 */
object RouteMusic {
    private const val GROUP_SEQ = 0
    private const val GROUP_BANK = 2
    private const val GROUP_WAVEARC = 3

    /**
     * The music of each course's map, by map number (src/data/map_headers.h dayMusicId; the
     * night's is the same on all of them): SEQ_GS_R_1_29, SEQ_GS_D_CHIKATSUURO, ...
     */
    private val BY_MAP = mapOf(
        33 to 1028, 117 to 1073, 47 to 1035, 94 to 1034, 60 to 1018, 99 to 1073, 88 to 1037, 35 to 1029, 43 to 1036,
        75 to 1025, 57 to 1047, 40 to 1033, 176 to 1080, 152 to 1157, 120 to 1080, 147 to 1083, 46 to 1037, 91 to 1056,
        77 to 1024, 107 to 1073, 95 to 1034, 90 to 1085, 41 to 1033, 96 to 1075, 78 to 1023, 30 to 1062, 34 to 1029,
    )

    /** The sequence (SEQ number in the sound archive) the course's place plays. */
    fun seqFor(course: FieldMap.Course): Int = BY_MAP[course.map] ?: 1028

    /** A sequence (SSEQ), its instrument bank (SBNK), the bank's wave archives (SWAR) and its volume. */
    class Song(val sseq: ByteArray, val bank: ByteArray, val arcs: Array<ByteArray?>, val volume: Int)

    /** Reads sequence [seq] and what it plays with from the ROM, or an exception saying what didn't fit. */
    fun load(src: CryArchive.Source, seq: Int): Song {
        val (start, end) = CryArchive.findFile(src, CryArchive.SDAT_PATH)
            ?: throw CryArchive.FormatException("no ${CryArchive.SDAT_PATH} in the ROM", wholeRom = true)
        val sdat = CryArchive.Sdat(src, start, end - start)
        val e = sdat.entry(GROUP_SEQ, seq, 12) ?: throw CryArchive.FormatException("no sequence $seq")
        val sseq = sdat.file(sdat.u16(e))
        val bankNo = sdat.u16(e + 4)
        val volume = sdat.u8(e + 6)
        val b = sdat.entry(GROUP_BANK, bankNo, 12) ?: throw CryArchive.FormatException("no bank $bankNo")
        val sbnk = sdat.file(sdat.u16(b))
        val arcs = Array(4) { i ->
            val a = sdat.u16(b + 4 + i * 2)
            if (a == 0xFFFF) null else sdat.entry(GROUP_WAVEARC, a, 4)?.let { runCatching { sdat.file(sdat.u16(it)) }.getOrNull() }
        }
        if (sseq.size < 0x20 || String(sseq, 0, 4, Charsets.ISO_8859_1) != "SSEQ") throw CryArchive.FormatException("sequence $seq is not a sequence")
        if (sbnk.size < 0x3C || String(sbnk, 0, 4, Charsets.ISO_8859_1) != "SBNK") throw CryArchive.FormatException("bank $bankNo is not a bank")
        return Song(sseq, sbnk, arcs, volume)
    }
}

/** The waves of a wave archive (SWAR), decoded the first time each is played. */
class WaveArchive(private val data: ByteArray) {
    /**
     * A wave: its samples, the DS timer it plays at (16756991 / rate), whether it loops and
     * from which sample.
     */
    class Wave(val samples: ShortArray, val timer: Int, val loop: Boolean, val loopStart: Int)

    private val count = if (data.size >= 0x3C && String(data, 0, 4, Charsets.ISO_8859_1) == "SWAR") CryArchive.u32(data, 0x38).toInt().coerceIn(0, 4096) else 0
    private val waves = HashMap<Int, Wave?>()

    fun wave(index: Int): Wave? {
        if (index < 0 || index >= count) return null
        return waves.getOrPut(index) { runCatching { read(index) }.getOrNull() }
    }

    private fun read(index: Int): Wave? {
        val at = CryArchive.u32(data, 0x3C + index * 4).toInt()
        if (at <= 0 || at + 12 > data.size) return null
        val type = data[at].toInt() and 0xFF
        val loop = data[at + 1].toInt() != 0
        val timer = CryArchive.u16(data, at + 4)
        val loopWords = CryArchive.u16(data, at + 6)
        val words = loopWords + CryArchive.u32(data, at + 8).toInt()
        val bytes = (words * 4).coerceAtMost(data.size - at - 12)
        if (bytes <= 4 || timer <= 0) return null
        val samples = CryArchive.decode(type, data.copyOfRange(at + 12, at + 12 + bytes))
        val loopStart = when (type) {
            0 -> loopWords * 4
            1 -> loopWords * 2
            else -> (loopWords * 4 - 4).coerceAtLeast(0) * 2
        }.coerceIn(0, maxOf(0, samples.size - 1))
        return Wave(samples, timer, loop, loopStart)
    }
}

/**
 * Plays a [RouteMusic.Song] the way the DS's own sound driver does: the Nitro SDK's sequencer,
 * channels and envelopes as decompiled in pret/pokediamond (arm7/lib/src/SND_seq.c,
 * SND_exChannel.c, SND_bank.c, SND_util.c): 16 channels, a sound frame every 2728 x 64 ARM7
 * cycles (about 192 a second), the tempo counted against 240, notes with their instruments'
 * attack, decay, sustain and release, pitch bends, portamento, vibrato, loops, calls, jumps,
 * variables. [render] fills a stereo buffer at [sampleRate]. Pure Kotlin.
 */
class SequencePlayer(private val song: RouteMusic.Song, val sampleRate: Int = 32_000) {

    companion object {
        /** Sound frames a second: the ARM7's 33513982 Hz / 64 / 2728. */
        const val FRAMES_PER_SECOND = 33_513_982.0 / 64 / 2728
        /** The DS's sound clock (33513982 / 2): a timer of t plays this / t samples a second. */
        const val CLOCK = 16_756_991.0
        private const val TIMER_RATE = 240
        private const val VOL_MIN = -723

        /** SNDi_DecibelSquareTable: 0.1 dB of a level (0-127) squared, -72.2 dB at the bottom. */
        private val DB = IntArray(128) { i ->
            when (i) {
                0 -> -32768
                1 -> -722
                else -> (400.0 * log10(i / 127.0)).roundToInt()
            }
        }

        fun decibel(level: Int) = DB[level.coerceIn(0, 127)]

        /** How quickly attacks above 108 rise (sAttackCoeffTable). */
        private val ATTACK = intArrayOf(0x00, 0x01, 0x05, 0x0E, 0x1A, 0x26, 0x33, 0x3F, 0x49, 0x54, 0x5C, 0x64, 0x6D, 0x74, 0x7B, 0x7F, 0x84, 0x89, 0x8F)

        /** The vibrato's sine, a quarter of it. */
        private val SINE = IntArray(33) { (127 * sin(it * Math.PI / 64)).roundToInt() }

        /** Which channels are taken first. */
        private val ORDER = intArrayOf(4, 5, 6, 7, 2, 0, 3, 1, 8, 9, 10, 11, 14, 12, 15, 13)

        private const val ENV_ATTACK = 0
        private const val ENV_DECAY = 1
        private const val ENV_SUSTAIN = 2
        private const val ENV_RELEASE = 3
        private const val LFO_PITCH = 0
        private const val LFO_VOLUME = 1
        private const val LFO_PAN = 2
        private const val TYPE_PCM = 0
        private const val TYPE_PSG = 1
        private const val TYPE_NOISE = 2
        private const val MAX_CALL = 3

        fun attackCoeff(attack: Int) = if (attack < 109) 255 - attack else ATTACK[(127 - attack).coerceIn(0, 18)]

        fun decayCoeff(v: Int) = when {
            v == 127 -> 0xFFFF
            v == 126 -> 0x3C00
            v < 50 -> v * 2 + 1
            else -> 0x1E00 / (126 - v)
        }

        private fun sinIdx(x: Int): Int = when {
            x < 0x20 -> SINE[x]
            x < 0x40 -> SINE[0x40 - x]
            x < 0x60 -> -SINE[x - 0x40]
            else -> -SINE[(0x20 - (x - 0x60)).coerceIn(0, 32)]
        }
    }

    private val seq: ByteArray = song.sseq
    /** Where the sequence's commands start (offsets in it are from here). */
    private val base: Int = CryArchive.u32(seq, 0x18).toInt().coerceIn(0, seq.size)
    private val arcs = Array(4) { song.arcs.getOrNull(it)?.let { a -> WaveArchive(a) } }
    private val bank = song.bank
    private val instCount = if (bank.size >= 0x3C) CryArchive.u32(bank, 0x38).toInt().coerceIn(0, 4096) else 0

    private class Inst(val type: Int, val wave: Int, val arc: Int, val rootKey: Int, val attack: Int, val decay: Int, val sustain: Int, val release: Int, val pan: Int)

    private inner class Track {
        var pos = -1
        var noteWait = true
        var muted = false
        var tie = false
        var noteFinishWait = false
        var portamento = false
        var cmp = true
        val callStack = IntArray(MAX_CALL)
        val loopCount = IntArray(MAX_CALL)
        var depth = 0
        var program = 0
        var priority = 64
        var volume = 127
        var expression = 127
        var pan = 0
        var pitchBend = 0
        var envAttack = 255
        var envDecay = 255
        var envSustain = 255
        var envRelease = 255
        var panRange = 127
        var bendRange = 2
        var portamentoKey = 60
        var portamentoTime = 0
        var sweepPitch = 0
        var transpose = 0
        var modTarget = LFO_PITCH
        var modDepth = 0
        var modRange = 1
        var modSpeed = 16
        var modDelay = 0
        var wait = 0
        /** Its channels, newest first. */
        val channels = ArrayList<Chan>()
    }

    private inner class Chan(val id: Int) {
        var active = false
        var start = false
        var ended = false
        var type = TYPE_PCM
        var wave: WaveArchive.Wave? = null
        var timer = 8006
        var pos = 0.0
        var duty = 0
        var lfsr = 0x7FFF
        var noiseHigh = true
        var track: Track? = null
        var priority = 0
        var length = 0
        var midiKey = 60
        var rootKey = 60
        var velocity = 127
        var envAttack = 0
        var envDecay = 0xFFFF
        var envSustain = 127
        var envRelease = 0xFFFF
        var envAtt = -92544
        var envStatus = ENV_ATTACK
        var initPan = 0
        var userPan = 0
        var panRange = 127
        var userDecay = 0
        var userDecay2 = 0
        var userPitch = 0
        var sweepPitch = 0
        var sweepLength = 0
        var sweepCounter = 0
        var autoSweep = true
        var lfoTarget = LFO_PITCH
        var lfoDepth = 0
        var lfoRange = 1
        var lfoSpeed = 16
        var lfoDelay = 0
        var lfoCounter = 0
        var lfoDelayCounter = 0
        /** What it puts out now: left and right gain (and where they were), source steps an output sample. */
        var gainL = 0f
        var gainR = 0f
        var lastL = 0f
        var lastR = 0f
        var step = 0.0
        var loudness = VOL_MIN
    }

    private val tracks = Array(16) { Track() }
    /** Which of [tracks] the sequence has (by number), else null. */
    private val playerTracks = arrayOfNulls<Track>(16)
    private val chans = Array(16) { Chan(it) }
    private var tempo = 120
    private var tempoCounter = TIMER_RATE
    private var playerVolume = 127
    private val fader = decibel(song.volume)
    private val localVars = IntArray(16) { -1 }
    private val globalVars = IntArray(16) { -1 }
    private var randomState = 0x12345678L
    private var frameLeft = 0.0
    private val samplesPerFrame = sampleRate / FRAMES_PER_SECOND
    private var mix = FloatArray(0)

    /** Whether it's still playing (route music loops, so it is unless the sequence ended). */
    var playing = true
        private set

    /** How loud (0-1) on top of the sequence's own volume. */
    var volume = 1f

    init {
        val t0 = tracks[0]
        t0.pos = 0
        playerTracks[0] = t0
        if (u8(0) == 0xFE) {
            var mask = (u8(1) or (u8(2) shl 8)) shr 1
            t0.pos = 3
            var i = 1
            while (mask != 0 && i < 16) {
                if (mask and 1 != 0) playerTracks[i] = tracks[i]
                mask = mask shr 1
                i++
            }
        }
    }

    private fun u8(at: Int): Int = if (base + at in seq.indices) seq[base + at].toInt() and 0xFF else 0xFF

    private fun random(): Int {
        randomState = (randomState * 1664525L + 1013904223L) and 0xFFFFFFFFL
        return (randomState ushr 16).toInt() and 0xFFFF
    }

    /** Fills [out] with [frames] stereo sample frames (left, right, ...). */
    fun render(out: ShortArray, frames: Int) {
        if (mix.size < frames * 2) mix = FloatArray(frames * 2)
        java.util.Arrays.fill(mix, 0, frames * 2, 0f)
        var i = 0
        while (i < frames) {
            if (frameLeft < 1.0) {
                soundFrame()
                frameLeft += samplesPerFrame
            }
            val n = minOf(frames - i, frameLeft.toInt().coerceAtLeast(1))
            for (c in chans) if (c.active && !c.start) mixChannel(c, i, n)
            frameLeft -= n
            i += n
        }
        val g = 0.42f * volume
        for (k in 0 until frames * 2) {
            var v = mix[k] * g
            // a soft limit rather than a hard clip
            if (v > 24_000f) v = 24_000f + (v - 24_000f) * 0.25f
            if (v < -24_000f) v = -24_000f + (v + 24_000f) * 0.25f
            out[k] = v.coerceIn(-32_767f, 32_767f).toInt().toShort()
        }
    }

    // ---- one sound frame: the sequencer, then the channels ----

    private fun soundFrame() {
        if (playing) {
            var ticks = 0
            while (tempoCounter >= TIMER_RATE) {
                tempoCounter -= TIMER_RATE
                ticks++
            }
            for (k in 0 until ticks) {
                if (stepTicks()) {
                    stopAll()
                    break
                }
            }
            tempoCounter += tempo
            for (t in playerTracks) if (t != null) trackUpdateChannels(t, release = true)
        }
        channelsMain()
        random()
    }

    /** One tick for every track; true when they've all ended. */
    private fun stepTicks(): Boolean {
        var playingAny = false
        for (i in 0 until 16) {
            val t = playerTracks[i] ?: continue
            if (t.pos < 0) continue
            if (trackStepTicks(t)) {
                trackStop(t)
                playerTracks[i] = null
            } else playingAny = true
        }
        return !playingAny
    }

    private fun stopAll() {
        playing = false
        for (i in 0 until 16) playerTracks[i]?.let { trackStop(it) }
        java.util.Arrays.fill(playerTracks, null)
    }

    private fun trackStop(t: Track) {
        trackReleaseChannels(t, -1)
        trackFreeChannels(t)
    }

    private fun trackReleaseChannels(t: Track, release: Int) {
        trackUpdateChannels(t, release = false)
        for (c in t.channels) {
            if (!c.active) continue
            if (release >= 0) c.envRelease = decayCoeff(release and 0xFF)
            c.priority = 1
            c.envStatus = ENV_RELEASE
        }
    }

    private fun trackFreeChannels(t: Track) {
        for (c in t.channels) c.track = null
        t.channels.clear()
    }

    private fun trackUpdateChannels(t: Track, release: Boolean) {
        val vol = (decibel(t.volume) + decibel(t.expression) + decibel(playerVolume)).coerceAtLeast(-0x8000)
        var pitch = t.pitchBend * (t.bendRange shl 6)
        pitch = pitch shr 7
        var pan = t.pan
        if (t.panRange != 127) pan = (pan * t.panRange + 0x40) shr 7
        pan = pan.coerceIn(-128, 127)
        for (c in t.channels) {
            c.userDecay2 = fader
            if (c.envStatus == ENV_RELEASE) continue
            c.userDecay = vol
            c.userPitch = pitch
            c.userPan = pan
            c.panRange = t.panRange
            c.lfoTarget = t.modTarget
            c.lfoDepth = t.modDepth
            c.lfoRange = t.modRange
            c.lfoSpeed = t.modSpeed
            c.lfoDelay = t.modDelay
            if (c.length == 0 && release) {
                c.priority = 1
                c.envStatus = ENV_RELEASE
            }
        }
    }

    // ---- the sequence ----

    private fun read8(t: Track): Int = u8(t.pos++)
    private fun read16(t: Track): Int = read8(t) or (read8(t) shl 8)
    private fun read24(t: Track): Int = read8(t) or (read8(t) shl 8) or (read8(t) shl 16)
    private fun readVlv(t: Track): Int {
        var v = 0
        for (k in 0 until 4) {
            val b = read8(t)
            v = (v shl 7) or (b and 0x7F)
            if (b and 0x80 == 0) break
        }
        return v
    }

    private fun variable(i: Int): Int = if (i < 16) localVars[i] else globalVars[(i - 16).coerceIn(0, 15)]
    private fun setVariable(i: Int, v: Int) {
        val s = v.toShort().toInt()
        if (i < 16) localVars[i] = s else if (i - 16 < 16) globalVars[i - 16] = s
    }

    /** A command's value: [kind] 0 a byte, 1 two bytes, 2 a variable length one, 3 random, 4 a variable. */
    private fun value(t: Track, kind: Int): Int = when (kind) {
        0 -> read8(t)
        1 -> read16(t)
        2 -> readVlv(t)
        3 -> {
            val lo = read16(t).toShort().toInt()
            val hi = read16(t).toShort().toInt()
            val ran = random()
            ((ran * (hi - lo + 1)) shr 16) + lo
        }
        else -> variable(read8(t))
    }

    /** One tick of track [t]; true when it ended. */
    private fun trackStepTicks(t: Track): Boolean {
        for (c in t.channels) {
            if (c.length > 0) c.length--
            if (!c.autoSweep && c.sweepCounter < c.sweepLength) c.sweepCounter++
        }
        if (t.noteFinishWait) {
            if (t.channels.isNotEmpty()) return false
            t.noteFinishWait = false
        }
        if (t.wait > 0) {
            t.wait--
            if (t.wait > 0) return false
        }
        var guard = 0
        while (t.wait == 0 && !t.noteFinishWait) {
            // a broken sequence mustn't hang the music
            if (++guard > 4096 || t.pos < 0 || base + t.pos >= seq.size) return true
            var special = false
            var kind = 0
            var run = true
            var cmd = read8(t)
            if (cmd == 0xA2) {
                cmd = read8(t)
                run = t.cmp
            }
            if (cmd == 0xA0) {
                cmd = read8(t)
                kind = 3
                special = true
            }
            if (cmd == 0xA1) {
                cmd = read8(t)
                kind = 4
                special = true
            }
            if (cmd and 0x80 == 0) {
                val velocity = read8(t) and 0x7F
                val length = value(t, if (special) kind else 2)
                if (!run) continue
                val key = (cmd + t.transpose).coerceIn(0, 127)
                if (!t.muted) playNote(t, key, velocity, if (length > 0) length else -1)
                t.portamentoKey = key
                if (t.noteWait) {
                    t.wait = length
                    if (length == 0) t.noteFinishWait = true
                }
                continue
            }
            when (cmd and 0xF0) {
                0x80 -> {
                    val par = value(t, if (special) kind else 2)
                    if (!run) continue
                    when (cmd) {
                        0x80 -> t.wait = par
                        0x81 -> if (par < 0x10000) t.program = par
                    }
                }
                0x90 -> when (cmd) {
                    0x93 -> {
                        val n = read8(t)
                        val off = read24(t)
                        if (!run) continue
                        val nt = playerTracks.getOrNull(n)
                        if (nt != null && nt !== t) {
                            trackStop(nt)
                            resetTrack(nt)
                            nt.pos = off
                        }
                    }
                    0x94 -> {
                        val off = read24(t)
                        if (run) t.pos = off
                    }
                    0x95 -> {
                        val off = read24(t)
                        if (run && t.depth < MAX_CALL) {
                            t.callStack[t.depth] = t.pos
                            t.depth++
                            t.pos = off
                        }
                    }
                }
                0xC0, 0xD0 -> {
                    val par = value(t, if (special) kind else 0) and 0xFF
                    if (!run) continue
                    val signed = par.toByte().toInt()
                    when (cmd) {
                        0xC0 -> t.pan = par - 0x40
                        0xC1 -> t.volume = par and 0x7F
                        0xC2 -> playerVolume = par and 0x7F
                        0xC3 -> t.transpose = signed
                        0xC4 -> t.pitchBend = signed
                        0xC5 -> t.bendRange = par
                        0xC6 -> t.priority = par
                        0xC7 -> t.noteWait = par != 0
                        0xC8 -> {
                            t.tie = par != 0
                            trackReleaseChannels(t, -1)
                            trackFreeChannels(t)
                        }
                        0xC9 -> {
                            t.portamentoKey = (par + t.transpose).coerceIn(0, 127)
                            t.portamento = true
                        }
                        0xCA -> t.modDepth = par
                        0xCB -> t.modSpeed = par
                        0xCC -> t.modTarget = par
                        0xCD -> t.modRange = par
                        0xCE -> t.portamento = par != 0
                        0xCF -> t.portamentoTime = par
                        0xD0 -> t.envAttack = par
                        0xD1 -> t.envDecay = par
                        0xD2 -> t.envSustain = par
                        0xD3 -> t.envRelease = par
                        0xD4 -> if (t.depth < MAX_CALL) {
                            t.callStack[t.depth] = t.pos
                            t.loopCount[t.depth] = par
                            t.depth++
                        }
                        0xD5 -> t.expression = par and 0x7F
                        0xD7 -> when (par) {
                            0 -> t.muted = false
                            1 -> t.muted = true
                            2 -> { t.muted = true; trackReleaseChannels(t, -1) }
                            3 -> { t.muted = true; trackReleaseChannels(t, 127); trackFreeChannels(t) }
                        }
                    }
                }
                0xE0 -> {
                    val par = value(t, if (special) kind else 1).toShort().toInt()
                    if (!run) continue
                    when (cmd) {
                        0xE3 -> t.sweepPitch = par
                        0xE1 -> tempo = par and 0xFFFF
                        0xE0 -> t.modDelay = par and 0xFFFF
                    }
                }
                0xB0 -> {
                    val v = read8(t)
                    val par = value(t, if (special) kind else 1).toShort().toInt()
                    if (!run) continue
                    val cur = variable(v)
                    when (cmd) {
                        0xB0 -> setVariable(v, par)
                        0xB1 -> setVariable(v, cur + par)
                        0xB2 -> setVariable(v, cur - par)
                        0xB3 -> setVariable(v, cur * par)
                        0xB4 -> if (par != 0) setVariable(v, cur / par)
                        0xB5 -> setVariable(v, if (par >= 0) cur shl par else cur shr -par)
                        0xB6 -> {
                            val neg = par < 0
                            val range = if (neg) -par else par
                            val r = (random() * (range + 1)) shr 16
                            setVariable(v, if (neg) -r else r)
                        }
                        0xB8 -> t.cmp = cur == par
                        0xB9 -> t.cmp = cur >= par
                        0xBA -> t.cmp = cur > par
                        0xBB -> t.cmp = cur <= par
                        0xBC -> t.cmp = cur < par
                        0xBD -> t.cmp = cur != par
                    }
                }
                0xF0 -> {
                    if (!run) continue
                    when (cmd) {
                        0xFD -> if (t.depth != 0) {
                            t.depth--
                            t.pos = t.callStack[t.depth]
                        }
                        0xFC -> if (t.depth != 0) {
                            var n = t.loopCount[t.depth - 1]
                            var done = false
                            if (n != 0) {
                                n--
                                if (n == 0) {
                                    t.depth--
                                    done = true
                                }
                            }
                            if (!done) {
                                t.loopCount[t.depth - 1] = n
                                t.pos = t.callStack[t.depth - 1]
                            }
                        }
                        0xFF -> return true
                    }
                }
            }
        }
        return false
    }

    private fun resetTrack(t: Track) {
        t.pos = -1; t.noteWait = true; t.muted = false; t.tie = false; t.noteFinishWait = false; t.portamento = false; t.cmp = true
        t.depth = 0; t.program = 0; t.priority = 64; t.volume = 127; t.expression = 127; t.pan = 0; t.pitchBend = 0
        t.envAttack = 255; t.envDecay = 255; t.envSustain = 255; t.envRelease = 255; t.panRange = 127; t.bendRange = 2
        t.portamentoKey = 60; t.portamentoTime = 0; t.sweepPitch = 0; t.transpose = 0
        t.modTarget = LFO_PITCH; t.modDepth = 0; t.modRange = 1; t.modSpeed = 16; t.modDelay = 0; t.wait = 0
        t.channels.clear()
    }

    // ---- notes ----

    /** The instrument [program] plays [key] with (SND_ReadInstData), or null. */
    private fun instrument(program: Int, key: Int): Inst? {
        if (program < 0 || program >= instCount) return null
        val rec = 0x3C + program * 4
        if (rec + 4 > bank.size) return null
        val type = bank[rec].toInt() and 0xFF
        val off = CryArchive.u16(bank, rec + 1)
        fun param(at: Int, kind: Int): Inst? {
            if (at < 0 || at + 10 > bank.size) return null
            fun b(i: Int) = bank[at + i].toInt() and 0xFF
            return Inst(kind, CryArchive.u16(bank, at), CryArchive.u16(bank, at + 2), b(4), b(5), b(6), b(7), b(8), b(9))
        }
        return when (type) {
            1, 2, 3, 4, 5 -> param(off, type)
            16 -> {
                if (off + 2 > bank.size) return null
                val low = bank[off].toInt() and 0xFF
                val high = bank[off + 1].toInt() and 0xFF
                if (key < low || key > high) return null
                val e = off + 2 + (key - low) * 12
                if (e + 12 > bank.size) null else param(e + 2, bank[e].toInt() and 0xFF)
            }
            17 -> {
                if (off + 8 > bank.size) return null
                var i = 0
                while (key > (bank[off + i].toInt() and 0xFF)) {
                    i++
                    if (i >= 8) return null
                }
                val e = off + 8 + i * 12
                if (e + 12 > bank.size) null else param(e + 2, bank[e].toInt() and 0xFF)
            }
            else -> null
        }
    }

    private fun playNote(t: Track, key: Int, velocity: Int, length: Int) {
        var c: Chan? = null
        if (t.tie) {
            c = t.channels.firstOrNull()
            c?.let { it.midiKey = key; it.velocity = velocity }
        }
        if (c == null) {
            val inst = instrument(t.program, key) ?: return
            val allowed = when (inst.type) {
                1 -> 0xFFFF
                2 -> 0x3F00
                3 -> 0xC000
                else -> return
            }
            val ch = alloc(allowed, 64 + t.priority) ?: return
            if (!noteOn(ch, key, velocity, if (t.tie) -1 else length, inst)) {
                ch.priority = 0
                ch.active = false
                return
            }
            ch.track = t
            t.channels.add(0, ch)
            c = ch
        }
        if (t.envAttack != 0xFF) c.envAttack = attackCoeff(t.envAttack)
        if (t.envDecay != 0xFF) c.envDecay = decayCoeff(t.envDecay)
        if (t.envSustain != 0xFF) c.envSustain = t.envSustain
        if (t.envRelease != 0xFF) c.envRelease = decayCoeff(t.envRelease)
        c.sweepPitch = t.sweepPitch
        if (t.portamento) c.sweepPitch += ((t.portamentoKey - key) shl 6)
        if (t.portamentoTime == 0) {
            c.sweepLength = length
            c.autoSweep = false
        } else {
            val swp = t.portamentoTime * t.portamentoTime * kotlin.math.abs(c.sweepPitch)
            c.sweepLength = swp shr 11
        }
        c.sweepCounter = 0
    }

    /** A channel of [mask] for a note of [priority]: a free one, else the quietest of the least important (SND_AllocExChannel). */
    private fun alloc(mask: Int, priority: Int): Chan? {
        var best: Chan? = null
        for (id in ORDER) {
            if (mask and (1 shl id) == 0) continue
            val c = chans[id]
            if (best == null) { best = c; continue }
            if (c.priority > best.priority) continue
            if (c.priority != best.priority || best.loudness > c.loudness) best = c
        }
        if (best == null || priority < best.priority) return null
        best.track?.channels?.remove(best)
        best.track = null
        best.active = false
        // set up anew (ExChannelSetup)
        best.priority = priority
        best.length = 0
        best.start = false
        best.ended = false
        best.autoSweep = true
        best.midiKey = 60
        best.rootKey = 60
        best.velocity = 127
        best.initPan = 0
        best.userDecay = 0
        best.userDecay2 = 0
        best.userPitch = 0
        best.userPan = 0
        best.panRange = 127
        best.sweepPitch = 0
        best.sweepLength = 0
        best.sweepCounter = 0
        best.envAttack = attackCoeff(127)
        best.envDecay = decayCoeff(127)
        best.envSustain = 127
        best.envRelease = decayCoeff(127)
        best.lfoTarget = LFO_PITCH
        best.lfoDepth = 0
        best.lfoRange = 1
        best.lfoSpeed = 16
        best.lfoDelay = 0
        return best
    }

    /** Starts [c] playing [key] with [inst] for [length] ticks (-1: until it ends) (SND_NoteOn). */
    private fun noteOn(c: Chan, key: Int, velocity: Int, length: Int, inst: Inst): Boolean {
        var len = length
        var release = inst.release
        if (release == 0xFF) {
            len = -1
            release = 0
        }
        when (inst.type) {
            1 -> {
                val w = arcs.getOrNull(inst.arc)?.wave(inst.wave) ?: return false
                c.type = TYPE_PCM
                c.wave = w
                c.timer = w.timer
            }
            2 -> {
                if (c.id < 8 || c.id > 13) return false
                c.type = TYPE_PSG
                c.duty = inst.wave and 7
                c.timer = 8006
            }
            3 -> {
                if (c.id < 14) return false
                c.type = TYPE_NOISE
                c.timer = 8006
                c.lfsr = 0x7FFF
            }
            else -> return false
        }
        // ExChannelStart
        c.envAtt = -92544
        c.envStatus = ENV_ATTACK
        c.length = len
        c.lfoCounter = 0
        c.lfoDelayCounter = 0
        c.start = true
        c.active = true
        c.ended = false
        c.pos = 0.0
        c.midiKey = key
        c.rootKey = inst.rootKey
        c.velocity = velocity
        c.envAttack = attackCoeff(inst.attack)
        c.envDecay = decayCoeff(inst.decay)
        c.envSustain = inst.sustain
        c.envRelease = decayCoeff(release)
        c.initPan = inst.pan - 0x40
        return true
    }

    // ---- the channels each frame (SND_ExChannelMain) ----

    private fun channelsMain() {
        for (c in chans) {
            if (!c.active) continue
            if (c.start) {
                c.start = false
                c.pos = 0.0
                c.lastL = 0f
                c.lastR = 0f
            } else if (c.ended) {
                channelDone(c)
                continue
            }
            var vol = decibel(c.velocity)
            var pitch = (c.midiKey - c.rootKey) * 0x40
            vol += envelope(c)
            pitch += sweep(c)
            vol += c.userDecay + c.userDecay2
            pitch += c.userPitch
            val lfo = lfo(c)
            var pan = 0
            when (c.lfoTarget) {
                LFO_VOLUME -> if (vol > -0x8000) vol += lfo
                LFO_PAN -> pan += lfo
                else -> pitch += lfo
            }
            pan += c.initPan
            if (c.panRange != 127) pan = (pan * c.panRange + 0x40) shr 7
            pan += c.userPan
            if (c.envStatus == ENV_RELEASE && vol <= VOL_MIN) {
                channelDone(c)
                continue
            }
            val v = vol.coerceIn(VOL_MIN, 0)
            c.loudness = v
            val gain = 10.0.pow(v / 200.0).toFloat()
            val p = (pan + 0x40).coerceIn(0, 127)
            c.gainL = gain * (127 - p) / 127f
            c.gainR = gain * p / 127f
            // the timer for this pitch (SND_CalcTimer), as samples a second
            val timer = (c.timer * 2.0.pow(-pitch / 768.0)).coerceIn(16.0, 65535.0)
            val t = if (c.type == TYPE_PSG) (timer.toInt() and 0xFFFC).coerceAtLeast(16).toDouble() else timer
            c.step = CLOCK / t / sampleRate
        }
    }

    /** [c] is done: it leaves its track. */
    private fun channelDone(c: Chan) {
        c.track?.channels?.remove(c)
        c.track = null
        c.priority = 0
        c.active = false
        c.ended = false
        c.gainL = 0f
        c.gainR = 0f
        c.loudness = VOL_MIN
    }

    private fun envelope(c: Chan): Int {
        when (c.envStatus) {
            ENV_ATTACK -> {
                c.envAtt = -((-c.envAtt * c.envAttack) shr 8)
                if (c.envAtt == 0) c.envStatus = ENV_DECAY
            }
            ENV_DECAY -> {
                val sustain = decibel(c.envSustain) shl 7
                c.envAtt -= c.envDecay
                if (c.envAtt <= sustain) {
                    c.envAtt = sustain
                    c.envStatus = ENV_SUSTAIN
                }
            }
            ENV_RELEASE -> c.envAtt = (c.envAtt - c.envRelease).coerceAtLeast(-0x7FFFFF)
        }
        return c.envAtt shr 7
    }

    private fun sweep(c: Chan): Int {
        if (c.sweepPitch == 0 || c.sweepCounter >= c.sweepLength) return 0
        val r = (c.sweepPitch.toLong() * (c.sweepLength - c.sweepCounter) / c.sweepLength).toInt()
        if (c.autoSweep) c.sweepCounter++
        return r
    }

    private fun lfo(c: Chan): Int {
        var r = if (c.lfoDepth == 0 || c.lfoDelayCounter < c.lfoDelay) 0L else (sinIdx((c.lfoCounter ushr 8) and 0x7F) * c.lfoDepth * c.lfoRange).toLong()
        if (r != 0L) {
            r = when (c.lfoTarget) {
                LFO_VOLUME -> r * 60
                else -> r shl 6
            }
            r = r shr 14
        }
        if (c.lfoDelayCounter < c.lfoDelay) {
            c.lfoDelayCounter++
        } else {
            var tmp = c.lfoCounter + (c.lfoSpeed shl 6)
            tmp = tmp ushr 8
            while (tmp >= 0x80) tmp -= 0x80
            c.lfoCounter = ((c.lfoCounter + (c.lfoSpeed shl 6)) and 0xFF) or (tmp shl 8)
        }
        return r.toInt()
    }

    // ---- making the sound ----

    private fun mixChannel(c: Chan, from: Int, n: Int) {
        val l0 = c.lastL
        val r0 = c.lastR
        val l1 = c.gainL
        val r1 = c.gainR
        c.lastL = l1
        c.lastR = r1
        if (l0 == 0f && r0 == 0f && l1 == 0f && r1 == 0f && c.type == TYPE_PCM) {
            advance(c, n)
            return
        }
        val inv = 1f / n
        var o = from * 2
        when (c.type) {
            TYPE_PCM -> {
                val w = c.wave ?: return
                val s = w.samples
                val size = s.size
                var pos = c.pos
                for (k in 0 until n) {
                    if (pos >= size) {
                        if (w.loop && size > w.loopStart) {
                            while (pos >= size) pos -= (size - w.loopStart)
                        } else {
                            c.ended = true
                            break
                        }
                    }
                    val i = pos.toInt()
                    val f = (pos - i).toFloat()
                    val a = s[i].toFloat()
                    val next = if (i + 1 < size) s[i + 1].toFloat() else if (w.loop) s[w.loopStart].toFloat() else a
                    val v = a + (next - a) * f
                    val u = k * inv
                    mix[o] += v * (l0 + (l1 - l0) * u)
                    mix[o + 1] += v * (r0 + (r1 - r0) * u)
                    o += 2
                    pos += c.step
                }
                c.pos = pos
            }
            TYPE_PSG -> {
                var pos = c.pos
                val high = c.duty + 1
                for (k in 0 until n) {
                    val stepIndex = pos.toInt() and 7
                    val v = if (c.duty < 7 && stepIndex < high) 0x5000f else -0x5000f
                    val u = k * inv
                    mix[o] += v * (l0 + (l1 - l0) * u)
                    mix[o + 1] += v * (r0 + (r1 - r0) * u)
                    o += 2
                    pos += c.step
                    if (pos > 1_000_000.0) pos -= 1_000_000.0
                }
                c.pos = pos
            }
            else -> {
                var pos = c.pos
                for (k in 0 until n) {
                    val before = pos.toInt()
                    pos += c.step
                    var steps = pos.toInt() - before
                    while (steps-- > 0) {
                        val carry = c.lfsr and 1
                        c.lfsr = c.lfsr shr 1
                        if (carry != 0) {
                            c.noiseHigh = false
                            c.lfsr = c.lfsr xor 0x6000
                        } else c.noiseHigh = true
                    }
                    val v = if (c.noiseHigh) 0x5000f else -0x5000f
                    val u = k * inv
                    mix[o] += v * (l0 + (l1 - l0) * u)
                    mix[o + 1] += v * (r0 + (r1 - r0) * u)
                    o += 2
                    if (pos > 1_000_000.0) pos -= 1_000_000.0
                }
                c.pos = pos
            }
        }
    }

    /** Moves a silent wave along (so it still ends, and loops, on time). */
    private fun advance(c: Chan, n: Int) {
        val w = c.wave ?: return
        var pos = c.pos + c.step * n
        val size = w.samples.size
        if (pos >= size) {
            if (w.loop && size > w.loopStart) {
                val len = (size - w.loopStart).toDouble()
                pos = w.loopStart + (pos - w.loopStart) % len
            } else c.ended = true
        }
        c.pos = pos
    }
}
