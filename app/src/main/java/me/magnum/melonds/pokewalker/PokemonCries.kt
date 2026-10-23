package me.magnum.melonds.pokewalker

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.nio.ByteBuffer

/**
 * Plays Pokémon cries, read from the HeartGold/SoulSilver ROM last started in melonDS (see
 * [CryArchive]). Cries are cached for the session; if the ROM isn't there (never started,
 * moved, a zipped copy...) the caller's fallback beep plays instead.
 */
object PokemonCries {
    private const val TAG = "PokemonCries"

    private val cache = LruCache<Int, CryArchive.Cry>(32)
    private var failedRom: String? = null
    private var track: AudioTrack? = null

    /** Plays [species]'s cry at the walker's [volume] (0 off, 1 low, 2 high); [fallback] runs on the main thread if it can't. */
    fun play(context: Context, species: Int, volume: Int, fallback: () -> Unit) {
        if (volume <= 0) return
        val app = context.applicationContext
        Pokewalker.scope.launch {
            val cry = load(app, species)
            if (cry == null) {
                withContext(Dispatchers.Main) { fallback() }
            } else {
                start(cry, volume)
            }
        }
    }

    /** Whether cries can be read at all (an HG/SS ROM has been started in melonDS). */
    fun available(context: Context): Boolean {
        val uri = Pokewalker.lastGameUri(context) ?: return false
        return uri != failedRom
    }

    private fun load(context: Context, species: Int): CryArchive.Cry? {
        synchronized(cache) { cache.get(species)?.let { return it } }
        val uri = Pokewalker.lastGameUri(context) ?: return null
        if (uri == failedRom) return null
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
                    CryArchive.load(src, species).also { synchronized(cache) { cache.put(species, it) } }
                }
            }
        } catch (e: CryArchive.FormatException) {
            // the same answer for every species: don't try this ROM again this session
            if (e.wholeRom) failedRom = uri
            Log.w(TAG, "No cry for $species: ${e.message}")
            PokewalkerNative.note("Cry for Pokémon #$species not available: ${e.message}")
            null
        } catch (e: Exception) {
            Log.w(TAG, "Could not read the cry for $species", e)
            null
        }
    }

    @Synchronized
    private fun start(cry: CryArchive.Cry, volume: Int) {
        try {
            track?.let {
                runCatching { it.stop() }
                it.release()
            }
            track = null
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(cry.sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(cry.samples.size * 2)
                .build()
            t.write(cry.samples, 0, cry.samples.size)
            t.setVolume(if (volume >= 2) 1f else 0.4f)
            t.play()
            track = t
        } catch (e: Exception) {
            Log.w(TAG, "Could not play a cry", e)
        }
    }

    @Synchronized
    fun release() {
        track?.let {
            runCatching { it.stop() }
            it.release()
        }
        track = null
    }
}
