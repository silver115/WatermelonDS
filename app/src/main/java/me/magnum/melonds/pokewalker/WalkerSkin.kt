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

    /** The day's step goal was just reached: celebrate. */
    fun celebrate() {}
}

/**
 * Lucario-inspired colours for the walker screen: deep blue-black, Lucario blue, cream
 * like its chest fur, and a touch of red like its eyes.
 */
object WalkerTheme {
    const val BG_TOP = 0xFF0D1220.toInt()
    const val BG_BOTTOM = 0xFF14213D.toInt()
    const val AURA = 0xFF3E7BC4.toInt()
    const val CARD = 0xFF181E2D.toInt()
    const val CARD_EDGE = 0xFF29344E.toInt()
    const val CONTROL = 0xFF222B41.toInt()
    const val SCREEN_FRAME = 0xFF090D16.toInt()
    const val INK = 0xFF0D1220.toInt()
    const val TEXT = 0xFFF3F5FA.toInt()
    const val TEXT_DIM = 0xFF9AA6BF.toInt()
    const val TEXT_FAINT = 0xFF5F6B88.toInt()
    const val BLUE = 0xFF4C8DE0.toInt()
    const val BLUE_LIGHT = 0xFF86B9F2.toInt()
    const val CREAM = 0xFFF2E2A2.toInt()
    const val GOLD = 0xFFF5D47E.toInt()
    const val RED = 0xFFE2525C.toInt()
}

/** The walker's four shades as colours, following its shade (contrast) setting. */
object LcdPalette {
    // lightest to darkest at the default shade setting (4)
    val CLASSIC = intArrayOf(0xFFC6CDB6.toInt(), 0xFF8F987F.toInt(), 0xFF575F4B.toInt(), 0xFF20261B.toInt())
    /** Cream screen with blue and black ink, to go with [WalkerTheme]. */
    val MODERN = intArrayOf(0xFFF5EDD6.toInt(), 0xFFB8C4D8.toInt(), 0xFF4E6A9E.toInt(), 0xFF161C2C.toInt())

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
