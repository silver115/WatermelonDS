package me.magnum.melonds.pokewalker

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/** A little picture of our own, ARGB, [width] x [height]. */
class CritterArt(val width: Int, val height: Int, val pixels: IntArray) {
    companion object {
        /** A picture from rows of characters, each a colour from [palette] ('.' and ' ' see-through). */
        fun of(palette: Map<Char, Int>, vararg rows: String): CritterArt {
            val w = rows.maxOf { it.length }
            val px = IntArray(w * rows.size)
            for ((y, row) in rows.withIndex()) for ((x, c) in row.withIndex()) px[y * w + x] = palette[c] ?: 0
            return CritterArt(w, rows.size, px)
        }
    }
}

/**
 * The little lives about the route map, all our own pixels, in the picture's own coordinates:
 * butterflies by day (they land, and flutter off when the Pokémon comes close), small birds
 * hopping on the ground (they fly off as it comes), dragonflies over water, fish moving under
 * it, moths round the lamps at night, dew glinting in the morning, stars twinkling on the water
 * and now and then a shooting star, a rainbow after the rain; and by the real date ([occasions],
 * see [SpecialDates]) autumn leaves falling and lying about (they scatter when walked through),
 * blossom petals in spring, snow at Christmas, wisps and glowing pumpkins at Halloween,
 * fireworks at New Year, hearts on Valentine's Day, confetti on Pokémon Day. Pure Kotlin.
 */
class MapCritters(private val scene: FieldMap.Scene, private val random: Random = Random.Default) {

    /** A picture to draw with its middle bottom at ([x], [y]) (picture pixels), [alpha] solid; [ground]: under everyone. */
    class Pic(val art: CritterArt, val x: Float, val y: Float, val alpha: Float = 1f, val ground: Boolean = false)

    /** The part of the picture in view now (left, top, right, bottom); things happen there. */
    var view: FloatArray = scene.view.copyOf()
    var occasions: Set<SpecialDates.Occasion> = emptySet()
    var weather = MapAmbience.Weather.CLEAR

    /** Shooting stars that went over since last taken (the Pokémon looks up). */
    var shootingStars = 0

    /** A rainbow after the rain: how strong it is now (0: none), its middle and radius (picture pixels). */
    var rainbow = 0f
        private set
    var rainbowX = 0f
        private set
    var rainbowY = 0f
        private set
    var rainbowR = 0f
        private set

    private class Flutter(
        var x: Float, var y: Float, var tx: Float, var ty: Float, val kind: Int, val color: Int, val born: Long, val life: Long,
        var landedUntil: Long = Long.MIN_VALUE, var fleeing: Boolean = false, var retarget: Long = 0L, var phase: Float = 0f,
        var vx: Float = 0f, var vy: Float = 0f,
    )

    private class Bird(var x: Float, var y: Float, var nextHop: Long, var facing: Int, var flying: Boolean = false,
                       var vx: Float = 0f, var vy: Float = 0f, var hopStart: Long = Long.MIN_VALUE, var peckAt: Long = Long.MIN_VALUE)

    private class Leaf(var x: Float, var y: Float, val color: Int, val shape: Int, var vx: Float = 0f, var vy: Float = 0f,
                       var lift: Float = 0f, var liftV: Float = 0f, var flying: Boolean = false)

    private class Spark(val x: Float, val y: Float, val start: Long, val life: Long, val color: Int, val kind: Int,
                        val vx: Float = 0f, val vy: Float = 0f)

    private class Burst(val x: Float, val y: Float, val start: Long, val color: Int, val riseFrom: Float)

    private val flutters = ArrayList<Flutter>()
    private val birds = ArrayList<Bird>()
    private val fish = ArrayList<Flutter>()
    private val drifts = ArrayList<Flutter>()
    private val sparks = ArrayList<Spark>()
    private val bursts = ArrayList<Burst>()
    private val groundLeaves = ArrayList<Leaf>()
    private var leavesFor = false
    private var lastNow = Long.MIN_VALUE
    private var nextBirdsAt = 0L
    private var nextStarAt = 0L
    private var nextBurstAt = 0L
    private var wasRaining = false
    private var rainbowAt = Long.MIN_VALUE
    private var star: FloatArray? = null
    private var starAt = 0L

    /** Where the ground is in the picture (projected once): tile, x, y. */
    private val ground: List<Triple<FieldMap.Tile, Float, Float>> by lazy {
        scene.tiles.values.mapNotNull { t -> scene.project(t.x, t.y, t.z)?.let { Triple(t, it[0], it[1]) } }
            .sortedWith(compareBy({ it.first.gy }, { it.first.gx }))
    }

    private fun inView(x: Float, y: Float, margin: Float = 0f) = x >= view[0] - margin && x <= view[2] + margin && y >= view[1] - margin && y <= view[3] + margin

    private fun groundInView(): List<Triple<FieldMap.Tile, Float, Float>> = ground.filter { inView(it.second, it.third, -6f) }

    // ---- what's around ----
    private val kind = scene.course.kind
    private val outdoor = scene.outdoor
    private val leafy = outdoor && kind != FieldMap.Kind.SEA && kind != FieldMap.Kind.ICE && kind != FieldMap.Kind.VOLCANO && kind != FieldMap.Kind.CAVE
    private val butterflyPlace = outdoor && kind in setOf(FieldMap.Kind.FIELD, FieldMap.Kind.FOREST, FieldMap.Kind.TOWN, FieldMap.Kind.LAKE, FieldMap.Kind.MOUNTAIN)
    private val birdPlace = outdoor && kind in setOf(FieldMap.Kind.FIELD, FieldMap.Kind.TOWN, FieldMap.Kind.BEACH, FieldMap.Kind.LAKE)

    /** Pumpkins at Halloween: a few spots at the edges of the ground, the same each time. */
    val pumpkins: List<FieldMap.Tile> by lazy {
        if (!outdoor) return@lazy emptyList<FieldMap.Tile>()
        val edge = ground.filter { (t, x, y) ->
            !t.tallGrass && scene.inView(x, y, scene.tilePixels) &&
                (0 until 4).count { d -> scene.tile(t.gx + DX[d], t.gy + DY[d]) == null } >= 1 &&
                scene.people.none { abs(it.home.gx - t.gx) + abs(it.home.gy - t.gy) <= 1 } &&
                (scene.start == null || abs(scene.start.gx - t.gx) + abs(scene.start.gy - t.gy) >= 3)
        }.map { it.first }.sortedBy { Math.floorMod((scene.course.name.hashCode() * 13 + it.gx) * 7919 + it.gy * 104729, 1_000_003) }
        val out = ArrayList<FieldMap.Tile>()
        for (t in edge) {
            if (out.size >= 3) break
            if (out.any { abs(it.gx - t.gx) + abs(it.gy - t.gy) < 5 }) continue
            out.add(t)
        }
        out
    }

    /** Whether something of ours stands on tile ([gx], [gy]) (a pumpkin). */
    fun blocks(gx: Int, gy: Int): Boolean = SpecialDates.Occasion.HALLOWEEN in occasions && pumpkins.any { it.gx == gx && it.gy == gy }

    // ---- from outside ----

    /** Someone walked by at picture point ([x], [y]): leaves on the ground scatter, birds fly off, butterflies too. */
    fun disturb(x: Float, y: Float, now: Long) {
        for (l in groundLeaves) {
            if (l.flying || hypot(l.x - x, l.y - y) > 12f) continue
            // a little toss up and away, and down again
            l.flying = true
            val a = random.nextFloat() * 2 * PI
            l.vx = (cos(a) * (14f + random.nextFloat() * 18f)).toFloat()
            l.vy = (sin(a) * 6f).toFloat()
            l.lift = 0.01f
            l.liftV = 30f + random.nextFloat() * 20f
        }
        for (b in birds) if (!b.flying && hypot(b.x - x, b.y - y) < 40f) flyOff(b, x)
        for (f in flutters) if (f.landedUntil != Long.MIN_VALUE && hypot(f.x - x, f.y - y) < 20f) { f.landedUntil = Long.MIN_VALUE; f.fleeing = true; f.retarget = now + 900 }
    }

    /** Leaves come down from a shaken tree's box (picture pixels). */
    fun leafBurst(left: Int, top: Int, right: Int, bottom: Int, now: Long) {
        val colors = if (SpecialDates.Occasion.AUTUMN in occasions) AUTUMN_COLORS else GREEN_COLORS
        repeat(7) {
            val x = left + random.nextFloat() * (right - left)
            val y = top + random.nextFloat() * (bottom - top) * 0.6f
            drifts.add(Flutter(x, y, 0f, 0f, DRIFT_LEAF, colors[random.nextInt(colors.size)], now, 1_600L + random.nextInt(900),
                vx = (random.nextFloat() - 0.5f) * 20f, vy = 16f + random.nextFloat() * 14f, phase = random.nextFloat() * 6f))
        }
    }

    // ---- each frame ----

    /** Moves everything to [now] at [minute] of the day; [pokemon] is the walking Pokémon's picture point, if any. */
    fun frame(now: Long, minute: Int, pokemon: FloatArray?, dots: MutableList<MapAmbience.Dot>, pics: MutableList<Pic>) {
        dots.clear()
        pics.clear()
        val dt = if (lastNow == Long.MIN_VALUE) 0.033f else ((now - lastNow).coerceIn(0, 200)) / 1000f
        lastNow = now
        val m = Math.floorMod(minute, 24 * 60)
        val night = m < 5 * 60 || m >= 19 * 60
        val day = outdoor && m in 6 * 60 until 18 * 60 + 30
        val clear = weather == MapAmbience.Weather.CLEAR
        fun chance(perSecond: Float) = random.nextFloat() < perSecond * dt

        // a rainbow after the rain, by day
        val raining = weather == MapAmbience.Weather.RAIN
        if (wasRaining && !raining && day) rainbowAt = now
        wasRaining = raining
        rainbow = if (rainbowAt == Long.MIN_VALUE || !day) 0f else {
            val age = now - rainbowAt
            when {
                age > RAINBOW_MS -> 0f
                age < 5_000 -> age / 5_000f * 0.32f
                age > RAINBOW_MS - 12_000 -> (RAINBOW_MS - age) / 12_000f * 0.32f
                else -> 0.32f
            }
        }
        if (rainbow > 0f) {
            rainbowX = (view[0] + view[2]) / 2f
            rainbowR = (view[3] - view[1]) * 0.95f
            rainbowY = view[3] + (view[3] - view[1]) * 0.25f
        }

        butterflies(now, dt, day && clear && butterflyPlace, pokemon, pics)
        groundBirds(now, dt, day && clear && birdPlace, pokemon, pics)
        dragonflies(now, dt, day && clear && outdoor, pics)
        fishes(now, dt, pics)
        moths(now, night && outdoor, dots)
        // dew glinting on the grass in the morning
        if (outdoor && clear && m in 5 * 60 until 9 * 60 && chance(5f)) {
            groundInView().randomOrNull(random)?.let { (_, x, y) -> sparks.add(Spark(x + random.nextFloat() * 10 - 5, y - random.nextFloat() * 4, now, 700, 0xFFE8FFFF.toInt(), SPARK_GLINT)) }
        }
        // stars twinkling on the water at night
        val water = scene.waterSpots
        if (outdoor && clear && night && water.size >= 2 && chance(4f)) {
            val i = random.nextInt(water.size / 2)
            val x = water[i * 2] + random.nextFloat() * 12 - 6
            val y = water[i * 2 + 1] + random.nextFloat() * 6 - 3
            if (inView(x, y)) sparks.add(Spark(x, y, now, 1_300, 0xFFF0F4FF.toInt(), SPARK_TWINKLE))
        }
        shootingStar(now, dt, outdoor && clear && (m >= 21 * 60 || m < 4 * 60), dots)
        seasons(now, dt, m, night, pokemon, dots, pics)
        // drifting things (leaves out of a tree, petals, snow, hearts, wisps)
        val it = drifts.iterator()
        while (it.hasNext()) {
            val d = it.next()
            val age = now - d.born
            if (age > d.life || d.y > view[3] + 12 || d.y < view[1] - 40) { it.remove(); continue }
            d.x += d.vx * dt
            d.y += d.vy * dt
            val u = age.toFloat() / d.life
            val fadeIn = minOf(1f, u * 8f, (1f - u) * 6f)
            when (d.kind) {
                DRIFT_LEAF, DRIFT_PETAL -> {
                    val sway = sin(age / 380f + d.phase) * 5f
                    val c = fade(d.color, fadeIn)
                    dots.add(MapAmbience.Dot(d.x + sway, d.y, if (d.kind == DRIFT_LEAF) 2 else 1, c, 1))
                    if (d.kind == DRIFT_LEAF && sin(age / 160f + d.phase) > 0) dots.add(MapAmbience.Dot(d.x + sway + 1, d.y + 1, 1, c))
                }
                DRIFT_SNOW -> dots.add(MapAmbience.Dot(d.x + sin(age / 500f + d.phase) * 3f, d.y, if (d.color == 2) 2 else 1, fade(0xFFFFFFFF.toInt(), fadeIn)))
                DRIFT_HEART -> pics.add(Pic(HEART, d.x + sin(age / 420f + d.phase) * 3f, d.y, fadeIn))
                DRIFT_WISP -> {
                    val glow = 0.6f + 0.4f * sin(age / 300f + d.phase)
                    d.vx += (random.nextFloat() - 0.5f) * 20f * dt
                    d.vy += (random.nextFloat() - 0.5f) * 16f * dt
                    d.vx = d.vx.coerceIn(-7f, 7f); d.vy = d.vy.coerceIn(-6f, 4f)
                    val a = glow * fadeIn
                    dots.add(MapAmbience.Dot(d.x - 3, d.y - 3, 7, fade(d.color, 0.10f * a)))
                    dots.add(MapAmbience.Dot(d.x - 2, d.y - 2, 5, fade(d.color, 0.22f * a)))
                    dots.add(MapAmbience.Dot(d.x - 1, d.y - 1, 3, fade(d.color, 0.55f * a)))
                    dots.add(MapAmbience.Dot(d.x, d.y, 1, fade(0xFFFFFFFF.toInt(), a)))
                }
                DRIFT_CONFETTI -> {
                    val flip = sin(age / 90f + d.phase) > 0
                    dots.add(MapAmbience.Dot(d.x + sin(age / 300f + d.phase) * 4f, d.y, if (flip) 2 else 1, fade(d.color, fadeIn), if (flip) 1 else 2))
                }
                DRIFT_FIREFLY -> {
                    val glow = (0.5f + 0.5f * sin(age / 380f + d.phase)).coerceIn(0f, 1f)
                    d.vx += (random.nextFloat() - 0.5f) * 30f * dt
                    d.vy += (random.nextFloat() - 0.5f) * 30f * dt
                    d.vx = d.vx.coerceIn(-9f, 9f); d.vy = d.vy.coerceIn(-7f, 7f)
                    if (glow > 0.1f) {
                        dots.add(MapAmbience.Dot(d.x - 2, d.y - 1, 5, fade(d.color, 0.12f * glow * fadeIn)))
                        dots.add(MapAmbience.Dot(d.x - 1, d.y - 1, 3, fade(d.color, 0.35f * glow * fadeIn)))
                        dots.add(MapAmbience.Dot(d.x, d.y, 1, fade(0xFFFFFFC0.toInt(), glow * fadeIn)))
                    }
                }
            }
        }
        // glints and twinkles
        val sp = sparks.iterator()
        while (sp.hasNext()) {
            val s = sp.next()
            val age = now - s.start
            if (age > s.life) { sp.remove(); continue }
            val u = age.toFloat() / s.life
            val k = sin(u * PI).toFloat()
            when (s.kind) {
                SPARK_GLINT -> {
                    dots.add(MapAmbience.Dot(s.x, s.y, 1, fade(s.color, 0.4f + 0.6f * k)))
                    if (k > 0.5f) {
                        val c = fade(s.color, k * 0.8f)
                        dots.add(MapAmbience.Dot(s.x - 1, s.y, 1, c)); dots.add(MapAmbience.Dot(s.x + 1, s.y, 1, c))
                        dots.add(MapAmbience.Dot(s.x, s.y - 1, 1, c)); dots.add(MapAmbience.Dot(s.x, s.y + 1, 1, c))
                    }
                }
                SPARK_TWINKLE -> dots.add(MapAmbience.Dot(s.x, s.y, 1, fade(s.color, k * k)))
                SPARK_FIREWORK -> {
                    // a spark of a burst: out and falling, fading
                    val t = age / 1000f
                    val x = s.x + s.vx * t
                    val y = s.y + s.vy * t + 22f * t * t
                    dots.add(MapAmbience.Dot(x, y, 1, fade(s.color, 1f - u)))
                    if (u < 0.5f) dots.add(MapAmbience.Dot(x - s.vx * 0.03f, y - s.vy * 0.03f, 1, fade(s.color, (1f - u) * 0.4f)))
                }
            }
        }
        // leaves lying about, and the ones blown up
        if (SpecialDates.Occasion.AUTUMN in occasions && leafy) {
            if (!leavesFor) placeGroundLeaves()
            for (l in groundLeaves) {
                if (l.flying) {
                    l.x += l.vx * dt
                    l.y += l.vy * dt
                    l.lift += l.liftV * dt
                    l.liftV -= 90f * dt
                    l.vx *= (1f - 1.5f * dt)
                    if (l.lift <= 0f) { l.lift = 0f; l.flying = false; l.liftV = 0f }
                }
                if (!inView(l.x, l.y, 4f)) continue
                pics.add(Pic(LEAVES[l.shape][Math.floorMod(l.color, LEAF_TINTS.size)], l.x, l.y - l.lift + 1, 1f, ground = !l.flying))
            }
        }
        // Halloween pumpkins, glowing after dark
        if (SpecialDates.Occasion.HALLOWEEN in occasions) {
            val lit = m >= 17 * 60 + 30 || m < 6 * 60
            for ((i, t) in pumpkins.withIndex()) {
                val p = scene.project(t.x, t.y, t.z) ?: continue
                if (!inView(p[0], p[1], 8f)) continue
                pics.add(Pic(if (lit) PUMPKIN_LIT else PUMPKIN, p[0], p[1] + 1, 1f, ground = true))
                if (lit) {
                    val f = 0.75f + 0.25f * sin(now / 120.0 + i * 2.1).toFloat()
                    dots.add(MapAmbience.Dot(p[0] - 5, p[1] - 9, 11, fade(0xFFFFB040.toInt(), 0.10f * f), 9))
                    dots.add(MapAmbience.Dot(p[0] - 3, p[1] - 7, 7, fade(0xFFFFC060.toInt(), 0.16f * f), 6))
                }
            }
        }
    }

    // ---- butterflies ----
    private fun butterflies(now: Long, dt: Float, on: Boolean, pokemon: FloatArray?, pics: MutableList<Pic>) {
        val spring = SpecialDates.Occasion.SPRING in occasions
        val most = if (spring) 4 else 3
        if (on && flutters.count { it.kind == FLUTTER_BUTTERFLY } < most && random.nextFloat() < dt / 6f) {
            groundInView().randomOrNull(random)?.let { (_, x, y) ->
                val color = BUTTERFLY_COLORS[random.nextInt(BUTTERFLY_COLORS.size)]
                flutters.add(Flutter(x, y - 14f - random.nextFloat() * 10f, x, y - 14f, FLUTTER_BUTTERFLY, color, now, 50_000L + random.nextInt(60_000), phase = random.nextFloat() * 6f))
            }
        }
        val it = flutters.iterator()
        while (it.hasNext()) {
            val f = it.next()
            if (f.kind != FLUTTER_BUTTERFLY) continue
            val age = now - f.born
            val gone = !inView(f.x, f.y, 40f)
            if (gone || (age > f.life && !inView(f.x, f.y))) { it.remove(); continue }
            val leaving = age > f.life || !on
            val landed = f.landedUntil != Long.MIN_VALUE && now < f.landedUntil
            // the Pokémon coming close: off it goes
            if (pokemon != null && hypot(pokemon[0] - f.x, pokemon[1] - 8f - f.y) < 18f && !f.fleeing) {
                f.fleeing = true
                f.landedUntil = Long.MIN_VALUE
                f.tx = f.x + (f.x - pokemon[0]).let { if (abs(it) < 1f) 30f else it * 3f }
                f.ty = f.y - 26f
                f.retarget = now + 1_500
            }
            if (!landed) {
                if (now >= f.retarget) {
                    f.fleeing = false
                    if (leaving) {
                        f.tx = f.x + (if (f.x < (view[0] + view[2]) / 2) -80f else 80f); f.ty = f.y - 60f
                        f.retarget = now + 4_000
                    } else if (random.nextInt(4) == 0) {
                        // down to rest on the ground a moment
                        groundInView().filter { abs(it.second - f.x) < 50 && abs(it.third - f.y) < 40 }.randomOrNull(random)?.let { (_, x, y) ->
                            f.tx = x + random.nextFloat() * 8 - 4; f.ty = y - 2f
                            f.landedUntil = Long.MIN_VALUE
                            f.vx = 1f // heading down to land
                        }
                        f.retarget = now + 2_500
                    } else {
                        f.tx = (f.x + random.nextFloat() * 70 - 35).coerceIn(view[0] + 8, view[2] - 8)
                        f.ty = (f.y + random.nextFloat() * 40 - 20).coerceIn(view[1] + 10, view[3] - 6)
                        f.vx = 0f
                        f.retarget = now + 1_200 + random.nextInt(1_600)
                    }
                }
                val dx = f.tx - f.x
                val dy = f.ty - f.y
                val d = hypot(dx, dy)
                val speed = if (f.fleeing) 34f else 15f
                if (d > 1f) {
                    f.x += dx / d * minOf(d, speed * dt)
                    f.y += dy / d * minOf(d, speed * dt)
                } else if (f.vx == 1f && !leaving) {
                    // landed
                    f.landedUntil = now + 2_000 + random.nextInt(2_500)
                    f.vx = 0f
                }
            } else if (now >= f.landedUntil - 50) {
                f.landedUntil = Long.MIN_VALUE
                f.retarget = now
            }
            val restNow = f.landedUntil != Long.MIN_VALUE && now < f.landedUntil
            val flap = if (restNow) ((now / 420) % 2 == 0L) else ((now / 85 + f.phase.toInt()) % 2 == 0L)
            val bob = if (restNow) 0f else sin(now / 170f + f.phase) * 1.5f
            pics.add(Pic(BUTTERFLY[Math.floorMod(f.color, BUTTERFLY_COLORS.size)][if (flap) 0 else 1], f.x, f.y + bob))
        }
    }

    // ---- small birds on the ground ----
    private fun groundBirds(now: Long, dt: Float, on: Boolean, pokemon: FloatArray?, pics: MutableList<Pic>) {
        if (on && birds.none { !it.flying } && now >= nextBirdsAt) {
            nextBirdsAt = now + 35_000 + random.nextInt(40_000)
            val spots = groundInView().filter { (t, x, y) -> !t.tallGrass && (pokemon == null || hypot(x - pokemon[0], y - pokemon[1]) > 70f) }
            spots.randomOrNull(random)?.let { (_, x, y) ->
                repeat(2 + random.nextInt(2)) { k ->
                    birds.add(Bird(x + k * 7f - 6f + random.nextFloat() * 4, y + random.nextFloat() * 6 - 3, now + 400 + random.nextInt(900), if (random.nextBoolean()) 1 else -1))
                }
            }
        }
        val it = birds.iterator()
        while (it.hasNext()) {
            val b = it.next()
            if (b.flying) {
                b.x += b.vx * dt
                b.y += b.vy * dt
                b.vy -= 6f * dt
                if (!inView(b.x, b.y, 30f)) { it.remove(); continue }
                val up = (now / 70) % 2 == 0L
                pics.add(Pic(if (b.facing > 0) BIRD_FLY_R[if (up) 0 else 1] else BIRD_FLY_L[if (up) 0 else 1], b.x, b.y))
                continue
            }
            if (pokemon != null && hypot(pokemon[0] - b.x, pokemon[1] - b.y) < 36f) { flyOff(b, pokemon[0]); continue }
            if (!on && random.nextFloat() < dt / 3f) { flyOff(b, b.x - b.facing); continue }
            if (now >= b.nextHop) {
                b.nextHop = now + 500 + random.nextInt(1_200)
                if (random.nextInt(3) == 0) b.facing = -b.facing
                if (random.nextBoolean()) {
                    b.hopStart = now
                    val nx = b.x + b.facing * (2f + random.nextFloat() * 3f)
                    if (groundNear(nx, b.y)) b.x = nx
                } else b.peckAt = now
            }
            val hop = if (now - b.hopStart in 0 until 160) 2f else 0f
            val peck = now - b.peckAt in 0 until 260
            pics.add(Pic(if (b.facing > 0) (if (peck) BIRD_PECK_R else BIRD_R) else (if (peck) BIRD_PECK_L else BIRD_L), b.x, b.y - hop, 1f, ground = true))
        }
    }

    private fun flyOff(b: Bird, fromX: Float) {
        b.flying = true
        b.facing = if (b.x >= fromX) 1 else -1
        b.vx = b.facing * (40f + random.nextFloat() * 25f)
        b.vy = -30f - random.nextFloat() * 20f
    }

    private fun groundNear(x: Float, y: Float): Boolean = ground.any { abs(it.second - x) < 9f && abs(it.third - y) < 7f }

    // ---- dragonflies ----
    private fun dragonflies(now: Long, dt: Float, on: Boolean, pics: MutableList<Pic>) {
        val water = scene.waterSpots
        val n = flutters.count { it.kind == FLUTTER_DRAGONFLY }
        if (on && water.size >= 12 && n < 2 && random.nextFloat() < dt / 8f) {
            val i = random.nextInt(water.size / 2)
            val x = water[i * 2]
            val y = water[i * 2 + 1] - 10f
            if (inView(x, y)) flutters.add(Flutter(x, y, x, y, FLUTTER_DRAGONFLY, random.nextInt(2), now, 30_000L + random.nextInt(30_000)))
        }
        for (f in flutters) {
            if (f.kind != FLUTTER_DRAGONFLY) continue
            if (now >= f.retarget) {
                // a dart, then hovering
                val i = random.nextInt(maxOf(1, water.size / 2))
                f.tx = if (water.size >= 2) water[i * 2] else f.x
                f.ty = if (water.size >= 2) water[i * 2 + 1] - 8f - random.nextFloat() * 10f else f.y
                if (hypot(f.tx - f.x, f.ty - f.y) > 60f) { f.tx = f.x + (f.tx - f.x) * 0.4f; f.ty = f.y + (f.ty - f.y) * 0.4f }
                f.retarget = now + 900 + random.nextInt(1_400)
            }
            val dx = f.tx - f.x
            val dy = f.ty - f.y
            val d = hypot(dx, dy)
            if (d > 0.5f) { f.x += dx / d * minOf(d, 70f * dt); f.y += dy / d * minOf(d, 70f * dt) }
            val jitter = if (d <= 0.5f) sin(now / 60f) * 0.6f else 0f
            pics.add(Pic(DRAGONFLY[f.color][((now / 40) % 2).toInt()], f.x, f.y + jitter))
        }
        flutters.removeAll { it.kind == FLUTTER_DRAGONFLY && (now - it.born > it.life || !on) }
    }

    // ---- fish under the water ----
    private fun fishes(now: Long, dt: Float, pics: MutableList<Pic>) {
        val water = scene.waterSpots
        if (water.size < 16) return
        if (fish.size < 3 && random.nextFloat() < dt / 3f) {
            val i = random.nextInt(water.size / 2)
            val x = water[i * 2]
            val y = water[i * 2 + 1]
            if (inView(x, y)) fish.add(Flutter(x, y, x, y, 0, 0, now, 40_000L + random.nextInt(40_000)))
        }
        val it = fish.iterator()
        while (it.hasNext()) {
            val f = it.next()
            val age = now - f.born
            if (age > f.life) { it.remove(); continue }
            if (now >= f.retarget) {
                // somewhere else under the water nearby (now and then a quick dart)
                val near = (0 until water.size / 2).map { water[it * 2] to water[it * 2 + 1] }.filter { (x, y) -> abs(x - f.x) < 40 && abs(y - f.y) < 24 }
                near.randomOrNull(random)?.let { (x, y) -> f.tx = x; f.ty = y }
                f.vx = if (random.nextInt(5) == 0) 28f else 6f
                f.retarget = now + 2_000 + random.nextInt(3_000)
            }
            val dx = f.tx - f.x
            val dy = f.ty - f.y
            val d = hypot(dx, dy)
            if (d > 0.5f) { f.x += dx / d * minOf(d, f.vx * dt); f.y += dy / d * minOf(d, f.vx * dt) }
            val a = minOf(1f, age / 1_500f, (f.life - age) / 1_500f)
            pics.add(Pic(if (dx >= 0) FISH_R else FISH_L, f.x, f.y + 1, a * 0.9f, ground = true))
        }
    }

    // ---- moths round the lamps ----
    private fun moths(now: Long, on: Boolean, dots: MutableList<MapAmbience.Dot>) {
        if (!on) return
        val g = scene.glows
        var i = 0
        var k = 0
        while (i + 3 < g.size) {
            if (g[i + 3] >= FieldMap.LAMP && inView(g[i], g[i + 1])) {
                for (j in 0 until 2) {
                    val a = now / (380.0 + j * 90) + k * 1.7 + j * 2.6
                    val r = 5f + 2.5f * sin(now / 700.0 + j).toFloat()
                    val x = g[i] + (cos(a) * r).toFloat()
                    val y = g[i + 1] - 4f + (sin(a * 1.3) * r * 0.6f).toFloat()
                    dots.add(MapAmbience.Dot(x, y, 1, if ((now / 60 + j) % 2 == 0L) 0xFFF4ECD8.toInt() else 0xC0E8DCC0.toInt()))
                }
                k++
            }
            i += 4
        }
    }

    // ---- a shooting star ----
    private fun shootingStar(now: Long, dt: Float, on: Boolean, dots: MutableList<MapAmbience.Dot>) {
        if (nextStarAt == 0L) nextStarAt = now + 30_000 + random.nextInt(60_000)
        if (on && star == null && now >= nextStarAt) {
            nextStarAt = now + 50_000 + random.nextInt(70_000)
            val dir = if (random.nextBoolean()) 1f else -1f
            star = floatArrayOf(view[0] + (view[2] - view[0]) * (0.2f + random.nextFloat() * 0.6f), view[1] + (view[3] - view[1]) * random.nextFloat() * 0.3f, dir)
            starAt = now
            shootingStars++
        }
        val s = star ?: return
        val age = now - starAt
        if (age > 700) { star = null; return }
        val t = age / 1000f
        val x = s[0] + s[2] * 240f * t
        val y = s[1] + 110f * t
        val fade = if (age > 450) (700 - age) / 250f else 1f
        for (k in 0 until 10) {
            val a = (1f - k / 10f) * fade
            dots.add(MapAmbience.Dot(x - s[2] * k * 2.2f, y - k * 1f, 1, fade(0xFFFFFFF0.toInt(), a)))
        }
        dots.add(MapAmbience.Dot(x - 1, y, 3, fade(0x80FFFFFF.toInt(), fade), 1))
    }

    // ---- by the date ----
    private fun seasons(now: Long, dt: Float, m: Int, night: Boolean, pokemon: FloatArray?, dots: MutableList<MapAmbience.Dot>, pics: MutableList<Pic>) {
        fun chance(perSecond: Float) = random.nextFloat() < perSecond * dt
        val w = view[2] - view[0]
        if (drifts.size > 160) return
        if (SpecialDates.Occasion.AUTUMN in occasions && leafy && chance(2.2f)) {
            drifts.add(Flutter(view[0] + random.nextFloat() * (w + 40) - 20, view[1] - 4, 0f, 0f, DRIFT_LEAF,
                AUTUMN_COLORS[random.nextInt(AUTUMN_COLORS.size)], now, 30_000, vx = 5f + random.nextFloat() * 6f, vy = 10f + random.nextFloat() * 7f, phase = random.nextFloat() * 6f))
        }
        if (SpecialDates.Occasion.SPRING in occasions && outdoor && chance(3f)) {
            drifts.add(Flutter(view[0] - 4, view[1] + random.nextFloat() * (view[3] - view[1]), 0f, 0f, DRIFT_PETAL,
                PETAL_COLORS[random.nextInt(PETAL_COLORS.size)], now, 30_000, vx = 14f + random.nextFloat() * 10f, vy = 3f + random.nextFloat() * 5f, phase = random.nextFloat() * 6f))
        }
        if (SpecialDates.Occasion.CHRISTMAS in occasions && outdoor && kind != FieldMap.Kind.ICE && chance(9f)) {
            drifts.add(Flutter(view[0] + random.nextFloat() * (w + 40) - 20, view[1] - 4, 0f, 0f, DRIFT_SNOW, if (random.nextInt(4) == 0) 2 else 1,
                now, 40_000, vx = random.nextFloat() * 6 - 3, vy = 11f + random.nextFloat() * 9f, phase = random.nextFloat() * 6f))
        }
        if (SpecialDates.Occasion.HALLOWEEN in occasions && (m >= 17 * 60 + 30 || m < 6 * 60) && drifts.count { it.kind == DRIFT_WISP } < 5 && chance(0.4f)) {
            groundInView().randomOrNull(random)?.let { (_, x, y) ->
                drifts.add(Flutter(x, y - 12f - random.nextFloat() * 14f, 0f, 0f, DRIFT_WISP, if (random.nextBoolean()) 0xFF8CA8FF.toInt() else 0xFFC890FF.toInt(),
                    now, 9_000L + random.nextInt(6_000), vx = random.nextFloat() * 6 - 3, vy = -random.nextFloat() * 3, phase = random.nextFloat() * 6f))
            }
        }
        if (SpecialDates.Occasion.SUMMER_NIGHTS in occasions && outdoor && night && drifts.count { it.kind == DRIFT_FIREFLY } < 10 && chance(1.5f)) {
            groundInView().randomOrNull(random)?.let { (_, x, y) ->
                drifts.add(Flutter(x, y - 6f - random.nextFloat() * 16f, 0f, 0f, DRIFT_FIREFLY, 0xFFD8FF70.toInt(), now, 7_000L + random.nextInt(6_000),
                    vx = random.nextFloat() * 10 - 5, vy = random.nextFloat() * 8 - 4, phase = random.nextFloat() * 6f))
            }
        }
        if (SpecialDates.Occasion.VALENTINE in occasions && chance(1.2f)) {
            groundInView().randomOrNull(random)?.let { (_, x, y) ->
                drifts.add(Flutter(x, y - 4f, 0f, 0f, DRIFT_HEART, 0, now, 4_000L + random.nextInt(2_000), vx = random.nextFloat() * 4 - 2, vy = -9f - random.nextFloat() * 5f, phase = random.nextFloat() * 6f))
            }
        }
        val confetti = SpecialDates.Occasion.POKEMON_DAY in occasions || (SpecialDates.Occasion.NEW_YEAR in occasions && !night)
        if (confetti && chance(4f)) {
            drifts.add(Flutter(view[0] + random.nextFloat() * (w + 20) - 10, view[1] - 4, 0f, 0f, DRIFT_CONFETTI,
                CONFETTI_COLORS[random.nextInt(CONFETTI_COLORS.size)], now, 30_000, vx = random.nextFloat() * 10 - 5, vy = 16f + random.nextFloat() * 10f, phase = random.nextFloat() * 6f))
        }
        // New Year fireworks at night: a rocket goes up, and bursts
        if (SpecialDates.Occasion.NEW_YEAR in occasions && night && outdoor) {
            if (now >= nextBurstAt) {
                nextBurstAt = now + 1_200 + random.nextInt(1_800)
                val x = view[0] + w * (0.15f + random.nextFloat() * 0.7f)
                val y = view[1] + (view[3] - view[1]) * (0.1f + random.nextFloat() * 0.3f)
                bursts.add(Burst(x, y, now, CONFETTI_COLORS[random.nextInt(CONFETTI_COLORS.size)], view[3]))
            }
            val it = bursts.iterator()
            while (it.hasNext()) {
                val b = it.next()
                val age = now - b.start
                if (age < ROCKET_MS) {
                    // the trail going up
                    val u = age.toFloat() / ROCKET_MS
                    val y = b.riseFrom + (b.y - b.riseFrom) * (1f - (1f - u) * (1f - u))
                    for (k in 0 until 4) dots.add(MapAmbience.Dot(b.x, y + k * 2f, 1, fade(0xFFFFE8B0.toInt(), 1f - k / 4f)))
                } else {
                    it.remove()
                    for (k in 0 until 28) {
                        val a = k * 2 * PI / 28 + random.nextDouble() * 0.1
                        val v = 34f + random.nextFloat() * 12f
                        sparks.add(Spark(b.x, b.y, now, 1_300, if (k % 5 == 0) 0xFFFFFFFF.toInt() else b.color, SPARK_FIREWORK,
                            (cos(a) * v).toFloat(), (sin(a) * v).toFloat()))
                    }
                }
            }
        }
    }

    /** Autumn leaves lying about on the ground in sight, the same places each time. */
    private fun placeGroundLeaves() {
        leavesFor = true
        val spots = ground.filter { (t, x, y) -> !t.tallGrass && scene.inView(x, y, -40f) }
            .sortedBy { Math.floorMod((scene.course.name.hashCode() * 31 + it.first.gx) * 7919 + it.first.gy * 104729, 1_000_003) }
        val r = Random(scene.course.name.hashCode())
        for ((t, x, y) in spots.take(26)) {
            groundLeaves.add(Leaf(x + r.nextFloat() * 10 - 5, y + r.nextFloat() * 6 - 3, r.nextInt(LEAF_TINTS.size), r.nextInt(2)))
            if (t.behavior == FieldEffects.SAND) groundLeaves.removeAt(groundLeaves.size - 1)
        }
    }

    private fun fade(color: Int, k: Float): Int {
        val a = (((color ushr 24) and 0xFF) * k.coerceIn(0f, 1f)).toInt()
        return (a shl 24) or (color and 0xFFFFFF)
    }

    companion object {
        private val DX = intArrayOf(0, 0, -1, 1)
        private val DY = intArrayOf(-1, 1, 0, 0)
        const val RAINBOW_MS = 120_000L
        private const val ROCKET_MS = 650L

        private const val FLUTTER_BUTTERFLY = 0
        private const val FLUTTER_DRAGONFLY = 1
        private const val DRIFT_LEAF = 0
        private const val DRIFT_PETAL = 1
        private const val DRIFT_SNOW = 2
        private const val DRIFT_HEART = 3
        private const val DRIFT_WISP = 4
        private const val DRIFT_CONFETTI = 5
        private const val DRIFT_FIREFLY = 6
        private const val SPARK_GLINT = 0
        private const val SPARK_TWINKLE = 1
        private const val SPARK_FIREWORK = 2

        private val GREEN_COLORS = intArrayOf(0xFF4E9A3C.toInt(), 0xFF6CC050.toInt(), 0xFF8AC848.toInt())
        private val AUTUMN_COLORS = intArrayOf(0xFFE07828.toInt(), 0xFFD04830.toInt(), 0xFFE8B030.toInt(), 0xFFB86030.toInt())
        private val PETAL_COLORS = intArrayOf(0xFFFFC8DC.toInt(), 0xFFFFE0EC.toInt(), 0xFFF8B0C8.toInt())
        private val CONFETTI_COLORS = intArrayOf(0xFFFF5050.toInt(), 0xFFFFD040.toInt(), 0xFF50C8FF.toInt(), 0xFF70E070.toInt(), 0xFFFF80D0.toInt(), 0xFFFFFFFF.toInt())
        private val BUTTERFLY_COLORS = intArrayOf(0xFFF8F8F0.toInt(), 0xFFF8E060.toInt(), 0xFFF0A040.toInt(), 0xFFB8D8F8.toInt())
        private val LEAF_TINTS = AUTUMN_COLORS

        private const val OUT = 0xFF3A2A1E.toInt()

        /** Butterflies: wings open, wings closed (by colour). */
        val BUTTERFLY: Array<Array<CritterArt>> = Array(BUTTERFLY_COLORS.size) { i ->
            val p = mapOf('W' to BUTTERFLY_COLORS[i], 'o' to OUT, 'b' to 0xFF302820.toInt())
            arrayOf(
                CritterArt.of(p, "WoWoW", "WWbWW", ".WbW.", "..b.."),
                CritterArt.of(p, ".ob..", ".WbW.", "..b..", "....."),
            )
        }

        private val BIRD_P = mapOf('B' to 0xFF8A5A34.toInt(), 'b' to 0xFF5E3C22.toInt(), 'w' to 0xFFE8DCC4.toInt(), 'y' to 0xFFF0C040.toInt(), 'k' to 0xFF101010.toInt())
        private val BIRD_R = CritterArt.of(BIRD_P, ".BBk.", "bBBBy", ".ww..", ".b.b.")
        private val BIRD_PECK_R = CritterArt.of(BIRD_P, ".....", ".BBB.", "bBBky", ".wwy.", ".b.b.")
        private val BIRD_FLY_R = arrayOf(
            CritterArt.of(BIRD_P, "b...b", ".BBBk", "..Bwy"),
            CritterArt.of(BIRD_P, ".BBBk", "bbBwy", "....."),
        )
        private val BIRD_L = mirror(BIRD_R)
        private val BIRD_PECK_L = mirror(BIRD_PECK_R)
        private val BIRD_FLY_L = arrayOf(mirror(BIRD_FLY_R[0]), mirror(BIRD_FLY_R[1]))

        /** Dragonflies (blue, red): wings up, wings level. */
        private val DRAGONFLY: Array<Array<CritterArt>> = arrayOf(0xFF3A80E0.toInt(), 0xFFE04838.toInt()).map { c ->
            val p = mapOf('D' to c, 'w' to 0xB0F0F8FF.toInt(), 'k' to 0xFF202020.toInt())
            arrayOf(CritterArt.of(p, ".w.w..", "kDDDDD", "......"), CritterArt.of(p, "......", "kDDDDD", ".w.w.."))
        }.toTypedArray()

        private val FISH_R = CritterArt.of(mapOf('f' to 0x55102830, 'F' to 0x70102830), ".fFFf.", "fFFFFf", ".fFFf.")
        private val FISH_L = mirror(FISH_R)

        private val HEART = CritterArt.of(mapOf('r' to 0xFFF05088.toInt(), 'w' to 0xFFFFD0E0.toInt()), ".r.r.", "rwrrr", "rrrrr", ".rrr.", "..r..")

        /** Leaves lying about: two shapes, each autumn colour. */
        private val LEAVES: Array<Array<CritterArt>> = arrayOf(
            Array(LEAF_TINTS.size) { CritterArt.of(mapOf('l' to LEAF_TINTS[it], 'v' to 0xFF6A3A18.toInt()), ".ll", "lvl", "ll.") },
            Array(LEAF_TINTS.size) { CritterArt.of(mapOf('l' to LEAF_TINTS[it], 'v' to 0xFF6A3A18.toInt()), "ll.", "lvl", ".l.") },
        )

        private val PUMPKIN_P = mapOf('o' to 0xFFE87820.toInt(), 'O' to 0xFFC85A10.toInt(), 'g' to 0xFF3C8A34.toInt(), 'k' to 0xFF2A1A0E.toInt(), 'y' to 0xFFFFE070.toInt())
        private val PUMPKIN = CritterArt.of(PUMPKIN_P, "....g....", "..OOgOO..", ".OooOooO.", "OookOkooO", "OoooOoooO", "OokkkkkoO", ".OooOooO.", "..OOOOO..")
        private val PUMPKIN_LIT = CritterArt.of(PUMPKIN_P, "....g....", "..OOgOO..", ".OooOooO.", "OooyOyooO", "OoooOoooO", "OoyyyyyoO", ".OooOooO.", "..OOOOO..")

        private fun mirror(a: CritterArt): CritterArt {
            val px = IntArray(a.pixels.size)
            for (y in 0 until a.height) for (x in 0 until a.width) px[y * a.width + x] = a.pixels[y * a.width + (a.width - 1 - x)]
            return CritterArt(a.width, a.height, px)
        }
    }
}
