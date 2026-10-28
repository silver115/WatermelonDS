package me.magnum.melonds.pokewalker

import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * Life on the route map besides the games, all just for fun (nothing goes to the walker or the
 * game):
 *  - now and then an item ball turns up (where the maps keep theirs, when one is in sight) and
 *    glints; the Pokémon spots it and goes to pick it up: one of the walk's own items;
 *  - a berry tossed onto the map ([toss]): the Pokémon runs over and eats it;
 *  - bird Pokémon fly over with their shadows now and then (Hoothoot and Noctowl at night), and
 *    a Magikarp leaps out of the water;
 *  - at night, left alone, the Pokémon falls [asleep], and wakes when you tap, press a button or
 *    start walking;
 *  - its [mood] follows today's steps: quicker and hoppier the more there are, sitting down to
 *    rest when you've been still a while;
 *  - a shiny Pokémon (or a shiny one following it) sparkles now and then;
 *  - emote bubbles ([emotes]) for what it and the people about do and see.
 * Pure Kotlin.
 */
class MapLife(
    val scene: FieldMap.Scene,
    private val pokemon: MapWanderer,
    private val crowd: MapCrowd,
    val effects: FieldEffects,
    val emotes: Emotes = Emotes(),
    private val random: Random = Random.Default,
) {
    /** An item ball on [tile] with [item] (the game's item number) in it, there since [shownAt]. */
    class Ball(val tile: FieldMap.Tile, val item: Int, val shownAt: Long) {
        /** When the Pokémon spotted it (Long.MIN_VALUE: not yet). */
        var spottedAt = Long.MIN_VALUE
            internal set
    }

    /** A berry ([kind] as the berry patch's) thrown from picture point ([fromX], [fromY]) at [thrownAt] to [tile]. */
    class Treat(val tile: FieldMap.Tile, val kind: Int, val fromX: Float, val fromY: Float, val thrownAt: Long) {
        val landsAt get() = thrownAt + TOSS_MS
        /** When the Pokémon started eating it (Long.MIN_VALUE: not yet). */
        var eatenAt = Long.MIN_VALUE
            internal set
        internal var landed = false
    }

    /** A bird Pokémon flying over, from world x [x0] to [x1] high above the ground at [y], [z]. */
    class Flyer(val kind: MapCrowd.WildKind, val x0: Double, val x1: Double, val y: Double, val z: Double, val start: Long, val ms: Long) {
        /** Where and how to draw it at [now]; null when it's not in the air then. */
        fun pose(now: Long): MapWanderer.Pose? {
            val u = (now - start).toDouble() / ms
            if (u < 0.0 || u > 1.0) return null
            val lift = 38.0 + 4.0 * sin(now / 260.0)
            return MapWanderer.Pose(x0 + (x1 - x0) * u, y, z, if (x1 > x0) FollowerSprites.RIGHT else FollowerSprites.LEFT,
                ((now / 110) % 2).toInt(), lift, true, aloft = true)
        }
    }

    companion object {
        /** A thrown berry's flight, and eating it. */
        const val TOSS_MS = 650L
        const val EAT_MS = 1_300L
        /** At night, how long without a tap or a button, and without new steps, before it falls asleep. */
        const val SLEEP_AFTER_MS = 30_000L
        const val SLEEP_STEPS_MS = 120_000L
        /** Without new steps for this long (and nobody playing with it for a minute), it sits down to rest. */
        const val REST_AFTER_MS = 10 * 60_000L
        /** How long an item ball waits to be spotted. */
        const val BALL_MS = 120_000L
        /** In [cries]: the walking Pokémon's own cry. */
        const val OWN_CRY = -1

        /**
         * Items when the walk has none of its own: Potion, Poké Ball, Antidote, Super Potion,
         * Repel, Escape Rope, Oran Berry, Great Ball, Ether, Full Heal.
         */
        val COMMON_ITEMS = intArrayOf(17, 4, 18, 26, 79, 78, 155, 3, 38, 27)

        /** Birds flying over by day (Pidgey, Spearow, Pidgeotto) and at night (Hoothoot, Noctowl). */
        val DAY_BIRDS = intArrayOf(16, 21, 17)
        val NIGHT_BIRDS = intArrayOf(163, 164)
        const val MAGIKARP = 129

        /** Picture pixels a second a bird flies across. */
        const val BIRD_SPEED = 46.0

        /** Bedtime for the Pokémon: 21:00 to 6:00. */
        fun night(minute: Int) = Math.floorMod(minute, 24 * 60).let { it >= 21 * 60 || it < 6 * 60 }

        private val PATS = arrayOf(Emote.HEART, Emote.SMILE, Emote.HAPPY, Emote.NOTE, Emote.GLAD)
        private val GREETINGS = arrayOf(Emote.SMILE, Emote.NOTE, Emote.GLAD, Emote.QUESTION, Emote.SILENT)
        private val DOOR_EMOTES = arrayOf(Emote.QUESTION, Emote.NOTE, Emote.SILENT, Emote.EXCLAIM)
        private val DX = intArrayOf(0, 0, -1, 1) // UP, DOWN, LEFT, RIGHT
        private val DY = intArrayOf(-1, 1, 0, 0)
    }

    // ---- what it has to work with, set by whoever draws the map ----
    /** The walk's own items (item numbers), for the item balls. */
    var items: IntArray = IntArray(0)
    /** Bird Pokémon that fly over (none: no birds), and the Magikarp that leaps out of the water. */
    var birds: List<MapCrowd.WildKind> = emptyList()
    var magikarp: MapCrowd.WildKind? = null
    /** Whether the walking Pokémon and the one following it are shiny. */
    var shiny = false
    var buddyShiny = false
    var buddy: MapBuddy? = null
    var games: MapGames? = null
    /** How big the walking Pokémon's and the buddy's sprites are (for sparkles round them). */
    var pokemonSize = 32
    var buddySize = 32
    /** Whether something can be put on a tile (nobody's there, it's not the berry patch). */
    var free: (FieldMap.Tile) -> Boolean = { true }
    /** Tiles behind the cards on the screen (nothing turns up there). */
    var hidden: ((FieldMap.Tile) -> Boolean)? = null

    // ---- what's going on ----
    var ball: Ball? = null
        private set
    var treat: Treat? = null
        private set
    val flyers = ArrayList<Flyer>()
    /** A Magikarp leaping out of the water. */
    var leap: MapGames.Leap? = null
        private set
    var asleep = false
        private set
    /** -1 resting, 0, 1 lively (lots of steps today), 2 very lively. */
    var mood = 0
        private set
    /** What to tell the player (taken by whoever shows it). */
    val lines = ArrayList<MapGames.Line>()
    /** Species whose cry should play ([OWN_CRY]: the walking Pokémon's), taken by whoever plays them. */
    val cries = ArrayList<Int>()

    private var lastNow = Long.MIN_VALUE
    private var carried = false
    private var startAsleep = false
    private var lastInteraction = 0L
    private var lastStepAt = 0L
    private var stepsKnown = false
    private var total = 0L
    private var today = 0L
    private var goal = 0
    private var nextBallAt = 0L
    private var nextGlintAt = 0L
    private var fetchAt = 0L
    private var nextBirdAt = 0L
    private var nextCarpAt = 0L
    private var leapSplashed = false
    private var nextShinyAt = 0L
    private var nextBuddyShinyAt = 0L
    private var nextSweatAt = 0L
    private var nextSpotAt = 0L
    private val hopsAt = ArrayList<Long>()
    private var lastLine: MapGames.Line? = null
    private val talking: MutableSet<MapCrowd.Walker> = Collections.newSetFromMap(IdentityHashMap())
    private val sightAgain = IdentityHashMap<MapCrowd.Walker, Long>()

    // ---- from outside ----

    /**
     * Carries on from [from], the same walk drawn again (in a new light, say): when it last
     * walked and was played with, and whether it was asleep (it stays asleep).
     */
    fun carryOn(from: MapLife) {
        carried = true
        lastInteraction = from.lastInteraction
        lastStepAt = from.lastStepAt
        stepsKnown = from.stepsKnown
        total = from.total
        today = from.today
        goal = from.goal
        startAsleep = from.asleep
    }

    /** A tap or a button: it wakes up if asleep, and won't fall asleep for a while. */
    fun interacted(now: Long) {
        lastInteraction = now
        wake(now)
    }

    /** A tap on it, the A button: it's happy (or, asleep, it wakes up). */
    fun patted(now: Long) {
        val wasAsleep = asleep
        interacted(now)
        if (!wasAsleep) emotes.show(pokemon, PATS[random.nextInt(PATS.size)], now, 1_400)
    }

    /** The walker's steps: all of them, today's and the day's goal. New steps wake it up. */
    fun setSteps(total: Long, today: Long, goal: Int, now: Long) {
        if (!stepsKnown) {
            stepsKnown = true
            // when the steps so far were walked isn't known: not just now
            lastStepAt = minOf(lastStepAt, now - SLEEP_STEPS_MS)
        } else if (total > this.total) {
            lastStepAt = now
            if (asleep) wake(now)
        }
        this.total = total
        this.today = today
        this.goal = goal
    }

    /** Today's steps reached the goal: hearts and hops. */
    fun celebrate(now: Long) {
        interacted(now)
        emotes.show(pokemon, Emote.HEART, now, 2_200)
        pokemon.hop(now)
        hopsAt.add(now + 450)
        hopsAt.add(now + 900)
    }

    /** The Pokémon stands at a door that's opening: it stays to peek in. */
    fun atDoor(now: Long) {
        pokemon.pauseUntil(now + 2_400)
        emotes.show(pokemon, DOOR_EMOTES[random.nextInt(DOOR_EMOTES.size)], now + 500, 1_400)
    }

    /** Wakes it up: "!" and a hop, unless [quietly]. */
    fun wake(now: Long, quietly: Boolean = false) {
        if (!asleep) return
        asleep = false
        pokemon.asleep = false
        buddy?.let { it.asleep = false; emotes.clear(it) }
        if (quietly) {
            emotes.clear(pokemon)
        } else {
            emotes.show(pokemon, Emote.EXCLAIM, now, 1_000)
            pokemon.hop(now)
        }
        pokemon.pauseUntil(now + 1_200)
    }

    /** Goes to pick up the item ball now (it was tapped). */
    fun fetchBall(now: Long) {
        val b = ball ?: return
        interacted(now)
        if (b.spottedAt == Long.MIN_VALUE) b.spottedAt = now
        fetchAt = now
    }

    /**
     * Throws a berry from picture point ([fromX], [fromY]) onto [tile], or the nearest ground
     * the Pokémon can get beside; false when there's none (or one is already out, or a game is on).
     */
    fun toss(tile: FieldMap.Tile, fromX: Float, fromY: Float, now: Long): Boolean {
        interacted(now)
        if (treat != null || games?.game != null) return false
        val target = if (landingOk(tile)) tile else {
            scene.tiles.values.filter { abs(it.gx - tile.gx) + abs(it.gy - tile.gy) in 1..4 }
                .sortedBy { abs(it.gx - tile.gx) + abs(it.gy - tile.gy) }
                .firstOrNull { landingOk(it) }
        } ?: return false
        treat = Treat(target, random.nextInt(MapGames.BERRY_KINDS), fromX, fromY, now)
        return true
    }

    /** Throws a berry a few steps from the Pokémon (from the games menu). */
    fun tossNear(fromX: Float, fromY: Float, now: Long): Boolean {
        val at = pokemon.next ?: pokemon.at
        val spot = scene.tiles.values.filter { abs(it.gx - at.gx) + abs(it.gy - at.gy) in 2..4 && hidden?.invoke(it) != true }
            .shuffled(random).firstOrNull { landingOk(it) }
        if (spot == null) {
            interacted(now)
            return false
        }
        return toss(spot, fromX, fromY, now)
    }

    /** Whether something of ours lies on tile ([gx], [gy]) (an item ball, a berry thrown there). */
    fun blocks(gx: Int, gy: Int): Boolean =
        ball?.tile?.let { it.gx == gx && it.gy == gy } == true || treat?.tile?.let { it.gx == gx && it.gy == gy } == true

    /** The tiles of ours (nobody may step onto them). */
    fun tiles(): List<FieldMap.Tile> = listOfNotNull(ball?.tile, treat?.tile)

    // ---- moving things along ----

    /** Moves everything along to [now] (after the Pokémon, the crowd and the games), at [minute] of the day in [weather]. */
    fun update(now: Long, minute: Int, weather: MapAmbience.Weather = MapAmbience.Weather.CLEAR) {
        if (lastNow == Long.MIN_VALUE) start(now)
        lastNow = now
        hopsAt.removeAll { at -> (now >= at).also { if (it) pokemon.hop(now) } }
        happenings(now)
        sleep(now, minute)
        mood(now)
        ball(now)
        treat(now)
        passers(now)
        sparkles(now)
        people(now)
        games(now)
        weather(now, weather)
        emotes.prune(now)
    }

    private fun start(now: Long) {
        if (!carried) {
            lastInteraction = now
            if (!stepsKnown) lastStepAt = now - SLEEP_STEPS_MS
        }
        if (startAsleep && scene.tile(pokemon.at.gx, pokemon.at.gy) != null) {
            asleep = true
            pokemon.asleep = true
            buddy?.asleep = true
        }
        nextBallAt = now + 20_000 + random.nextInt(20_000)
        nextBirdAt = now + 12_000 + random.nextInt(15_000)
        nextCarpAt = now + 8_000 + random.nextInt(15_000)
        nextShinyAt = now + 2_000 + random.nextInt(3_000)
        nextBuddyShinyAt = now + 5_000 + random.nextInt(4_000)
        nextSweatAt = now + 8_000 + random.nextInt(8_000)
    }

    /** What the Pokémon did: a "?" sniffing, "..." reading a sign, dust landing from a jump. */
    private fun happenings(now: Long) {
        for (h in pokemon.happenings) {
            when (h) {
                MapWanderer.Happening.SNIFF -> if (random.nextInt(3) != 0) emotes.show(pokemon, Emote.QUESTION, now + 150, 1_300)
                MapWanderer.Happening.READ -> emotes.show(pokemon, Emote.SILENT, now + 200, 1_800)
                MapWanderer.Happening.LANDED -> effects.dust(pokemon.at.x, pokemon.at.y, pokemon.at.z, now)
            }
        }
        pokemon.happenings.clear()
    }

    private fun sleep(now: Long, minute: Int) {
        val bd = buddy
        if (asleep) {
            if (!night(minute) || games?.game != null) {
                wake(now, quietly = true)
                return
            }
            // "Zz", on and on
            if (!emotes.pending(pokemon, now)) emotes.show(pokemon, Emote.SLEEP, now, 2_600)
            if (bd != null && !emotes.pending(bd, now)) emotes.show(bd, Emote.SLEEP, now + 700, 2_600)
            return
        }
        if (!night(minute) || games?.game != null || !pokemon.idle || pokemon.held) return
        if (treat != null || ball?.spottedAt?.let { it != Long.MIN_VALUE } == true) return
        if (now - lastInteraction < SLEEP_AFTER_MS || now - lastStepAt < SLEEP_STEPS_MS) return
        // not while swimming
        if (scene.tile(pokemon.at.gx, pokemon.at.gy) == null) return
        asleep = true
        pokemon.asleep = true
        bd?.asleep = true
        emotes.show(pokemon, Emote.SLEEP, now, 2_600)
    }

    private fun mood(now: Long) {
        // still for a while, and nothing to go and get
        val still = now - lastStepAt >= REST_AFTER_MS && now - lastInteraction >= 60_000 && treat == null &&
            ball.let { it == null || it.spottedAt == Long.MIN_VALUE }
        mood = when {
            still -> -1
            today >= 10_000 || (goal > 0 && today >= goal) -> 2
            today >= 5_000 -> 1
            else -> 0
        }
        pokemon.mood = if (asleep) 0 else mood
        pokemon.pace = when (mood) {
            2 -> 240L
            1 -> 265L
            -1 -> 380L
            else -> MapWanderer.STEP_MS
        }
    }

    // item balls
    private fun ball(now: Long) {
        val b = ball
        if (b == null) {
            if (now >= nextBallAt) {
                nextBallAt = now + 60_000 + random.nextInt(60_000)
                spawnBall(now)
            }
            return
        }
        if (b.spottedAt == Long.MIN_VALUE && now - b.shownAt > BALL_MS) {
            ball = null
            return
        }
        // a glint now and then
        if (now >= nextGlintAt) {
            effects.sparkle(b.tile.x, b.tile.y, b.tile.z, now, dx = 3f, dy = -10f)
            nextGlintAt = now + 1_600 + random.nextInt(1_600)
        }
        if (asleep || games?.game != null || treat != null || pokemon.held) return
        if (b.spottedAt == Long.MIN_VALUE) {
            // it spots it once it's been there a moment and it's free
            if (now - b.shownAt < 1_200 || !pokemon.idle) return
            b.spottedAt = now
            pokemon.face(MapWanderer.towards(pokemon.at, b.tile))
            emotes.show(pokemon, Emote.EXCLAIM, now, 1_000)
            pokemon.pauseUntil(now + 900)
            fetchAt = now + 900
            return
        }
        if (pokemon.idle && beside(pokemon.at, b.tile)) {
            val way = MapWanderer.towards(pokemon.at, b.tile)
            if (pokemon.facing != way) {
                pokemon.face(way)
                return
            }
            // picked up
            ball = null
            nextBallAt = now + 60_000 + random.nextInt(60_000)
            effects.sparkle(b.tile.x, b.tile.y, b.tile.z, now, dy = -6f)
            emotes.show(pokemon, if (random.nextBoolean()) Emote.HAPPY else Emote.NOTE, now, 1_500)
            pokemon.hop(now)
            pokemon.pauseUntil(now + 1_200)
            lines.add(MapGames.Line(MapGames.What.ITEM_FOUND, 0, b.item))
            return
        }
        if (pokemon.idle && now >= fetchAt) {
            fetchAt = now + 1_500
            // it can't get to it after all: it's left there
            if (!goBeside(b.tile, now) && now - b.spottedAt > 15_000) ball = null
        }
    }

    private fun spawnBall(now: Long) {
        val at = pokemon.next ?: pokemon.at
        fun ok(t: FieldMap.Tile) = !t.tallGrass && free(t) && hidden?.invoke(t) != true && treat?.tile !== t &&
            abs(t.gx - at.gx) + abs(t.gy - at.gy) in 3..12 && neighbours(t).any { pokemon.canReach(it, 30) }
        // where the maps keep their item balls, if one of those will do
        val spot = scene.itemSpots.filter { ok(it) }.randomOrNull(random)
            ?: scene.tiles.values.filter { abs(it.gx - at.gx) + abs(it.gy - at.gy) in 3..12 }.shuffled(random).take(40).firstOrNull { ok(it) }
            ?: return
        val own = items.filter { it in 1..536 }
        val item = if (own.isNotEmpty()) own[random.nextInt(own.size)] else COMMON_ITEMS[random.nextInt(COMMON_ITEMS.size)]
        ball = Ball(spot, item, now)
        nextGlintAt = now + 500
    }

    // a berry thrown
    private fun treat(now: Long) {
        val t = treat ?: return
        if (t.eatenAt != Long.MIN_VALUE) {
            if (now - t.eatenAt >= EAT_MS) {
                treat = null
                emotes.show(pokemon, Emote.HEART, now, 1_600)
                pokemon.hop(now)
                pokemon.pauseUntil(now + 1_000)
                cries.add(OWN_CRY)
                lines.add(MapGames.Line(MapGames.What.TREAT_EATEN, 0, t.kind))
            }
            return
        }
        if (now < t.landsAt) return
        if (!t.landed) {
            // it lands: the Pokémon sees it and runs over
            t.landed = true
            if (asleep) wake(now)
            emotes.show(pokemon, Emote.EXCLAIM, now, 900)
            pokemon.rush(now + 8_000)
            pokemon.pauseUntil(now + 400)
            fetchAt = now + 400
            return
        }
        if (games?.game != null || now - t.landsAt > 25_000) {
            treat = null
            pokemon.rush(Long.MIN_VALUE)
            return
        }
        if (pokemon.idle && beside(pokemon.at, t.tile)) {
            val way = MapWanderer.towards(pokemon.at, t.tile)
            if (pokemon.facing != way) {
                pokemon.face(way)
                return
            }
            t.eatenAt = now
            pokemon.rush(Long.MIN_VALUE)
            pokemon.nibble(now, EAT_MS)
            pokemon.pauseUntil(now + EAT_MS + 1_200)
            return
        }
        if (pokemon.idle && now >= fetchAt) {
            fetchAt = now + 1_000
            if (!goBeside(t.tile, now) && now - t.landsAt > 3_000) {
                treat = null
                pokemon.rush(Long.MIN_VALUE)
                lines.add(MapGames.Line(MapGames.What.TREAT_NOWHERE))
            }
        }
    }

    /** Somewhere a berry can land: ground nobody's on that the Pokémon can stand beside. */
    private fun landingOk(t: FieldMap.Tile): Boolean {
        if (scene.tile(t.gx, t.gy) == null) return false
        val at = pokemon.at
        val next = pokemon.next
        if ((t.gx == at.gx && t.gy == at.gy) || (next != null && t.gx == next.gx && t.gy == next.gy)) return false
        if (!free(t) || ball?.tile === t) return false
        return beside(at, t) || neighbours(t).any { pokemon.canReach(it, 30) }
    }

    // birds and Magikarp
    private fun passers(now: Long) {
        flyers.removeAll { now - it.start > it.ms }
        if (birds.isNotEmpty() && now >= nextBirdAt) {
            nextBirdAt = now + 35_000 + random.nextInt(40_000)
            launchBird(now)
        }
        val l = leap
        if (l != null) {
            // back into the water with a splash
            if (!leapSplashed && now - l.start >= MapGames.LEAP_MS * 85 / 100) {
                leapSplashed = true
                effects.splash(l.tile.x, l.tile.y, l.tile.z, now)
            }
            if (now - l.start > MapGames.LEAP_MS) leap = null
        }
        val carp = magikarp ?: return
        if (leap != null || now < nextCarpAt) return
        nextCarpAt = now + 25_000 + random.nextInt(35_000)
        val bobber = games?.bobber
        val water = scene.water.values.filter { w ->
            scene.tile(w.gx, w.gy) == null && hidden?.invoke(w) != true && (bobber == null || abs(bobber.gx - w.gx) + abs(bobber.gy - w.gy) > 2)
        }
        if (water.size < 6) return
        val w = water[random.nextInt(water.size)]
        leap = MapGames.Leap(carp.model, carp.species, w, now)
        leapSplashed = false
        effects.splash(w.x, w.y, w.z, now)
    }

    /** A bird across the picture over a row of ground, high up, its shadow on the ground. */
    private fun launchBird(now: Long) {
        // over ground in the lower part of the picture, so the bird high above it is in the picture too
        val rows = scene.tiles.values.filter { t ->
            hidden?.invoke(t) != true && scene.project(t.x, t.y, t.z)?.let { it[1] in scene.height * 0.42f..scene.height * 0.92f } == true
        }
        val row = rows.randomOrNull(random) ?: scene.tiles.values.randomOrNull(random) ?: return
        val p0 = scene.project(row.x, row.y, row.z) ?: return
        val p1 = scene.project(row.x + 16.0, row.y, row.z) ?: return
        val perUnit = (p1[0] - p0[0]) / 16.0
        if (perUnit < 0.05) return
        val left = row.x + (-40.0 - p0[0]) / perUnit
        val right = row.x + (scene.width + 40.0 - p0[0]) / perUnit
        val ltr = random.nextBoolean()
        val ms = ((scene.width + 80) / BIRD_SPEED * 1000).toLong()
        flyers.add(Flyer(birds[random.nextInt(birds.size)], if (ltr) left else right, if (ltr) right else left, row.y, row.z, now, ms))
    }

    private fun sparkles(now: Long) {
        if (shiny && now >= nextShinyAt) {
            nextShinyAt = now + 6_000 + random.nextInt(6_000)
            val p = pokemon.pose(now)
            if (p.alpha > 0.5f) effects.sparkles(p.x, p.y, p.z, pokemonSize, now)
        }
        val bd = buddy
        if (buddyShiny && bd != null && now >= nextBuddyShinyAt) {
            nextBuddyShinyAt = now + 6_000 + random.nextInt(6_000)
            val p = bd.pose(now)
            effects.sparkles(p.x, p.y, p.z, buddySize, now)
        }
    }

    /** The people: they greet the Pokémon when it comes up to them; trainers spot it in front of them. */
    private fun people(now: Long) {
        talking.retainAll(crowd.walkers.toSet())
        for (w in crowd.walkers) {
            if (w.talking) {
                if (talking.add(w)) emotes.show(w, if (w.person.trainer) Emote.EXCLAIM else GREETINGS[random.nextInt(GREETINGS.size)], now, 1_600)
            } else {
                talking.remove(w)
            }
        }
        if (!asleep) {
            val at = pokemon.at
            for (w in crowd.walkers) {
                if (!w.person.trainer || w.wild || w.talking || w.next != null) continue
                if (now < (sightAgain[w] ?: Long.MIN_VALUE)) continue
                val d = w.facing.coerceIn(0, 3)
                if ((1..4).any { k -> w.at.gx + DX[d] * k == at.gx && w.at.gy + DY[d] * k == at.gy }) {
                    emotes.show(w, Emote.EXCLAIM, now, 1_200)
                    sightAgain[w] = now + 30_000
                }
            }
        }
        sightAgain.keys.retainAll(crowd.walkers.toSet())
        // a wild Pokémon popping out close by: it notices
        if (asleep || games?.game != null || !pokemon.idle || now < nextSpotAt) return
        for ((t, appeared) in crowd.popped) {
            if (!appeared || abs(t.gx - pokemon.at.gx) + abs(t.gy - pokemon.at.gy) > 5) continue
            if (t.gx != pokemon.at.gx || t.gy != pokemon.at.gy) pokemon.face(MapWanderer.towards(pokemon.at, t))
            emotes.show(pokemon, Emote.EXCLAIM, now, 1_000)
            nextSpotAt = now + 12_000
            break
        }
    }

    /** How the games go shows on its face. */
    private fun games(now: Long) {
        val g = games ?: return
        if (g.biting) emotes.show(pokemon, Emote.EXCLAIM, now, 250)
        val l = g.line
        if (l === lastLine) return
        lastLine = l
        val e = when (l?.what) {
            MapGames.What.FISH_CAUGHT, MapGames.What.TAG_CAUGHT, MapGames.What.CONTEST_END -> Emote.HAPPY
            MapGames.What.CONTEST_CAUGHT, MapGames.What.BERRY_PLANTED -> Emote.NOTE
            MapGames.What.UNOWN_FOUND -> Emote.EXCLAIM
            MapGames.What.UNOWN_DONE, MapGames.What.BERRY_PICKED -> Emote.HEART
            MapGames.What.FISH_AWAY, MapGames.What.TAG_AWAY, MapGames.What.CONTEST_NONE -> Emote.SAD
            MapGames.What.FISH_NOWHERE, MapGames.What.BERRY_NOWHERE -> Emote.QUESTION
            else -> null
        } ?: return
        emotes.show(pokemon, e, now, 1_500)
    }

    /** Caught in the rain now and then. */
    private fun weather(now: Long, weather: MapAmbience.Weather) {
        if (weather != MapAmbience.Weather.RAIN || asleep || games?.game != null || now < nextSweatAt) return
        nextSweatAt = now + 25_000 + random.nextInt(25_000)
        if (pokemon.idle && !emotes.pending(pokemon, now)) emotes.show(pokemon, Emote.SWEAT, now, 1_400)
    }

    // ---- helpers ----

    private fun beside(a: FieldMap.Tile, b: FieldMap.Tile) = abs(a.gx - b.gx) + abs(a.gy - b.gy) == 1

    /** Ground beside [t] (a step from it) that someone could stand on to reach it. */
    private fun neighbours(t: FieldMap.Tile): List<FieldMap.Tile> =
        (0 until 4).mapNotNull { d -> scene.tile(t.gx + DX[d], t.gy + DY[d]) }.filter { FieldMap.stepOk(t, it) }

    /** Sends the Pokémon beside [target], looking at it; false when it can't get there. */
    private fun goBeside(target: FieldMap.Tile, now: Long): Boolean {
        val from = pokemon.next ?: pokemon.at
        return neighbours(target).sortedBy { abs(it.gx - from.gx) + abs(it.gy - from.gy) }.any { pokemon.goTo(it, now, target) }
    }
}
