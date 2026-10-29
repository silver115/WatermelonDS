package me.magnum.melonds.pokewalker

import kotlin.math.abs
import kotlin.random.Random

/** The days of the year the route map dresses up for, going by the real date. Pure Kotlin. */
object SpecialDates {
    enum class Occasion {
        /** New Year's Eve evening and New Year's Day: fireworks at night, confetti by day. */
        NEW_YEAR,
        /** 14 February: hearts float up. */
        VALENTINE,
        /** 27 February, Pokémon Day: confetti, and Pikachu drops by. */
        POKEMON_DAY,
        /** Late March and April: blossom petals drift by. */
        SPRING,
        /** July and August: more fireflies at night. */
        SUMMER_NIGHTS,
        /** The last week of October: wisps after dark, glowing pumpkins, a ghost drops by. */
        HALLOWEEN,
        /** Late September to November: autumn leaves fall and lie about. */
        AUTUMN,
        /** 20 to 26 December: snow, and Delibird drops by. */
        CHRISTMAS,
    }

    /** What's on at [month] (1-12), [day] (1-31) and [minute] of the day. */
    fun on(month: Int, day: Int, minute: Int): Set<Occasion> {
        val out = HashSet<Occasion>()
        if ((month == 12 && day == 31 && minute >= 18 * 60) || (month == 1 && day == 1)) out.add(Occasion.NEW_YEAR)
        if (month == 2 && day == 14) out.add(Occasion.VALENTINE)
        if (month == 2 && day == 27) out.add(Occasion.POKEMON_DAY)
        if ((month == 3 && day >= 20) || month == 4) out.add(Occasion.SPRING)
        if (month == 7 || month == 8) out.add(Occasion.SUMMER_NIGHTS)
        if (month == 10 && day >= 24) out.add(Occasion.HALLOWEEN)
        if ((month == 9 && day >= 22) || month == 10 || month == 11) out.add(Occasion.AUTUMN)
        if (month == 12 && day in 20..26) out.add(Occasion.CHRISTMAS)
        return out
    }
}

/** Who drops by the route map today: a different wild Pokémon each day, by the date. Pure Kotlin. */
object DailyVisitor {
    /** Friendly faces from all over (the game has a following sprite for every one of them). */
    val GUESTS = intArrayOf(
        25, 133, 175, 39, 35, 132, 183, 194, 79, 54, 52, 238, 172, 174, 173, 216, 161, 187, 179, 231,
        206, 1, 4, 7, 152, 155, 158, 113, 143, 446, 427, 417, 403, 393, 387, 390, 399, 440, 447, 300,
        298, 311, 312, 363, 252, 255, 258, 263, 280, 220, 209, 228, 218, 190, 215, 37, 58, 77, 104, 108,
    )
    /** Who comes at Halloween. Gastly, Misdreavus, Duskull, Drifloon, Shuppet. */
    val SPOOKY = intArrayOf(92, 200, 355, 425, 353)
    const val DELIBIRD = 225
    const val PIKACHU = 25
    /** Once in a long while. */
    const val MEW = 151

    /** Today's visitor for [epochDay] (days since 1 January 1970, local time) and what's on. */
    fun species(epochDay: Long, occasions: Set<SpecialDates.Occasion>): Int {
        val h = mix(epochDay)
        return when {
            SpecialDates.Occasion.CHRISTMAS in occasions -> DELIBIRD
            SpecialDates.Occasion.POKEMON_DAY in occasions -> PIKACHU
            SpecialDates.Occasion.HALLOWEEN in occasions -> SPOOKY[Math.floorMod(h, SPOOKY.size)]
            Math.floorMod(h / 7, 150) == 0 -> MEW
            else -> GUESTS[Math.floorMod(h, GUESTS.size)]
        }
    }

    private fun mix(day: Long): Int {
        var x = day * 0x9E3779B97F4A7C15uL.toLong()
        x = x xor (x ushr 29)
        x *= 0xBF58476D1CE4E5B9uL.toLong()
        return (x xor (x ushr 32)).toInt() and 0x7FFFFFFF
    }
}

/**
 * Today's visitor on the route map: it comes in from the side now and then, goes up to the
 * walking Pokémon to say hello, plays about near it for a few minutes, and goes again. A tap
 * on it and it's pleased to see you. Just for fun. Pure Kotlin.
 */
class MapVisitor(
    val scene: FieldMap.Scene,
    val kind: MapCrowd.WildKind,
    private val pokemon: MapWanderer,
    private val emotes: Emotes,
    private val random: Random = Random.Default,
) {
    enum class State { AWAY, ARRIVING, GREETING, STAYING, LEAVING }

    companion object {
        /** How long after the map opens it first comes, how long it stays, how long until it's back. */
        const val FIRST_MS = 25_000L
        const val STAY_MS = 150_000L
        const val AGAIN_MS = 12 * 60_000L
        private val DX = intArrayOf(0, 0, -1, 1)
        private val DY = intArrayOf(-1, 1, 0, 0)
    }

    var state = State.AWAY
        private set
    /** Its walking about (null while it's away). */
    var walker: MapWanderer? = null
        private set
    /** What to tell the player (taken by whoever shows it). */
    val lines = ArrayList<MapGames.Line>()
    /** Its cry should play (taken). */
    var cry = false

    /** Whether a tile can be seen right now; tiles it may not step on (someone's there). */
    var inSight: (FieldMap.Tile) -> Boolean = { true }
    var blocked: (Int, Int) -> Boolean = { _, _ -> false }
    /** Not now (a game's on, or the Pokémon's asleep): it stays away, or goes. */
    var quiet = false

    private var nextAt = Long.MIN_VALUE
    private var stateAt = 0L
    private var exit: FieldMap.Tile? = null
    private var fade = 0L
    private var nextPlayAt = 0L

    /** Comes by soon (say, the first time the map is shown today). */
    fun comeSoon(now: Long, inMs: Long = FIRST_MS) {
        if (state == State.AWAY) nextAt = now + inMs
    }

    fun update(now: Long) {
        if (nextAt == Long.MIN_VALUE) nextAt = now + FIRST_MS + random.nextInt(20_000)
        val w = walker
        w?.update(now)
        when (state) {
            State.AWAY -> if (now >= nextAt && !quiet && pokemon.idle) arrive(now)
            State.ARRIVING -> {
                w ?: return leave(now)
                if (quiet) return leave(now)
                if (w.idle && near(w.at, pokemon.at) <= 1) greet(now)
                else if (w.idle && now - stateAt > 1_000) {
                    if (!goBeside(w, pokemon.next ?: pokemon.at, now) && now - stateAt > 12_000) leave(now)
                }
                if (now - stateAt > 30_000) leave(now)
            }
            State.GREETING -> if (now - stateAt > 2_200) {
                state = State.STAYING
                stateAt = now
                nextPlayAt = now + 8_000 + random.nextInt(10_000)
            }
            State.STAYING -> {
                w ?: return leave(now)
                if (quiet || now - stateAt > STAY_MS) return leave(now)
                // it keeps close to the Pokémon, and now and then they play
                if (w.idle && near(w.at, pokemon.at) > 5 && now - fade > 3_000) {
                    fade = now
                    goBeside(w, pokemon.at, now)
                }
                if (now >= nextPlayAt && w.idle && pokemon.idle && near(w.at, pokemon.at) <= 3) {
                    nextPlayAt = now + 14_000 + random.nextInt(14_000)
                    w.face(MapWanderer.towards(w.at, pokemon.at))
                    pokemon.face(MapWanderer.towards(pokemon.at, w.at))
                    w.perform(MapWanderer.Trick.BOUNCE, now)
                    pokemon.perform(MapWanderer.Trick.BOUNCE, now + 150)
                    emotes.show(w, Emote.NOTE, now, 1_400)
                    emotes.show(pokemon, Emote.NOTE, now + 300, 1_400)
                }
            }
            State.LEAVING -> {
                w ?: run { state = State.AWAY; return }
                val ex = exit
                val there = ex != null && w.idle && w.at.gx == ex.gx && w.at.gy == ex.gy
                if (there || now - stateAt > 20_000 || (w.idle && now - stateAt > 1_500 && ex != null && !w.goTo(ex, now))) {
                    walker = null
                    state = State.AWAY
                    nextAt = now + AGAIN_MS + random.nextInt(8 * 60_000)
                }
            }
        }
    }

    /** It comes in from out of sight (or from the edge of what can be seen), heading for the Pokémon. */
    private fun arrive(now: Long) {
        val at = pokemon.at
        val ground = scene.tiles.values.filter { near(it, at) in 6..14 && !blocked(it.gx, it.gy) }
        // out of sight first, so it walks in
        val start = ground.filter { !inSight(it) }.shuffled(random).firstOrNull { pokemon.canReach(it, 30) }
            ?: ground.filter { inSight(it) }.shuffled(random).firstOrNull { pokemon.canReach(it, 30) }
        if (start == null) {
            nextAt = now + 60_000
            return
        }
        val w = MapWanderer(scene, random)
        w.placeAt(start)
        w.blocked = { gx, gy -> blocked(gx, gy) || (gx == pokemon.at.gx && gy == pokemon.at.gy) || pokemon.next?.let { it.gx == gx && it.gy == gy } == true }
        w.avoid = { t -> near(t, pokemon.at) > 6 }
        w.pace = 300L
        walker = w
        exit = start
        state = State.ARRIVING
        stateAt = now
        goBeside(w, at, now)
    }

    private fun greet(now: Long) {
        val w = walker ?: return
        state = State.GREETING
        stateAt = now
        w.face(MapWanderer.towards(w.at, pokemon.at))
        w.hop(now)
        emotes.show(w, if (random.nextBoolean()) Emote.HEART else Emote.GLAD, now, 1_600)
        if (pokemon.idle) pokemon.face(MapWanderer.towards(pokemon.at, w.at))
        emotes.show(pokemon, Emote.EXCLAIM, now + 500, 900)
        emotes.show(pokemon, Emote.HAPPY, now + 1_500, 1_200)
        pokemon.pauseUntil(now + 2_400)
        cry = true
        lines.add(MapGames.Line(MapGames.What.VISITOR_HI, kind.species))
    }

    /** Off it goes (back the way it came, if it can). */
    fun leave(now: Long) {
        val w = walker
        if (w == null || state == State.LEAVING || state == State.AWAY) {
            if (w == null && state != State.AWAY) { state = State.AWAY; nextAt = now + AGAIN_MS }
            return
        }
        state = State.LEAVING
        stateAt = now
        w.avoid = null
        emotes.show(w, Emote.SMILE, now, 1_200)
        val ex = exit?.takeIf { !inSight(it) } ?: scene.tiles.values.filter { !inSight(it) && near(it, w.at) in 4..16 }.shuffled(random).firstOrNull { w.canReach(it, 30) }
            ?: exit
        exit = ex
        if (ex != null) w.goTo(ex, now)
    }

    /** A tap on it: it hops and is pleased. */
    fun patted(now: Long) {
        val w = walker ?: return
        w.hop(now)
        emotes.show(w, Emote.HEART, now, 1_400)
        cry = true
        lines.add(MapGames.Line(MapGames.What.VISITOR_HI, kind.species))
    }

    /** Where to draw it, if it's here, and how solid (it fades out going off when it can't walk off). */
    fun pose(now: Long): MapWanderer.Pose? = walker?.pose(now)

    /** The tiles it stands on or steps to (nobody else may). */
    fun tiles(): List<FieldMap.Tile> = walker?.let { listOfNotNull(it.at, it.next) } ?: emptyList()

    private fun near(a: FieldMap.Tile, b: FieldMap.Tile) = abs(a.gx - b.gx) + abs(a.gy - b.gy)

    private fun goBeside(w: MapWanderer, t: FieldMap.Tile, now: Long): Boolean =
        (0 until 4).mapNotNull { d -> scene.tile(t.gx + DX[d], t.gy + DY[d]) }
            .filter { !blocked(it.gx, it.gy) }
            .sortedBy { near(it, w.at) }
            .any { w.goTo(it, now, t) }
}
