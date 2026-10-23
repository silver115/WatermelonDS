package me.magnum.melonds.pokewalker

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Draws the virtual walker: its 96x64 four-shade screen, scaled up with crisp pixels,
 * in a simple handheld body with three buttons (left, middle, right).
 */
@SuppressLint("ViewConstructor")
class WalkerDeviceView(context: Context, private val onButton: (Int) -> Unit) : View(context), WalkerSkin {

    companion object {
        const val LCD_WIDTH = 96
        const val LCD_HEIGHT = 64
    }

    override val view: View get() = this

    override var shade = 4
        private set

    private val bitmap = Bitmap.createBitmap(LCD_WIDTH, LCD_HEIGHT, Bitmap.Config.ARGB_8888)
    private val pixels = IntArray(LCD_WIDTH * LCD_HEIGHT)
    private val shades = ByteArray(LCD_WIDTH * LCD_HEIGHT)
    private var palette = paletteFor(4)

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE9E7E1.toInt() }
    private val bodyEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFC9C6BE.toInt(); style = Paint.Style.STROKE }
    private val bezelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF39404C.toInt() }
    private val lcdPaint = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
    private val buttonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF39404C.toInt() }
    private val buttonPressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1C2028.toInt() }
    private val buttonRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB9B5AC.toInt(); style = Paint.Style.STROKE }

    private val body = RectF()
    private val bezel = RectF()
    private val lcd = Rect()
    private val src = Rect(0, 0, LCD_WIDTH, LCD_HEIGHT)
    private val buttonX = FloatArray(3)
    private var buttonY = 0f
    private val buttonR = FloatArray(3)
    private var corner = 0f
    private val pressed = BooleanArray(3)
    private val pointerButton = HashMap<Int, Int>()

    init {
        isFocusable = true
        setBackgroundColor(0xFF1D2230.toInt())
        redrawBitmap()
    }

    /** New picture from the walker (shades 0-3) and the walker's shade setting. */
    override fun setFrame(frame: ByteArray, newShade: Int) {
        if (newShade != shade) {
            shade = newShade
            palette = paletteFor(newShade)
        }
        System.arraycopy(frame, 0, shades, 0, min(frame.size, shades.size))
        redrawBitmap()
        invalidate()
    }

    /** Shows a hardware key press on the matching on-screen button. */
    override fun showPressed(button: Int, down: Boolean) {
        if (button in 0..2 && pressed[button] != down) {
            pressed[button] = down
            invalidate()
        }
    }

    private fun paletteFor(shade: Int): IntArray = LcdPalette.forShade(LcdPalette.CLASSIC, shade)

    private fun redrawBitmap() {
        for (i in pixels.indices) pixels[i] = palette[shades[i].toInt() and 3]
        bitmap.setPixels(pixels, 0, LCD_WIDTH, 0, 0, LCD_WIDTH, LCD_HEIGHT)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Body in LCD pixels: 8 padding, 96x64 screen, 10 gap, 22 for buttons, 8 padding.
        val unitsW = LCD_WIDTH + 16
        val unitsH = 8 + LCD_HEIGHT + 10 + 22 + 8
        var unit = floor(min(w * 0.94f / unitsW, h * 0.94f / unitsH))
        if (unit < 1f) unit = min(w.toFloat() / unitsW, h.toFloat() / unitsH)
        val bw = unitsW * unit
        val bh = unitsH * unit
        val left = (w - bw) / 2f
        val top = (h - bh) / 2f
        body.set(left, top, left + bw, top + bh)
        corner = 12f * unit
        bodyEdgePaint.strokeWidth = max(1f, unit / 2f)
        val lcdLeft = (left + 8 * unit).toInt()
        val lcdTop = (top + 8 * unit).toInt()
        lcd.set(lcdLeft, lcdTop, lcdLeft + (LCD_WIDTH * unit).toInt(), lcdTop + (LCD_HEIGHT * unit).toInt())
        bezel.set(lcd.left - 3 * unit, lcd.top - 3 * unit, lcd.right + 3 * unit, lcd.bottom + 3 * unit)
        buttonY = lcd.bottom + (10 + 11) * unit
        val cx = (body.left + body.right) / 2f
        buttonX[0] = cx - 30 * unit
        buttonX[1] = cx
        buttonX[2] = cx + 30 * unit
        buttonR[0] = 7 * unit
        buttonR[1] = 9 * unit
        buttonR[2] = 7 * unit
        buttonRimPaint.strokeWidth = max(1f, unit)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRoundRect(body, corner, corner, bodyPaint)
        canvas.drawRoundRect(body, corner, corner, bodyEdgePaint)
        canvas.drawRoundRect(bezel, corner / 4f, corner / 4f, bezelPaint)
        canvas.drawBitmap(bitmap, src, lcd, lcdPaint)
        for (i in 0..2) {
            canvas.drawCircle(buttonX[i], buttonY, buttonR[i] + buttonRimPaint.strokeWidth, buttonRimPaint)
            canvas.drawCircle(buttonX[i], buttonY, buttonR[i], if (pressed[i]) buttonPressedPaint else buttonPaint)
        }
    }

    private fun buttonAt(x: Float, y: Float): Int {
        var best = -1
        var bestDistance = Float.MAX_VALUE
        for (i in 0..2) {
            val d = hypot(x - buttonX[i], y - buttonY)
            // generous touch targets: twice the drawn radius
            if (d <= buttonR[i] * 2f && d < bestDistance) {
                best = i
                bestDistance = d
            }
        }
        return best
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                val button = buttonAt(event.getX(index), event.getY(index))
                if (button >= 0) {
                    pointerButton[event.getPointerId(index)] = button
                    pressed[button] = true
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onButton(button)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = event.getPointerId(event.actionIndex)
                pointerButton.remove(id)?.let { pressed[it] = false; invalidate() }
            }
            MotionEvent.ACTION_CANCEL -> {
                pointerButton.clear()
                pressed.fill(false)
                invalidate()
            }
        }
        return true
    }
}
