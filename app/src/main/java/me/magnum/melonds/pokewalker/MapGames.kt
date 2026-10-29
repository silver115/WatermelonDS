package me.magnum.melonds.pokewalker

import kotlin.math.abs
import kotlin.random.Random

/**
 * Little games on the route map:
 *  - fishing: the Pokémon goes to the water's edge and casts; tap when something bites;
 *  - tag: a wild Pokémon runs off and the Pokémon chases it;
 *  - a Bug-Catching Contest: a minute of tapping the bugs that come out of the tall grass;
 *  - an Unown hunt in caves: Unown hide, faint, one letter at a time; find them to spell a word;
 *  - a berry patch: plant a berry that grows as you walk (real steps), and pick it when it's ready;
 *  - and the ones in [MapPlay]: rustling grass and dowsing (the walker's own Poké Radar and
 *    dowsing, with real catches and finds: [rewards]), hide and seek, headbutting trees, smashing
 *    rocks, and catching what falls.
 * All but the walker's two are just for fun (nothing goes to the walker or the game). What
 * happens is told in [line]s for the screen to put in words. Pure Kotlin.
 */
class MapGames(
    val scene: FieldMap.Scene,
    private val pokemon: MapWanderer,
    private val crowd: MapCrowd,
    private val random: Random = Random.Default,
) {
    enum class Game { FISHING, TAG, CONTEST, UNOWN, BERRY, RUSTLING, DOWSING, HIDE_SEEK, HEADBUTT, ROCK_SMASH, CATCH }

    enum class What {
        FISH_WALK, FISH_WAIT, FISH_BITE, FISH_CAUGHT, FISH_AWAY, FISH_NOWHERE,
        TAG_CHASE, TAG_CAUGHT, TAG_AWAY, TAG_NOBODY,
        CONTEST_ON, CONTEST_CAUGHT, CONTEST_END, CONTEST_NONE,
        UNOWN_ON, UNOWN_FOUND, UNOWN_DONE,
        BERRY_PLANTED, BERRY_GROWING, BERRY_PICKED, BERRY_NOWHERE,
        /** From [MapLife]: an item ball picked up ([Line.n]: the item), a berry eaten ([Line.n]: which), one it couldn't get to. */
        ITEM_FOUND, TREAT_EATEN, TREAT_NOWHERE,
        /** Rustling grass ([Line.species] the walker's Pokémon, [Line.n] its level). */
        RUSTLE_ON, RUSTLE_PICK, RUSTLE_WAIT, RUSTLE_WRONG, RUSTLE_MISSED, RUSTLE_APPEARED, RUSTLE_THROW, RUSTLE_CAUGHT,
        RUSTLE_BROKE_FREE, RUSTLE_FLED, RUSTLE_NOWHERE,
        /** Dowsing ([Line.n]: steps left, or the item). */
        DOWSE_ON, DOWSE_WARMER, DOWSE_COLDER, DOWSE_SAME, DOWSE_HOT, DOWSE_DIG, DOWSE_FOUND, DOWSE_NONE, DOWSE_NOWHERE,
        HIDE_ON, HIDE_FOUND, HIDE_OVER, HIDE_NOWHERE,
        HEADBUTT_POKEMON, HEADBUTT_BERRY, HEADBUTT_NOTHING, HEADBUTT_NOWHERE,
        SMASH_ITEM, SMASH_POKEMON, SMASH_NOTHING, SMASH_NOWHERE,
        /** Catch the falling ([Line.n]: points, [Line.m]: seconds left or the best). */
        CATCH_ON, CATCH_END,
        /** The walker's say on a radar catch or a find: stored ([Line.species] / [Line.n]), or not ([Line.word]: why). */
        WALKER_CAUGHT, WALKER_FOUND, WALKER_ERROR,
        /** Today's visitor came by, and went. */
        VISITOR_HI, VISITOR_BYE,
    }

    /** Something to tell the player: [what], about [species] (0: none), with numbers [n] and [m] and a [word]. */
    data class Line(val what: What, val species: Int = 0, val n: Int = 0, val m: Int = 0, val word: String = "")

    /** Where a berry plant's state is kept between visits (steps it was planted at, which berry, how many picked). */
    interface BerryStore {
        var planted: Long
        var kind: Int
        var picked: Int
    }

    /** Where the catching game's best score is kept. */
    interface ScoreStore {
        var best: Int
    }

    /** Something real a game won: a Pokémon caught in the rustling grass, an item dowsed (for the walker to keep). */
    sealed class Reward {
        class Caught(val slot: Int, val species: Int) : Reward()
        class Dowsed(val item: Int) : Reward()
    }

    /** A fish leaping out of the water: its sprite, where, since when. */
    class Leap(val model: Int, val species: Int, val tile: FieldMap.Tile, val start: Long)

    companion object {
        const val BITE_MS = 1_000L
        const val TAG_MS = 25_000L
        const val CONTEST_MS = 60_000L
        const val LEAP_MS = 1_300L
        /** Steps from planting to each stage: sprout, plant, flowers, berries. */
        val BERRY_STAGES = longArrayOf(0, 200, 500, 900)
        const val BERRY_KINDS = 4
        val UNOWN_WORDS = arrayOf("CAVE", "ROCK", "DARK", "WALK", "STEP", "RUIN", "ALPH", "GLOW", "ECHO", "DEEP", "HIKE", "LAMP")
        /** The bugs of the contest and how many points each is worth at best (src/bug_contest.c scores by rarity and level). */
        val BUGS = intArrayOf(10, 11, 12, 13, 14, 15, 46, 48, 123, 127)
        private val BUG_POINTS = intArrayOf(30, 34, 52, 30, 34, 52, 48, 48, 72, 72)
        /** The games [MapPlay] runs. */
        val PLAYED = setOf(Game.RUSTLING, Game.DOWSING, Game.HIDE_SEEK, Game.HEADBUTT, Game.ROCK_SMASH, Game.CATCH)
    }

    // what the game has to work with, set by whoever draws the map
    /** Fish that can be caught here. */
    var fishKinds: List<MapCrowd.WildKind> = emptyList()
    /** Bugs for the contest. */
    var bugKinds: List<MapCrowd.WildKind> = emptyList()
    /** Unown sprites, A to Z (empty: no Unown here). */
    var unownModels: IntArray = IntArray(0)
    /** Where the berry patch is, and its store (null: no patch here). */
    var berryTile: FieldMap.Tile? = null
    var berryStore: BerryStore? = null
    /** The walker's steps so far (berries grow with them). */
    var steps: Long = 0

    var game: Game? = null
        private set
    var line: Line? = null
        private set
    private var lineUntil = 0L
    private var lineFrom = 0L
    /** When [line] shows from (something said a moment ahead). */
    val lineStart get() = lineFrom
    /** Species whose cry should play (a catch), since last asked. */
    val cries = ArrayList<Int>()
    /** What the walker should keep (taken by whoever stores it). */
    val rewards = ArrayList<Reward>()
    /** Things to do a moment from now. */
    private val pending = ArrayList<Pair<Long, (Long) -> Unit>>()

    /** The newer games. */
    val play = MapPlay(this, scene, pokemon, crowd, random)

    internal fun reward(r: Reward) {
        rewards.add(r)
    }

    /** Does [action] at [at] (on an update). */
    internal fun later(at: Long, action: (Long) -> Unit) {
        pending.add(at to action)
    }

    /** The game that's on is over (it tidied up after itself). */
    internal fun end(now: Long) {
        game = null
        crowd.wildPaused = false
    }

    // fishing
    private var phase = 0
    private var phaseAt = 0L
    private var shore: FieldMap.Tile? = null
    /** The float on the water while fishing, and whether something's biting. */
    var bobber: FieldMap.Tile? = null
        private set
    var biting = false
        private set
    var leap: Leap? = null
        private set
    var fishCaught = 0
        private set

    // tag
    private var target: MapCrowd.Walker? = null
    private var gameStart = 0L
    private var lastChase = 0L

    // contest
    private val bugsOut = HashSet<MapCrowd.Walker>()
    private var nextBugAt = 0L
    private var bestSpecies = 0
    private var bestScore = 0
    private var contestCaught = 0

    // unown
    private var word = ""
    private var found = 0
    private var unown: MapCrowd.Walker? = null
    private var nextUnownAt = 0L

    /** The games there are here (at [now]: rocks come and go). */
    fun available(now: Long = 0L): List<Game> {
        val out = ArrayList<Game>()
        if (fishKinds.isNotEmpty() && shoreTiles.isNotEmpty()) out.add(Game.FISHING)
        if (crowd.wildKinds.isNotEmpty()) out.add(Game.TAG)
        if (bugKinds.isNotEmpty() && scene.outdoor && scene.tiles.values.count { it.tallGrass } >= 8) out.add(Game.CONTEST)
        if (unownModels.size >= 26) out.add(Game.UNOWN)
        if (berryTile != null && berryStore != null) out.add(Game.BERRY)
        if (play.canRustle()) out.add(Game.RUSTLING)
        if (play.canDowse()) out.add(Game.DOWSING)
        if (play.canHide()) out.add(Game.HIDE_SEEK)
        if (play.canHeadbutt()) out.add(Game.HEADBUTT)
        if (play.canSmash(now)) out.add(Game.ROCK_SMASH)
        if (play.canCatch()) out.add(Game.CATCH)
        return out
    }

    /** Rustling grass for the walker's Poké Radar [e] (the walker already picked it and took the watts). */
    fun startRustling(e: MapPlay.Encounter, now: Long): Boolean {
        stop(now)
        game = Game.RUSTLING
        if (!play.startRustling(e, now)) { game = null; crowd.wildPaused = false; return false }
        return true
    }

    /** Dowsing for the walker's [item] (it already picked it and took the watts). */
    fun startDowsing(item: Int, now: Long): Boolean {
        stop(now)
        game = Game.DOWSING
        if (!play.startDowsing(item, now)) { game = null; return false }
        return true
    }

    /** Headbutts [tree] now. */
    fun headbutt(tree: FieldMap.Tree, now: Long): Boolean {
        stop(now)
        game = Game.HEADBUTT
        if (!play.startHeadbutt(tree, now)) { game = null; return false }
        return true
    }

    /** Smashes [rock] now. */
    fun smash(rock: MapPlay.Rock, now: Long): Boolean {
        stop(now)
        game = Game.ROCK_SMASH
        if (!play.startSmash(rock, now)) { game = null; return false }
        return true
    }

    /** Ground beside the water. */
    private val shoreTiles: List<FieldMap.Tile> by lazy {
        scene.tiles.values.filter { t -> waterBeside(t) != null }
    }

    private fun waterBeside(t: FieldMap.Tile): FieldMap.Tile? {
        val dx = intArrayOf(0, 0, -1, 1)
        val dy = intArrayOf(-1, 1, 0, 0)
        for (d in intArrayOf(FollowerSprites.UP, FollowerSprites.LEFT, FollowerSprites.RIGHT, FollowerSprites.DOWN)) {
            val k = FieldMap.Scene.key(t.gx + dx[d], t.gy + dy[d])
            if (scene.tiles[k] == null) scene.water[k]?.let { return it }
        }
        return null
    }

    internal fun say(l: Line, now: Long, ms: Long = 4_000) {
        line = l
        lineFrom = now
        lineUntil = now + ms
    }

    /** What to show now (null: nothing). */
    fun lineAt(now: Long): Line? = if (now >= lineFrom && (game != null || now < lineUntil)) line else null

    /** Shows [l] for [ms] (something that happened on the map besides the games). */
    fun announce(l: Line, now: Long, ms: Long = 5_000) {
        if (game == null) say(l, now, ms)
    }

    /**
     * Starts [g] (stopping whatever was going on); false when it can't be played here. Rustling
     * grass and dowsing need the walker's say first: see [startRustling] and [startDowsing].
     */
    fun start(g: Game, now: Long): Boolean {
        if (g !in available(now)) return false
        stop(now)
        val started = when (g) {
            Game.FISHING -> startFishing(null, now)
            Game.TAG -> startTag(null, now)
            Game.CONTEST -> startContest(now)
            Game.UNOWN -> startUnown(now)
            Game.BERRY -> visitBerry(now)
            Game.RUSTLING, Game.DOWSING -> false
            Game.HIDE_SEEK -> { game = g; play.startHiding(now) }
            Game.HEADBUTT -> { game = g; play.startHeadbutt(null, now) }
            Game.ROCK_SMASH -> { game = g; play.startSmash(null, now) }
            Game.CATCH -> { game = g; play.startCatching(now) }
        }
        if (!started && game == g && g in PLAYED) { game = null; crowd.wildPaused = false; pokemon.held = false }
        return started
    }

    /** Stops the game that's on. */
    fun stop(now: Long) {
        when (val g = game) {
            Game.FISHING -> { pokemon.held = false; bobber = null; biting = false }
            Game.TAG -> target?.let { it.flee = false; crowd.leave(it, now) }
            Game.CONTEST -> { for (b in bugsOut) crowd.leave(b, now); bugsOut.clear() }
            Game.UNOWN -> unown?.let { crowd.leave(it, now) }
            null -> {}
            else -> if (g in PLAYED) play.stop(g, now)
        }
        crowd.wildPaused = false
        target = null
        unown = null
        game = null
    }

    // ---- taps --------------------------------------------------------------------------------------

    /**
     * A tap: on [walker] (someone in the crowd), on the water at [water], on the berry patch
     * ([onBerry]), on [ground], or elsewhere. True when the game took it (the Pokémon shouldn't
     * walk there).
     */
    fun tap(walker: MapCrowd.Walker?, water: FieldMap.Tile?, onBerry: Boolean, now: Long, ground: FieldMap.Tile? = null): Boolean {
        val g = game
        if (g != null && g in PLAYED) return play.tap(g, walker, ground, now)
        when (game) {
            Game.FISHING -> {
                if (phase == 2 && biting) { catchFish(now); return true }
                if (phase >= 1) { stop(now); say(Line(What.FISH_AWAY), now, 2_500); return true }
                return false
            }
            Game.CONTEST -> {
                if (walker != null && walker in bugsOut && !walker.leaving) { catchBug(walker, now); return true }
                return false
            }
            Game.UNOWN -> {
                if (walker != null && walker === unown && !walker.leaving) { foundUnown(walker, now); return true }
                return false
            }
            Game.TAG, Game.BERRY -> return false
            else -> {}
        }
        // no game on: a tap on a wild Pokémon is a game of tag, on the water fishing, on the patch the berry
        if (walker != null && walker.wild && !walker.leaving && crowd.wildKinds.isNotEmpty() && walker !== play.radarWild) return startTag(walker, now)
        if (onBerry && berryTile != null && berryStore != null) return visitBerry(now)
        if (water != null && fishKinds.isNotEmpty() && shoreTiles.isNotEmpty()) return startFishing(water, now)
        return false
    }

    /** The A button: reels in a bite, throws a ball. True when the game took it. */
    fun action(now: Long): Boolean {
        if (game == Game.FISHING && phase == 2 && biting) { catchFish(now); return true }
        val g = game
        if (g != null && g in PLAYED && play.action(g, now)) return true
        return false
    }

    /** Fishing from where the Pokémon stands, at [water] beside it (the A button facing the water). */
    fun fishHere(water: FieldMap.Tile, now: Long): Boolean {
        if (fishKinds.isEmpty() || shoreTiles.isEmpty()) return false
        stop(now)
        return startFishing(water, now)
    }

    // ---- moving the games along ----------------------------------------------------------------------

    fun update(now: Long) {
        leap?.let { if (now - it.start > LEAP_MS) leap = null }
        if (pending.isNotEmpty()) {
            val due = pending.filter { now >= it.first }
            pending.removeAll(due)
            for ((_, action) in due) action(now)
        }
        when (game) {
            Game.FISHING -> fishing(now)
            Game.TAG -> tag(now)
            Game.CONTEST -> contest(now)
            Game.UNOWN -> unownHunt(now)
            Game.BERRY -> berry(now)
            else -> {}
        }
        play.update(game, now)
    }

    // fishing: 0 walking to the water, 1 waiting for a bite, 2 a bite, 3 caught (the fish leaps)
    private fun startFishing(water: FieldMap.Tile?, now: Long): Boolean {
        val from = pokemon.next ?: pokemon.at
        val spots = if (water != null) shoreTiles.filter { abs(it.gx - water.gx) + abs(it.gy - water.gy) <= 3 }.ifEmpty { shoreTiles } else shoreTiles
        val spot = spots.sortedBy { abs(it.gx - from.gx) + abs(it.gy - from.gy) }.firstOrNull { pokemon.goTo(it, now, waterBeside(it)) }
        if (spot == null) { say(Line(What.FISH_NOWHERE), now); return true }
        game = Game.FISHING
        shore = spot
        phase = 0
        phaseAt = now
        crowd.wildPaused = true
        say(Line(What.FISH_WALK), now)
        return true
    }

    private fun fishing(now: Long) {
        val s = shore ?: run { stop(now); return }
        when (phase) {
            0 -> {
                if (pokemon.idle && pokemon.at.gx == s.gx && pokemon.at.gy == s.gy) {
                    val w = waterBeside(s) ?: run { stop(now); return }
                    pokemon.face(MapWanderer.towards(s, w))
                    pokemon.held = true
                    // cast out a few tiles, as far as the water goes
                    val ddx = w.gx - s.gx
                    val ddy = w.gy - s.gy
                    bobber = (3 downTo 1).firstNotNullOfOrNull { k ->
                        val key = FieldMap.Scene.key(s.gx + ddx * k, s.gy + ddy * k)
                        if ((1..k).all { j -> scene.water.containsKey(FieldMap.Scene.key(s.gx + ddx * j, s.gy + ddy * j)) && scene.tiles[FieldMap.Scene.key(s.gx + ddx * j, s.gy + ddy * j)] == null }) scene.water[key] else null
                    } ?: w
                    phase = 1
                    phaseAt = now + 2_200 + random.nextInt(5_000)
                    say(Line(What.FISH_WAIT), now)
                } else if (now - phaseAt > 20_000 || (pokemon.idle && now - phaseAt > 1_500 && abs(pokemon.at.gx - s.gx) + abs(pokemon.at.gy - s.gy) > 0 && !pokemon.goTo(s, now, waterBeside(s)))) {
                    stop(now)
                    say(Line(What.FISH_NOWHERE), now)
                }
            }
            1 -> if (now >= phaseAt) {
                phase = 2
                biting = true
                phaseAt = now + BITE_MS
                say(Line(What.FISH_BITE), now)
            }
            2 -> if (now >= phaseAt) {
                biting = false
                stop(now)
                say(Line(What.FISH_AWAY), now, 3_000)
            }
            3 -> if (now >= phaseAt) stop(now)
        }
    }

    private fun catchFish(now: Long) {
        val b = bobber ?: return
        val kind = fishKinds[random.nextInt(fishKinds.size)]
        leap = Leap(kind.model, kind.species, b, now)
        biting = false
        bobber = null
        phase = 3
        phaseAt = now + LEAP_MS
        fishCaught++
        cries.add(kind.species)
        pokemon.held = false
        pokemon.hop(now)
        pokemon.pauseUntil(now + LEAP_MS)
        say(Line(What.FISH_CAUGHT, kind.species, 15 + random.nextInt(70)), now, 5_000)
    }

    // tag
    private fun startTag(w: MapCrowd.Walker?, now: Long): Boolean {
        val t = w ?: crowd.wildOut().filter { !it.person.swimming }.minByOrNull { abs(it.at.gx - pokemon.at.gx) + abs(it.at.gy - pokemon.at.gy) }
            ?: crowd.wildKinds.filter { !it.swims }.randomOrNull(random)?.let { crowd.spawnWild(it, now, TAG_MS + 5_000, near = pokemon.at) }
        if (t == null || t.person.swimming) { say(Line(What.TAG_NOBODY), now); return true }
        stop(now)
        game = Game.TAG
        target = t
        t.flee = true
        t.waitUntil = now
        t.leaveAt = now + TAG_MS + 5_000
        crowd.wildPaused = true
        gameStart = now
        lastChase = 0
        say(Line(What.TAG_CHASE, t.person.species, (TAG_MS / 1000).toInt()), now)
        return true
    }

    private fun tag(now: Long) {
        val t = target ?: run { stop(now); return }
        if (t.leaving || t !in crowd.walkers) { stop(now); say(Line(What.TAG_AWAY, t.person.species), now); return }
        val p = pokemon.next ?: pokemon.at
        val tt = t.next ?: t.at
        if (abs(p.gx - tt.gx) + abs(p.gy - tt.gy) <= 1 && pokemon.idle && now - gameStart > 1_500) {
            // tagged!
            val secs = ((now - gameStart) / 1000).toInt().coerceAtLeast(1)
            t.flee = false
            t.still = true
            pokemon.face(MapWanderer.towards(pokemon.at, tt))
            pokemon.hop(now)
            crowd.leave(t, now + 900)
            cries.add(t.person.species)
            target = null
            game = null
            crowd.wildPaused = false
            say(Line(What.TAG_CAUGHT, t.person.species, secs), now, 5_000)
            return
        }
        if (now - gameStart > TAG_MS) {
            t.flee = false
            crowd.leave(t, now)
            target = null
            game = null
            crowd.wildPaused = false
            say(Line(What.TAG_AWAY, t.person.species), now)
            return
        }
        // after it: to beside it, again and again as it runs
        if (now - lastChase > 450 || pokemon.idle) {
            lastChase = now
            val dx = intArrayOf(0, 0, -1, 1)
            val dy = intArrayOf(-1, 1, 0, 0)
            (0 until 4).mapNotNull { scene.tile(tt.gx + dx[it], tt.gy + dy[it]) }
                .sortedBy { abs(it.gx - p.gx) + abs(it.gy - p.gy) }
                .firstOrNull { pokemon.goTo(it, now, tt) }
        }
        say(Line(What.TAG_CHASE, t.person.species, ((TAG_MS - (now - gameStart)) / 1000).toInt().coerceAtLeast(0)), now)
    }

    // the Bug-Catching Contest
    private fun startContest(now: Long): Boolean {
        game = Game.CONTEST
        gameStart = now
        crowd.wildPaused = true
        for (w in crowd.wildOut()) crowd.leave(w, now)
        bugsOut.clear()
        nextBugAt = now + 800
        bestSpecies = 0
        bestScore = 0
        contestCaught = 0
        say(Line(What.CONTEST_ON, 0, (CONTEST_MS / 1000).toInt(), 0), now)
        return true
    }

    private fun contest(now: Long) {
        bugsOut.removeAll { it.leaving || it !in crowd.walkers }
        val left = CONTEST_MS - (now - gameStart)
        if (left <= 0) {
            for (b in bugsOut) crowd.leave(b, now)
            bugsOut.clear()
            game = null
            crowd.wildPaused = false
            if (bestSpecies == 0) say(Line(What.CONTEST_NONE), now, 6_000)
            else say(Line(What.CONTEST_END, bestSpecies, bestScore, if (bestScore >= 70) 1 else if (bestScore >= 55) 2 else 3), now, 8_000)
            return
        }
        if (now >= nextBugAt && bugsOut.size < 3 && bugKinds.isNotEmpty()) {
            crowd.spawnWild(bugKinds[random.nextInt(bugKinds.size)], now, 4_000L + random.nextInt(3_000))?.let { bugsOut.add(it) }
            nextBugAt = now + 900 + random.nextInt(1_600)
        }
        if (line?.what != What.CONTEST_CAUGHT || now >= lineUntil - 2_500) {
            say(Line(What.CONTEST_ON, bestSpecies, (left / 1000).toInt() + 1, contestCaught), now)
        }
    }

    private fun catchBug(w: MapCrowd.Walker, now: Long) {
        val species = w.person.species
        val i = BUGS.indexOf(species)
        val score = (if (i >= 0) BUG_POINTS[i] else 30) + random.nextInt(25)
        w.still = true
        crowd.leave(w, now)
        bugsOut.remove(w)
        contestCaught++
        cries.add(species)
        pokemon.hop(now)
        if (score > bestScore) { bestScore = score; bestSpecies = species }
        say(Line(What.CONTEST_CAUGHT, species, score), now, 3_000)
    }

    // the Unown hunt
    private fun startUnown(now: Long): Boolean {
        game = Game.UNOWN
        word = UNOWN_WORDS[random.nextInt(UNOWN_WORDS.size)]
        found = 0
        unown = null
        nextUnownAt = now + 600
        crowd.wildPaused = true
        say(Line(What.UNOWN_ON, 201, 0, word.length, pattern()), now)
        return true
    }

    /** The word so far: found letters, and a line for each still to find. */
    private fun pattern(): String = word.mapIndexed { i, c -> if (i < found) c else '_' }.joinToString(" ")

    private fun unownHunt(now: Long) {
        val u = unown
        if (u != null && (u.leaving || u !in crowd.walkers)) {
            unown = null
            nextUnownAt = now + 1_000
        }
        if (unown == null && now >= nextUnownAt) {
            val letter = word[found] - 'A'
            val kind = MapCrowd.WildKind(unownModels[letter], 201, false)
            unown = crowd.spawnWild(kind, now, 7_000L, onGrassOnly = false)?.also { it.faint = true; it.still = true }
            nextUnownAt = now + 1_500
        }
    }

    private fun foundUnown(w: MapCrowd.Walker, now: Long) {
        w.faint = false
        crowd.leave(w, now + 700)
        unown = null
        found++
        cries.add(201)
        pokemon.hop(now)
        nextUnownAt = now + 1_400
        if (found >= word.length) {
            game = null
            crowd.wildPaused = false
            say(Line(What.UNOWN_DONE, 201, found, word.length, word), now, 7_000)
        } else {
            say(Line(What.UNOWN_FOUND, 201, found, word.length, pattern()), now)
        }
    }

    // the berry patch
    /** How far along the plant is: -1 nothing planted, 0 sprout ... 3 berries to pick. */
    fun berryStage(): Int {
        val store = berryStore ?: return -1
        if (store.planted < 0) return -1
        val grown = (steps - store.planted).coerceAtLeast(0)
        var stage = 0
        for (i in BERRY_STAGES.indices) if (grown >= BERRY_STAGES[i]) stage = i
        return stage
    }

    /** Which berry it is (0 ... [BERRY_KINDS] - 1). */
    fun berryKind(): Int = (berryStore?.kind ?: 0).coerceIn(0, BERRY_KINDS - 1)

    private fun visitBerry(now: Long): Boolean {
        val b = berryTile ?: return false
        val from = pokemon.next ?: pokemon.at
        val dx = intArrayOf(0, 0, -1, 1)
        val dy = intArrayOf(-1, 1, 0, 0)
        val spot = (0 until 4).mapNotNull { scene.tile(b.gx + dx[it], b.gy + dy[it]) }
            .sortedBy { abs(it.gx - from.gx) + abs(it.gy - from.gy) }
            .firstOrNull { pokemon.goTo(it, now, b) }
        if (spot == null) { say(Line(What.BERRY_NOWHERE), now); return true }
        game = Game.BERRY
        shore = spot
        gameStart = now
        line = null
        return true
    }

    private fun berry(now: Long) {
        val spot = shore ?: run { stop(now); return }
        val store = berryStore ?: run { stop(now); return }
        if (!pokemon.idle) {
            if (now - gameStart > 20_000) stop(now)
            return
        }
        if (pokemon.at.gx != spot.gx || pokemon.at.gy != spot.gy) {
            if (now - gameStart > 1_500) stop(now)
            return
        }
        berryTile?.let { pokemon.face(MapWanderer.towards(pokemon.at, it)) }
        pokemon.hop(now)
        pokemon.pauseUntil(now + 1_500)
        when (berryStage()) {
            -1 -> {
                store.kind = random.nextInt(BERRY_KINDS)
                store.planted = steps
                say(Line(What.BERRY_PLANTED, 0, store.kind), now, 5_000)
            }
            3 -> {
                val n = 2 + random.nextInt(3)
                store.picked = store.picked + n
                store.planted = steps
                say(Line(What.BERRY_PICKED, 0, store.kind, n), now, 5_000)
            }
            else -> {
                val left = (store.planted + BERRY_STAGES[3] - steps).coerceAtLeast(1)
                say(Line(What.BERRY_GROWING, 0, store.kind, left.toInt()), now, 5_000)
            }
        }
        game = null
        shore = null
    }
}

/** The berry patch drawn in the DS's pixels: the soil, then a sprout, a plant, flowers and berries. Our own art. */
object BerryArt {
    private val BERRY = intArrayOf(0xFF3C64D8.toInt(), 0xFFF08CB4.toInt(), 0xFFD8303C.toInt(), 0xFFF0D040.toInt())
    private val FLOWER = intArrayOf(0xFFE8F0FF.toInt(), 0xFFFFD0E8.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFF0B0.toInt())
    private const val STEM = 0xFF3C8A34.toInt()
    private const val LEAF = 0xFF6CC85A.toInt()
    private const val SOIL = 0xFF7A5230.toInt()
    private const val SOIL_DARK = 0xFF56381F.toInt()
    private const val OUTLINE = 0xFF1E2E1A.toInt()

    /** One frame (any direction) for [stage] (-1: just soil ... 3: berries) of berry [kind]. */
    fun sprite(stage: Int, kind: Int): FollowerSprites.Sprite {
        val px = IntArray(32 * 32)
        fun put(x: Int, y: Int, c: Int) { if (x in 0 until 32 && y in 0 until 32) px[y * 32 + x] = c }
        fun dot(cx: Int, cy: Int, r: Int, c: Int) {
            for (y in -r..r) for (x in -r..r) if (x * x + y * y <= r * r + r) put(cx + x, cy + y, c)
        }
        val k = kind.coerceIn(0, BERRY.size - 1)
        if (stage >= 0) {
            val top = when (stage) { 0 -> 23; 1 -> 15; else -> 12 }
            for (y in top..27) put(16, y, STEM)
            val leaves = when (stage) { 0 -> intArrayOf(23); 1 -> intArrayOf(24, 20, 16); else -> intArrayOf(25, 21, 17, 13) }
            for ((i, y) in leaves.withIndex()) {
                val side = if (i % 2 == 0) -1 else 1
                put(16 + side, y, LEAF); put(16 + side * 2, y - 1, LEAF); put(16 + side * 3, y - 1, LEAF)
                put(16 - side, y + 1, LEAF); put(16 - side * 2, y, LEAF)
            }
            when (stage) {
                2 -> { dot(16, 11, 1, FLOWER[k]); dot(12, 16, 1, FLOWER[k]); dot(20, 18, 1, FLOWER[k]); put(16, 11, 0xFFF8E060.toInt()) }
                3 -> {
                    for ((x, y) in listOf(16 to 10, 12 to 15, 20 to 17)) {
                        dot(x, y, 2, BERRY[k])
                        put(x - 1, y - 1, 0xFFFFFFFF.toInt())
                    }
                }
            }
            // a dark edge so it reads on any ground
            val copy = px.copyOf()
            for (y in 0 until 32) for (x in 0 until 32) {
                if (copy[y * 32 + x] != 0 || y >= 27) continue
                val near = listOf(x - 1 to y, x + 1 to y, x to y - 1, x to y + 1).any { (a, b) -> a in 0 until 32 && b in 0 until 32 && copy[b * 32 + a] != 0 }
                if (near) px[y * 32 + x] = OUTLINE
            }
        }
        // the soil
        for (y in -2..2) for (x in -7..7) {
            val e = (x / 7.5) * (x / 7.5) + (y / 2.5) * (y / 2.5)
            if (e <= 1.0 && px[(28 + y) * 32 + 16 + x] == 0) put(16 + x, 28 + y, if (e > 0.6 || (x + y) % 3 == 0) SOIL_DARK else SOIL)
        }
        return FollowerSprites.Sprite(arrayOf(px), 32, 1)
    }

    /** A berry tossed for the Pokémon to eat: one berry of [kind] with a leaf, 16x16. Our own art. */
    fun treat(kind: Int): FollowerSprites.Sprite {
        val px = IntArray(16 * 16)
        fun put(x: Int, y: Int, c: Int) { if (x in 0 until 16 && y in 0 until 16) px[y * 16 + x] = c }
        val k = kind.coerceIn(0, BERRY.size - 1)
        val cx = 8
        val cy = 11
        for (y in -3..3) for (x in -3..3) if (x * x + y * y <= 10) put(cx + x, cy + y, BERRY[k])
        put(cx, cy - 4, STEM)
        put(cx + 1, cy - 5, LEAF); put(cx + 2, cy - 5, LEAF); put(cx + 2, cy - 6, LEAF); put(cx + 3, cy - 6, LEAF)
        // a dark edge so it reads on any ground, and a shine
        val copy = px.copyOf()
        for (y in 0 until 16) for (x in 0 until 16) {
            if (copy[y * 16 + x] != 0) continue
            val near = listOf(x - 1 to y, x + 1 to y, x to y - 1, x to y + 1).any { (a, b) -> a in 0 until 16 && b in 0 until 16 && copy[b * 16 + a] != 0 }
            if (near) px[y * 16 + x] = OUTLINE
        }
        put(cx - 1, cy - 1, 0xFFFFFFFF.toInt())
        put(cx - 2, cy - 1, 0xFFFFFFFF.toInt())
        return FollowerSprites.Sprite(arrayOf(px), 16, 1)
    }
}
