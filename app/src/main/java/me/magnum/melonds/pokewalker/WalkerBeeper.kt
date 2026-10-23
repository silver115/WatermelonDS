package me.magnum.melonds.pokewalker

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

/**
 * Short square-wave beeps for the walker's screen, like the device's buzzer.
 * Sound ids match WalkerDevice::Sound: 1 move, 2 select, 3 back, 4 good, 5 bad, 6 caught.
 */
class WalkerBeeper {
    private companion object {
        const val RATE = 22050
        const val TAG = "WalkerBeeper"
    }

    private val tracks = HashMap<Int, AudioTrack>()

    fun play(sound: Int, volume: Int) {
        if (volume <= 0) return
        try {
            val track = tracks.getOrPut(sound) { build(notesFor(sound) ?: return) }
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.stop()
            track.reloadStaticData()
            track.setVolume(if (volume >= 2) 0.8f else 0.3f)
            track.play()
        } catch (e: Exception) {
            Log.w(TAG, "Could not play a beep", e)
        }
    }

    fun release() {
        tracks.values.forEach { runCatching { it.release() } }
        tracks.clear()
    }

    /** (frequency in Hz, length in ms) pairs. */
    private fun notesFor(sound: Int): List<Pair<Int, Int>>? = when (sound) {
        1 -> listOf(2400 to 22)
        2 -> listOf(1800 to 30, 2400 to 40)
        3 -> listOf(2400 to 30, 1600 to 40)
        4 -> listOf(1600 to 40, 2000 to 40, 2600 to 60)
        5 -> listOf(520 to 130)
        6 -> listOf(1600 to 60, 2000 to 60, 2400 to 60, 3200 to 160)
        else -> null
    }

    private fun build(notes: List<Pair<Int, Int>>): AudioTrack {
        val gap = RATE * 6 / 1000
        val total = notes.sumOf { (_, ms) -> RATE * ms / 1000 + gap }
        val samples = ShortArray(total)
        var pos = 0
        for ((freq, ms) in notes) {
            val n = RATE * ms / 1000
            val half = RATE / (2 * freq).coerceAtLeast(1)
            for (i in 0 until n) samples[pos + i] = if ((i / half.coerceAtLeast(1)) % 2 == 0) 5000 else -5000
            pos += n + gap
        }
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(samples.size * 2)
            .build()
        track.write(samples, 0, samples.size)
        return track
    }
}
