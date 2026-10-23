package me.magnum.melonds.pokewalker

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.TypedValue
import android.view.View
import kotlin.math.min
import kotlin.math.sin

/** A short burst of confetti and a "goal reached" banner over a view, for the day's step goal. */
class Confetti(private val view: View, private val message: String) {
    private var start = 0L
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val pill = RectF()
    private val colors = intArrayOf(
        WalkerTheme.BLUE, WalkerTheme.CREAM, WalkerTheme.RED, WalkerTheme.BLUE_LIGHT, WalkerTheme.GOLD, 0xFFFFFFFF.toInt(),
    )

    fun start() {
        start = SystemClock.uptimeMillis()
        view.invalidate()
    }

    fun draw(canvas: Canvas) {
        if (start == 0L) return
        val t = (SystemClock.uptimeMillis() - start) / 1000f
        if (t > 3.4f) {
            start = 0L
            return
        }
        val w = view.width.toFloat()
        val h = view.height.toFloat()
        val d = view.resources.displayMetrics.density
        val fade = ((3.4f - t) / 0.6f).coerceIn(0f, 1f)
        for (i in 0 until 110) {
            val delay = RouteScene.rnd(i, 201) * 0.7f
            val tt = t - delay
            if (tt <= 0f) continue
            val x = RouteScene.rnd(i, 202) * w + sin(tt * 3f + i).toFloat() * w * 0.03f + (RouteScene.rnd(i, 203) - 0.5f) * w * 0.12f * tt
            val y = -12f * d + tt * h * (0.28f + 0.22f * RouteScene.rnd(i, 204)) + tt * tt * h * 0.05f
            if (y > h) continue
            paint.color = colors[i % colors.size]
            paint.alpha = (255 * fade).toInt()
            val s = (4f + 3f * RouteScene.rnd(i, 205)) * d
            canvas.save()
            canvas.rotate(tt * 360f * (0.6f + RouteScene.rnd(i, 206)) + i * 37f, x, y)
            canvas.drawRect(x - s, y - s * 0.45f, x + s, y + s * 0.45f, paint)
            canvas.restore()
        }
        // the banner, popping in and out
        val pop = min(1f, t / 0.25f) * fade
        if (pop > 0f) {
            text.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 18f, view.resources.displayMetrics)
            val tw = text.measureText(message)
            val ph = 44f * d
            val pw = tw + 40f * d
            val cy = h * 0.14f
            pill.set(w / 2 - pw / 2, cy - ph / 2, w / 2 + pw / 2, cy + ph / 2)
            paint.color = WalkerTheme.CREAM
            paint.alpha = (255 * pop).toInt()
            canvas.drawRoundRect(pill, ph / 2, ph / 2, paint)
            text.color = WalkerTheme.INK
            text.alpha = (255 * pop).toInt()
            canvas.drawText(message, w / 2, cy + text.textSize * 0.35f, text)
        }
        view.postInvalidateOnAnimation()
    }
}
