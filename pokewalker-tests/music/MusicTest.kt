import me.magnum.melonds.pokewalker.CryArchive
import me.magnum.melonds.pokewalker.FieldMap
import me.magnum.melonds.pokewalker.RouteMusic
import me.magnum.melonds.pokewalker.SequencePlayer
import java.io.RandomAccessFile
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.system.exitProcess

/**
 * Checks the route music player ([RouteMusic], [SequencePlayer]) on the made-up sound archive
 * from gen_music_rom.py: the right sequence for a course, notes at the right pitch and time,
 * rests silent, loops going round, tracks panned apart, a square wave, a key split, a silent
 * track, loops and calls and variables and conditions, a random rest, the end of a sequence,
 * and a slow attack.
 *
 *   python3 pokewalker-tests/music/gen_music_rom.py /tmp/music.nds
 *   kotlinc -d /tmp/music.jar $P/CryArchive.kt $P/NitroFiles.kt $P/FieldMap.kt ... $P/RouteMusic.kt pokewalker-tests/music/MusicTest.kt
 *   java -cp /tmp/music.jar:$KOTLIN_HOME/lib/kotlin-stdlib.jar MusicTestKt /tmp/music.nds
 */
private var failed = 0
private var checks = 0

private fun check(ok: Boolean, what: String) {
    checks++
    if (!ok) {
        failed++
        println("FAIL: $what")
    }
}

private const val RATE = 32_000

/** [seconds] of [song] as left and right samples. */
private fun play(song: RouteMusic.Song, seconds: Double, player: SequencePlayer = SequencePlayer(song, RATE)): Pair<FloatArray, FloatArray> {
    val n = (seconds * RATE).toInt()
    val buf = ShortArray(1024 * 2)
    val l = FloatArray(n)
    val r = FloatArray(n)
    var at = 0
    while (at < n) {
        val k = minOf(1024, n - at)
        player.render(buf, k)
        for (i in 0 until k) {
            l[at + i] = buf[i * 2].toFloat()
            r[at + i] = buf[i * 2 + 1].toFloat()
        }
        at += k
    }
    return l to r
}

/** The pitch (Hz) between [from] and [to] seconds, by counting zero crossings; 0 when silent. */
private fun pitch(s: FloatArray, from: Double, to: Double): Double {
    val a = (from * RATE).toInt()
    val b = (to * RATE).toInt()
    if (rms(s, from, to) < 200) return 0.0
    var crossings = 0
    for (i in a + 1 until b) if ((s[i - 1] < 0f) != (s[i] < 0f)) crossings++
    return crossings / 2.0 / (to - from)
}

private fun rms(s: FloatArray, from: Double, to: Double): Double {
    val a = (from * RATE).toInt()
    val b = (to * RATE).toInt()
    var sum = 0.0
    for (i in a until b) sum += s[i].toDouble() * s[i]
    return sqrt(sum / (b - a))
}

private fun near(v: Double, want: Double, tolerance: Double = 0.03) = abs(v - want) <= want * tolerance

fun main(args: Array<String>) {
    val f = RandomAccessFile(args[0], "r")
    val src = object : CryArchive.Source {
        override val size = f.length()
        override fun read(pos: Long, len: Int): ByteArray { val b = ByteArray(len); f.seek(pos); f.readFully(b); return b }
    }

    // which sequence each course plays
    check(RouteMusic.seqFor(FieldMap.COURSES[0]) == 1028, "Route 29 plays SEQ_GS_R_1_29 (1028)")
    check(RouteMusic.seqFor(FieldMap.COURSES[4]) == 1018, "New Bark Town plays SEQ_GS_T_WAKABA (1018)")
    check(RouteMusic.seqFor(FieldMap.COURSES[1]) == 1073 && RouteMusic.seqFor(FieldMap.COURSES[5]) == 1073, "Ilex Forest and Union Cave play SEQ_GS_D_CHIKATSUURO")
    check(FieldMap.COURSES.all { RouteMusic.seqFor(it) in 1000..1200 }, "every course has its music")

    // A: notes, rests and a loop at 120 beats a minute (a quarter note is half a second)
    val a = RouteMusic.load(src, 1028)
    val (al, ar) = play(a, 4.5)
    check(near(pitch(al, 0.05, 0.45), 440.0), "A4 plays at 440 Hz (got ${pitch(al, 0.05, 0.45)})")
    check(near(pitch(ar, 0.05, 0.45), 440.0), "on both sides")
    check(rms(al, 0.6, 0.95) < 100, "the rest after it is silent (rms ${rms(al, 0.6, 0.95)})")
    check(near(pitch(al, 1.05, 1.45), 523.25), "C5 comes in at one second at 523 Hz (got ${pitch(al, 1.05, 1.45)})")
    check(rms(al, 1.6, 1.95) < 100, "then silence")
    check(near(pitch(al, 2.05, 2.45), 440.0) && near(pitch(al, 4.05, 4.45), 440.0), "and round again (the jump back)")
    check(abs(rms(al, 0.1, 0.4) - rms(ar, 0.1, 0.4)) < rms(al, 0.1, 0.4) * 0.05, "a centred track is as loud on both sides")
    // a note starts on its tick: there's sound just after one second, not just before
    check(rms(al, 0.97, 0.995) < 100 && rms(al, 1.015, 1.04) > 1000, "the second note starts on time")

    // B: three tracks; the square wave on the left, the key split's sine on the right, the third silent
    val b = RouteMusic.load(src, 1073)
    val (bl, br) = play(b, 2.0)
    check(near(pitch(bl, 0.1, 0.9), 261.6), "a square wave (PSG) C4 at 261.6 Hz on the left (got ${pitch(bl, 0.1, 0.9)})")
    check(near(pitch(br, 0.1, 0.9), 440.0), "the key split plays A3 on its own sine at 440 Hz on the right (got ${pitch(br, 0.1, 0.9)})")
    check(near(pitch(bl, 1.1, 1.9), 261.6) && near(pitch(br, 1.1, 1.9), 440.0), "both tracks go round")
    // the square wave: flat tops (most samples at the top or the bottom)
    val flat = (3200 until 28800).count { abs(abs(bl[it]) - bl.slice(3200 until 28800).maxOf { v -> abs(v) }) < 50f }
    check(flat > 20000, "a square wave is flat at its top and bottom ($flat of 25600 samples)")

    // C: a loop of calls, variables, conditions, a random rest, then the end (tempo 240: 24 ticks = 0.125 s)
    val c = RouteMusic.load(src, 1018)
    val cp = SequencePlayer(c, RATE)
    val (cl, _) = play(c, 1.6, cp)
    check(near(pitch(cl, 0.02, 0.115), 440.0, 0.05), "the first time round the loop (got ${pitch(cl, 0.02, 0.115)})")
    check(rms(cl, 0.14, 0.24) < 100, "the call's rest")
    check(near(pitch(cl, 0.27, 0.365), 440.0, 0.05) && near(pitch(cl, 0.52, 0.615), 440.0, 0.05), "the second and third times round")
    check(rms(cl, 0.64, 0.74) < 100, "three times only")
    check(near(pitch(cl, 0.77, 0.865), 523.25, 0.05), "a condition that holds plays its note (got ${pitch(cl, 0.77, 0.865)})")
    check(rms(cl, 0.885, 1.11) < 100, "one that doesn't, doesn't: its note is skipped, and the random rest is quiet")
    check(rms(cl, 1.15, 1.6) < 50, "the sequence ended")
    check(!cp.playing, "and the player says so")

    // D: a slow attack rises, a slow release falls away
    val d = RouteMusic.load(src, 1157)
    val (dl, _) = play(d, 2.0)
    val early = rms(dl, 0.0, 0.02)
    val held = rms(dl, 0.5, 0.9)
    check(early < held * 0.6 && held > 1000, "a slow attack starts quieter (${early.toInt()} then ${held.toInt()})")
    check(rms(dl, 1.0, 1.03) > 50 && rms(dl, 1.0, 1.03) < held, "and fades out slowly after the note")

    // volume
    val q = SequencePlayer(a, RATE).also { it.volume = 0.25f }
    val (ql, _) = play(a, 0.5, q)
    check(near(rms(ql, 0.1, 0.4), rms(al, 0.1, 0.4) * 0.25, 0.05), "the player's volume scales it")

    // a sequence the archive doesn't have
    check(runCatching { RouteMusic.load(src, 1083) }.isFailure, "no sequence: an error, not music")

    println("$checks checks, $failed failed")
    if (failed > 0) exitProcess(1)
}
