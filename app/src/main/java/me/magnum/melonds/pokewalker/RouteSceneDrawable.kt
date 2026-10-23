package me.magnum.melonds.pokewalker

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.SystemClock
import java.util.Calendar

/** [SceneCanvas] on an Android canvas. */
class AndroidSceneCanvas : SceneCanvas {
    var canvas: Canvas? = null
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val path = Path()

    override fun gradientRect(l: Float, t: Float, r: Float, b: Float, top: Int, bottom: Int) {
        fill.shader = LinearGradient(0f, t, 0f, b, top, bottom, Shader.TileMode.CLAMP)
        canvas?.drawRect(l, t, r, b, fill)
        fill.shader = null
    }

    override fun polygon(xy: FloatArray, color: Int) {
        if (xy.size < 6) return
        path.reset()
        path.moveTo(xy[0], xy[1])
        var i = 2
        while (i + 1 < xy.size) {
            path.lineTo(xy[i], xy[i + 1])
            i += 2
        }
        path.close()
        fill.color = color
        canvas?.drawPath(path, fill)
    }

    override fun circle(cx: Float, cy: Float, r: Float, color: Int) {
        fill.color = color
        canvas?.drawCircle(cx, cy, r, fill)
    }

    override fun line(x0: Float, y0: Float, x1: Float, y1: Float, width: Float, color: Int) {
        stroke.color = color
        stroke.strokeWidth = width
        canvas?.drawLine(x0, y0, x1, y1, stroke)
    }

    override fun glow(cx: Float, cy: Float, r: Float, inner: Int) {
        if (r <= 0f) return
        fill.shader = RadialGradient(cx, cy, r, inner, inner and 0x00FFFFFF, Shader.TileMode.CLAMP)
        canvas?.drawCircle(cx, cy, r, fill)
        fill.shader = null
    }
}

/**
 * The walk's surroundings behind the whole walker screen: the route's landscape under a live
 * sky, gently dimmed so the cards and buttons on top stay easy to read, and optionally the
 * walking Pokémon strolling along the ground. Call [invalidateSelf] now and then to animate.
 */
class RouteSceneDrawable(private val density: Float) : Drawable() {
    val scene = RouteScene()
    private val sceneCanvas = AndroidSceneCanvas()
    private val scrim = Paint()
    private val spritePaint = Paint().apply { isFilterBitmap = false }
    private val src = Rect()
    private val dst = RectF()

    /** The walking Pokémon's two frames (null: nobody walking). */
    var walker: Array<Bitmap>? = null
        set(value) { field = value; invalidateSelf() }

    /** Show [walker] strolling along the bottom (the classic look). */
    var showWalker = false
        set(value) { field = value; invalidateSelf() }

    /** Height of the navigation bar drawn over the bottom (the walker strolls above it). */
    var bottomInset = 0f

    /** Darken the left edge too (where the side rail is in landscape). */
    var railSide = false
        set(value) { field = value; invalidateSelf() }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        val cal = Calendar.getInstance()
        val minute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val ms = SystemClock.uptimeMillis()
        canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        canvas.clipRect(0f, 0f, w, h)
        sceneCanvas.canvas = canvas
        scene.paint(sceneCanvas, w, h, minute, ms)
        sceneCanvas.canvas = null

        // dim it a little, and more under the bars where text and icons sit
        canvas.drawColor(0x2E000000)
        val top = 96f * density
        scrim.shader = LinearGradient(0f, 0f, 0f, top, 0x8C000000.toInt(), 0x00000000, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, top, scrim)
        if (railSide) {
            val side = 88f * density
            scrim.shader = LinearGradient(0f, 0f, side, 0f, 0x73000000, 0x00000000, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, side, h, scrim)
        }
        scrim.shader = null

        val frames = walker
        if (showWalker && frames != null && frames.isNotEmpty()) {
            // strolling left to right along the ground, two steps a second
            val scale = (2.5f * density).coerceAtLeast(2f)
            val sw = frames[0].width * scale
            val sh = frames[0].height * scale
            val span = w + sw
            val x = (ms * 0.03f * density) % span - sw
            val y = h - bottomInset - sh - 8f * density
            val frame = frames[((ms / 500) % frames.size).toInt()]
            src.set(0, 0, frame.width, frame.height)
            dst.set(x, y, x + sw, y + sh)
            canvas.drawBitmap(frame, src, dst, spritePaint)
        }
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE

    companion object {
        /**
         * The walking Pokémon's small two-frame sprite from the walker's memory, cut out of its
         * background: the light area outside the outline is see-through, inside it stays light.
         */
        fun walkerSprite(image: ByteArray, palette: IntArray): Array<Bitmap>? {
            val addr = 0x91BE // walking Pokémon, small: 2 frames of 32x24
            val w = 32
            val h = 24
            val frameBytes = w * h / 4
            if (image.size < addr + frameBytes * 2) return null
            var any = false
            val frames = Array(2) { f ->
                val shades = IntArray(w * h)
                val base = addr + f * frameBytes
                for (s in 0 until h / 8) for (c in 0 until w) {
                    val hi = image[base + (s * w + c) * 2].toInt() and 0xFF
                    val lo = image[base + (s * w + c) * 2 + 1].toInt() and 0xFF
                    for (j in 0 until 8) shades[(s * 8 + j) * w + c] = (((hi shr j) and 1) shl 1) or ((lo shr j) and 1)
                }
                if (shades.any { it != 0 }) any = true
                // flood the light background in from the edges
                val outside = BooleanArray(w * h)
                val stack = ArrayDeque<Int>()
                for (x in 0 until w) { stack.add(x); stack.add((h - 1) * w + x) }
                for (y in 0 until h) { stack.add(y * w); stack.add(y * w + w - 1) }
                while (stack.isNotEmpty()) {
                    val p = stack.removeLast()
                    if (outside[p] || shades[p] != 0) continue
                    outside[p] = true
                    val x = p % w
                    val y = p / w
                    if (x > 0) stack.add(p - 1)
                    if (x < w - 1) stack.add(p + 1)
                    if (y > 0) stack.add(p - w)
                    if (y < h - 1) stack.add(p + w)
                }
                val pixels = IntArray(w * h) { if (outside[it]) 0 else palette[shades[it]] }
                Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
            }
            return if (any) frames else null
        }
    }
}
