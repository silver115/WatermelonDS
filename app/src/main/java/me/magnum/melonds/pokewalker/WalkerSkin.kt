package me.magnum.melonds.pokewalker

import android.graphics.Bitmap
import android.graphics.Color
import android.view.View

/** A look for the walker screen (classic handheld or modern). */
interface WalkerSkin {
    val view: View
    val shade: Int

    /** New picture from the walker (96x64 shades 0-3) and its shade setting (0-9). */
    fun setFrame(frame: ByteArray, newShade: Int)

    /** Shows a hardware key press on the matching on-screen button (0 left, 1 middle, 2 right). */
    fun showPressed(button: Int, down: Boolean)

    /** Walker numbers for skins that show more than the screen. */
    fun setStatus(status: WalkerStatus?, stepGoal: Int) {}
}

/** The walker's four shades as colours, following its shade (contrast) setting. */
object LcdPalette {
    // lightest to darkest at the default shade setting (4)
    val CLASSIC = intArrayOf(0xFFC6CDB6.toInt(), 0xFF8F987F.toInt(), 0xFF575F4B.toInt(), 0xFF20261B.toInt())
    val MODERN = intArrayOf(0xFFEAF0F7.toInt(), 0xFFA9B6CB.toInt(), 0xFF56637D.toInt(), 0xFF141B2B.toInt())

    fun forShade(base: IntArray, shade: Int): IntArray {
        // Shade 0 is the lightest picture, 9 the darkest; 4 is the walker's default.
        val t = (shade - 4) / 5f
        return IntArray(4) { i ->
            when {
                i == 0 -> base[0]
                t >= 0 -> mix(base[i], 0xFF000000.toInt(), t * 0.55f)
                else -> mix(base[i], base[0], -t * 0.6f)
            }
        }
    }

    fun mix(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        fun ch(shift: Int) = ((((a shr shift) and 0xFF) * (1 - k) + ((b shr shift) and 0xFF) * k).toInt()) and 0xFF
        return Color.argb(255, ch(16), ch(8), ch(0))
    }

    /** The 96x64 picture as a bitmap, each walker pixel scale x scale screen pixels (no blur). */
    fun render(frame: ByteArray, palette: IntArray, scale: Int): Bitmap {
        val w = WalkerDeviceView.LCD_WIDTH
        val h = WalkerDeviceView.LCD_HEIGHT
        val pixels = IntArray(w * scale * h * scale)
        for (y in 0 until h * scale) {
            val row = (y / scale) * w
            for (x in 0 until w * scale) pixels[y * w * scale + x] = palette[frame[row + x / scale].toInt() and 3]
        }
        return Bitmap.createBitmap(pixels, w * scale, h * scale, Bitmap.Config.ARGB_8888)
    }
}
