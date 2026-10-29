package me.magnum.melonds.pokewalker

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.util.Log
import java.io.FileInputStream
import java.nio.ByteBuffer

/**
 * Plays the route's music quietly while exploring the route map: the course's own tune read
 * from the HeartGold/SoulSilver ROM last started in melonDS ([RouteMusic]), played by
 * [SequencePlayer] on a thread of its own into an [AudioTrack], fading in and out. When the ROM
 * can't be read (or has no music where it should be), it stays quiet.
 */
object RouteMusicOut {
    private const val TAG = "RouteMusic"
    private const val RATE = 32_000
    private const val CHUNK = 1024

    private var thread: Thread? = null
    @Volatile private var wanted: Int = -1
    @Volatile private var fadeOut = false
    @Volatile private var failedRom: String? = null
    private var song: Pair<String, RouteMusic.Song>? = null

    /** How loud, 0 to 1 (it's always quiet). */
    @Volatile var volume = 0.55f

    /** Plays [course]'s music (or keeps playing it). */
    @Synchronized
    fun play(context: Context, course: FieldMap.Course) {
        val seq = RouteMusic.seqFor(course)
        val uri = Pokewalker.lastGameUri(context) ?: return
        if (uri == failedRom) return
        fadeOut = false
        if (wanted == seq && thread?.isAlive == true) return
        wanted = seq
        if (thread?.isAlive == true) return
        val app = context.applicationContext
        thread = Thread({ run(app) }, "RouteMusic").apply {
            priority = Thread.NORM_PRIORITY + 1
            isDaemon = true
            start()
        }
    }

    /** Fades out and stops. */
    fun stop() {
        fadeOut = true
    }

    private fun run(context: Context) {
        var track: AudioTrack? = null
        try {
            while (true) {
                val seq = wanted
                val uri = Pokewalker.lastGameUri(context) ?: return
                val s = load(context, uri, seq) ?: return
                val player = SequencePlayer(s, RATE)
                if (track == null) track = newTrack()
                track.play()
                val buf = ShortArray(CHUNK * 2)
                var gain = 0f
                while (true) {
                    // fade in, and out when asked (or when another course's music is wanted)
                    val leaving = fadeOut || wanted != seq
                    val target = if (leaving) 0f else volume
                    val step = CHUNK.toFloat() / RATE / (if (leaving) 0.6f else 1.6f)
                    gain = if (gain < target) minOf(target, gain + step) else maxOf(target, gain - step)
                    if (leaving && gain <= 0f) break
                    player.volume = gain
                    player.render(buf, CHUNK)
                    if (track.write(buf, 0, buf.size) < 0) return
                    if (!player.playing) break
                }
                if (fadeOut || !player.playing) return
            }
        } catch (e: CryArchive.FormatException) {
            Log.w(TAG, "No route music: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "Route music stopped", e)
        } finally {
            track?.let {
                runCatching { it.pause(); it.flush(); it.stop() }
                it.release()
            }
            synchronized(this) {
                if (thread === Thread.currentThread()) thread = null
                // asked to play again while it was fading out: start over
                if (!fadeOut && wanted >= 0 && thread == null) {
                    thread = Thread({ run(context) }, "RouteMusic").apply { isDaemon = true; start() }
                }
            }
        }
    }

    private fun newTrack(): AudioTrack {
        val min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(min * 2, CHUNK * 4 * 4))
            .build()
    }

    /** The sequence [seq] from the ROM at [uri] (kept for next time). */
    private fun load(context: Context, uri: String, seq: Int): RouteMusic.Song? {
        val key = "$uri|$seq"
        song?.let { if (it.first == key) return it.second }
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
                                if (n < 0) throw CryArchive.FormatException("short read")
                                at += n
                            }
                            return buf.array()
                        }
                    }
                    RouteMusic.load(src, seq).also { song = key to it }
                }
            }
        } catch (e: CryArchive.FormatException) {
            if (e.wholeRom) failedRom = uri
            PokewalkerNative.note("Route music not available: ${e.message}")
            null
        }
    }
}
