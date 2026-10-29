package me.magnum.melonds.pokewalker

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Little things moving over the route map, in the picture's own pixels: sunlight glinting on
 * water, cloud shadows and birds passing over, leaves, petals or snow drifting by, fireflies at
 * night, dust and drips in caves, embers by the volcano, and grass shaken up where the Pokémon
 * walks. All our own; picked by the course's kind and the time of day. And the [weather]: rain
 * (always at the Lake of Rage, as in the game, and passing showers elsewhere), snow on Mt. Silver,
 * morning mist, blowing sand on the beach, and the dark of Dark Cave. Pure Kotlin.
 */
class MapAmbience(private val scene: FieldMap.Scene, private val random: Random = Random.Default) {

    /** A [size] x [tall] block of pixels of [color] (ARGB) at picture position ([x], [y]). */
    class Dot(val x: Float, val y: Float, val size: Int, val color: Int, val tall: Int = size)

    /** A soft shadow ([rx] x [ry] picture pixels) of a cloud passing over; [mist] a soft white bank of fog instead. */
    class Cloud(val x: Float, val y: Float, val rx: Float, val ry: Float, val alpha: Float, val mist: Boolean = false)

    enum class Weather { CLEAR, RAIN, SNOW, MIST, SAND, DARK }

    /** The weather now (see [weatherFor]). */
    var weather = Weather.CLEAR

    /** A colour laid over the whole picture for the weather (ARGB, 0: none). */
    var tint = 0
        private set

    /** How dark it is around the Pokémon's light (0: not dark). */
    var darkness = 0f
        private set

    private val mist = ArrayList<FloatArray>() // x, y, rx, ry, speed

    private class Mote(
        var x: Float, var y: Float, var vx: Float, var vy: Float,
        val born: Long, val life: Long, val color: Int, val size: Int, val kind: Int, val phase: Float,
    )

    /** The part of the picture in view now (left, top, right, bottom): things happen there. */
    var view: FloatArray = scene.view.copyOf()
    private val x0 get() = view[0]
    private val y0 get() = view[1]
    private val w get() = view[2] - view[0]
    private val h get() = view[3] - view[1]
    private val kind = scene.course.kind
    private val name = scene.course.name.lowercase()
    private val motes = ArrayList<Mote>()
    private val clouds = ArrayList<FloatArray>() // x, y, rx, ry, speed
    private var birdsAt = Long.MIN_VALUE
    private var birdsFrom = 0f
    private var birdsY = 0f
    private var birdsDir = 1f
    private var lastNow = Long.MIN_VALUE

    /** What to draw at [now] (ms) at [minute] of the day. */
    fun frame(now: Long, minute: Int, dots: MutableList<Dot>, cloudsOut: MutableList<Cloud>) {
        if (lastNow == Long.MIN_VALUE) {
            // start as if it had been going for a while: leaves already on their way down
            lastNow = now - 15_000
            var t = now - 15_000 + 33
            while (t < now) { frame(t, minute, dots, cloudsOut); t += 33 }
        }
        dots.clear()
        cloudsOut.clear()
        val dt = if (lastNow == Long.MIN_VALUE) 0f else ((now - lastNow).coerceIn(0, 200)) / 1000f
        lastNow = now
        val night = minute < 5 * 60 || minute >= 19 * 60
        val wet = weather == Weather.RAIN || weather == Weather.MIST || weather == Weather.SNOW || weather == Weather.SAND
        val day = scene.outdoor && !night && minute in 6 * 60 until 18 * 60 + 30 && !wet
        tint = when (weather) {
            Weather.RAIN -> 0x2C1A2840
            Weather.SNOW -> 0x14E8F0FF
            Weather.MIST -> 0x18F0F4F8
            Weather.SAND -> 0x22D8B070
            else -> 0
        }
        darkness = if (weather == Weather.DARK) 0.72f else 0f
        weatherMotes(now, dt)

        spawn(now, night, day)
        val it = motes.iterator()
        while (it.hasNext()) {
            val m = it.next()
            val age = now - m.born
            if (age > m.life || m.x < x0 - 48 || m.x > x0 + w + 48 || m.y < y0 - 48 || m.y > y0 + h + 48) { it.remove(); continue }
            m.x += m.vx * dt
            m.y += m.vy * dt
            val u = age.toFloat() / m.life
            when (m.kind) {
                SPARKLE -> {
                    // a glint: grows and shrinks
                    val s = sin(u * PI).toFloat()
                    dots.add(Dot(m.x, m.y, 1, fade(m.color, 0.4f + 0.6f * s)))
                    if (s > 0.35f) {
                        val c = fade(m.color, s)
                        dots.add(Dot(m.x - 1, m.y, 1, c)); dots.add(Dot(m.x + 1, m.y, 1, c))
                        dots.add(Dot(m.x, m.y - 1, 1, c)); dots.add(Dot(m.x, m.y + 1, 1, c))
                    }
                    if (s > 0.8f) {
                        val c = fade(m.color, (s - 0.8f) * 3f)
                        dots.add(Dot(m.x - 2, m.y, 1, c)); dots.add(Dot(m.x + 2, m.y, 1, c))
                    }
                }
                FIREFLY -> {
                    val glow = (0.5f + 0.5f * sin(age / 380f + m.phase)).coerceIn(0f, 1f)
                    m.vx += (random.nextFloat() - 0.5f) * 30f * dt
                    m.vy += (random.nextFloat() - 0.5f) * 30f * dt
                    m.vx = m.vx.coerceIn(-9f, 9f); m.vy = m.vy.coerceIn(-7f, 7f)
                    val edge = minOf(1f, u * 6f, (1 - u) * 6f)
                    if (glow > 0.1f) {
                        dots.add(Dot(m.x - 2, m.y - 1, 5, fade(m.color, 0.12f * glow * edge)))
                        dots.add(Dot(m.x - 1, m.y - 1, 3, fade(m.color, 0.35f * glow * edge)))
                        dots.add(Dot(m.x, m.y, 1, fade(0xFFFFFFC0.toInt(), glow * edge)))
                    }
                }
                LEAF, PETAL, SNOW -> {
                    // sway as it drifts down
                    val sway = sin(age / 420f + m.phase) * (if (m.kind == SNOW) 4f else 9f)
                    val c = fade(m.color, minOf(1f, u * 8f, (1 - u) * 8f))
                    dots.add(Dot(m.x + sway * 0.4f, m.y, m.size, c))
                    if (m.kind == LEAF && sin(age / 200f + m.phase) > 0) dots.add(Dot(m.x + sway * 0.4f + 1, m.y, 1, c))
                }
                DUST -> dots.add(Dot(m.x, m.y, 1, fade(m.color, sin(u * PI).toFloat() * 0.7f)))
                EMBER -> dots.add(Dot(m.x + sin(age / 300f + m.phase) * 2f, m.y, m.size, fade(m.color, 1 - u)))
                RIPPLE -> {
                    // a drip's ring spreading on the water
                    val r = 1f + u * 5f
                    val c = fade(m.color, (1 - u) * 0.8f)
                    for (k in 0 until 12) {
                        val a = k * PI / 6
                        dots.add(Dot(m.x + (cos(a) * r).toFloat(), m.y + (sin(a) * r * 0.45f).toFloat(), 1, c))
                    }
                }
                GRASS -> {
                    m.vy += 60f * dt // falls back down
                    dots.add(Dot(m.x, m.y, 1, fade(m.color, 1 - u)))
                }
                DROP -> dots.add(Dot(m.x, m.y, 1, m.color, 4))
                SPLASH -> {
                    val c = fade(m.color, 1 - u)
                    val r = 1f + u * 2.5f
                    dots.add(Dot(m.x - r, m.y - u * 2f, 1, c)); dots.add(Dot(m.x + r, m.y - u * 2f, 1, c))
                }
                SPARK -> {
                    m.vy += 20f * dt
                    dots.add(Dot(m.x, m.y, 1, fade(m.color, 1 - u)))
                }
            }
        }

        if (weather == Weather.MIST) {
            // banks of mist drifting across
            if (mist.isEmpty()) repeat(5) { mist.add(newMist(x0 + random.nextFloat() * (w + 240) - 120)) }
            for (c in mist) {
                c[0] += c[4] * dt
                if (c[0] - c[2] > x0 + w + 20 || c[0] + c[2] < x0 - 300) { val n = newMist(x0 - c[2] - 20 - random.nextFloat() * 80); System.arraycopy(n, 0, c, 0, 5) }
                cloudsOut.add(Cloud(c[0], c[1], c[2], c[3], 0.34f, mist = true))
            }
        }
        if (day) {
            // cloud shadows drifting slowly over
            if (clouds.isEmpty()) repeat(3) { clouds.add(newCloud(x0 + random.nextFloat() * (w + 200) - 100)) }
            for (c in clouds) {
                c[0] += c[4] * dt
                if (c[0] - c[2] > x0 + w + 20 || c[0] + c[2] < x0 - 300) { val n = newCloud(x0 - c[2] - 20 - random.nextFloat() * 120); System.arraycopy(n, 0, c, 0, 5) }
                cloudsOut.add(Cloud(c[0], c[1], c[2], c[3], 0.17f))
            }
            birds(now, dots)
        }
    }

    /** A Fire type's sparks where it walks, at picture position ([x], [y]). */
    fun sparks(x: Float, y: Float, now: Long) {
        if (motes.size > 140) return
        val colors = intArrayOf(0xFFFFD040.toInt(), 0xFFFF8030.toInt(), 0xFFFFF0A0.toInt())
        repeat(2) {
            motes.add(Mote(x + random.nextFloat() * 8 - 4, y - random.nextFloat() * 4, (random.nextFloat() - 0.5f) * 14f,
                -14f - random.nextFloat() * 14f, now, 380L + random.nextInt(260), colors[random.nextInt(colors.size)], 1, SPARK, 0f))
        }
    }

    /** Puffs of dust where someone came out of the cave floor (or went back), at ([x], [y]). */
    fun puff(x: Float, y: Float, now: Long) {
        repeat(6) {
            motes.add(Mote(x + random.nextFloat() * 10 - 5, y - random.nextFloat() * 3, (random.nextFloat() - 0.5f) * 24f,
                -6f - random.nextFloat() * 8f, now, 500L + random.nextInt(250), 0xFFD8D0C0.toInt(), 1, DUST, 0f))
        }
    }

    /** Splashes on the water at ([x], [y]) (something jumped in or out). */
    fun splash(x: Float, y: Float, now: Long) {
        repeat(3) { k ->
            motes.add(Mote(x + (k - 1) * 3f, y + k, 0f, 0f, now, 600L + k * 120L, 0xFFE8F6FF.toInt(), 1, RIPPLE, 0f))
        }
        repeat(6) {
            motes.add(Mote(x + random.nextFloat() * 6 - 3, y - 2, (random.nextFloat() - 0.5f) * 30f, -30f - random.nextFloat() * 20f,
                now, 450L, 0xFFE8F6FF.toInt(), 1, GRASS, 0f))
        }
    }

    private fun newMist(x: Float): FloatArray {
        val rx = 110f + random.nextFloat() * 110f
        return floatArrayOf(x, y0 + random.nextFloat() * h, rx, rx * (0.28f + random.nextFloat() * 0.15f), 3f + random.nextFloat() * 4f)
    }

    /** Rain falling and splashing, snow, blowing sand. */
    private fun weatherMotes(now: Long, dt: Float) {
        fun chance(perSecond: Float) = random.nextFloat() < perSecond / 30f
        when (weather) {
            Weather.RAIN -> {
                // falling streaks, a little slanted, and splashes where they land
                repeat(4) {
                    if (chance(30f) && motes.size < 260) motes.add(Mote(x0 + random.nextFloat() * (w + 60) - 30, y0 - 6f, -40f, 300f + random.nextFloat() * 60f,
                        now, 900L, 0x88D8E4F4.toInt(), 1, DROP, 0f))
                }
                if (chance(26f)) {
                    val water = scene.waterSpots
                    if (water.isNotEmpty() && random.nextInt(3) == 0) {
                        val i = random.nextInt(water.size / 2)
                        motes.add(Mote(water[i * 2], water[i * 2 + 1], 0f, 0f, now, 600L, 0xFFD8ECFF.toInt(), 1, RIPPLE, 0f))
                    } else {
                        motes.add(Mote(x0 + random.nextFloat() * w, y0 + random.nextFloat() * h, 0f, 0f, now, 260L, 0xFFE0ECF8.toInt(), 1, SPLASH, 0f))
                    }
                }
            }
            Weather.SNOW -> if (kind != FieldMap.Kind.ICE && chance(10f) && motes.size < 200) motes.add(Mote(x0 + random.nextFloat() * (w + 40) - 20, y0 - 4f,
                random.nextFloat() * 8 - 4, 12f + random.nextFloat() * 10, now, 30_000L, 0xFFFFFFFF.toInt(), if (random.nextInt(4) == 0) 2 else 1, SNOW, random.nextFloat() * 6f))
            Weather.SAND -> if (chance(24f) && motes.size < 220) motes.add(Mote(x0 - 4f, y0 + random.nextFloat() * h, 90f + random.nextFloat() * 60f,
                random.nextFloat() * 10 - 5, now, 6_000L, if (random.nextBoolean()) 0xC0E8D0A0.toInt() else 0xA0D0B080.toInt(), 1, DUST, 0f))
            else -> {}
        }
    }

    /** The Pokémon walked into tall grass at picture position ([x], [y]): blades fly up. */
    fun rustle(x: Float, y: Float, now: Long) {
        val green = intArrayOf(0xFF4A9C3A.toInt(), 0xFF6CC04A.toInt(), 0xFF2F7A2C.toInt())
        repeat(6) {
            motes.add(Mote(x + random.nextFloat() * 10 - 5, y - 2 - random.nextFloat() * 3, (random.nextFloat() - 0.5f) * 30f,
                -20f - random.nextFloat() * 25f, now, 420L + random.nextInt(200), green[random.nextInt(green.size)], 1, GRASS, 0f))
        }
    }

    private fun newCloud(x: Float): FloatArray {
        val rx = 50f + random.nextFloat() * 70f
        return floatArrayOf(x, y0 + random.nextFloat() * h, rx, rx * (0.45f + random.nextFloat() * 0.2f), 5f + random.nextFloat() * 5f)
    }

    /** Now and then a pair of birds' shadows flies across. */
    private fun birds(now: Long, dots: MutableList<Dot>) {
        val flight = 9_000L
        if (birdsAt == Long.MIN_VALUE || now - birdsAt > flight + 18_000 + random.nextInt(20_000)) {
            birdsAt = now
            birdsDir = if (random.nextBoolean()) 1f else -1f
            birdsFrom = if (birdsDir > 0) x0 - 20f else x0 + w + 20f
            birdsY = y0 + h * (0.2f + random.nextFloat() * 0.6f)
        }
        val age = now - birdsAt
        if (age > flight) return
        val bx = birdsFrom + birdsDir * (w + 40f) * age / flight
        val by = birdsY - (w + 40f) * 0.25f * age / flight
        val shade = 0x55000000
        for (b in 0 until 2) {
            val x = bx - birdsDir * b * 14f
            val y = by + b * 9f
            val up = ((age / 140) + b) % 2 == 0L
            // a little "v" (wings up) or "-" (wings down)
            dots.add(Dot(x, y, 1, shade))
            dots.add(Dot(x - 1, y + if (up) -1 else 0, 1, shade)); dots.add(Dot(x + 1, y + if (up) -1 else 0, 1, shade))
            dots.add(Dot(x - 2, y + if (up) -2 else 1, 1, shade)); dots.add(Dot(x + 2, y + if (up) -2 else 1, 1, shade))
        }
    }

    private fun spawn(now: Long, night: Boolean, day: Boolean) {
        if (motes.size > 90) return
        val water = scene.waterSpots
        val waterTiles = water.size / 2
        fun chance(perSecond: Float) = random.nextFloat() < perSecond / 30f // called about 30 times a second

        // glints on the water (fewer at night, none in caves)
        if (waterTiles > 0 && scene.outdoor && chance(minOf(20f, 2f + waterTiles / 6f) * if (night) 0.3f else 1f)) {
            val i = random.nextInt(waterTiles)
            motes.add(Mote(water[i * 2] + random.nextFloat() * 12 - 6, water[i * 2 + 1] + random.nextFloat() * 6 - 3, 0f, 0f, now,
                500L + random.nextInt(400), if (night) 0xFFB8C8F0.toInt() else 0xFFFFFFFF.toInt(), 1, SPARKLE, 0f))
        }
        when {
            !scene.outdoor && kind != FieldMap.Kind.ICE -> {
                // a cave: dust in the air, drips on the water
                if (chance(4f)) motes.add(Mote(x0 + random.nextFloat() * w, y0 + random.nextFloat() * h, random.nextFloat() * 4 - 2,
                    random.nextFloat() * 3 - 1, now, 3_000L + random.nextInt(3_000), 0xFFD8D0C0.toInt(), 1, DUST, 0f))
                if (waterTiles > 0 && chance(0.6f)) {
                    val i = random.nextInt(waterTiles)
                    motes.add(Mote(water[i * 2], water[i * 2 + 1], 0f, 0f, now, 900L, 0xFFC8E0FF.toInt(), 1, RIPPLE, 0f))
                }
            }
            kind == FieldMap.Kind.ICE -> if (chance(9f)) motes.add(Mote(x0 + random.nextFloat() * w, y0 - 4f, random.nextFloat() * 6 - 3,
                10f + random.nextFloat() * 8, now, 30_000L, 0xFFFFFFFF.toInt(), if (random.nextInt(4) == 0) 2 else 1, SNOW, random.nextFloat() * 6f))
            kind == FieldMap.Kind.VOLCANO -> if (chance(4f)) motes.add(Mote(x0 + random.nextFloat() * w, y0 + h + 2, random.nextFloat() * 4 - 2,
                -12f - random.nextFloat() * 10, now, 6_000L + random.nextInt(3_000),
                if (random.nextBoolean()) 0xFFFF8A30.toInt() else 0xFFFFC840.toInt(), 1, EMBER, random.nextFloat() * 6f))
            night -> if (kind != FieldMap.Kind.SEA && motes.count { it.kind == FIREFLY } < 16 && chance(3f)) {
                motes.add(Mote(x0 + random.nextFloat() * w, y0 + random.nextFloat() * h, random.nextFloat() * 10 - 5, random.nextFloat() * 8 - 4,
                    now, 6_000L + random.nextInt(6_000), 0xFFD8FF70.toInt(), 1, FIREFLY, random.nextFloat() * 6f))
            }
            kind == FieldMap.Kind.FOREST || kind == FieldMap.Kind.MOUNTAIN || name.contains("yellow") -> if (chance(3f)) {
                val colors = if (name.contains("yellow")) intArrayOf(0xFFF0C030.toInt(), 0xFFE08A20.toInt(), 0xFFD8B040.toInt())
                             else intArrayOf(0xFF5AA040.toInt(), 0xFF7CC050.toInt(), 0xFFB0C040.toInt())
                motes.add(Mote(x0 + random.nextFloat() * (w + 60) - 30, y0 - 4f, 6f + random.nextFloat() * 6, 9f + random.nextFloat() * 6, now,
                    40_000L, colors[random.nextInt(colors.size)], 2, LEAF, random.nextFloat() * 6f))
            }
            day && (kind == FieldMap.Kind.FIELD || kind == FieldMap.Kind.TOWN || kind == FieldMap.Kind.LAKE) -> if (chance(2.5f)) {
                val colors = intArrayOf(0xFFFFC8D8.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFE070.toInt())
                motes.add(Mote(x0 - 4f, y0 + random.nextFloat() * h, 14f + random.nextFloat() * 10, 2f + random.nextFloat() * 4, now,
                    40_000L, colors[random.nextInt(colors.size)], if (random.nextInt(3) == 0) 2 else 1, PETAL, random.nextFloat() * 6f))
            }
        }
    }

    private fun fade(color: Int, k: Float): Int {
        val a = (((color ushr 24) and 0xFF) * k.coerceIn(0f, 1f)).toInt()
        return (a shl 24) or (color and 0xFFFFFF)
    }

    companion object {
        /**
         * The weather on [course] at [epochMillis] (local time [minute] of the day): the game's
         * own where the map has some (the Lake of Rage's rain, Dark Cave's darkness), snow on
         * Mt. Silver, else now and then a shower, morning mist or (by the sea) blowing sand,
         * picked by the hour so it lasts a while and comes back the same.
         */
        fun weatherFor(course: FieldMap.Course, day: Int, minute: Int): Weather {
            when (course.map) {
                88 -> return Weather.RAIN      // the Lake of Rage: WEATHER_RAIN in its map header
                176 -> return Weather.DARK     // Dark Cave: WEATHER_FLASH, too dark to see without Flash
                90 -> return Weather.SNOW      // Mt. Silver
            }
            if (course.kind == FieldMap.Kind.CAVE || course.kind == FieldMap.Kind.ICE || course.kind == FieldMap.Kind.VOLCANO) return Weather.CLEAR
            if (course.place.contains("Forest") && course.kind == FieldMap.Kind.FOREST && course.matrix != 0) return Weather.CLEAR
            val hour = minute / 60
            val roll = Math.floorMod((course.name.hashCode() * 31 + day) * 131 + hour * 7919, 100)
            return when {
                roll < 9 -> Weather.RAIN
                hour in 5..8 && roll < 30 && (course.kind == FieldMap.Kind.LAKE || course.kind == FieldMap.Kind.FIELD || course.kind == FieldMap.Kind.FOREST) -> Weather.MIST
                course.kind == FieldMap.Kind.BEACH && roll in 90..96 -> Weather.SAND
                else -> Weather.CLEAR
            }
        }

        private const val SPARKLE = 0
        private const val FIREFLY = 1
        private const val LEAF = 2
        private const val PETAL = 3
        private const val SNOW = 4
        private const val DUST = 5
        private const val EMBER = 6
        private const val RIPPLE = 7
        private const val GRASS = 8
        private const val DROP = 9
        private const val SPLASH = 10
        private const val SPARK = 11
    }
}
