package me.magnum.melonds.pokewalker

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * The walking Pokémon exploring a [FieldMap.Scene] the way the game's wandering people do: a
 * step or a few one way, a look around, now and then a walk over to somewhere else in sight,
 * and a little hop when it's happy. It hops down ledges, goes and reads signs, sniffs at tall
 * grass and looks out over water. Its type shows: a Water type [swims], a Flying type [flies]
 * over to somewhere else, a Ghost type [fades] out and back in a few tiles away. It goes up to
 * doors (they open for it: see [SceneProps]). Its [mood] shows in how it walks and hops, and it
 * can sit down to rest or fall [asleep]. It can also be sent somewhere ([goTo]) or walked about
 * ([walk]). Times are milliseconds on any steady clock. Pure Kotlin, so it can be tested on its own.
 */
class MapWanderer(val scene: FieldMap.Scene, private val random: Random = Random.Default) {

    /** Where to draw it: a world position on the ground, which way it faces, its frame and how high it hops. */
    class Pose(
        val x: Double, val y: Double, val z: Double, val facing: Int, val frame: Int, val hop: Double, val moving: Boolean,
        /** Standing in tall grass (its feet are hidden in it). */
        val grass: Boolean = false,
        /** Swimming: only its top half shows above the water. */
        val inWater: Boolean = false,
        /** Up in the air: drawn over everything, its shadow on the ground below. */
        val aloft: Boolean = false,
        /** How solid it's drawn (1: fully). */
        val alpha: Float = 1f,
        /** How many pixels it's lowered into the ground, sitting or lying down (what goes under isn't drawn). */
        val sink: Int = 0,
        /** Pixels it's drawn to the side of its spot (a shake). */
        val dx: Int = 0,
        /** How tall it's drawn (1: as it is; more, stretching up), its feet where they are. */
        val stretch: Float = 1f,
    )

    /** What it just did that the map shows (an emote, a puff of dust), since last taken. */
    enum class Happening { SNIFF, READ, LANDED, SHOOK, DUG }

    /** Little things it does now and then, standing about. */
    enum class Trick {
        /** Stretches up tall. */
        STRETCH,
        /** Turns round on the spot. */
        SPIN,
        /** Shakes itself off (out of the water, in the rain). */
        SHAKE,
        /** Digs at the ground. */
        DIG,
        /** Looks up at the sky. */
        LOOK_UP,
        /** Hops up and down, pleased. */
        BOUNCE,
    }

    /** How it gets to the tile it's heading for. */
    enum class Move { WALK, JUMP, FLY, FADE }

    companion object {
        /** One tile's walk (the game's is 16 frames, about 270 ms; a stroll is a little slower). */
        const val STEP_MS = 320L
        const val HOP_MS = 380L
        /** How high a hop goes, in world units (about picture pixels). */
        const val HOP_HEIGHT = 9.0
        /** After being walked about by hand (or sent somewhere), how long before it goes off on its own again. */
        const val MANUAL_MS = 8_000L
        /** Hopping down a ledge: two tiles, a little arc. */
        const val JUMP_MS = 520L
        const val JUMP_HEIGHT = 12.0
        /** Flying over to somewhere: how high it goes. */
        const val FLY_HEIGHT = 44.0
        /** Fading out and back in somewhere else. */
        const val FADE_MS = 1_400L
        /** How long it stands reading a sign. */
        const val READ_MS = 2_600L
        /** The highest anything goes above its spot (for the painter's buffer). */
        const val MAX_LIFT = 48
        /** Running over to something (a treat). */
        const val RUN_MS = 190L
        /** Resting, it sits down once it's been standing this long. */
        const val SIT_AFTER_MS = 1_500L

        private val DX = intArrayOf(0, 0, -1, 1) // UP, DOWN, LEFT, RIGHT (FollowerSprites order)
        private val DY = intArrayOf(-1, 1, 0, 0)

        /** Turning round: down, left, up, right. */
        private val SPIN_ORDER = intArrayOf(FollowerSprites.DOWN, FollowerSprites.LEFT, FollowerSprites.UP, FollowerSprites.RIGHT)

        /** How long each [Trick] takes. */
        private val TRICK_MS = longArrayOf(1_100, 880, 700, 1_400, 1_800, 1_100)

        fun towards(from: FieldMap.Tile, t: FieldMap.Tile) = when {
            abs(t.gx - from.gx) >= abs(t.gy - from.gy) && t.gx > from.gx -> FollowerSprites.RIGHT
            abs(t.gx - from.gx) >= abs(t.gy - from.gy) && t.gx < from.gx -> FollowerSprites.LEFT
            t.gy > from.gy -> FollowerSprites.DOWN
            else -> FollowerSprites.UP
        }
    }

    var at: FieldMap.Tile = scene.start ?: scene.tiles.values.firstOrNull() ?: throw IllegalArgumentException("nowhere to walk")
        private set
    /** The tile it's stepping onto, if it's walking. */
    var next: FieldMap.Tile? = null
        private set
    /** The tile it last left (where whoever follows it steps to). */
    var previous: FieldMap.Tile? = null
        private set
    private var stepStart = 0L
    private var stepMs = STEP_MS
    var move = Move.WALK
        private set
    var facing = FollowerSprites.DOWN
        private set
    private var waitUntil = Long.MIN_VALUE
    private val path = ArrayDeque<FieldMap.Tile>()
    private var onTrip = false
    private var hopStart = Long.MIN_VALUE
    private var hopHeight = HOP_HEIGHT
    private var lastNow = Long.MIN_VALUE
    private var manualUntil = Long.MIN_VALUE
    private var heldDirection = -1
    private var queuedDirection = -1
    private var reading = false
    private var rushUntil = Long.MIN_VALUE
    private var nibbleStart = Long.MIN_VALUE
    private var nibbleUntil = Long.MIN_VALUE
    private var steeredUntil = Long.MIN_VALUE
    private var trick: Trick? = null
    private var trickStart = Long.MIN_VALUE
    private var trickMs = 0L
    private var trickFacing = FollowerSprites.DOWN

    /** What it did since last taken (bounded: the oldest go when nobody takes them). */
    val happenings = ArrayList<Happening>()

    private fun happened(h: Happening) {
        if (happenings.size >= 32) happenings.removeAt(0)
        happenings.add(h)
    }

    /** How long a step takes when it strolls (quicker when it's lively, slower resting). */
    var pace = STEP_MS

    /**
     * How it feels: -1 resting (it sits down when it stops, and mostly stays put), 0, 1 lively,
     * 2 very lively (it hops more).
     */
    var mood = 0

    /** Asleep: it lies facing the front and doesn't go anywhere until woken (set false). */
    var asleep = false
        set(value) {
            field = value
            if (value) {
                facing = FollowerSprites.DOWN
                path.clear()
                onTrip = false
                lookAt = null
                reading = false
                heldDirection = -1
                queuedDirection = -1
                trick = null
            }
        }

    /** Tiles it would rather not head for (say, the ones behind the cards on the screen). */
    var avoid: ((FieldMap.Tile) -> Boolean)? = null

    /** Tiles it can't step onto right now (someone's standing there). */
    var blocked: ((Int, Int) -> Boolean)? = null

    /** A Water type: it may go into the water and swim. */
    var swims = false
    /** A Flying type: now and then it flies over to somewhere else in sight. */
    var flies = false
    /** A Ghost type: now and then it fades out and back in a few tiles away. */
    var fades = false

    /** Kept where it is (a game has it fishing, say): it doesn't wander off on its own. */
    var held = false

    /** Where to look once the walk it was sent on ([goTo]) is over. */
    private var lookAt: FieldMap.Tile? = null

    /** Whether it's standing still, not walking, flying or fading. */
    val idle get() = next == null

    /** Moves things along to [now]. */
    fun update(now: Long) {
        if (lastNow == Long.MIN_VALUE || now < lastNow || now - lastNow > 5_000) {
            // first time, or back after a while: stand where it got to and think again
            next?.let { at = it }
            next = null
            move = Move.WALK
            path.clear()
            onTrip = false
            waitUntil = now + 300
        }
        lastNow = now
        repeat(4) {
            val n = next
            if (n != null) {
                if (now - stepStart < stepMs) return
                at = n
                next = null
                if (move == Move.JUMP || move == Move.FLY) happened(Happening.LANDED)
                move = Move.WALK
                val arrivedAt = stepStart + stepMs
                val wanted = if (queuedDirection >= 0) queuedDirection else heldDirection
                queuedDirection = -1
                if (wanted >= 0) {
                    if (tryStep(wanted, arrivedAt)) return@repeat
                    facing = wanted
                    heldDirection = -1
                    waitUntil = now + pause()
                    return
                }
                if (path.isNotEmpty()) {
                    val t = path.removeFirst()
                    if (neighbours(at, t)) { startStep(t, arrivedAt); return@repeat }
                    path.clear()
                    lookAt = null
                    reading = false
                }
                lookAt?.let { facing = towards(at, it); lookAt = null }
                if (reading) happened(Happening.READ)
                if (onTrip && !reading && random.nextInt(if (mood >= 2) 2 else if (mood == 1) 3 else 4) == 0) hop(now)
                onTrip = false
                waitUntil = now + if (reading) READ_MS else pause()
                reading = false
                return
            }
            if (held || asleep || now < waitUntil || now < manualUntil) return
            decide(now)
            if (next == null) return
        }
    }

    /** Where and how to draw it at [now] (call [update] first). */
    fun pose(now: Long): Pose {
        var x = at.x
        var y = at.y
        var z = at.z
        val n = next
        var on = at
        var arc = 0.0
        var alpha = 1f
        var aloft = false
        if (n != null) {
            val t = ((now - stepStart).toDouble() / stepMs).coerceIn(0.0, 1.0)
            if (move == Move.FADE) {
                // out where it was, back in where it's going
                if (t >= 0.5) { x = n.x; y = n.y; z = n.z; on = n }
                alpha = abs(1 - 2 * t).toFloat().coerceAtLeast(0.04f)
            } else {
                x += (n.x - at.x) * t
                y += (n.y - at.y) * t
                z += (n.z - at.z) * t
                if (t > 0.5) on = n
                when (move) {
                    Move.JUMP -> arc = sin(PI * t) * JUMP_HEIGHT
                    Move.FLY -> { arc = sin(PI * t) * FLY_HEIGHT; aloft = arc > 10 }
                    else -> {}
                }
            }
        }
        val water = isWater(on)
        // walking: a step every 160 ms (flapping faster); standing: the slow step in place the game's followers do; asleep, still
        val frame = when {
            n != null && move == Move.FLY -> ((now / 90) % 2).toInt()
            n != null && move != Move.FADE -> ((now / 160) % 2).toInt()
            asleep -> 0
            else -> ((now / 480) % 2).toInt()
        }
        val u = (now - hopStart).toDouble() / HOP_MS
        var hop = if (hopStart != Long.MIN_VALUE && u >= 0.0 && u <= 1.0) sin(PI * u) * hopHeight else 0.0
        // eating: little bobs
        if (now in nibbleStart until nibbleUntil) hop = maxOf(hop, abs(sin(PI * (now - nibbleStart) / 320.0)) * 2.5)
        hop = maxOf(hop, arc)
        // a trick it's doing, standing about
        var face = facing
        var dx = 0
        var stretch = 1f
        var shown = frame
        val tr = trick
        if (tr != null && n == null && !asleep && now >= trickStart && now < trickStart + trickMs) {
            val age = now - trickStart
            val u = age.toDouble() / trickMs
            when (tr) {
                Trick.STRETCH -> stretch = 1f + 0.16f * sin(PI * u).toFloat()
                Trick.SPIN -> face = SPIN_ORDER[(SPIN_ORDER.indexOf(trickFacing) + (age / 110).toInt()) % 4]
                Trick.SHAKE -> { dx = if ((age / 45) % 2 == 0L) -1 else 1; hop = maxOf(hop, 1.0) }
                Trick.DIG -> { shown = ((age / 90) % 2).toInt(); hop = maxOf(hop, abs(sin(PI * age / 180.0)) * 1.5) }
                Trick.LOOK_UP -> face = FollowerSprites.UP
                Trick.BOUNCE -> hop = maxOf(hop, abs(sin(PI * age / 260.0)) * 5.0)
            }
        }
        // lying down asleep (breathing), or sitting down to rest
        val sink = when {
            n != null || water || hop > 0.5 -> 0
            asleep -> if ((now / 1_400) % 2 == 0L) 4 else 3
            mood < 0 && now - (stepStart + stepMs) > SIT_AFTER_MS -> 2
            else -> 0
        }
        return Pose(x, y, z, face, shown, hop, n != null && move != Move.FADE,
            grass = on.tallGrass && !water, inWater = water && arc < 4, aloft = aloft, alpha = alpha, sink = sink, dx = dx, stretch = stretch)
    }

    /**
     * Does [t] where it stands for [ms] (it stays put meanwhile); false when it's busy walking,
     * asleep or kept where it is.
     */
    fun perform(t: Trick, now: Long, ms: Long = TRICK_MS[t.ordinal]): Boolean {
        if (next != null || asleep) return false
        trick = t
        trickStart = now
        trickMs = ms
        trickFacing = facing
        pauseUntil(now + ms + 300)
        when (t) {
            Trick.SHAKE -> happened(Happening.SHOOK)
            Trick.DIG -> happened(Happening.DUG)
            else -> {}
        }
        return true
    }

    /** The trick it's doing at [now], if any. */
    fun trickAt(now: Long): Trick? = trick?.takeIf { next == null && now >= trickStart && now < trickStart + trickMs }

    /** Whether it's being walked about by hand right now (it goes back to roaming [MANUAL_MS] after the last press). */
    fun steered(now: Long) = now < steeredUntil

    private fun isWater(t: FieldMap.Tile) = scene.tile(t.gx, t.gy) == null && scene.water.containsKey(FieldMap.Scene.key(t.gx, t.gy))

    /** Puts it standing on [tile]. */
    fun placeAt(tile: FieldMap.Tile) {
        at = tile
        next = null
        move = Move.WALK
        path.clear()
        onTrip = false
        lookAt = null
        reading = false
    }

    /** A happy little hop. */
    fun hop(now: Long) {
        if (hopStart == Long.MIN_VALUE || now - hopStart > HOP_MS) { hopStart = now; hopHeight = HOP_HEIGHT }
    }

    /** A small bob, as when sniffing at something. */
    private fun nod(now: Long) {
        if (hopStart == Long.MIN_VALUE || now - hopStart > HOP_MS) { hopStart = now; hopHeight = 3.0 }
    }

    /** Eating something: little bobs for [ms]. */
    fun nibble(now: Long, ms: Long) {
        nibbleStart = now
        nibbleUntil = now + ms
    }

    /** Runs (rather than strolls) wherever it goes until [until]. */
    fun rush(until: Long) {
        rushUntil = until
    }

    /** Whether it can walk to [target] from where it is (within [limit] steps). */
    fun canReach(target: FieldMap.Tile, limit: Int = 40): Boolean = route(next ?: at, target, limit) != null

    /** Turns to look [direction]. */
    fun face(direction: Int) {
        if (next == null) facing = direction.coerceIn(0, 3)
    }

    /** Waits where it is until [until] before doing anything on its own. */
    fun pauseUntil(until: Long) {
        waitUntil = maxOf(waitUntil, until)
    }

    /**
     * Sends it to [target] by the shortest way it can walk, to look at [look] once there; false
     * when it can't get there.
     */
    fun goTo(target: FieldMap.Tile, now: Long, look: FieldMap.Tile? = null): Boolean {
        // from the tile it's stepping onto, if it's on its way somewhere
        val route = route(next ?: at, target, 80) ?: return false
        manualUntil = now + MANUAL_MS
        trick = null
        heldDirection = -1
        queuedDirection = -1
        path.clear()
        reading = false
        lookAt = look
        if (route.isEmpty()) {
            if (look != null && next == null) { facing = towards(at, look); lookAt = null } else if (look == null) hop(now)
            return true
        }
        path.addAll(route)
        onTrip = true
        if (next == null) startStep(path.removeFirst(), now)
        return true
    }

    /**
     * Walks a step [direction] (UP, DOWN, LEFT, RIGHT); keeps going while [held]. Facing a wall
     * it just turns that way, like the game's people do.
     */
    fun walk(direction: Int, held: Boolean, now: Long) {
        manualUntil = now + MANUAL_MS
        steeredUntil = now + MANUAL_MS
        trick = null
        path.clear()
        onTrip = false
        lookAt = null
        reading = false
        heldDirection = if (held) direction else -1
        if (next != null) {
            // one more step that way once this one's done
            queuedDirection = direction
        } else if (!tryStep(direction, now)) {
            facing = direction
        }
    }

    /** Lets go of the direction being held. */
    fun release() {
        heldDirection = -1
    }

    /** Stops being walked about by hand: it goes back to roaming on its own after a moment. */
    fun letGo(now: Long) {
        heldDirection = -1
        queuedDirection = -1
        steeredUntil = Long.MIN_VALUE
        manualUntil = minOf(manualUntil, now + 600)
    }

    /** The tile in front of it (the way it's facing), if there's one it could stand on or something there. */
    fun facingTile(): Pair<Int, Int> = (at.gx + DX[facing]) to (at.gy + DY[facing])

    // ---- making up its mind -------------------------------------------------------------------

    private fun decide(now: Long) {
        if (flies && mood >= 0 && random.nextInt(100) < 14 && fly(now)) return
        if (fades && mood >= 0 && random.nextInt(100) < 12 && fade(now)) return
        val roll = random.nextInt(100)
        when {
            // resting: it mostly stays sitting where it is, now and then a look round or a few steps
            mood < 0 -> when {
                roll < 65 -> { lookAround(now); waitUntil += 1_500 }
                roll < 85 -> wander(now)
                else -> sniff(now)
            }
            roll < 42 -> wander(now)
            roll < 60 -> lookAround(now)
            roll < 82 -> trip(now)
            roll < 88 -> if (!visitSomething(now)) trip(now)
            // lively, it hops about more
            roll < 95 - 4 * mood.coerceIn(0, 2) -> sniff(now)
            else -> { hop(now); waitUntil = now + 700 + random.nextInt(600) }
        }
    }

    private fun pause() = (500L + random.nextInt(1700)) * (if (mood < 0) 2 else 1)

    /** Over to a sign to read, or to a door. */
    private fun visitSomething(now: Long): Boolean {
        if (scene.doors.isEmpty()) return readSign(now)
        return if (random.nextBoolean()) visitDoor(now) || readSign(now) else readSign(now) || visitDoor(now)
    }

    private fun lookAround(now: Long) {
        var d = random.nextInt(4)
        if (d == facing) d = (d + 1 + random.nextInt(3)) % 4
        facing = d
        waitUntil = now + 600 + random.nextInt(1100)
    }

    /** A few steps one way, most often the way it's facing. */
    private fun wander(now: Long) {
        val dir = if (random.nextInt(10) < 6) facing else random.nextInt(4)
        val n = 1 + random.nextInt(3)
        path.clear()
        var cur = at
        for (i in 0 until n) {
            val t = step(cur, dir) ?: break
            path.add(t)
            cur = t
        }
        if (path.isEmpty() || (avoid?.invoke(cur) == true && random.nextInt(4) != 0)) {
            path.clear()
            facing = dir
            waitUntil = now + 400 + random.nextInt(800)
            return
        }
        startStep(path.removeFirst(), now)
    }

    /** Over to somewhere else in sight it can walk to. */
    private fun trip(now: Long) {
        val seen = reachable(at, 14)
        val far = seen.filter { (t, d) -> d >= 3 && avoid?.invoke(t) != true }.keys.toList()
        val choice = (far.ifEmpty { seen.filter { it.value >= 2 }.keys.toList() }).randomOrNull(random)
        val route = choice?.let { route(at, it, 30) }
        if (route.isNullOrEmpty()) { wander(now); return }
        path.clear()
        path.addAll(route)
        onTrip = true
        startStep(path.removeFirst(), now)
    }

    /** Over to a sign it can get to, to stand and read it. */
    private fun readSign(now: Long): Boolean {
        if (scene.signs.isEmpty()) return false
        val seen = reachable(at, 16)
        var best: Pair<FieldMap.Tile, FieldMap.Tile>? = null
        var bestD = Int.MAX_VALUE
        for (s in scene.signs) {
            // read from the front (south of it) if it can, else from the side
            for (k in 0 until 4) {
                val dir = intArrayOf(FollowerSprites.DOWN, FollowerSprites.LEFT, FollowerSprites.RIGHT, FollowerSprites.UP)[k]
                val t = scene.tile(s.gx + DX[dir], s.gy + DY[dir]) ?: continue
                val d = seen[t] ?: continue
                if (avoid?.invoke(t) == true) continue
                val cost = d + k * 3
                if (cost < bestD) { bestD = cost; best = t to s }
                break
            }
        }
        val (spot, sign) = best ?: return false
        val route = route(at, spot, 30) ?: return false
        path.clear()
        lookAt = sign
        reading = true
        if (route.isEmpty()) {
            facing = towards(at, sign)
            lookAt = null
            reading = false
            waitUntil = now + READ_MS
            happened(Happening.READ)
            return true
        }
        path.addAll(route)
        onTrip = true
        startStep(path.removeFirst(), now)
        return true
    }

    /** Up to a door it can get to, to stand in front of it (the door opens: see [SceneProps]). */
    private fun visitDoor(now: Long): Boolean {
        if (scene.doors.isEmpty()) return false
        val seen = reachable(at, 16)
        var best: Pair<FieldMap.Tile, FieldMap.Tile>? = null
        var bestD = Int.MAX_VALUE
        for ((door, _) in scene.doors) {
            val front = scene.tile(door.gx, door.gy + 1) ?: continue
            val d = seen[front] ?: continue
            if (d == 0 || avoid?.invoke(front) == true) continue
            if (d < bestD) { bestD = d; best = front to door }
        }
        val (front, door) = best ?: return false
        val route = route(at, front, 30) ?: return false
        if (route.isEmpty()) return false
        path.clear()
        path.addAll(route)
        lookAt = door
        onTrip = true
        startStep(path.removeFirst(), now)
        return true
    }

    /** Next to tall grass or water: it turns to it and has a sniff. */
    private fun sniff(now: Long) {
        val dirs = (0 until 4).filter { d ->
            val gx = at.gx + DX[d]
            val gy = at.gy + DY[d]
            scene.tile(gx, gy)?.tallGrass == true && !at.tallGrass || (scene.tile(gx, gy) == null && scene.water.containsKey(FieldMap.Scene.key(gx, gy)))
        }
        if (dirs.isEmpty()) { lookAround(now); return }
        facing = dirs[random.nextInt(dirs.size)]
        nod(now)
        happened(Happening.SNIFF)
        waitUntil = now + 1_200 + random.nextInt(1_200)
    }

    /** Off into the air and down somewhere else in sight. */
    private fun fly(now: Long): Boolean {
        val options = scene.tiles.values.filter { t ->
            val d = abs(t.gx - at.gx) + abs(t.gy - at.gy)
            d in 5..14 && blocked?.invoke(t.gx, t.gy) != true && avoid?.invoke(t) != true
        }
        val t = options.randomOrNull(random) ?: return false
        facing = towards(at, t)
        startMove(t, now, Move.FLY, (450L + (abs(t.gx - at.gx) + abs(t.gy - at.gy)) * 110L).coerceAtMost(2_000L))
        return true
    }

    /** Out of sight and back a few tiles away. */
    private fun fade(now: Long): Boolean {
        val options = scene.tiles.values.filter { t ->
            val d = abs(t.gx - at.gx) + abs(t.gy - at.gy)
            d in 3..8 && blocked?.invoke(t.gx, t.gy) != true && avoid?.invoke(t) != true
        }
        val t = options.randomOrNull(random) ?: return false
        startMove(t, now, Move.FADE, FADE_MS)
        return true
    }

    // ---- moving ---------------------------------------------------------------------------------

    private fun tryStep(direction: Int, now: Long): Boolean {
        val t = step(at, direction) ?: return false
        startStep(t, now)
        return true
    }

    private fun startStep(t: FieldMap.Tile, now: Long) {
        facing = towards(at, t)
        val jump = abs(t.gx - at.gx) + abs(t.gy - at.gy) == 2
        startMove(t, now, if (jump) Move.JUMP else Move.WALK, if (jump) JUMP_MS else if (now < rushUntil) RUN_MS else pace)
    }

    private fun startMove(t: FieldMap.Tile, now: Long, how: Move, ms: Long) {
        previous = at
        next = t
        move = how
        stepMs = ms
        stepStart = now
    }

    /** A tile it may stand on: ground, or water when it swims. */
    private fun tileAt(gx: Int, gy: Int): FieldMap.Tile? {
        scene.tile(gx, gy)?.let { return it }
        return if (swims) scene.water[FieldMap.Scene.key(gx, gy)] else null
    }

    /** Whether it can go from [a] to the neighbouring [b]: a step on land, or in and out of the water. */
    private fun fits(a: FieldMap.Tile, b: FieldMap.Tile): Boolean =
        if (isWater(a) || isWater(b)) abs(a.y - b.y) <= 24.0 else FieldMap.stepOk(a, b)

    /** The tile a step [direction] from [from] takes it to, if it can go there (two tiles down a ledge). */
    private fun step(from: FieldMap.Tile, direction: Int): FieldMap.Tile? {
        val gx = from.gx + DX[direction]
        val gy = from.gy + DY[direction]
        val t = tileAt(gx, gy)
        if (t != null) {
            if (blocked?.invoke(t.gx, t.gy) == true) return null
            return if (fits(from, t)) t else null
        }
        // a ledge it can hop down: over it to the ground beyond
        if (scene.ledges[FieldMap.Scene.key(gx, gy)] == direction && !isWater(from)) {
            val land = scene.tile(gx + DX[direction], gy + DY[direction]) ?: return null
            if (blocked?.invoke(land.gx, land.gy) == true) return null
            if (land.y > from.y + 2.0) return null
            return land
        }
        return null
    }

    private fun neighbours(a: FieldMap.Tile, b: FieldMap.Tile): Boolean {
        val d = abs(a.gx - b.gx) + abs(a.gy - b.gy)
        if (d == 1) return fits(a, b) && blocked?.invoke(b.gx, b.gy) != true
        if (d == 2 && (a.gx == b.gx || a.gy == b.gy)) {
            val dir = when {
                b.gx > a.gx -> FollowerSprites.RIGHT
                b.gx < a.gx -> FollowerSprites.LEFT
                b.gy > a.gy -> FollowerSprites.DOWN
                else -> FollowerSprites.UP
            }
            return step(a, dir)?.let { it.gx == b.gx && it.gy == b.gy } == true
        }
        return false
    }

    /** Tiles it can walk to from [from] within [limit] steps, with how many steps. */
    private fun reachable(from: FieldMap.Tile, limit: Int): Map<FieldMap.Tile, Int> {
        // in the order they're found (not by the tiles' identity hashes), so it goes the same way each run
        val dist = LinkedHashMap<FieldMap.Tile, Int>()
        val queue = ArrayDeque<FieldMap.Tile>()
        dist[from] = 0
        queue.add(from)
        while (queue.isNotEmpty()) {
            val a = queue.removeFirst()
            val d = dist.getValue(a)
            if (d >= limit) continue
            for (dir in 0 until 4) {
                val b = step(a, dir) ?: continue
                if (b in dist) continue
                dist[b] = d + 1
                queue.add(b)
            }
        }
        return dist
    }

    /** The steps from [from] to [to] (not including [from]), or null when it can't get there in [limit]. */
    private fun route(from: FieldMap.Tile, to: FieldMap.Tile, limit: Int): List<FieldMap.Tile>? {
        if (from === to || (from.gx == to.gx && from.gy == to.gy)) return emptyList()
        val parent = HashMap<FieldMap.Tile, FieldMap.Tile>()
        val dist = HashMap<FieldMap.Tile, Int>()
        val queue = ArrayDeque<FieldMap.Tile>()
        dist[from] = 0
        queue.add(from)
        while (queue.isNotEmpty()) {
            val a = queue.removeFirst()
            if (a.gx == to.gx && a.gy == to.gy) {
                val out = ArrayList<FieldMap.Tile>()
                var c = a
                while (c !== from) { out.add(c); c = parent.getValue(c) }
                out.reverse()
                return out
            }
            val d = dist.getValue(a)
            if (d >= limit) continue
            for (dir in 0 until 4) {
                val b = step(a, dir) ?: continue
                if (b in dist) continue
                dist[b] = d + 1
                parent[b] = a
                queue.add(b)
            }
        }
        return null
    }
}

/**
 * A second Pokémon following the walking one around, the way the game's following Pokémon trail
 * the player: into the tile it just left, catching up by the shortest way when it falls behind
 * (and, left far behind, fading back in beside it). It keeps to the ground. Pure Kotlin.
 */
class MapBuddy(val scene: FieldMap.Scene, start: FieldMap.Tile) {
    var at: FieldMap.Tile = start
        private set
    var next: FieldMap.Tile? = null
        private set
    var facing = FollowerSprites.DOWN
        private set
    private var stepStart = 0L
    private var stepMs = MapWanderer.STEP_MS
    private var fading = false
    private var lastNow = Long.MIN_VALUE

    /** Asleep (when the one it follows is): it lies still facing the front. */
    var asleep = false

    private val dx = intArrayOf(0, 0, -1, 1)
    private val dy = intArrayOf(-1, 1, 0, 0)

    /** Moves it along to [now], after [leader]. [blocked] says where someone else is standing. */
    fun update(now: Long, leader: MapWanderer, blocked: (Int, Int) -> Boolean = { _, _ -> false }) {
        if (lastNow == Long.MIN_VALUE || now < lastNow || now - lastNow > 5_000) {
            next?.let { at = it }
            next = null
            fading = false
        }
        lastNow = now
        val n = next
        if (n != null) {
            if (now - stepStart < stepMs) return
            at = n
            next = null
            fading = false
        }
        if (asleep) return
        val lead = leader.next ?: leader.at
        val d = dist(at, lead)
        if (d == 0 && leader.next == null) {
            // it walked back onto us: step aside
            val aside = (0 until 4).mapNotNull { scene.tile(at.gx + dx[it], at.gy + dy[it]) }
                .firstOrNull { FieldMap.stepOk(at, it) && !blocked(it.gx, it.gy) }
            if (aside != null) start(aside, now, MapWanderer.STEP_MS)
            return
        }
        if (d <= 1) {
            if (leader.next == null) facing = MapWanderer.towards(at, lead)
            return
        }
        // where the leader just was, if that's next to us; else the way there
        val trail = leader.at.takeIf { leader.next != null && dist(at, it) == 1 && scene.tile(it.gx, it.gy) != null }
        val target = trail?.takeIf { FieldMap.stepOk(at, it) && !blocked(it.gx, it.gy) }
            ?: firstStepTowards(lead, blocked)
        if (target != null) {
            start(target, now, if (d > 3) 240L else MapWanderer.STEP_MS)
        } else if (d > 6 && leader.next == null) {
            // left far behind (it flew, swam or jumped off): back in beside it
            val beside = (0 until 4).mapNotNull { scene.tile(lead.gx + dx[it], lead.gy + dy[it]) }
                .filter { !blocked(it.gx, it.gy) }
                .minByOrNull { dist(it, at) }
            if (beside != null) {
                start(beside, now, MapWanderer.FADE_MS)
                fading = true
            }
        }
    }

    private fun start(t: FieldMap.Tile, now: Long, ms: Long) {
        facing = MapWanderer.towards(at, t)
        next = t
        stepStart = now
        stepMs = ms
    }

    private fun dist(a: FieldMap.Tile, b: FieldMap.Tile) = abs(a.gx - b.gx) + abs(a.gy - b.gy)

    /** The first step on the shortest way on the ground to beside [goal]. */
    private fun firstStepTowards(goal: FieldMap.Tile, blocked: (Int, Int) -> Boolean): FieldMap.Tile? {
        val parent = HashMap<FieldMap.Tile, FieldMap.Tile>()
        val queue = ArrayDeque<FieldMap.Tile>()
        val seen = HashSet<FieldMap.Tile>()
        queue.add(at)
        seen.add(at)
        var steps = 0
        while (queue.isNotEmpty() && steps < 2_000) {
            steps++
            val a = queue.removeFirst()
            if (a !== at && dist(a, goal) <= 1) {
                var c = a
                while (parent[c] !== at) c = parent.getValue(c)
                return c
            }
            for (k in 0 until 4) {
                val b = scene.tile(a.gx + dx[k], a.gy + dy[k]) ?: continue
                if (b in seen || !FieldMap.stepOk(a, b) || blocked(b.gx, b.gy)) continue
                if (b.gx == goal.gx && b.gy == goal.gy) continue
                seen.add(b)
                parent[b] = a
                queue.add(b)
            }
        }
        return null
    }

    fun pose(now: Long): MapWanderer.Pose {
        var x = at.x
        var y = at.y
        var z = at.z
        val n = next
        var on = at
        var alpha = 1f
        if (n != null) {
            val t = ((now - stepStart).toDouble() / stepMs).coerceIn(0.0, 1.0)
            if (fading) {
                if (t >= 0.5) { x = n.x; y = n.y; z = n.z; on = n }
                alpha = abs(1 - 2 * t).toFloat().coerceAtLeast(0.04f)
            } else {
                x += (n.x - at.x) * t
                y += (n.y - at.y) * t
                z += (n.z - at.z) * t
                if (t > 0.5) on = n
            }
        }
        if (asleep && n == null) {
            // lying down, breathing (out of step with the other one)
            return MapWanderer.Pose(x, y, z, FollowerSprites.DOWN, 0, 0.0, false, grass = on.tallGrass,
                sink = if ((now / 1_400 + 1) % 2 == 0L) 4 else 3)
        }
        val frame = if (n != null && !fading) ((now / 160) % 2).toInt() else ((now / 480 + 1) % 2).toInt()
        return MapWanderer.Pose(x, y, z, facing, frame, 0.0, n != null, grass = on.tallGrass, alpha = alpha)
    }
}

/**
 * Draws the walking Pokémon in the picture's own pixels: its overworld sprite standing on the
 * ground with a little shadow, hidden wherever the scene has something nearer (a tree in front,
 * a roof). Fills [buffer], which covers [bufferWidth] x [bufferHeight] picture pixels from
 * ([left], [top]).
 */
class MapSpritePainter(val scene: FieldMap.Scene, val sprite: FollowerSprites.Sprite) {
    val size = sprite.size
    // the shadow's half width and height
    private val rx = size * 0.28f
    private val ry = (size * 0.09f).coerceAtLeast(2f)
    private val below = ry.toInt() + 2
    val bufferWidth = size + 8
    val bufferHeight = size + MapWanderer.MAX_LIFT + below + 2
    val buffer = IntArray(bufferWidth * bufferHeight)
    var left = 0
        private set
    var top = 0
        private set

    /** Rows of empty space under the feet in the sprite's frames. */
    private val feet: Int = run {
        var lowest = 0
        for (f in sprite.frames) for (y in size - 1 downTo 0) {
            if ((0 until size).any { f[y * size + it] ushr 24 != 0 }) { if (y > lowest) lowest = y; break }
        }
        size - 1 - lowest
    }

    /** Rows of empty space over the head. */
    private val headroom: Int = run {
        var highest = size
        for (f in sprite.frames) for (y in 0 until size) {
            if ((0 until size).any { f[y * size + it] ushr 24 != 0 }) { if (y < highest) highest = y; break }
        }
        highest.coerceAtMost(size - 1)
    }

    /** Where the sprite was last drawn (its top left, in picture pixels), for taps and things drawn over it. */
    var spriteLeft = 0
        private set
    var spriteTop = 0
        private set

    /** Rows of empty space over the head of the sprite. */
    val headSpace get() = headroom

    /** Whether it casts a shadow (not someone swimming). */
    var shadow = true

    /** Fills [buffer] for [pose]; false when the Pokémon is out of the picture. */
    fun paint(pose: MapWanderer.Pose): Boolean {
        val a = scene.project(pose.x, pose.y, pose.z) ?: return false
        // the sprite stands up from the ground, a little in front of its spot
        val depth = (scene.project(pose.x, pose.y, pose.z + 6.0) ?: a)[2]
        val ax = a[0].roundToInt()
        val ay = a[1].roundToInt()
        buffer.fill(0)
        left = ax - bufferWidth / 2
        top = ay + below + 1 - bufferHeight
        val w = scene.width
        val h = scene.height
        fun visible(px: Int, py: Int) = px in 0 until w && py in 0 until h && scene.depth[py * w + px] <= depth * 1.0008f
        val alpha = pose.alpha.coerceIn(0f, 1f)

        // swimming, only the top shows over the water; in tall grass its feet (and shadow) are hidden, as in the game, unless it hops out
        val body = size - feet - headroom
        val cutLine = when {
            pose.inWater -> ay - (body * 0.45f).roundToInt()
            pose.grass && pose.hop < 3.0 -> ay - 5
            // sitting or lying down: lowered, and what's under the ground isn't drawn
            pose.sink > 0 -> ay
            else -> Int.MAX_VALUE
        }
        // the shadow on the ground
        if (shadow && !pose.inWater && (cutLine == Int.MAX_VALUE || (pose.sink > 0 && !pose.grass))) for (dy in -ry.toInt()..ry.toInt()) for (dx in -rx.toInt()..rx.toInt()) {
            if ((dx / rx) * (dx / rx) + (dy / ry) * (dy / ry) > 1f) continue
            val px = ax + dx
            val py = ay + dy
            if (!visible(px, py)) continue
            val bx = px - left
            val by = py - top
            if (bx in 0 until bufferWidth && by in 0 until bufferHeight) buffer[by * bufferWidth + bx] = shade(0x48000000, alpha * if (pose.aloft) 0.6f else 1f)
        }

        // the Pokémon, feet on the spot (stretched up from its feet, or shaken to the side)
        val frame = sprite.frame(pose.facing, pose.frame)
        spriteLeft = ax - size / 2 + pose.dx
        spriteTop = ay + 1 + feet - size - pose.hop.roundToInt() + pose.sink
        val stretch = pose.stretch.coerceIn(0.5f, 1.5f)
        val drawn = if (stretch == 1f) size else (size * stretch).roundToInt().coerceIn(1, size + MapWanderer.MAX_LIFT / 2)
        val bottomRow = spriteTop + size - 1
        var any = false
        for (d in 0 until drawn) {
            val fromBottom = drawn - 1 - d
            val y = if (drawn == size) d else size - 1 - (fromBottom / stretch).toInt().coerceIn(0, size - 1)
            val py = bottomRow - fromBottom
            if (py > cutLine) continue
            for (x in 0 until size) {
                val c = frame[y * size + x]
                if (c ushr 24 == 0) continue
                val px = spriteLeft + x
                if (pose.aloft) { if (px !in 0 until w || py !in 0 until h) continue } else if (!visible(px, py)) continue
                val bx = px - left
                val by = py - top
                if (bx in 0 until bufferWidth && by in 0 until bufferHeight) {
                    buffer[by * bufferWidth + bx] = if (alpha >= 1f) c else shade(c, alpha)
                    any = true
                }
            }
        }
        // a ring of water where it swims
        if (pose.inWater) {
            val half = (body * 0.38f).roundToInt().coerceAtLeast(4)
            for (dx in -half..half) {
                val px = ax + dx
                val py = cutLine + 1 + if ((dx + (pose.frame * 2)) % 4 == 0) 1 else 0
                if (!visible(px, py)) continue
                val bx = px - left
                val by = py - top
                if (bx in 0 until bufferWidth && by in 0 until bufferHeight) buffer[by * bufferWidth + bx] = shade(0xC8E8F6FF.toInt(), alpha)
            }
        }
        return any || a[0] in 0f..w.toFloat() && a[1] in 0f..h.toFloat()
    }

    private fun shade(c: Int, k: Float): Int = ((((c ushr 24) * k).toInt().coerceIn(0, 255)) shl 24) or (c and 0xFFFFFF)

    /** Whether picture position ([px], [py]) is on the Pokémon as last painted (with some room to spare). */
    fun hit(px: Float, py: Float): Boolean {
        val pad = size * 0.15f
        return px >= spriteLeft - pad && px <= spriteLeft + size + pad && py >= spriteTop - pad && py <= spriteTop + size + pad
    }
}
