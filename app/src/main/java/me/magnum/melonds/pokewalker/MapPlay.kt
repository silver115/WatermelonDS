package me.magnum.melonds.pokewalker

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * The route map's newer games, run by [MapGames]:
 *  - rustling grass: patches of grass rustle; one shakes hard with a "!" for a moment: pick it
 *    in time and a wild Pokémon jumps out; throw a Poké Ball. This is the walker's own Poké
 *    Radar: the walker picks the Pokémon and spends its watts before the game starts
 *    ([Encounter]), and a catch is stored on the walker ([MapGames.Reward.Caught]);
 *  - dowsing: something's buried nearby; walk the Pokémon about and it's warmer or colder,
 *    standing on it, it digs it up. The walker's own dowsing ([MapGames.Reward.Dowsed]);
 *  - hide and seek: a wild Pokémon hides behind a building or a tree (or in the grass) and
 *    peeks out now and then; walk up beside it to find it;
 *  - headbutt: the Pokémon headbutts a tree; it shakes, leaves fall, and a Pokémon or a berry
 *    might drop out;
 *  - rock smash: cracked rocks (the map's own, or a few of ours) break; some have something inside;
 *  - catch the falling: leaves and berries come down; get under them to catch them, for points.
 * All but the walker's two are just for fun. Pure Kotlin.
 */
class MapPlay internal constructor(
    private val games: MapGames,
    private val scene: FieldMap.Scene,
    private val pokemon: MapWanderer,
    private val crowd: MapCrowd,
    private val random: Random,
) {
    /** A Poké Radar encounter the walker picked: its route slot (0-2), species and level. */
    class Encounter(val slot: Int, val species: Int, val level: Int)

    /** A patch of grass in the rustling game. */
    class Patch(val tile: FieldMap.Tile) {
        internal var right = false
        internal var nextRustle = 0L
        internal var rustleAt = Long.MIN_VALUE
    }

    /** A Poké Ball thrown at [to] from [from] at [start]: it rocks [wobbles] times, then [caught] or not. */
    class Throw(val from: FieldMap.Tile, val to: FieldMap.Tile, val start: Long, val wobbles: Int, val caught: Boolean) {
        val landsAt get() = start + THROW_MS
        val doneAt get() = start + THROW_MS + ABSORB_MS + wobbles * WOBBLE_MS
    }

    /** Something falling: a Pokémon or a berry out of a tree, or a leaf or berry in the catching game. */
    class Drop(
        val kind: Int, val tile: FieldMap.Tile, val start: Long, val ms: Long,
        /** How high it starts (picture pixels), and how far it sways. */
        val height: Float, val sway: Float,
        /** For a Pokémon: which one; for a berry: which berry. */
        val species: Int = 0, val model: Int = 0, val berry: Int = 0,
        /** Where it starts across, picture pixels to the side of the tile. */
        val fromDx: Float = 0f,
    ) {
        /** When it was caught (Long.MIN_VALUE: not). */
        var caughtAt = Long.MIN_VALUE
            internal set
        val landsAt get() = start + ms
    }

    /** A rock to smash: the map's own, or one of ours ([own]). */
    class Rock(val tile: FieldMap.Tile, val own: Boolean) {
        /** When it was smashed (Long.MIN_VALUE: it's whole), and when it's back. */
        var brokenAt = Long.MIN_VALUE
            internal set
        var backAt = Long.MIN_VALUE
            internal set
        internal var shakeAt = Long.MIN_VALUE
        /** Whether it stands there at [now] (whole, or coming back). */
        fun standing(now: Long) = brokenAt == Long.MIN_VALUE || now >= backAt
        /** How solid it's drawn at [now] (fading back in). */
        fun alpha(now: Long): Float = if (brokenAt == Long.MIN_VALUE) 1f else ((now - backAt) / 800f).coerceIn(0f, 1f)
        /** Pixels it's shaken to the side at [now]. */
        fun dx(now: Long): Int = if (shakeAt != Long.MIN_VALUE && now - shakeAt in 0 until SMASH_SHAKE_MS) (if ((now / 45) % 2 == 0L) -1 else 1) else 0
    }

    /** Someone hiding: [kind] at [tile], in the grass or behind something. */
    class Hider(val kind: MapCrowd.WildKind, val tile: FieldMap.Tile, val inGrass: Boolean, val facing: Int) {
        internal var peekAt = Long.MIN_VALUE
        /** When it was found or came out (Long.MIN_VALUE: still hiding). */
        var outAt = Long.MIN_VALUE
            internal set
        var found = false
            internal set
    }

    /** A bit of a broken rock flying off: from where (picture pixels), which way, when. */
    class Chip(val x: Float, val y: Float, val vx: Float, val vy: Float, val start: Long, val color: Int)

    companion object {
        /** Rustling grass: watching the patches, the "!" one shaking, how long a wild Pokémon waits for a ball. */
        const val RUSTLE_WATCH_MS = 2_200L
        const val RUSTLE_WINDOW_MS = 2_600L
        const val RUSTLE_WAIT_MS = 15_000L
        /** A ball's flight, the Pokémon going into it, and each rock of the ball. */
        const val THROW_MS = 520L
        const val ABSORB_MS = 260L
        const val WOBBLE_MS = 460L
        /** Dowsing: how many steps and how long. */
        const val DOWSE_STEPS = 40
        const val DOWSE_MS = 60_000L
        const val DIG_MS = 1_400L
        /** Hide and seek: how long, and how often it peeks out. */
        const val HIDE_MS = 60_000L
        const val PEEK_MS = 1_000L
        /** A tree shaking, and a Pokémon or berry dropping out of it. */
        const val SHAKE_MS = 900L
        const val DROP_MS = 620L
        /** A rock shaking before it breaks, its bits flying, and how long until it's back. */
        const val SMASH_SHAKE_MS = 360L
        const val CRUMBLE_MS = 700L
        const val ROCK_BACK_MS = 120_000L
        /** Catch the falling: how long a round is. */
        const val CATCH_MS = 45_000L

        /** In a [Drop]: a Pokémon, a berry; in the catching game a leaf, a berry, a golden berry. */
        const val DROP_POKEMON = 0
        const val DROP_BERRY = 1
        const val FALL_LEAF = 2
        const val FALL_BERRY = 3
        const val FALL_GOLDEN = 4

        /** Points for a caught leaf, berry, golden berry. */
        fun points(kind: Int) = when (kind) { FALL_LEAF -> 1; FALL_BERRY -> 3; FALL_GOLDEN -> 5; else -> 0 }

        /** Pokémon that drop out of trees (src/data/headbutt: Ledyba, Spinarak, Hoothoot, Pineco, Exeggcute, Aipom, Heracross, Caterpie, Weedle). */
        val HEADBUTT_SPECIES = intArrayOf(165, 167, 163, 204, 102, 190, 214, 10, 13)
        /** Pokémon under rocks: Geodude, Shuckle, Krabby (by the sea). */
        val SMASH_SPECIES = intArrayOf(74, 213, 98)
        /** What's in a rock: Hard Stone, Pearl, Big Pearl, Stardust, Star Piece, Heart Scale, Everstone, Soft Sand. */
        val SMASH_ITEMS = intArrayOf(238, 88, 89, 90, 91, 93, 229, 237)

        /** How likely a Poké Ball catches the walker's route Pokémon in [slot] (rarer ones are harder, as on the walker). */
        fun catchChance(slot: Int) = when (slot) { 0 -> 0.5; 1 -> 0.6; else -> 0.75 }

        private val DX = intArrayOf(0, 0, -1, 1) // UP, DOWN, LEFT, RIGHT
        private val DY = intArrayOf(-1, 1, 0, 0)
    }

    // ---- what it has to work with, set by whoever draws the map ----
    /** The route's wild Pokémon (who hides; who the radar finds), and who drops out of trees and from under rocks. */
    var wildKinds: List<MapCrowd.WildKind> = emptyList()
    var headbuttKinds: List<MapCrowd.WildKind> = emptyList()
    var smashKinds: List<MapCrowd.WildKind> = emptyList()
    /** Whether a tile can be seen right now (in the view, not behind the cards): where games are set up. */
    var inSight: (FieldMap.Tile) -> Boolean = { true }
    /** Whether something can be put on a tile (nobody's there). */
    var free: (FieldMap.Tile) -> Boolean = { true }
    /** The game's little effects (sparkles, dust), if there are any. */
    var effects: FieldEffects? = null
    /** The best score in the catching game, kept between visits. */
    var bestScore: MapGames.ScoreStore? = null
    /** A berry that dropped out of a tree onto a tile (the map's life has the Pokémon eat it). */
    var onBerry: ((FieldMap.Tile, Int, Long) -> Unit)? = null

    // ---- what's going on ----
    val patches = ArrayList<Patch>()
    var encounter: Encounter? = null
        private set
    /** The wild Pokémon that jumped out of the grass, waiting for a ball. */
    var radarWild: MapCrowd.Walker? = null
        private set
    var ball: Throw? = null
        private set
    var dowseSpot: FieldMap.Tile? = null
        private set
    var dowseItem = 0
        private set
    var hider: Hider? = null
        private set
    /** The tree being headbutted and when it started shaking. */
    var tree: FieldMap.Tree? = null
        private set
    var shakeAt = Long.MIN_VALUE
        private set
    val drops = ArrayList<Drop>()
    val rocks = ArrayList<Rock>()
    val chips = ArrayList<Chip>()
    var score = 0
        private set

    private var phase = 0
    private var phaseAt = 0L
    private var startAt = 0L
    private var lastTile: FieldMap.Tile? = null
    private var lastDist = 0
    private var steps = 0
    private var target: Rock? = null
    private var nextFallAt = 0L
    private var rocksPlaced = false

    // ---- what there is here ----

    /** Tall grass in sight that the Pokémon can get to (patches to rustle). */
    private fun grassInSight() = scene.tiles.values.filter { it.tallGrass && inSight(it) }

    fun canRustle() = grassInSight().size >= 6
    fun canDowse() = scene.tiles.values.count { inSight(it) } >= 20
    fun canHide() = wildKinds.any { !it.swims } && (scene.hiding.any { inSight(it) } || grassInSight().size >= 6)
    fun canHeadbutt() = scene.trees.any { inSight(it.front) }
    fun canSmash(now: Long): Boolean { placeRocks(); return rocks.any { it.standing(now) && inSight(it.tile) } }
    fun canCatch() = scene.outdoor && scene.tiles.values.count { inSight(it) } >= 25

    /** Whether something of the games' stands on tile ([gx], [gy]) (a rock). */
    fun blocks(gx: Int, gy: Int, now: Long): Boolean = rocks.any { it.tile.gx == gx && it.tile.gy == gy && it.standing(now) }

    /** The rock standing on tile ([gx], [gy]), if any. */
    fun rockAt(gx: Int, gy: Int, now: Long): Rock? = rocks.firstOrNull { it.tile.gx == gx && it.tile.gy == gy && it.standing(now) }

    /**
     * The map's cracked rocks, and in rocky places a few of ours (once, on open ground in sight
     * away from where people walk).
     */
    fun placeRocks() {
        if (rocksPlaced) return
        rocksPlaced = true
        for (t in scene.rocks) rocks.add(Rock(t, false))
        if (rocks.isNotEmpty()) return
        val count = when (scene.course.kind) {
            FieldMap.Kind.MOUNTAIN, FieldMap.Kind.CAVE, FieldMap.Kind.VOLCANO, FieldMap.Kind.ICE -> 3
            FieldMap.Kind.BEACH, FieldMap.Kind.FIELD, FieldMap.Kind.FOREST, FieldMap.Kind.LAKE -> 2
            else -> 0
        }
        if (count == 0) return
        val homes = scene.people.map { it.home }
        // the same spots each time for the place
        val spots = scene.tiles.values.filter { t ->
            !t.tallGrass && scene.project(t.x, t.y, t.z)?.let { scene.inView(it[0], it[1], scene.tilePixels * 1.5f) } == true &&
                (0 until 4).count { d -> scene.tile(t.gx + DX[d], t.gy + DY[d]) != null } >= 3 &&
                homes.none { abs(it.gx - t.gx) + abs(it.gy - t.gy) <= 2 } &&
                (scene.start == null || abs(scene.start.gx - t.gx) + abs(scene.start.gy - t.gy) >= 3)
        }.sortedBy { Math.floorMod((scene.course.name.hashCode() * 17 + it.gx) * 104729 + it.gy * 7919, 1_000_003) }
        for (t in spots) {
            if (rocks.size >= count) break
            if (rocks.any { abs(it.tile.gx - t.gx) + abs(it.tile.gy - t.gy) < 4 }) continue
            rocks.add(Rock(t, true))
        }
    }

    // ---- starting and stopping ----

    /** Rustling grass for the walker's [e]: four patches near the Pokémon. */
    fun startRustling(e: Encounter, now: Long): Boolean {
        val from = pokemon.next ?: pokemon.at
        // the nearer grass first, give or take (each tile's share of chance drawn once, so the sort holds)
        val grass = grassInSight().filter { free(it) && dist(it, from) in 2..9 }
            .map { it to dist(it, from) + random.nextInt(4) }.sortedBy { it.second }.map { it.first }
        patches.clear()
        for (t in grass) {
            if (patches.size >= 4) break
            if (patches.any { abs(it.tile.gx - t.gx) + abs(it.tile.gy - t.gy) < 2 }) continue
            patches.add(Patch(t).also { it.nextRustle = now + 200 + random.nextInt(900) })
        }
        if (patches.size < 2) {
            patches.clear()
            encounter = e
            phase = 9
            games.say(MapGames.Line(MapGames.What.RUSTLE_NOWHERE, e.species), now, 6_000)
            fled(now)
            return false
        }
        patches[random.nextInt(patches.size)].right = true
        encounter = e
        phase = 0
        phaseAt = now
        startAt = now
        pokemon.held = true
        crowd.wildPaused = true
        for (w in crowd.wildOut()) crowd.leave(w, now)
        games.say(MapGames.Line(MapGames.What.RUSTLE_ON), now)
        return true
    }

    /** Dowsing for the walker's [item]: buried somewhere a few steps away. */
    fun startDowsing(item: Int, now: Long): Boolean {
        val from = pokemon.next ?: pokemon.at
        val spot = scene.tiles.values.filter { inSight(it) && free(it) && !it.tallGrass && abs(it.gx - from.gx) + abs(it.gy - from.gy) in 4..10 }
            .shuffled(random).firstOrNull { pokemon.canReach(it, 30) }
        dowseItem = item
        if (spot == null) {
            games.say(MapGames.Line(MapGames.What.DOWSE_NOWHERE, 0, item), now, 6_000)
            return false
        }
        dowseSpot = spot
        steps = 0
        lastTile = from
        lastDist = dist(from, spot)
        phase = 0
        startAt = now
        pokemon.held = true
        games.say(MapGames.Line(MapGames.What.DOWSE_ON, 0, DOWSE_STEPS), now)
        return true
    }

    /** Hide and seek with one of the route's Pokémon. */
    fun startHiding(now: Long): Boolean {
        val kinds = wildKinds.filter { !it.swims }
        if (kinds.isEmpty()) return false
        val from = pokemon.next ?: pokemon.at
        fun reachable(t: FieldMap.Tile) = (0 until 4).any { d -> scene.tile(t.gx + DX[d], t.gy + DY[d])?.let { n -> FieldMap.stepOk(n, t) && pokemon.canReach(n, 40) } == true }
        val behind = scene.hiding.filter { inSight(it) && dist(it, from) >= 4 }.shuffled(random).firstOrNull { reachable(it) }
        val spot = behind ?: grassInSight().filter { free(it) && dist(it, from) >= 4 }.shuffled(random).firstOrNull { pokemon.canReach(it, 40) }
        if (spot == null) {
            games.say(MapGames.Line(MapGames.What.HIDE_NOWHERE), now, 5_000)
            return false
        }
        val kind = kinds[random.nextInt(kinds.size)]
        hider = Hider(kind, spot, behind == null, random.nextInt(4)).also { it.peekAt = now + 3_000 + random.nextInt(2_000) }
        startAt = now
        pokemon.held = true
        crowd.wildPaused = true
        games.say(MapGames.Line(MapGames.What.HIDE_ON, kind.species, (HIDE_MS / 1000).toInt()), now)
        return true
    }

    /** The Pokémon goes and headbutts [t] (else the nearest tree in sight). */
    fun startHeadbutt(t: FieldMap.Tree?, now: Long): Boolean {
        val from = pokemon.next ?: pokemon.at
        val choice = t ?: scene.trees.filter { inSight(it.front) }.sortedBy { dist(it.front, from) }.firstOrNull { pokemon.canReach(it.front, 40) }
        if (choice == null || !pokemon.goTo(choice.front, now, choice.tile)) {
            games.say(MapGames.Line(MapGames.What.HEADBUTT_NOWHERE), now, 4_000)
            return false
        }
        tree = choice
        shakeAt = Long.MIN_VALUE
        phase = 0
        startAt = now
        return true
    }

    /** The Pokémon goes and smashes [r] (else the nearest rock in sight). */
    fun startSmash(r: Rock?, now: Long): Boolean {
        placeRocks()
        val from = pokemon.next ?: pokemon.at
        val choice = r ?: rocks.filter { it.standing(now) && it.alpha(now) >= 1f && inSight(it.tile) }.minByOrNull { dist(it.tile, from) }
        if (choice == null || !goBeside(choice.tile, now)) {
            games.say(MapGames.Line(MapGames.What.SMASH_NOWHERE), now, 4_000)
            return false
        }
        target = choice
        phase = 0
        startAt = now
        return true
    }

    /** A round of catching what falls. */
    fun startCatching(now: Long): Boolean {
        drops.removeAll { it.kind >= FALL_LEAF }
        score = 0
        startAt = now
        nextFallAt = now + 900
        pokemon.held = true
        crowd.wildPaused = true
        games.say(MapGames.Line(MapGames.What.CATCH_ON, 0, 0, (CATCH_MS / 1000).toInt()), now)
        return true
    }

    /** Stops [g] (it was on). */
    fun stop(g: MapGames.Game, now: Long) {
        when (g) {
            MapGames.Game.RUSTLING -> {
                radarWild?.let { if (!it.leaving) { it.still = false; crowd.leave(it, now) } }
                radarWild = null
                patches.clear()
                ball = null
                encounter = null
            }
            MapGames.Game.DOWSING -> dowseSpot = null
            MapGames.Game.HIDE_SEEK -> hider = null
            MapGames.Game.HEADBUTT -> if (shakeAt == Long.MIN_VALUE) tree = null
            MapGames.Game.ROCK_SMASH -> target = null
            MapGames.Game.CATCH -> drops.removeAll { it.kind >= FALL_LEAF }
            else -> {}
        }
        pokemon.held = false
    }

    // ---- taps and the A button ----

    /** A tap on the ground at [ground] (and on someone, [walker]); true when the game took it. */
    fun tap(g: MapGames.Game, walker: MapCrowd.Walker?, ground: FieldMap.Tile?, now: Long): Boolean {
        when (g) {
            MapGames.Game.RUSTLING -> {
                val w = radarWild
                if (phase == 2 && w != null && (walker === w || (ground != null && dist(ground, w.at) <= 1))) { throwBall(now); return true }
                if (phase <= 1 && ground != null) {
                    val p = patches.minByOrNull { dist(it.tile, ground) }
                    if (p != null && dist(p.tile, ground) <= 1) { pick(p, now); return true }
                }
                return phase <= 3
            }
            MapGames.Game.DOWSING, MapGames.Game.HIDE_SEEK, MapGames.Game.CATCH -> {
                // the Pokémon is walked about by taps
                if (ground != null) pokemon.goTo(ground, now)
                return true
            }
            else -> return false
        }
    }

    /** The A button; true when the game took it. */
    fun action(g: MapGames.Game, now: Long): Boolean {
        if (g == MapGames.Game.RUSTLING && phase == 2) { throwBall(now); return true }
        return false
    }

    // ---- moving the games along ----

    fun update(g: MapGames.Game?, now: Long) {
        // always: things falling, bits of rock flying, rocks coming back
        drops.removeAll { d -> now - d.landsAt > (if (d.kind == FALL_LEAF) 1_400 else 600) && d.kind != DROP_POKEMON || (d.kind == DROP_POKEMON && now > d.landsAt) }
        chips.removeAll { now - it.start > CRUMBLE_MS }
        when (g) {
            MapGames.Game.RUSTLING -> rustling(now)
            MapGames.Game.DOWSING -> dowsing(now)
            MapGames.Game.HIDE_SEEK -> hiding(now)
            MapGames.Game.HEADBUTT -> headbutt(now)
            MapGames.Game.ROCK_SMASH -> smash(now)
            MapGames.Game.CATCH -> catching(now)
            else -> {}
        }
        if (g != MapGames.Game.HEADBUTT && shakeAt != Long.MIN_VALUE && now - shakeAt > SHAKE_MS + DROP_MS + 200) { tree = null; shakeAt = Long.MIN_VALUE }
    }

    // rustling: 0 watching, 1 the right one shakes, 2 out and waiting for a ball, 3 the ball, 9 over
    private fun rustling(now: Long) {
        val e = encounter ?: run { games.end(now); return }
        when (phase) {
            0, 1 -> {
                // the patches rustle now and then; the right one hard, with a "!", once watched a while
                for (p in patches) {
                    if (now >= p.nextRustle) {
                        p.rustleAt = now
                        p.nextRustle = now + 700 + random.nextInt(1_300)
                    }
                }
                if (phase == 0 && now - phaseAt >= RUSTLE_WATCH_MS) {
                    phase = 1
                    phaseAt = now
                    games.say(MapGames.Line(MapGames.What.RUSTLE_PICK), now)
                }
                // steered into a patch: that's the pick
                if (phase == 1) {
                    val at = pokemon.at
                    patches.firstOrNull { it.tile.gx == at.gx && it.tile.gy == at.gy }?.let { pick(it, now); return }
                }
                if (phase == 1 && now - phaseAt >= RUSTLE_WINDOW_MS) {
                    phase = 9
                    games.say(MapGames.Line(MapGames.What.RUSTLE_MISSED, e.species), now, 6_000)
                    fled(now)
                }
            }
            2 -> {
                val w = radarWild
                if (w == null || w.leaving || w !in crowd.walkers) { phase = 9; games.say(MapGames.Line(MapGames.What.RUSTLE_FLED, e.species), now, 6_000); fled(now); return }
                pokemon.face(MapWanderer.towards(pokemon.at, w.at))
                if (now - phaseAt > RUSTLE_WAIT_MS) {
                    w.still = false
                    w.flee = true
                    crowd.leave(w, now + 800)
                    phase = 9
                    games.say(MapGames.Line(MapGames.What.RUSTLE_FLED, e.species), now, 6_000)
                    fled(now)
                }
            }
            3 -> {
                val b = ball ?: run { phase = 9; return }
                val w = radarWild
                if (now < b.doneAt) return
                if (b.caught) {
                    if (now - b.doneAt < 900) return
                    // gotcha: to the walker
                    w?.let { walkers -> crowd.walkers.remove(walkers) }
                    radarWild = null
                    ball = null
                    effects?.sparkles(b.to.x, b.to.y, b.to.z, 24, now)
                    games.reward(MapGames.Reward.Caught(e.slot, e.species))
                    games.cries.add(e.species)
                    pokemon.perform(MapWanderer.Trick.BOUNCE, now)
                    games.say(MapGames.Line(MapGames.What.RUSTLE_CAUGHT, e.species, e.level), now, 7_000)
                    phase = 9
                    fled(now)
                } else {
                    // it broke free and runs off
                    ball = null
                    if (w != null) {
                        w.still = false
                        w.flee = true
                        crowd.leave(w, now + 900)
                    }
                    games.cries.add(e.species)
                    games.say(MapGames.Line(MapGames.What.RUSTLE_BROKE_FREE, e.species), now, 6_000)
                    phase = 9
                    fled(now)
                }
            }
            else -> fled(now)
        }
    }

    private fun pick(p: Patch, now: Long) {
        val e = encounter ?: return
        if (phase == 0) {
            games.say(MapGames.Line(MapGames.What.RUSTLE_WAIT), now, 1_500)
            return
        }
        if (phase != 1) return
        // walked into it (with the D-pad): it jumps out in front of the Pokémon; else the Pokémon goes up to it
        val stand = pokemon.next ?: pokemon.at
        val into = stand.gx == p.tile.gx && stand.gy == p.tile.gy
        if (!into) goBeside(p.tile, now)
        p.rustleAt = now
        if (!p.right) {
            phase = 9
            games.say(MapGames.Line(MapGames.What.RUSTLE_WRONG, e.species), now, 6_000)
            fled(now)
            return
        }
        val out = if (!into) p.tile else (0 until 4).mapNotNull { d -> scene.tile(p.tile.gx + DX[d], p.tile.gy + DY[d])?.let { it to d } }
            .filter { (t, _) -> free(t) && !crowd.occupied(t.gx, t.gy) && FieldMap.stepOk(p.tile, t) }
            .maxByOrNull { (t, d) -> (if (d == pokemon.facing) 4 else 0) + (if (t.tallGrass) 2 else 0) }?.first ?: p.tile
        val kind = wildKinds.firstOrNull { it.species == e.species && !it.swims } ?: wildKinds.firstOrNull { it.species == e.species }
        val w = kind?.let { crowd.spawnWild(it, now, RUSTLE_WAIT_MS + 20_000, onGrassOnly = true, at = out) }
        w?.still = true
        w?.let { it.facing = MapWanderer.towards(it.at, stand) }
        radarWild = w
        patches.retainAll { it === p }
        phase = 2
        phaseAt = now
        games.cries.add(e.species)
        games.say(MapGames.Line(MapGames.What.RUSTLE_APPEARED, e.species, e.level), now)
        // no sprite for it: it's thrown at straight away
        if (w == null) throwBall(now, p.tile)
    }

    private fun throwBall(now: Long, at: FieldMap.Tile? = null) {
        val e = encounter ?: return
        if (phase != 2) return
        val to = radarWild?.at ?: at ?: patches.firstOrNull()?.tile ?: return
        val caught = random.nextDouble() < catchChance(e.slot)
        // how often it rocks: three times when caught, else it breaks free after one to three
        val wobbles = if (caught) 3 else 1 + random.nextInt(3)
        ball = Throw(pokemon.at, to, now, wobbles, caught)
        pokemon.face(MapWanderer.towards(pokemon.at, to))
        pokemon.hop(now)
        phase = 3
        phaseAt = now
        games.say(MapGames.Line(MapGames.What.RUSTLE_THROW, e.species), now)
    }

    /** Whether [w] is in the ball right now (not to be drawn). */
    fun inBall(w: MapCrowd.Walker, now: Long): Boolean {
        val b = ball ?: return false
        return w === radarWild && now >= b.landsAt + ABSORB_MS / 2 && (b.caught || now < b.doneAt)
    }

    /** How hard patch [p] is shaking at [now] (0: still, up to 1). */
    fun rustle(p: Patch, now: Long): Float {
        if (phase == 1 && p.right) return 0.75f + 0.25f * sin(now / 40.0).toFloat().let { abs(it) }
        val age = now - p.rustleAt
        return if (age in 0 until 520) (1f - age / 520f) * 0.55f else 0f
    }

    /** Whether the right patch shows its "!" now. */
    fun alert(p: Patch): Boolean = phase == 1 && p.right

    private fun fled(now: Long) {
        patches.clear()
        encounter = null
        pokemon.held = false
        games.end(now)
    }

    // dowsing
    private fun dowsing(now: Long) {
        val spot = dowseSpot ?: run { games.end(now); return }
        if (phase == 1) {
            // digging it up
            if (now - phaseAt >= DIG_MS) {
                effects?.sparkles(spot.x, spot.y, spot.z, 20, now)
                games.reward(MapGames.Reward.Dowsed(dowseItem))
                games.say(MapGames.Line(MapGames.What.DOWSE_FOUND, 0, dowseItem), now, 7_000)
                pokemon.hop(now)
                dowseSpot = null
                pokemon.held = false
                games.end(now)
            }
            return
        }
        val at = pokemon.at
        if (at !== lastTile) {
            lastTile = at
            steps++
            val d = dist(at, spot)
            when {
                d == 0 -> {
                    phase = 1
                    phaseAt = now
                    pokemon.perform(MapWanderer.Trick.DIG, now, DIG_MS)
                    games.say(MapGames.Line(MapGames.What.DOWSE_DIG), now)
                    return
                }
                d <= 1 -> games.say(MapGames.Line(MapGames.What.DOWSE_HOT, 0, DOWSE_STEPS - steps), now)
                d < lastDist -> games.say(MapGames.Line(MapGames.What.DOWSE_WARMER, 0, DOWSE_STEPS - steps), now)
                d > lastDist -> games.say(MapGames.Line(MapGames.What.DOWSE_COLDER, 0, DOWSE_STEPS - steps), now)
                else -> games.say(MapGames.Line(MapGames.What.DOWSE_SAME, 0, DOWSE_STEPS - steps), now)
            }
            lastDist = d
        }
        // a faint glint when it's very close
        if (lastDist <= 1 && (now / 600) % 3 == 0L && now - phaseAt > 600) {
            phaseAt = now
            effects?.sparkle(spot.x, spot.y, spot.z, now, dy = -2f)
        }
        if (steps >= DOWSE_STEPS || now - startAt > DOWSE_MS) {
            games.say(MapGames.Line(MapGames.What.DOWSE_NONE, 0, dowseItem), now, 7_000)
            dowseSpot = null
            pokemon.held = false
            games.end(now)
        }
    }

    // hide and seek
    private fun hiding(now: Long) {
        val h = hider ?: run { games.end(now); return }
        if (h.outAt != Long.MIN_VALUE) {
            // out: it hops about a moment, then goes
            if (now - h.outAt > 2_600) {
                hider = null
                pokemon.held = false
                games.end(now)
            }
            return
        }
        // peeking out now and then (and rustling the grass it's in)
        if (now >= h.peekAt + PEEK_MS) h.peekAt = now + 4_500 + random.nextInt(3_500)
        val at = pokemon.at
        val found = pokemon.idle && (dist(at, h.tile) == 1 || (h.inGrass && dist(at, h.tile) == 0))
        if (found) {
            h.found = true
            h.outAt = now
            if (at.gx != h.tile.gx || at.gy != h.tile.gy) pokemon.face(MapWanderer.towards(at, h.tile))
            pokemon.hop(now)
            games.cries.add(h.kind.species)
            games.say(MapGames.Line(MapGames.What.HIDE_FOUND, h.kind.species, ((now - startAt) / 1000).toInt().coerceAtLeast(1)), now, 6_000)
            return
        }
        if (now - startAt > HIDE_MS) {
            h.outAt = now
            games.say(MapGames.Line(MapGames.What.HIDE_OVER, h.kind.species), now, 6_000)
        }
    }

    /** Whether [h] shows at [now] (peeking, or out), and how high it hops. */
    fun hiderPose(now: Long): MapWanderer.Pose? {
        val h = hider ?: return null
        val t = h.tile
        if (h.outAt != Long.MIN_VALUE) {
            val age = now - h.outAt
            val hop = abs(sin(PI * age / 330.0)) * 7.0
            val facing = if (h.found) MapWanderer.towards(t, pokemon.at) else FollowerSprites.DOWN
            return MapWanderer.Pose(t.x, t.y, t.z, facing, ((now / 160) % 2).toInt(), if (age < 1_400) hop else 0.0, false, grass = h.inGrass)
        }
        val age = now - h.peekAt
        if (age !in 0 until PEEK_MS) return null
        // up for a look, and back down
        val up = sin(PI * age / PEEK_MS).toFloat()
        return MapWanderer.Pose(t.x, t.y, t.z, h.facing, 0, if (h.inGrass) up * 3.0 else up * 6.0, false, grass = h.inGrass)
    }

    /** Whether the grass someone's hiding in is rustling at [now]. */
    fun hiderRustles(now: Long): Boolean {
        val h = hider ?: return false
        return h.inGrass && h.outAt == Long.MIN_VALUE && now - h.peekAt in 0 until PEEK_MS
    }

    // headbutt: 0 going to the tree, 1 shaking, then something drops
    private fun headbutt(now: Long) {
        val t = tree ?: run { games.end(now); return }
        when (phase) {
            0 -> {
                val at = pokemon.at
                if (pokemon.idle && at.gx == t.front.gx && at.gy == t.front.gy) {
                    pokemon.face(MapWanderer.towards(at, t.tile))
                    pokemon.hop(now)
                    shakeAt = now + 120
                    phase = 1
                    phaseAt = now
                    pokemon.pauseUntil(now + SHAKE_MS + DROP_MS + 600)
                } else if (pokemon.idle && now - startAt > 1_500 || now - startAt > 20_000) {
                    tree = null
                    games.end(now)
                }
            }
            1 -> if (now - phaseAt >= SHAKE_MS) {
                phase = 2
                dropFromTree(t, now)
                games.end(now)
            }
        }
    }

    private fun dropFromTree(t: FieldMap.Tree, now: Long) {
        val roll = random.nextInt(100)
        // where it lands: beside the Pokémon, under the tree
        val at = pokemon.at
        val land = (0 until 4).mapNotNull { d -> scene.tile(t.front.gx + DX[d], t.front.gy + DY[d]) }
            .filter { (it.gx != at.gx || it.gy != at.gy) && free(it) && FieldMap.stepOk(t.front, it) }
            .minByOrNull { dist(it, t.tile) + random.nextInt(2) }
        val top = scene.project(t.tile.x, t.tile.y, t.tile.z)
        val height = if (top != null) (top[1] - t.top).coerceIn(18f, 60f) else 36f
        val fromDx = if (land != null && top != null) (scene.project(t.tile.x, t.tile.y, t.tile.z)!![0] - (scene.project(land.x, land.y, land.z)?.get(0) ?: top[0])) else 0f
        when {
            roll < 45 && headbuttKinds.isNotEmpty() && land != null -> {
                val kind = headbuttKinds[random.nextInt(headbuttKinds.size)]
                drops.add(Drop(DROP_POKEMON, land, now, DROP_MS, height, 0f, kind.species, kind.model, fromDx = fromDx))
                games.later(now + DROP_MS) {
                    crowd.spawnWild(kind, it, 14_000L + random.nextInt(8_000), onGrassOnly = false, at = land)
                    effects?.dust(land.x, land.y, land.z, it)
                    games.cries.add(kind.species)
                }
                games.say(MapGames.Line(MapGames.What.HEADBUTT_POKEMON, kind.species), now + DROP_MS, 6_000)
            }
            roll < 72 && land != null -> {
                val berry = random.nextInt(MapGames.BERRY_KINDS)
                drops.add(Drop(DROP_BERRY, land, now, DROP_MS, height, 0f, berry = berry, fromDx = fromDx))
                games.later(now + DROP_MS) { onBerry?.invoke(land, berry, it) }
                games.say(MapGames.Line(MapGames.What.HEADBUTT_BERRY, 0, berry), now + DROP_MS, 5_000)
            }
            else -> games.say(MapGames.Line(MapGames.What.HEADBUTT_NOTHING), now, 5_000)
        }
    }

    /** How far the tree being headbutted is shaken to the side at [now] (pixels), or null when none is. */
    fun treeShake(now: Long): Int? {
        if (tree == null || shakeAt == Long.MIN_VALUE) return null
        val age = now - shakeAt
        if (age !in 0 until SHAKE_MS) return null
        // big shakes settling down
        val k = 1f - age.toFloat() / SHAKE_MS
        val s = sin(age / 38.0).toFloat() * 2.2f * k
        return s.toInt()
    }

    // rock smash: 0 going to the rock, 1 shaking it, 2 bits flying
    private fun smash(now: Long) {
        val r = target ?: run { games.end(now); return }
        when (phase) {
            0 -> {
                val at = pokemon.at
                if (pokemon.idle && dist(at, r.tile) == 1) {
                    pokemon.face(MapWanderer.towards(at, r.tile))
                    pokemon.hop(now)
                    r.shakeAt = now + 150
                    phase = 1
                    phaseAt = now
                    pokemon.pauseUntil(now + SMASH_SHAKE_MS + CRUMBLE_MS + 800)
                } else if (pokemon.idle && now - startAt > 1_500 || now - startAt > 20_000) {
                    target = null
                    games.end(now)
                }
            }
            1 -> if (now - phaseAt >= SMASH_SHAKE_MS + 150) {
                r.brokenAt = now
                r.backAt = now + ROCK_BACK_MS + random.nextInt(60_000)
                crumble(r, now)
                effects?.dust(r.tile.x, r.tile.y, r.tile.z, now)
                val roll = random.nextInt(100)
                when {
                    roll < 30 -> {
                        val items = if (scene.course.kind == FieldMap.Kind.BEACH) SMASH_ITEMS else SMASH_ITEMS.filter { it != 237 }.toIntArray()
                        val item = items[random.nextInt(items.size)]
                        effects?.sparkle(r.tile.x, r.tile.y, r.tile.z, now + 200, dy = -6f)
                        games.say(MapGames.Line(MapGames.What.SMASH_ITEM, 0, item), now, 6_000)
                    }
                    roll < 52 && smashKinds.isNotEmpty() -> {
                        val kinds = smashKinds.filter { it.species != 98 || scene.water.isNotEmpty() }.ifEmpty { smashKinds }
                        val kind = kinds[random.nextInt(kinds.size)]
                        val tile = r.tile
                        games.later(now + 300) {
                            crowd.spawnWild(kind, it, 12_000L + random.nextInt(8_000), onGrassOnly = false, at = tile)
                            games.cries.add(kind.species)
                        }
                        games.say(MapGames.Line(MapGames.What.SMASH_POKEMON, kind.species), now + 300, 6_000)
                    }
                    else -> games.say(MapGames.Line(MapGames.What.SMASH_NOTHING), now, 5_000)
                }
                target = null
                games.end(now)
            }
        }
    }

    /** Bits of [r] flying off. */
    private fun crumble(r: Rock, now: Long) {
        val p = scene.project(r.tile.x, r.tile.y, r.tile.z) ?: return
        val colors = intArrayOf(0xFF8C8478.toInt(), 0xFF6E665C.toInt(), 0xFFB0A898.toInt(), 0xFF5A5248.toInt())
        repeat(12) { k ->
            val a = k * PI / 6 + random.nextDouble() * 0.4
            val speed = 30f + random.nextFloat() * 40f
            chips.add(Chip(p[0] + (random.nextFloat() - 0.5f) * 8f, p[1] - 5f - random.nextFloat() * 6f,
                (kotlin.math.cos(a) * speed).toFloat(), -40f - random.nextFloat() * 50f, now, colors[k % colors.size]))
        }
    }

    // catch the falling
    private fun catching(now: Long) {
        val left = CATCH_MS - (now - startAt)
        // what came down: caught if the Pokémon's under it when it lands
        val p = pokemon.pose(now)
        for (d in drops) {
            if (d.kind < FALL_LEAF || d.caughtAt != Long.MIN_VALUE || now < d.landsAt || now - d.landsAt > 80) continue
            if (abs(p.x - d.tile.x) <= 10.0 && abs(p.z - d.tile.z) <= 10.0) {
                d.caughtAt = now
                score += points(d.kind)
                effects?.sparkle(d.tile.x, d.tile.y, d.tile.z, now, dy = -14f)
                if (d.kind != FALL_LEAF) pokemon.hop(now)
            }
        }
        if (left <= 0) {
            val store = bestScore
            val best = maxOf(store?.best ?: 0, score)
            val record = store != null && score > store.best
            if (record) store!!.best = score
            drops.removeAll { it.kind >= FALL_LEAF }
            pokemon.held = false
            games.say(MapGames.Line(MapGames.What.CATCH_END, 0, score, best, if (record) "record" else ""), now, 8_000)
            if (record || score >= 20) pokemon.perform(MapWanderer.Trick.BOUNCE, now)
            games.end(now)
            return
        }
        if (now >= nextFallAt && left > 2_500) {
            nextFallAt = now + 750 + random.nextInt(800)
            val from = pokemon.next ?: pokemon.at
            val spot = scene.tiles.values.filter { inSight(it) && dist(it, from) in 1..4 && drops.none { d -> d.tile === it } }.randomOrNull(random)
            if (spot != null) {
                val roll = random.nextInt(100)
                val kind = when { roll < 58 -> FALL_LEAF; roll < 92 -> FALL_BERRY; else -> FALL_GOLDEN }
                val ms = if (kind == FALL_LEAF) 2_300L + random.nextInt(700) else 1_600L + random.nextInt(600)
                drops.add(Drop(kind, spot, now, ms, 110f, if (kind == FALL_LEAF) 7f else 0f, berry = random.nextInt(MapGames.BERRY_KINDS)))
            }
        }
        games.say(MapGames.Line(MapGames.What.CATCH_ON, 0, score, ((left + 999) / 1000).toInt()), now)
    }

    /**
     * Where [d] is at [now]: picture position of the tile it falls to and how high above it
     * it is (and to the side), or null when it's gone.
     */
    fun dropAt(d: Drop, now: Long): FloatArray? {
        if (now < d.start) return null
        val ground = scene.project(d.tile.x, d.tile.y, d.tile.z) ?: return null
        val u = ((now - d.start).toFloat() / d.ms).coerceIn(0f, 1f)
        if (d.caughtAt != Long.MIN_VALUE) return null
        // falling faster and faster (a leaf drifts down steadily, swaying)
        val lift = if (d.kind == FALL_LEAF) d.height * (1f - u) else d.height * (1f - u * u)
        val side = d.fromDx * (1f - u) + if (d.sway != 0f) sin(u * 9.0).toFloat() * d.sway * (1f - u * 0.6f) else 0f
        return floatArrayOf(ground[0] + side, ground[1], lift)
    }

    /** The ball at [now]: where (picture), how high and which way it leans (-1, 0, 1); null when there's none. */
    fun ballAt(now: Long): FloatArray? {
        val b = ball ?: return null
        val a = scene.project(b.from.x, b.from.y, b.from.z) ?: return null
        val c = scene.project(b.to.x, b.to.y, b.to.z) ?: return null
        if (now < b.landsAt) {
            val u = (now - b.start).toFloat() / THROW_MS
            return floatArrayOf(a[0] + (c[0] - a[0]) * u, a[1] - 12f + (c[1] - a[1] + 12f) * u, sin(PI * u).toFloat() * 30f + 4f * (1f - u), 0f)
        }
        val age = now - b.landsAt - ABSORB_MS
        // rocking: a lean one way and back, each rock
        val lean = if (age < 0 || now >= b.doneAt) 0f else {
            val k = (age % WOBBLE_MS).toFloat() / WOBBLE_MS
            if (k < 0.5f) (if (((age / WOBBLE_MS) % 2) == 0L) -1f else 1f) * sin(PI * k * 2).toFloat() else 0f
        }
        // when it breaks free, the ball's gone; when caught it stays a moment
        if (!b.caught && now >= b.doneAt) return null
        return floatArrayOf(c[0], c[1], if (age < 0) 4f * (1f - (now - b.landsAt).toFloat() / ABSORB_MS) else 0f, lean)
    }

    // ---- helpers ----

    private fun dist(a: FieldMap.Tile, b: FieldMap.Tile) = abs(a.gx - b.gx) + abs(a.gy - b.gy)

    /** Sends the Pokémon beside [t] (the nearest side it can get to), to look at it. */
    private fun goBeside(t: FieldMap.Tile, now: Long): Boolean {
        val from = pokemon.next ?: pokemon.at
        if (dist(from, t) == 1 && pokemon.idle) { pokemon.face(MapWanderer.towards(from, t)); return true }
        return (0 until 4).mapNotNull { d -> scene.tile(t.gx + DX[d], t.gy + DY[d]) }
            .filter { FieldMap.stepOk(it, t) || abs(it.y - t.y) <= FieldMap.MAX_STEP }
            .sortedBy { dist(it, from) }
            .any { pokemon.goTo(it, now, t) }
    }
}
