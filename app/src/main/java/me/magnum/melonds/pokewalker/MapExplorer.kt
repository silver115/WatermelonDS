package me.magnum.melonds.pokewalker

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * The walking Pokémon exploring a [FieldMap.Scene] the way the game's wandering people do: a
 * step or a few one way, a look around, now and then a walk over to somewhere else in sight,
 * and a little hop when it's happy. It can also be sent somewhere ([goTo]) or walked about
 * ([walk]). Times are milliseconds on any steady clock. Pure Kotlin, so it can be tested on its own.
 */
class MapWanderer(val scene: FieldMap.Scene, private val random: Random = Random.Default) {

    /** Where to draw it: a world position on the ground, which way it faces, its frame and how high it hops. */
    class Pose(val x: Double, val y: Double, val z: Double, val facing: Int, val frame: Int, val hop: Double, val moving: Boolean)

    companion object {
        /** One tile's walk (the game's is 16 frames, about 270 ms; a stroll is a little slower). */
        const val STEP_MS = 320L
        const val HOP_MS = 380L
        /** How high a hop goes, in world units (about picture pixels). */
        const val HOP_HEIGHT = 9.0
        /** After being walked about by hand, how long before it goes off on its own again. */
        const val MANUAL_MS = 6_000L

        private val DX = intArrayOf(0, 0, -1, 1) // UP, DOWN, LEFT, RIGHT (FollowerSprites order)
        private val DY = intArrayOf(-1, 1, 0, 0)
    }

    var at: FieldMap.Tile = scene.start ?: scene.tiles.values.firstOrNull() ?: throw IllegalArgumentException("nowhere to walk")
        private set
    private var next: FieldMap.Tile? = null
    private var stepStart = 0L
    var facing = FollowerSprites.DOWN
        private set
    private var waitUntil = Long.MIN_VALUE
    private val path = ArrayDeque<FieldMap.Tile>()
    private var onTrip = false
    private var hopStart = Long.MIN_VALUE
    private var lastNow = Long.MIN_VALUE
    private var manualUntil = Long.MIN_VALUE
    private var heldDirection = -1
    private var queuedDirection = -1

    /** Tiles it would rather not head for (say, the ones behind the cards on the screen). */
    var avoid: ((FieldMap.Tile) -> Boolean)? = null

    /** Moves things along to [now]. */
    fun update(now: Long) {
        if (lastNow == Long.MIN_VALUE || now < lastNow || now - lastNow > 5_000) {
            // first time, or back after a while: stand where it got to and think again
            next?.let { at = it }
            next = null
            path.clear()
            onTrip = false
            waitUntil = now + 300
        }
        lastNow = now
        repeat(4) {
            val n = next
            if (n != null) {
                if (now - stepStart < STEP_MS) return
                at = n
                next = null
                val arrivedAt = stepStart + STEP_MS
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
                }
                if (onTrip && random.nextInt(4) == 0) hop(now)
                onTrip = false
                waitUntil = now + pause()
                return
            }
            if (now < waitUntil || now < manualUntil) return
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
        if (n != null) {
            val t = ((now - stepStart).toDouble() / STEP_MS).coerceIn(0.0, 1.0)
            x += (n.x - at.x) * t
            y += (n.y - at.y) * t
            z += (n.z - at.z) * t
        }
        // walking: a step every 160 ms; standing: the slow step in place the game's followers do
        val frame = if (n != null) ((now / 160) % 2).toInt() else ((now / 480) % 2).toInt()
        val u = (now - hopStart).toDouble() / HOP_MS
        val hop = if (hopStart != Long.MIN_VALUE && u >= 0.0 && u <= 1.0) sin(PI * u) * HOP_HEIGHT else 0.0
        return Pose(x, y, z, facing, frame, hop, n != null)
    }

    /** Puts it standing on [tile]. */
    fun placeAt(tile: FieldMap.Tile) {
        at = tile
        next = null
        path.clear()
        onTrip = false
    }

    /** A happy little hop. */
    fun hop(now: Long) {
        if (hopStart == Long.MIN_VALUE || now - hopStart > HOP_MS) hopStart = now
    }

    /** Sends it to [target] by the shortest way it can walk; false when it can't get there. */
    fun goTo(target: FieldMap.Tile, now: Long): Boolean {
        // from the tile it's stepping onto, if it's on its way somewhere
        val route = route(next ?: at, target, 80) ?: return false
        manualUntil = now + MANUAL_MS
        heldDirection = -1
        queuedDirection = -1
        path.clear()
        if (route.isEmpty()) { hop(now); return true }
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
        path.clear()
        onTrip = false
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

    // ---- making up its mind -------------------------------------------------------------------

    private fun decide(now: Long) {
        val roll = random.nextInt(100)
        when {
            roll < 48 -> wander(now)
            roll < 70 -> lookAround(now)
            roll < 94 -> trip(now)
            else -> { hop(now); waitUntil = now + 700 + random.nextInt(600) }
        }
    }

    private fun pause() = 500L + random.nextInt(1700)

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

    // ---- moving ---------------------------------------------------------------------------------

    private fun tryStep(direction: Int, now: Long): Boolean {
        val t = step(at, direction) ?: return false
        startStep(t, now)
        return true
    }

    private fun startStep(t: FieldMap.Tile, now: Long) {
        facing = when {
            t.gx > at.gx -> FollowerSprites.RIGHT
            t.gx < at.gx -> FollowerSprites.LEFT
            t.gy > at.gy -> FollowerSprites.DOWN
            else -> FollowerSprites.UP
        }
        next = t
        stepStart = now
    }

    /** The tile a step [direction] from [from], if it can go there. */
    private fun step(from: FieldMap.Tile, direction: Int): FieldMap.Tile? {
        val t = scene.tile(from.gx + DX[direction], from.gy + DY[direction]) ?: return null
        return if (FieldMap.stepOk(from, t)) t else null
    }

    private fun neighbours(a: FieldMap.Tile, b: FieldMap.Tile) =
        abs(a.gx - b.gx) + abs(a.gy - b.gy) == 1 && FieldMap.stepOk(a, b)

    /** Tiles it can walk to from [from] within [limit] steps, with how many steps. */
    private fun reachable(from: FieldMap.Tile, limit: Int): Map<FieldMap.Tile, Int> {
        val dist = HashMap<FieldMap.Tile, Int>()
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
    val bufferHeight = size + MapWanderer.HOP_HEIGHT.toInt() + below + 2
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

    // where the sprite was last drawn, for taps
    private var spriteLeft = 0
    private var spriteTop = 0

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

        // the shadow on the ground
        for (dy in -ry.toInt()..ry.toInt()) for (dx in -rx.toInt()..rx.toInt()) {
            if ((dx / rx) * (dx / rx) + (dy / ry) * (dy / ry) > 1f) continue
            val px = ax + dx
            val py = ay + dy
            if (!visible(px, py)) continue
            val bx = px - left
            val by = py - top
            if (bx in 0 until bufferWidth && by in 0 until bufferHeight) buffer[by * bufferWidth + bx] = 0x48000000
        }

        // the Pokémon, feet on the spot
        val frame = sprite.frame(pose.facing, pose.frame)
        spriteLeft = ax - size / 2
        spriteTop = ay + 1 + feet - size - pose.hop.roundToInt()
        var any = false
        for (y in 0 until size) for (x in 0 until size) {
            val c = frame[y * size + x]
            if (c ushr 24 == 0) continue
            val px = spriteLeft + x
            val py = spriteTop + y
            if (!visible(px, py)) continue
            val bx = px - left
            val by = py - top
            if (bx in 0 until bufferWidth && by in 0 until bufferHeight) {
                buffer[by * bufferWidth + bx] = c
                any = true
            }
        }
        return any || a[0] in 0f..w.toFloat() && a[1] in 0f..h.toFloat()
    }

    /** Whether picture position ([px], [py]) is on the Pokémon as last painted (with some room to spare). */
    fun hit(px: Float, py: Float): Boolean {
        val pad = size * 0.15f
        return px >= spriteLeft - pad && px <= spriteLeft + size + pad && py >= spriteTop - pad && py <= spriteTop + size + pad
    }
}
