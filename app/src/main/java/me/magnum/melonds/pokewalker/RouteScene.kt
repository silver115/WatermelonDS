package me.magnum.melonds.pokewalker

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** The few drawing calls a scene needs, so it can be drawn on Android and as SVG for previews. */
interface SceneCanvas {
    fun gradientRect(l: Float, t: Float, r: Float, b: Float, top: Int, bottom: Int)
    fun polygon(xy: FloatArray, color: Int)
    fun circle(cx: Float, cy: Float, r: Float, color: Int)
    fun line(x0: Float, y0: Float, x1: Float, y1: Float, width: Float, color: Int)
    /** A soft round glow: [inner] at the centre fading to transparent at [r]. */
    fun glow(cx: Float, cy: Float, r: Float, inner: Int)
}

/**
 * Where the walk is taking place, drawn as a little landscape: the route's kind of place (from
 * the route picture index the game sends: field, forest, suburbs, town, mountain, cave, lake,
 * beach), touches from the route's name (a volcano, snow, a storm, palm trees, open sea, a night
 * sky, autumn leaves, flowers, a spooky cave), and a live sky that follows the time of day.
 * Our own simple drawing; nothing from the game.
 */
class RouteScene {

    enum class Place { FIELD, FOREST, SUBURBS, URBAN, MOUNTAIN, CAVE, LAKE, BEACH }
    enum class Mood { PLAIN, VOLCANO, SNOW, STORM, RESORT, OCEAN, NIGHT, AUTUMN, FLOWERS, SCARY }

    var place = Place.FIELD
        private set
    var mood = Mood.PLAIN
        private set

    /** From the route's picture index (0-7) and its name. */
    fun setRoute(imageIndex: Int, name: String) {
        place = Place.entries.getOrElse(imageIndex) { Place.FIELD }
        val n = name.lowercase()
        mood = when {
            "volcano" in n -> Mood.VOLCANO
            "icy" in n || "white lake" in n || "snow" in n -> Mood.SNOW
            "storm" in n -> Mood.STORM
            "resort" in n -> Mood.RESORT
            "beyond the sea" in n -> Mood.OCEAN
            "night sky" in n -> Mood.NIGHT
            "yellow forest" in n -> Mood.AUTUMN
            "meadow" in n -> Mood.FLOWERS
            "scary" in n -> Mood.SCARY
            else -> Mood.PLAIN
        }
        // names that say more than the picture index
        if (mood == Mood.VOLCANO) place = Place.MOUNTAIN
        if (mood == Mood.OCEAN || mood == Mood.RESORT || mood == Mood.STORM) place = if (place == Place.LAKE) Place.LAKE else Place.BEACH
    }

    fun setPlace(p: Place, m: Mood) {
        place = p
        mood = m
    }

    // ---- sky ---------------------------------------------------------------------------------

    private class Key(val minute: Int, val top: Int, val bottom: Int, val light: Float)

    private val keys = arrayOf(
        Key(0, 0xFF070B1E.toInt(), 0xFF1A2348.toInt(), 0f),
        Key(5 * 60, 0xFF141A3C.toInt(), 0xFF3A2E58.toInt(), 0.05f),
        Key(6 * 60 + 30, 0xFF36508F.toInt(), 0xFFF2A07B.toInt(), 0.45f),
        Key(8 * 60, 0xFF3F7FCC.toInt(), 0xFFA9D3F5.toInt(), 0.85f),
        Key(12 * 60, 0xFF3A86DA.toInt(), 0xFFB7DEFA.toInt(), 1f),
        Key(16 * 60 + 30, 0xFF4A82C8.toInt(), 0xFFF4C890.toInt(), 0.9f),
        Key(18 * 60 + 30, 0xFF39407E.toInt(), 0xFFF48C5C.toInt(), 0.5f),
        Key(20 * 60, 0xFF1B2252.toInt(), 0xFF4C3D72.toInt(), 0.18f),
        Key(22 * 60, 0xFF0A0F27.toInt(), 0xFF19224A.toInt(), 0.02f),
        Key(24 * 60, 0xFF070B1E.toInt(), 0xFF1A2348.toInt(), 0f),
    )

    private var skyTop = 0
    private var skyBottom = 0
    private var light = 1f // 0 night .. 1 midday
    private var u = 1f     // size unit: the box's short side, so tall screens don't get giant stars

    private fun sky(minute: Int) {
        val m = ((minute % 1440) + 1440) % 1440
        var i = 0
        while (i < keys.size - 2 && keys[i + 1].minute <= m) i++
        val a = keys[i]
        val b = keys[i + 1]
        val t = (m - a.minute).toFloat() / (b.minute - a.minute)
        skyTop = mix(a.top, b.top, t)
        skyBottom = mix(a.bottom, b.bottom, t)
        light = a.light + (b.light - a.light) * t
        when (mood) {
            Mood.NIGHT -> {
                skyTop = 0xFF060A1C.toInt()
                skyBottom = 0xFF1B1F4A.toInt()
                light = 0f
            }
            Mood.STORM -> {
                skyTop = mix(skyTop, 0xFF2B313D.toInt(), 0.7f)
                skyBottom = mix(skyBottom, 0xFF4A525E.toInt(), 0.7f)
                light *= 0.55f
            }
            Mood.VOLCANO -> skyBottom = mix(skyBottom, 0xFFB5482E.toInt(), 0.45f)
            else -> Unit
        }
    }

    // ---- drawing -----------------------------------------------------------------------------------

    /**
     * Draws the scene in a [w] x [h] box. [minute] is the local time of day (minutes since
     * midnight); [ms] drives the gentle animation (clouds, stars, waves, weather).
     */
    fun paint(c: SceneCanvas, w: Float, h: Float, minute: Int, ms: Long) {
        sky(minute)
        u = min(w, h)
        if (place == Place.CAVE) {
            cave(c, w, h, ms)
            return
        }
        c.gradientRect(0f, 0f, w, h, skyTop, skyBottom)
        celestial(c, w, h, minute, ms)
        if (mood != Mood.NIGHT && mood != Mood.STORM) clouds(c, w, h, ms, 3, 0.85f)
        if (mood == Mood.STORM) clouds(c, w, h, ms, 5, 1f)
        when (place) {
            Place.FIELD -> field(c, w, h, ms)
            Place.FOREST -> forest(c, w, h, ms)
            Place.SUBURBS -> suburbs(c, w, h)
            Place.URBAN -> urban(c, w, h)
            Place.MOUNTAIN -> mountain(c, w, h, ms)
            Place.LAKE -> lake(c, w, h, ms)
            Place.BEACH -> beach(c, w, h, ms)
            Place.CAVE -> Unit
        }
        weather(c, w, h, ms)
    }

    /** Daylight 0..1 of the last painted scene (dark scenes want lighter text). */
    val daylight: Float get() = light

    private fun lit(day: Int, night: Int) = mix(night, day, light)

    private fun celestial(c: SceneCanvas, w: Float, h: Float, minute: Int, ms: Long) {
        val horizon = h * 0.62f
        // stars at night
        val starAlpha = ((0.35f - light) / 0.35f).coerceIn(0f, 1f)
        if (starAlpha > 0f) {
            for (i in 0 until 46) {
                val x = rnd(i, 1) * w
                val y = rnd(i, 2) * horizon * 0.95f
                val tw = 0.55f + 0.45f * sin(ms / 600.0 + i * 1.7).toFloat()
                val r = (0.6f + rnd(i, 3) * 1.1f) * u / 220f
                c.circle(x, y, max(r, 0.6f), alpha(0xFFFFFFFF.toInt(), starAlpha * tw * 0.9f))
            }
            if (mood == Mood.NIGHT) {
                // now and then a shooting star
                val period = 7000L
                val t = (ms % period) / 900f
                if (t < 1f) {
                    val sx = w * (0.15f + 0.5f * rnd((ms / period).toInt(), 9))
                    val sy = horizon * 0.15f
                    c.line(sx + t * w * 0.25f, sy + t * h * 0.12f, sx + t * w * 0.25f - w * 0.06f, sy + t * h * 0.12f - h * 0.03f, u / 160f, alpha(0xFFFFFFFF.toInt(), 1f - t))
                }
            }
        }
        val m = ((minute % 1440) + 1440) % 1440
        // the sun from 6:00 to 18:45, the moon from 18:30 to 6:30
        val sunT = (m - 360) / 765f
        if (sunT in 0f..1f && mood != Mood.NIGHT && mood != Mood.STORM) {
            val x = w * (0.08f + 0.84f * sunT)
            val y = horizon - sin(PI * sunT).toFloat() * horizon * 0.8f + h * 0.02f
            val warm = abs(sunT - 0.5f) * 2f
            val col = mix(0xFFFFF4C9.toInt(), 0xFFFFB067.toInt(), warm * warm)
            c.glow(x, y, u * 0.22f, alpha(col, 0.55f))
            c.circle(x, y, u * 0.055f, col)
        }
        val moonT = (if (m >= 1110) m - 1110 else m + 330) / 720f
        if ((moonT in 0f..1f && light < 0.5f) || mood == Mood.NIGHT) {
            val t = if (mood == Mood.NIGHT) 0.7f else moonT
            val x = w * (0.1f + 0.8f * t)
            val y = horizon - sin(PI * t).toFloat() * horizon * 0.75f + h * 0.04f
            c.glow(x, y, u * 0.16f, alpha(0xFFDDE6FF.toInt(), 0.35f))
            c.circle(x, y, u * 0.045f, 0xFFF1F3FF.toInt())
            c.circle(x + u * 0.02f, y - u * 0.012f, u * 0.04f, mix(skyTop, skyBottom, 0.3f)) // crescent
        }
    }

    private fun clouds(c: SceneCanvas, w: Float, h: Float, ms: Long, count: Int, strength: Float) {
        val dark = mood == Mood.STORM
        val base = when {
            dark -> 0xFF5A616E.toInt()
            light < 0.3f -> 0xFF8A93B8.toInt()
            else -> mix(0xFFFFD2B8.toInt(), 0xFFFFFFFF.toInt(), ((light - 0.4f) / 0.5f).coerceIn(0f, 1f))
        }
        val a = if (dark) 0.95f else (0.25f + 0.6f * light) * strength
        for (i in 0 until count) {
            val cw = u * (0.3f + 0.2f * rnd(i, 4))
            val speed = 0.004f + 0.004f * rnd(i, 5)
            val x = ((rnd(i, 6) * (w + cw) + ms * speed * w / 1000f) % (w + cw)) - cw * 0.5f
            val y = h * (0.1f + 0.28f * rnd(i, 7))
            val r = cw * 0.22f
            // opaque, mixed with the sky behind it, so the puffs don't show where they overlap
            val col = mix(mix(skyTop, skyBottom, y / h), base, a)
            c.circle(x, y, r, col)
            c.circle(x + r * 0.9f, y - r * 0.35f, r * 1.15f, col)
            c.circle(x + r * 1.9f, y, r * 0.95f, col)
            c.polygon(floatArrayOf(x, y, x + r * 1.9f, y, x + r * 1.9f, y + r * 0.6f, x, y + r * 0.6f), col)
        }
    }

    // ---- places ------------------------------------------------------------------------------------

    private fun hill(c: SceneCanvas, w: Float, h: Float, baseY: Float, amp: Float, freq: Float, phase: Float, color: Int) {
        val n = 40
        val xy = FloatArray((n + 1) * 2 + 4)
        for (i in 0..n) {
            val x = w * i / n
            val y = baseY + amp * sin(freq * x / w * 2 * PI + phase).toFloat() + amp * 0.35f * sin(freq * 2.7 * x / w * 2 * PI + phase * 1.9).toFloat()
            xy[i * 2] = x
            xy[i * 2 + 1] = y
        }
        xy[(n + 1) * 2] = w
        xy[(n + 1) * 2 + 1] = h
        xy[(n + 1) * 2 + 2] = 0f
        xy[(n + 1) * 2 + 3] = h
        c.polygon(xy, color)
    }

    private fun roundTree(c: SceneCanvas, x: Float, ground: Float, size: Float, leaf: Int, trunk: Int) {
        c.polygon(floatArrayOf(x - size * 0.08f, ground, x + size * 0.08f, ground, x + size * 0.06f, ground - size * 0.6f, x - size * 0.06f, ground - size * 0.6f), trunk)
        c.circle(x, ground - size * 0.75f, size * 0.38f, leaf)
        c.circle(x - size * 0.22f, ground - size * 0.6f, size * 0.26f, leaf)
        c.circle(x + size * 0.22f, ground - size * 0.62f, size * 0.27f, leaf)
    }

    private fun pine(c: SceneCanvas, x: Float, ground: Float, size: Float, color: Int) {
        val wdt = size * 0.42f
        c.polygon(floatArrayOf(x, ground - size, x + wdt * 0.6f, ground - size * 0.55f, x - wdt * 0.6f, ground - size * 0.55f), color)
        c.polygon(floatArrayOf(x, ground - size * 0.8f, x + wdt, ground - size * 0.15f, x - wdt, ground - size * 0.15f), color)
        c.polygon(floatArrayOf(x - size * 0.05f, ground, x + size * 0.05f, ground, x + size * 0.05f, ground - size * 0.18f, x - size * 0.05f, ground - size * 0.18f), color)
    }

    private fun field(c: SceneCanvas, w: Float, h: Float, ms: Long) {
        val far = lit(0xFF7FB89A.toInt(), 0xFF1D3442.toInt())
        val mid = lit(0xFF5AA06A.toInt(), 0xFF173029.toInt())
        val near = lit(0xFF3F8A4E.toInt(), 0xFF10241E.toInt())
        hill(c, w, h, h * 0.66f, h * 0.05f, 1.3f, 0.4f, far)
        hill(c, w, h, h * 0.74f, h * 0.045f, 1.8f, 2.1f, mid)
        for (i in 0 until 4) {
            val x = w * (0.1f + 0.25f * i + 0.08f * rnd(i, 11))
            roundTree(c, x, h * 0.76f, u * (0.16f + 0.05f * rnd(i, 12)), lit(0xFF3E7D46.toInt(), 0xFF0F2119.toInt()), lit(0xFF6B4A33.toInt(), 0xFF1A1512.toInt()))
        }
        hill(c, w, h, h * 0.86f, h * 0.035f, 1.1f, 4.0f, near)
        grassTufts(c, w, h, h * 0.9f, lit(0xFF2F7440.toInt(), 0xFF0B1C15.toInt()))
        if (mood == Mood.FLOWERS) {
            val cols = intArrayOf(0xFFFF8FB1.toInt(), 0xFFFFE07A.toInt(), 0xFFFFFFFF.toInt(), 0xFFB79CFF.toInt())
            for (i in 0 until 28) {
                c.circle(rnd(i, 13) * w, h * (0.84f + 0.14f * rnd(i, 14)), u * 0.009f, mix(cols[i % 4], 0xFF101820.toInt(), 1f - (0.35f + 0.65f * light)))
            }
        }
        if (light < 0.3f) fireflies(c, w, h, ms)
    }

    private fun grassTufts(c: SceneCanvas, w: Float, h: Float, y: Float, color: Int) {
        for (i in 0 until 18) {
            val x = rnd(i, 21) * w
            val s = u * (0.03f + 0.02f * rnd(i, 22))
            val yy = y + rnd(i, 23) * h * 0.08f
            c.polygon(floatArrayOf(x - s * 0.5f, yy, x - s * 0.15f, yy - s, x, yy), color)
            c.polygon(floatArrayOf(x - s * 0.1f, yy, x + s * 0.2f, yy - s * 1.2f, x + s * 0.3f, yy), color)
            c.polygon(floatArrayOf(x + s * 0.2f, yy, x + s * 0.6f, yy - s * 0.8f, x + s * 0.6f, yy), color)
        }
    }

    private fun forest(c: SceneCanvas, w: Float, h: Float, ms: Long) {
        val autumn = mood == Mood.AUTUMN
        val farHill = lit(if (autumn) 0xFFB79A6A.toInt() else 0xFF6FA48E.toInt(), 0xFF1B2D3C.toInt())
        hill(c, w, h, h * 0.64f, h * 0.05f, 1.2f, 1.0f, farHill)
        val farTree = lit(if (autumn) 0xFFD9963A.toInt() else 0xFF3F7B5A.toInt(), 0xFF132620.toInt())
        val n = 16
        for (i in 0 until n) {
            val x = w * (i + 0.5f * rnd(i, 31)) / (n - 1)
            pine(c, x, h * (0.72f + 0.03f * rnd(i, 32)), u * (0.2f + 0.08f * rnd(i, 33)), farTree)
        }
        hill(c, w, h, h * 0.8f, h * 0.03f, 1.6f, 3.0f, lit(if (autumn) 0xFF8E6A33.toInt() else 0xFF2E6A42.toInt(), 0xFF0E1E18.toInt()))
        val nearTree = lit(if (autumn) 0xFFE87A2E.toInt() else 0xFF2A5E3C.toInt(), 0xFF0B1A14.toInt())
        val nearTree2 = lit(if (autumn) 0xFFF2B33D.toInt() else 0xFF336F46.toInt(), 0xFF0D1D16.toInt())
        pine(c, w * 0.04f, h * 0.98f, u * 0.55f, nearTree)
        pine(c, w * 0.96f, h * 0.98f, u * 0.6f, nearTree2)
        roundTree(c, w * 0.16f, h * 0.95f, u * 0.36f, nearTree2, lit(0xFF5A3E2A.toInt(), 0xFF140F0C.toInt()))
        roundTree(c, w * 0.84f, h * 0.96f, u * 0.32f, nearTree, lit(0xFF5A3E2A.toInt(), 0xFF140F0C.toInt()))
        hill(c, w, h, h * 0.93f, h * 0.02f, 2.0f, 0.5f, lit(if (autumn) 0xFF6E4E26.toInt() else 0xFF24563A.toInt(), 0xFF0A1712.toInt()))
        if (autumn) leaves(c, w, h, ms)
        if (light < 0.3f) fireflies(c, w, h, ms)
    }

    private fun suburbs(c: SceneCanvas, w: Float, h: Float) {
        hill(c, w, h, h * 0.66f, h * 0.04f, 1.1f, 0.8f, lit(0xFF86B79E.toInt(), 0xFF1D3343.toInt()))
        val ground = h * 0.8f
        hill(c, w, h, ground, h * 0.015f, 1.0f, 2.0f, lit(0xFF64A672.toInt(), 0xFF15291F.toInt()))
        val roofs = intArrayOf(0xFFD0604E.toInt(), 0xFF5578B8.toInt(), 0xFFE0A044.toInt(), 0xFF7A5BA8.toInt())
        for (i in 0 until 5) {
            val x = w * (0.08f + 0.2f * i + 0.04f * rnd(i, 41))
            val s = u * (0.13f + 0.03f * rnd(i, 42))
            val wall = lit(0xFFF1E6D2.toInt(), 0xFF2A2F3C.toInt())
            val base = ground + h * 0.02f
            c.polygon(floatArrayOf(x - s * 0.6f, base, x + s * 0.6f, base, x + s * 0.6f, base - s * 0.75f, x - s * 0.6f, base - s * 0.75f), wall)
            c.polygon(floatArrayOf(x - s * 0.75f, base - s * 0.72f, x + s * 0.75f, base - s * 0.72f, x, base - s * 1.3f), lit(roofs[i % 4], mix(roofs[i % 4], 0xFF0A0D16.toInt(), 0.75f)))
            val win = if (light < 0.35f) 0xFFFFD27A.toInt() else 0xFF7FA7D6.toInt()
            c.polygon(floatArrayOf(x - s * 0.35f, base - s * 0.5f, x - s * 0.1f, base - s * 0.5f, x - s * 0.1f, base - s * 0.28f, x - s * 0.35f, base - s * 0.28f), win)
            c.polygon(floatArrayOf(x + s * 0.12f, base, x + s * 0.36f, base, x + s * 0.36f, base - s * 0.42f, x + s * 0.12f, base - s * 0.42f), lit(0xFF7A5236.toInt(), 0xFF1A1410.toInt()))
        }
        // a path and a fence
        c.polygon(floatArrayOf(0f, h, w, h, w, h * 0.9f, 0f, h * 0.93f), lit(0xFFC9B48C.toInt(), 0xFF2B2822.toInt()))
        val fence = lit(0xFFF5EEE0.toInt(), 0xFF3A3F4C.toInt())
        c.line(0f, h * 0.885f, w, h * 0.875f, u * 0.01f, fence)
        var x = w * 0.02f
        while (x < w) {
            c.line(x, h * 0.9f, x, h * 0.85f, u * 0.012f, fence)
            x += w * 0.06f
        }
    }

    private fun urban(c: SceneCanvas, w: Float, h: Float) {
        val farCol = lit(0xFF8AA3C4.toInt(), 0xFF1A2236.toInt())
        val nearCol = lit(0xFF5C7397.toInt(), 0xFF121828.toInt())
        for (layer in 0..1) {
            val col = if (layer == 0) farCol else nearCol
            var x = -w * 0.02f
            var i = 0
            while (x < w) {
                val bw = w * (0.07f + 0.06f * rnd(i + layer * 50, 51))
                val bh = h * (if (layer == 0) 0.28f + 0.22f * rnd(i, 52) else 0.18f + 0.2f * rnd(i + 99, 53))
                val base = h * (if (layer == 0) 0.78f else 0.86f)
                c.polygon(floatArrayOf(x, base, x + bw, base, x + bw, base - bh, x, base - bh), col)
                if (layer == 1 || light < 0.4f) {
                    // windows, lit at night
                    val lit = light < 0.4f
                    var wy = base - bh + h * 0.03f
                    var row = 0
                    while (wy < base - h * 0.03f) {
                        var wx = x + bw * 0.15f
                        var colN = 0
                        while (wx < x + bw * 0.8f) {
                            val on = rnd(i * 31 + row * 7 + colN + layer * 500, 54) > (if (lit) 0.45f else 0.7f)
                            if (on) c.polygon(floatArrayOf(wx, wy, wx + bw * 0.14f, wy, wx + bw * 0.14f, wy + h * 0.025f, wx, wy + h * 0.025f),
                                if (lit) alpha(0xFFFFD98A.toInt(), 0.85f) else alpha(0xFFDCE8F7.toInt(), 0.5f))
                            wx += bw * 0.28f
                            colN++
                        }
                        wy += h * 0.05f
                        row++
                    }
                }
                x += bw + w * 0.008f
                i++
            }
        }
        c.polygon(floatArrayOf(0f, h, w, h, w, h * 0.86f, 0f, h * 0.86f), lit(0xFF5B6270.toInt(), 0xFF15171E.toInt()))
        var x = 0f
        while (x < w) {
            c.line(x, h * 0.93f, x + w * 0.05f, h * 0.93f, u * 0.008f, lit(0xFFF2E9C9.toInt(), 0xFF6B6A5E.toInt()))
            x += w * 0.1f
        }
    }

    private fun mountain(c: SceneCanvas, w: Float, h: Float, ms: Long) {
        val volcano = mood == Mood.VOLCANO
        val snowy = mood == Mood.SNOW
        val farCol = lit(if (snowy) 0xFFB9C9DE.toInt() else 0xFF8C9BB5.toInt(), 0xFF1F2740.toInt())
        // far range
        val far = floatArrayOf(0f, h * 0.62f, w * 0.12f, h * 0.42f, w * 0.22f, h * 0.55f, w * 0.36f, h * 0.3f, w * 0.5f, h * 0.5f,
            w * 0.62f, h * 0.36f, w * 0.78f, h * 0.56f, w * 0.9f, h * 0.4f, w, h * 0.52f, w, h, 0f, h)
        c.polygon(far, farCol)
        if (volcano) {
            val cone = lit(0xFF6E4A3E.toInt(), 0xFF24161A.toInt())
            c.polygon(floatArrayOf(w * 0.2f, h * 0.85f, w * 0.43f, h * 0.28f, w * 0.57f, h * 0.28f, w * 0.8f, h * 0.85f), cone)
            c.glow(w * 0.5f, h * 0.27f, u * 0.25f, alpha(0xFFFF7A3A.toInt(), 0.75f))
            c.polygon(floatArrayOf(w * 0.44f, h * 0.29f, w * 0.56f, h * 0.29f, w * 0.53f, h * 0.36f, w * 0.5f, h * 0.31f, w * 0.47f, h * 0.38f), 0xFFFF8A3D.toInt())
        } else {
            // snow caps on the far peaks
            val cap = alpha(0xFFF4F8FF.toInt(), 0.5f + 0.5f * light)
            for ((px, py) in listOf(w * 0.36f to h * 0.3f, w * 0.62f to h * 0.36f, w * 0.12f to h * 0.42f, w * 0.9f to h * 0.4f)) {
                val s = h * (if (snowy) 0.12f else 0.07f)
                c.polygon(floatArrayOf(px, py, px + s * 0.9f, py + s, px + s * 0.3f, py + s * 0.8f, px, py + s * 1.05f, px - s * 0.3f, py + s * 0.8f, px - s * 0.9f, py + s), cap)
            }
        }
        // near slopes and a rocky path
        val nearCol = lit(if (snowy) 0xFFDCE6F2.toInt() else if (volcano) 0xFF4E3A36.toInt() else 0xFF6D6A5E.toInt(), if (snowy) 0xFF2A3348.toInt() else 0xFF15141A.toInt())
        c.polygon(floatArrayOf(0f, h * 0.7f, w * 0.18f, h * 0.62f, w * 0.34f, h * 0.76f, w * 0.52f, h * 0.7f, w * 0.7f, h * 0.8f, w * 0.86f, h * 0.68f, w, h * 0.74f, w, h, 0f, h), nearCol)
        val rock = lit(if (snowy) 0xFFA9B6C8.toInt() else 0xFF4B4943.toInt(), 0xFF0E0E12.toInt())
        for (i in 0 until 7) {
            val x = rnd(i, 61) * w
            val y = h * (0.86f + 0.1f * rnd(i, 62))
            val s = u * (0.03f + 0.03f * rnd(i, 63))
            c.polygon(floatArrayOf(x - s, y, x - s * 0.4f, y - s * 0.8f, x + s * 0.5f, y - s * 0.6f, x + s, y), rock)
        }
    }

    private fun lake(c: SceneCanvas, w: Float, h: Float, ms: Long) {
        val snowy = mood == Mood.SNOW
        val horizon = h * 0.6f
        hill(c, w, h, horizon - h * 0.04f, h * 0.05f, 1.4f, 0.3f, lit(if (snowy) 0xFFC9D6E8.toInt() else 0xFF6F9F86.toInt(), 0xFF1A2A3A.toInt()))
        val treeCol = lit(if (snowy) 0xFF7C93A8.toInt() else 0xFF3C7558.toInt(), 0xFF10201C.toInt())
        for (i in 0 until 12) pine(c, w * (i + 0.4f * rnd(i, 71)) / 11f, horizon + h * 0.01f, u * (0.12f + 0.06f * rnd(i, 72)), treeCol)
        // water mirrors the sky
        c.gradientRect(0f, horizon, w, h, mix(skyBottom, 0xFF1E5D8C.toInt(), 0.45f), mix(skyTop, 0xFF0C2440.toInt(), 0.6f))
        for (i in 0 until 14) {
            val y = horizon + (h - horizon) * (0.08f + 0.9f * rnd(i, 73))
            val len = w * (0.06f + 0.1f * rnd(i, 74))
            val x = ((rnd(i, 75) * w + ms * 0.01f * (0.5f + rnd(i, 76))) % (w + len)) - len
            c.line(x, y, x + len, y, u * 0.006f, alpha(0xFFFFFFFF.toInt(), 0.15f + 0.25f * light))
        }
        // near shore
        hill(c, w, h, h * 0.94f, h * 0.02f, 1.5f, 1.4f, lit(if (snowy) 0xFFE8EEF6.toInt() else 0xFF4A7E52.toInt(), if (snowy) 0xFF2B3346.toInt() else 0xFF0E1C16.toInt()))
    }

    private fun beach(c: SceneCanvas, w: Float, h: Float, ms: Long) {
        val horizon = h * 0.6f
        val ocean = mood == Mood.OCEAN
        val storm = mood == Mood.STORM
        c.gradientRect(0f, horizon, w, h, mix(skyBottom, if (storm) 0xFF35505E.toInt() else 0xFF2E8FB8.toInt(), 0.55f), mix(0xFF0B3A5C.toInt(), 0xFF06121F.toInt(), 1f - light))
        if (ocean) {
            // a far island
            c.polygon(floatArrayOf(w * 0.68f, horizon, w * 0.76f, horizon - h * 0.05f, w * 0.84f, horizon - h * 0.03f, w * 0.9f, horizon), lit(0xFF5C8F6C.toInt(), 0xFF13222A.toInt()))
        }
        // waves rolling in
        val rough = if (storm) 2.2f else 1f
        for (row in 0 until 6) {
            val y = horizon + (h - horizon) * (0.12f + row * (if (ocean) 0.15f else 0.09f))
            val n = 24
            val xy = FloatArray((n + 1) * 2)
            for (i in 0..n) {
                val x = w * i / n
                xy[i * 2] = x
                xy[i * 2 + 1] = y + rough * h * 0.008f * sin(x / w * 12 + ms / 700.0 + row * 1.3).toFloat()
            }
            for (i in 0 until n) c.line(xy[i * 2], xy[i * 2 + 1], xy[i * 2 + 2], xy[i * 2 + 3], u * 0.006f, alpha(0xFFFFFFFF.toInt(), 0.18f + 0.22f * light))
        }
        if (!ocean) {
            // sand with foam at the water's edge
            val shore = h * 0.8f
            val n = 30
            val xy = FloatArray((n + 1) * 2 + 4)
            for (i in 0..n) {
                val x = w * i / n
                xy[i * 2] = x
                xy[i * 2 + 1] = shore + h * 0.02f * sin(x / w * 5 + ms / 1400.0).toFloat()
            }
            xy[(n + 1) * 2] = w
            xy[(n + 1) * 2 + 1] = h
            xy[(n + 1) * 2 + 2] = 0f
            xy[(n + 1) * 2 + 3] = h
            c.polygon(xy, lit(0xFFF0D9A4.toInt(), 0xFF3A3326.toInt()))
            for (i in 0 until n) c.line(xy[i * 2], xy[i * 2 + 1], xy[i * 2 + 2], xy[i * 2 + 3], u * 0.012f, alpha(0xFFFFFFFF.toInt(), 0.5f + 0.3f * light))
            if (mood == Mood.RESORT || mood == Mood.PLAIN) {
                palm(c, w * 0.1f, h * 0.98f, u * 0.55f)
                if (mood == Mood.RESORT) palm(c, w * 0.88f, h * 0.99f, u * 0.48f)
            }
        }
    }

    private fun palm(c: SceneCanvas, x: Float, ground: Float, size: Float) {
        val trunk = lit(0xFF8A6542.toInt(), 0xFF1C1610.toInt())
        val leaf = lit(0xFF3F9B5A.toInt(), 0xFF0E2018.toInt())
        val topX = x + size * 0.18f
        val topY = ground - size
        c.polygon(floatArrayOf(x - size * 0.04f, ground, x + size * 0.04f, ground, topX + size * 0.025f, topY, topX - size * 0.025f, topY), trunk)
        // fronds fanning out and drooping at the tips
        for (deg in intArrayOf(-175, -145, -115, -65, -35, -5)) {
            val a = deg * PI / 180
            val len = size * 0.42f
            val tipX = topX + cos(a).toFloat() * len
            val tipY = topY + sin(a).toFloat() * len * 0.55f + len * 0.32f
            val midX = topX + cos(a).toFloat() * len * 0.5f
            val midY = topY + sin(a).toFloat() * len * 0.5f
            val nx = -sin(a).toFloat() * size * 0.05f
            val ny = cos(a).toFloat() * size * 0.05f
            c.polygon(floatArrayOf(topX, topY, midX + nx, midY + ny, tipX, tipY, midX - nx, midY - ny), leaf)
        }
        c.circle(topX, topY, size * 0.035f, trunk)
    }

    private fun cave(c: SceneCanvas, w: Float, h: Float, ms: Long) {
        val scary = mood == Mood.SCARY
        c.gradientRect(0f, 0f, w, h, if (scary) 0xFF140D22.toInt() else 0xFF14161E.toInt(), if (scary) 0xFF2B1840.toInt() else 0xFF2A2833.toInt())
        // the way out: a faint light at the far end
        c.glow(w * 0.5f, h * 0.55f, u * 0.45f, alpha(if (scary) 0xFF8A5BC4.toInt() else 0xFF9DB4D8.toInt(), 0.35f))
        val rock = if (scary) 0xFF0C0814.toInt() else 0xFF0E0F14.toInt()
        val rock2 = if (scary) 0xFF1E1430.toInt() else 0xFF22232C.toInt()
        // ceiling and stalactites
        val top = floatArrayOf(0f, 0f, w, 0f, w, h * 0.12f, w * 0.85f, h * 0.18f, w * 0.7f, h * 0.1f, w * 0.55f, h * 0.2f, w * 0.4f, h * 0.12f, w * 0.25f, h * 0.22f, w * 0.1f, h * 0.14f, 0f, h * 0.2f)
        c.polygon(top, rock2)
        for (i in 0 until 9) {
            val x = w * (0.05f + 0.11f * i + 0.04f * rnd(i, 81))
            val len = h * (0.12f + 0.16f * rnd(i, 82))
            c.polygon(floatArrayOf(x - w * 0.02f, h * 0.12f, x + w * 0.02f, h * 0.12f, x, h * 0.12f + len), rock)
        }
        // walls and floor
        c.polygon(floatArrayOf(0f, 0f, w * 0.14f, h * 0.3f, w * 0.1f, h * 0.7f, w * 0.18f, h, 0f, h), rock)
        c.polygon(floatArrayOf(w, 0f, w * 0.86f, h * 0.35f, w * 0.92f, h * 0.68f, w * 0.82f, h, w, h), rock)
        c.polygon(floatArrayOf(0f, h, w, h, w, h * 0.85f, w * 0.7f, h * 0.8f, w * 0.45f, h * 0.86f, w * 0.2f, h * 0.8f, 0f, h * 0.86f), rock2)
        for (i in 0 until 6) {
            val x = w * (0.15f + 0.14f * i)
            val hh = h * (0.08f + 0.1f * rnd(i, 83))
            c.polygon(floatArrayOf(x - w * 0.025f, h * 0.88f, x + w * 0.025f, h * 0.88f, x, h * 0.88f - hh), rock)
        }
        // glowing crystals
        for (i in 0 until 7) {
            val x = rnd(i, 84) * w
            val y = h * (0.25f + 0.6f * rnd(i, 85))
            val pulse = 0.5f + 0.5f * sin(ms / 900.0 + i).toFloat()
            val col = if (scary) 0xFFB07CFF.toInt() else if (i % 2 == 0) 0xFF7FE3FF.toInt() else 0xFFB49CFF.toInt()
            c.glow(x, y, u * 0.06f, alpha(col, 0.25f + 0.3f * pulse))
            c.polygon(floatArrayOf(x, y - u * 0.03f, x + u * 0.012f, y, x, y + u * 0.015f, x - u * 0.012f, y), alpha(col, 0.9f))
        }
        if (scary) {
            // eyes in the dark, blinking now and then
            for (i in 0 until 3) {
                val blink = ((ms / 200 + i * 7) % 23) == 0L
                if (blink) continue
                val x = w * (0.2f + 0.3f * i + 0.05f * rnd(i, 86))
                val y = h * (0.3f + 0.15f * rnd(i, 87))
                c.circle(x, y, u * 0.01f, 0xFFFFE066.toInt())
                c.circle(x + u * 0.035f, y, u * 0.01f, 0xFFFFE066.toInt())
            }
        }
    }

    // ---- weather and little lives ------------------------------------------------------------------

    private fun weather(c: SceneCanvas, w: Float, h: Float, ms: Long) {
        when (mood) {
            Mood.SNOW -> for (i in 0 until 46) {
                val speed = 0.02f + 0.02f * rnd(i, 91)
                val y = (rnd(i, 92) * h + ms * speed * h / 600f) % h
                val x = (rnd(i, 93) * w + sin(ms / 1100.0 + i).toFloat() * w * 0.02f + w) % w
                c.circle(x, y, u * (0.005f + 0.006f * rnd(i, 94)), alpha(0xFFFFFFFF.toInt(), 0.85f))
            }
            Mood.STORM -> {
                for (i in 0 until 54) {
                    val y = (rnd(i, 95) * h + ms * 0.9f * h / 600f) % h
                    val x = (rnd(i, 96) * w + w - y * 0.25f) % w
                    c.line(x, y, x - u * 0.02f, y + u * 0.07f, u * 0.004f, alpha(0xFFC9D6E6.toInt(), 0.55f))
                }
                if ((ms / 100) % 97 < 2) c.gradientRect(0f, 0f, w, h, alpha(0xFFFFFFFF.toInt(), 0.35f), alpha(0xFFFFFFFF.toInt(), 0f)) // lightning
            }
            Mood.VOLCANO -> for (i in 0 until 22) {
                val t = ((rnd(i, 97) * 4000 + ms) % 4000) / 4000f
                val x = w * (0.45f + 0.1f * rnd(i, 98)) + sin(t * 6 + i).toFloat() * w * 0.04f
                val y = h * 0.3f - t * h * 0.3f
                c.circle(x, y, u * 0.007f, alpha(0xFFFFA048.toInt(), 1f - t))
            }
            else -> Unit
        }
    }

    private fun fireflies(c: SceneCanvas, w: Float, h: Float, ms: Long) {
        for (i in 0 until 12) {
            val on = sin(ms / 500.0 + i * 2.3).toFloat()
            if (on < 0f) continue
            val x = rnd(i, 101) * w + sin(ms / 1700.0 + i).toFloat() * w * 0.02f
            val y = h * (0.62f + 0.3f * rnd(i, 102)) + cos(ms / 1300.0 + i).toFloat() * h * 0.02f
            c.glow(x, y, u * 0.03f, alpha(0xFFE6FF7A.toInt(), on * 0.6f))
            c.circle(x, y, u * 0.005f, alpha(0xFFF4FFB0.toInt(), on))
        }
    }

    private fun leaves(c: SceneCanvas, w: Float, h: Float, ms: Long) {
        for (i in 0 until 14) {
            val speed = 0.015f + 0.015f * rnd(i, 111)
            val y = (rnd(i, 112) * h + ms * speed * h / 600f) % h
            val x = (rnd(i, 113) * w + sin(ms / 900.0 + i).toFloat() * w * 0.04f + w) % w
            val s = u * 0.012f
            c.polygon(floatArrayOf(x, y - s, x + s, y, x, y + s, x - s, y), if (i % 2 == 0) 0xFFF29A3A.toInt() else 0xFFE8C24A.toInt())
        }
    }

    // ---- helpers -------------------------------------------------------------------------------------

    companion object {
        /** Mixes colour [a] towards [b] by [t] (0..1), alpha included. */
        fun mix(a: Int, b: Int, t: Float): Int {
            val k = t.coerceIn(0f, 1f)
            fun ch(s: Int) = (((a ushr s) and 0xFF) * (1 - k) + ((b ushr s) and 0xFF) * k).toInt().coerceIn(0, 255)
            return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }

        /** [color] with its alpha scaled by [a]. */
        fun alpha(color: Int, a: Float): Int {
            val base = (color ushr 24) and 0xFF
            val v = (base * a.coerceIn(0f, 1f)).toInt()
            return (v shl 24) or (color and 0x00FFFFFF)
        }

        /** A fixed pseudo-random number in 0..1 for (i, salt). */
        fun rnd(i: Int, salt: Int): Float {
            var x = i * 374761393 + salt * 668265263
            x = (x xor (x ushr 13)) * 1274126177
            x = x xor (x ushr 16)
            return (x and 0xFFFFFF) / 16777216f
        }
    }
}
