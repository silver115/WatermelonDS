package me.magnum.melonds.pokewalker

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Touchscreen use of the walker's own screen, shared by both looks: a tap acts on what is
 * under the finger (the walker decides what that is, see `WalkerDevice::Touch`), and a
 * sideways swipe is the left or right button (swipe left = next, like turning a page).
 * A soft ripple shows where the screen was touched.
 */
class LcdGestures(
    private val view: View,
    private val onTap: (x: Int, y: Int) -> Unit,
    private val onSwipe: (button: Int) -> Unit,
) {
    private val slop = ViewConfiguration.get(view.context).scaledTouchSlop.toFloat()
    private val density = view.resources.displayMetrics.density
    private var pointer = -1
    private var downX = 0f
    private var downY = 0f
    private var moved = false

    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var rippleX = 0f
    private var rippleY = 0f
    private var rippleAt = 0L

    /** Colour of the touch ripple (drawn with transparency). */
    var rippleColor = 0xFFFFFFFF.toInt()

    /**
     * Handles [event] if it belongs to a touch that started on [screen] (the walker's screen,
     * in view coordinates). Returns true when it did; other touches are left to the caller.
     */
    fun onTouch(event: MotionEvent, screen: Rect): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val x = event.x
                val y = event.y
                if (screen.isEmpty || !screen.contains(x.toInt(), y.toInt())) return false
                pointer = event.getPointerId(0)
                downX = x
                downY = y
                moved = false
                rippleX = x
                rippleY = y
                rippleAt = SystemClock.uptimeMillis()
                view.invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (pointer < 0) return false
                val i = event.findPointerIndex(pointer)
                if (i >= 0 && (abs(event.getX(i) - downX) > slop || abs(event.getY(i) - downY) > slop)) moved = true
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (pointer < 0 || event.getPointerId(event.actionIndex) != pointer) return false
                pointer = -1
                val dx = event.getX(event.actionIndex) - downX
                val dy = event.getY(event.actionIndex) - downY
                val swipe = max(40f * density, screen.width() * 0.12f)
                if (abs(dx) >= swipe && abs(dx) >= abs(dy) * 1.5f) {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onSwipe(if (dx < 0) 2 else 0)
                } else if (!moved) {
                    val lx = ((downX - screen.left) * WalkerDeviceView.LCD_WIDTH / screen.width()).toInt()
                    val ly = ((downY - screen.top) * WalkerDeviceView.LCD_HEIGHT / screen.height()).toInt()
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onTap(lx.coerceIn(0, WalkerDeviceView.LCD_WIDTH - 1), ly.coerceIn(0, WalkerDeviceView.LCD_HEIGHT - 1))
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                val had = pointer >= 0
                pointer = -1
                return had
            }
        }
        return false
    }

    /** Draws the ripple over [screen] while it fades; keeps redrawing until it is gone. */
    fun draw(canvas: Canvas, screen: Rect) {
        if (rippleAt == 0L) return
        val t = (SystemClock.uptimeMillis() - rippleAt) / 380f
        if (t >= 1f) {
            rippleAt = 0L
            return
        }
        val k = min(1f, t)
        ripplePaint.color = rippleColor
        ripplePaint.alpha = ((1f - k) * 110).toInt()
        canvas.save()
        canvas.clipRect(screen)
        canvas.drawCircle(rippleX, rippleY, (12f + 30f * k) * density, ripplePaint)
        canvas.restore()
        view.postInvalidateOnAnimation()
    }
}
