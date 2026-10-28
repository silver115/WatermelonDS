package me.magnum.melonds.pokewalker

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * A small software rasterizer for drawing the game's 3D areas into a picture once: textured,
 * vertex-coloured triangles with a depth buffer, see-through texels and translucent polygons,
 * a bit like the DS's own 3D engine. Pure Kotlin so it can be tested on its own.
 */
class SoftRenderer(val width: Int, val height: Int) {
    /** ARGB pixels. */
    val color = IntArray(width * height)
    /** 1/w of the nearest surface drawn at each pixel (0 = nothing; bigger is nearer). */
    val depth = FloatArray(width * height)

    /** world -> clip space (column vectors). */
    var viewProj: DoubleArray = Mat4.identity()

    /** When set, marks each pixel something was drawn on. */
    var written: BooleanArray? = null

    /** Which winding faces the camera: Nitro models are counter-clockwise on screen from the front. */
    var frontIsClockwise = false

    fun clear(argb: Int) {
        color.fill(argb)
        depth.fill(0f)
    }

    /** Draws [batches]: everything solid first, then translucent polygons over it. */
    fun draw(batches: List<Nitro.Batch>) {
        for (b in batches) if (!translucent(b)) drawBatch(b, false)
        for (b in batches) if (translucent(b)) drawBatch(b, true)
    }

    private fun translucent(b: Nitro.Batch) = b.alpha in 1..30 || b.image?.translucent == true

    /** Takes the pixels of layers whose texture moves (see [drawCaptured]). */
    fun interface Capture {
        /**
         * A pixel [idx] of a layer in [slot] in front of what's drawn: its texture position
         * [u], [v] in texels (not wrapped), its vertex colour and 1/w [iw].
         */
        fun pixel(idx: Int, slot: Int, u: Float, v: Float, r: Float, g: Float, b: Float, iw: Float)
    }

    private var capture: Capture? = null
    private var captureSlot = 0

    /**
     * Instead of drawing [layers] (textured batches with their slot numbers), hands each of
     * their pixels that is in front of what's drawn to [capture], so the caller can colour them
     * again whenever the texture moves.
     */
    fun drawCaptured(layers: List<Pair<Nitro.Batch, Int>>, capture: Capture) {
        this.capture = capture
        try {
            for ((b, slot) in layers) {
                if (b.image == null) continue
                captureSlot = slot
                drawBatch(b, false)
            }
        } finally {
            this.capture = null
        }
    }

    // clip-space vertex: x, y, z, w, u, v, r, g, b
    private val cv = Array(9) { FloatArray(9) }
    private val tmp = Array(9) { FloatArray(9) }

    private fun drawBatch(b: Nitro.Batch, blend: Boolean) {
        if (b.alpha == 0) return // wireframe polygons on the DS: only their edges, skip
        val m = viewProj
        val v = b.v
        for (t in 0 until b.triangles) {
            for (k in 0 until 3) {
                val o = (t * 3 + k) * 8
                val x = v[o].toDouble(); val y = v[o + 1].toDouble(); val z = v[o + 2].toDouble()
                val c = cv[k]
                c[0] = (m[0] * x + m[1] * y + m[2] * z + m[3]).toFloat()
                c[1] = (m[4] * x + m[5] * y + m[6] * z + m[7]).toFloat()
                c[2] = (m[8] * x + m[9] * y + m[10] * z + m[11]).toFloat()
                c[3] = (m[12] * x + m[13] * y + m[14] * z + m[15]).toFloat()
                c[4] = v[o + 3]; c[5] = v[o + 4]; c[6] = v[o + 5]; c[7] = v[o + 6]; c[8] = v[o + 7]
            }
            // clip against the near plane (z > -w)
            val n = clipNear(3)
            if (n < 3) continue
            for (i in 1 until n - 1) triangle(cv[0], cv[i], cv[i + 1], b, blend)
        }
    }

    /** Sutherland-Hodgman against z = -w; returns the vertex count left in [cv]. */
    private fun clipNear(n: Int): Int {
        var allIn = true
        for (i in 0 until n) if (cv[i][2] < -cv[i][3] || cv[i][3] <= 1e-6f) { allIn = false; break }
        if (allIn) return n
        var out = 0
        for (i in 0 until n) {
            val a = cv[i]
            val b = cv[(i + 1) % n]
            val da = a[2] + a[3]
            val db = b[2] + b[3]
            if (da >= 0) { a.copyInto(tmp[out++]) }
            if ((da >= 0) != (db >= 0)) {
                val f = da / (da - db)
                val d = tmp[out++]
                for (k in 0 until 9) d[k] = a[k] + (b[k] - a[k]) * f
            }
            if (out >= 8) break
        }
        for (i in 0 until out) tmp[i].copyInto(cv[i])
        return out
    }

    private fun triangle(a: FloatArray, b: FloatArray, c: FloatArray, batch: Nitro.Batch, blend: Boolean) {
        if (a[3] <= 1e-6f || b[3] <= 1e-6f || c[3] <= 1e-6f) return
        // screen space (y down)
        val iwa = 1f / a[3]; val iwb = 1f / b[3]; val iwc = 1f / c[3]
        val ax = (a[0] * iwa + 1f) * 0.5f * width; val ay = (1f - a[1] * iwa) * 0.5f * height
        val bx = (b[0] * iwb + 1f) * 0.5f * width; val by = (1f - b[1] * iwb) * 0.5f * height
        val cx = (c[0] * iwc + 1f) * 0.5f * width; val cy = (1f - c[1] * iwc) * 0.5f * height
        val area = (bx - ax) * (cy - ay) - (cx - ax) * (by - ay)
        if (area == 0f || area.isNaN()) return
        // area > 0: clockwise on screen (y down)
        val clockwise = area > 0f
        val front = clockwise == frontIsClockwise
        if (front && !batch.drawFront) return
        if (!front && !batch.drawBack) return

        val minX = max(0, floor(min(ax, min(bx, cx))).toInt())
        val maxX = min(width - 1, ceil(max(ax, max(bx, cx))).toInt())
        val minY = max(0, floor(min(ay, min(by, cy))).toInt())
        val maxY = min(height - 1, ceil(max(ay, max(by, cy))).toInt())
        if (minX > maxX || minY > maxY) return
        val inv = 1f / area

        val img = batch.image
        val polyA = batch.alpha / 31f
        // perspective-correct attributes: attr / w
        val ua = a[4] * iwa; val ub = b[4] * iwb; val uc = c[4] * iwc
        val va = a[5] * iwa; val vb = b[5] * iwb; val vc = c[5] * iwc
        val ra = a[6] * iwa; val rb = b[6] * iwb; val rc = c[6] * iwc
        val ga = a[7] * iwa; val gb = b[7] * iwb; val gc = c[7] * iwc
        val ba = a[8] * iwa; val bb = b[8] * iwb; val bc = c[8] * iwc

        for (y in minY..maxY) {
            val py = y + 0.5f
            val rowStart = y * width
            for (x in minX..maxX) {
                val px = x + 0.5f
                // barycentric weights
                val w0 = ((bx - px) * (cy - py) - (cx - px) * (by - py)) * inv
                val w1 = ((cx - px) * (ay - py) - (ax - px) * (cy - py)) * inv
                val w2 = 1f - w0 - w1
                if (w0 < -1e-5f || w1 < -1e-5f || w2 < -1e-5f) continue
                val iw = w0 * iwa + w1 * iwb + w2 * iwc
                val idx = rowStart + x
                if (iw <= depth[idx]) continue
                val z = 1f / iw
                var r = (w0 * ra + w1 * rb + w2 * rc) * z
                var g = (w0 * ga + w1 * gb + w2 * gc) * z
                var bl = (w0 * ba + w1 * bb + w2 * bc) * z
                val cap = capture
                if (cap != null && img != null) {
                    val u = (w0 * ua + w1 * ub + w2 * uc) * z
                    val vv = (w0 * va + w1 * vb + w2 * vc) * z
                    cap.pixel(idx, captureSlot, u * img.width, vv * img.height, r, g, bl, iw)
                    continue
                }
                var alpha = polyA
                if (img != null) {
                    val u = (w0 * ua + w1 * ub + w2 * uc) * z
                    val vv = (w0 * va + w1 * vb + w2 * vc) * z
                    val tx = wrap(floor(u * img.width).toInt(), img.width, img.repeatS, img.mirrorS)
                    val ty = wrap(floor(vv * img.height).toInt(), img.height, img.repeatT, img.mirrorT)
                    val texel = img.argb[ty * img.width + tx]
                    val ta = (texel ushr 24) / 255f
                    if (ta <= 0f) continue
                    if (batch.mode == 1) {
                        // decal: the texel over the vertex colour by its alpha
                        r = r * (1 - ta) + ((texel ushr 16) and 0xFF) / 255f * ta
                        g = g * (1 - ta) + ((texel ushr 8) and 0xFF) / 255f * ta
                        bl = bl * (1 - ta) + (texel and 0xFF) / 255f * ta
                    } else {
                        r *= ((texel ushr 16) and 0xFF) / 255f
                        g *= ((texel ushr 8) and 0xFF) / 255f
                        bl *= (texel and 0xFF) / 255f
                        alpha *= ta
                    }
                }
                if (!blend) {
                    if (alpha < 0.5f) continue
                    color[idx] = pack(r, g, bl, 1f)
                    depth[idx] = iw
                    written?.set(idx, true)
                } else {
                    if (alpha <= 0.01f) continue
                    written?.set(idx, true)
                    val d = color[idx]
                    val dr = ((d ushr 16) and 0xFF) / 255f
                    val dg = ((d ushr 8) and 0xFF) / 255f
                    val db = (d and 0xFF) / 255f
                    color[idx] = pack(r * alpha + dr * (1 - alpha), g * alpha + dg * (1 - alpha), bl * alpha + db * (1 - alpha), 1f)
                }
            }
        }
    }

    private fun wrap(i: Int, n: Int, repeat: Boolean, mirror: Boolean): Int = wrapTexel(i, n, repeat, mirror)

    private fun pack(r: Float, g: Float, b: Float, a: Float): Int {
        val ri = (r.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        val gi = (g.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        val bi = (b.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        val ai = (a.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        return (ai shl 24) or (ri shl 16) or (gi shl 8) or bi
    }

    /** Screen position (x, y) and 1/w of a world point, or null when it is behind the camera. */
    fun project(x: Double, y: Double, z: Double): FloatArray? {
        val m = viewProj
        val cx = m[0] * x + m[1] * y + m[2] * z + m[3]
        val cy = m[4] * x + m[5] * y + m[6] * z + m[7]
        val cw = m[12] * x + m[13] * y + m[14] * z + m[15]
        if (cw <= 1e-6) return null
        return floatArrayOf(((cx / cw + 1) * 0.5 * width).toFloat(), ((1 - cy / cw) * 0.5 * height).toFloat(), (1 / cw).toFloat())
    }

    companion object {
        /** View matrix looking from [eye] at [target] with +Y up. */
        fun lookAt(eye: DoubleArray, target: DoubleArray): DoubleArray {
            var fx = target[0] - eye[0]; var fy = target[1] - eye[1]; var fz = target[2] - eye[2]
            val fl = sqrt(fx * fx + fy * fy + fz * fz)
            fx /= fl; fy /= fl; fz /= fl
            // side = forward x up(0,1,0)
            var sx = -fz; var sy = 0.0; var sz = fx
            val sl = sqrt(sx * sx + sz * sz).takeIf { it > 1e-9 } ?: 1.0
            sx /= sl; sz /= sl
            // up = side x forward
            val ux = sy * fz - sz * fy; val uy = sz * fx - sx * fz; val uz = sx * fy - sy * fx
            val m = Mat4.identity()
            m[0] = sx; m[1] = sy; m[2] = sz; m[3] = -(sx * eye[0] + sy * eye[1] + sz * eye[2])
            m[4] = ux; m[5] = uy; m[6] = uz; m[7] = -(ux * eye[0] + uy * eye[1] + uz * eye[2])
            m[8] = -fx; m[9] = -fy; m[10] = -fz; m[11] = fx * eye[0] + fy * eye[1] + fz * eye[2]
            return m
        }

        /** OpenGL-style perspective projection; [fovy] in radians. */
        fun perspective(fovy: Double, aspect: Double, near: Double, far: Double): DoubleArray {
            val f = 1 / tan(fovy / 2)
            val m = DoubleArray(16)
            m[0] = f / aspect
            m[5] = f
            m[10] = (far + near) / (near - far)
            m[11] = 2 * far * near / (near - far)
            m[14] = -1.0
            return m
        }
    }
}

/** A texel coordinate wrapped the way the DS does: clamped, repeated or mirrored. */
internal fun wrapTexel(i: Int, n: Int, repeat: Boolean, mirror: Boolean): Int {
    if (!repeat) return i.coerceIn(0, n - 1)
    if (!mirror) return Math.floorMod(i, n)
    val m = Math.floorMod(i, n * 2)
    return if (m < n) m else n * 2 - 1 - m
}
