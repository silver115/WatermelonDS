import me.magnum.melonds.pokewalker.CryArchive
import me.magnum.melonds.pokewalker.FieldAnimations
import me.magnum.melonds.pokewalker.FieldMap
import me.magnum.melonds.pokewalker.FieldPeople
import me.magnum.melonds.pokewalker.MapCrowd
import me.magnum.melonds.pokewalker.MapBuddy
import me.magnum.melonds.pokewalker.MapGames
import me.magnum.melonds.pokewalker.BerryArt
import me.magnum.melonds.pokewalker.PokemonTypes
import me.magnum.melonds.pokewalker.MapAmbience
import me.magnum.melonds.pokewalker.FollowerSprites
import me.magnum.melonds.pokewalker.MapSpritePainter
import me.magnum.melonds.pokewalker.MapWanderer
import me.magnum.melonds.pokewalker.WalkingPokemon
import me.magnum.melonds.pokewalker.WalkParty
import me.magnum.melonds.pokewalker.PropAnimations
import me.magnum.melonds.pokewalker.FieldEffectArt
import me.magnum.melonds.pokewalker.FieldEffects
import me.magnum.melonds.pokewalker.Emote
import me.magnum.melonds.pokewalker.Emotes
import me.magnum.melonds.pokewalker.MapLife
import me.magnum.melonds.pokewalker.SceneProps
import java.io.RandomAccessFile
import kotlin.math.abs
import kotlin.random.Random
import kotlin.system.exitProcess

/**
 * The route map on the fake ROM from gen_map_rom.py: which spot is picked, what is drawn, which
 * tiles the Pokémon may walk on and how high, the follower sprite, the wandering and the drawing
 * of the sprite behind things, the people of the place, the wild Pokémon, the one following,
 * the moves by type, ledges and signs, the weather and the games; buildings that move (a
 * windmill, a door) and change by the time of day, lit windows and lamps, the game's field
 * effects, and life on the map (item balls, a tossed berry, birds and Magikarp, sleep, moods,
 * sparkles, emote bubbles). Usage: MapTest <fake.nds> <fake_without_maps.nds>
 */
private var checks = 0
private var failures = 0

private fun check(ok: Boolean, what: String) {
    checks++
    if (!ok) {
        failures++
        println("FAIL: $what")
    }
}

private fun source(path: String): CryArchive.Source {
    val f = RandomAccessFile(path, "r")
    return object : CryArchive.Source {
        override val size = f.length()
        override fun read(pos: Long, len: Int): ByteArray {
            val b = ByteArray(len)
            f.seek(pos)
            f.readFully(b)
            return b
        }
    }
}

private fun near(a: Double, b: Double) = abs(a - b) < 0.01

private fun red(c: Int) = (c ushr 16) and 0xFF
private fun green(c: Int) = (c ushr 8) and 0xFF
private fun blue(c: Int) = c and 0xFF

fun main(args: Array<String>) {
    val src = source(args[0])
    val w = 384
    val h = 216

    // ---- which course is which ----
    check(FieldMap.courseFor("Refreshing Field", 0).map == 33, "Refreshing Field is Route 29")
    check(FieldMap.courseFor("refreshing  field!", 3).map == 33, "course names match whatever the punctuation")
    check(FieldMap.courseFor("Somewhere New", 5).map == 99, "an unknown course goes by its route picture (cave)")
    check(FieldMap.areaBank(33) == 2 && FieldMap.areaBank(99) == 87, "area banks of Route 29 and Union Cave")

    // ---- the outdoor course ----
    val course = FieldMap.COURSES[0]
    val log = ArrayList<String>()
    val scene = FieldMap.render(src, course, w, h, 900) { log.add(it) }
    println(log.joinToString("\n"))
    check(scene.width == w && scene.height == h && scene.image.size == w * h, "picture size")
    check(scene.tiles.isNotEmpty(), "there are tiles to walk on")
    check(scene.lightKey == FieldMap.dayKey(900) && FieldMap.dayKey(900) != FieldMap.dayKey(1100) && FieldMap.dayKey(590) != FieldMap.dayKey(610), "drawn with the 15:00 light (and redrawn when the light or the time of day changes)")
    check(scene.start != null && scene.tile(scene.start!!.gx, scene.start!!.gy) != null, "the start is a walkable tile")

    // every walkable tile is open ground of block 0 (blocks: x 32-63, y 0-31) or block 1's path
    var wrong = 0
    for (t in scene.tiles.values) {
        val bx = Math.floorDiv(t.gx, 32)
        val by = Math.floorDiv(t.gy, 32)
        val x = t.gx - bx * 32
        val y = t.gy - by * 32
        val ok = when {
            bx == 1 && by == 0 -> y in 5..27 && x >= 4 && !(x in 20..24 && y in 12..15) && !(x in 14..17 && y in 18..21) && !(x in 9..10 && y in 12..13)
            bx == 1 && by == 1 -> x in 15..17
            else -> false
        }
        if (!ok) { wrong++; if (wrong < 5) println("  not walkable: ${t.gx},${t.gy}") }
    }
    check(wrong == 0, "walkable tiles avoid walls, water, the house and other maps ($wrong wrong)")
    check(scene.tiles.values.any { it.gy in 9..23 && it.gx - 32 in 6..13 }, "tall grass is walkable")

    // heights from the BDHC surfaces: flat at 16, the slope rising half a unit per unit from x 96
    val flatTile = scene.tiles.values.firstOrNull { it.gx - 32 in 9..20 && it.gy in 6..24 }
    check(flatTile != null && near(flatTile.y, 16.0), "flat ground is at height 16 (${flatTile?.y})")
    val slope = scene.tiles.values.filter { it.gx - 32 >= 22 }
    for (t in slope) {
        val local = (t.gx - 32) * 16 + 8 - 256.0
        if (!near(t.y, 16 + (local - 96) / 2)) { check(false, "slope height at ${t.gx},${t.gy}: ${t.y}"); break }
    }
    check(slope.size >= 10, "the slope is in sight (${slope.size} tiles)")
    check(scene.tiles.values.any { it.gx - 32 == 21 } && scene.tiles.values.none { it.gx - 32 == 21 && !near(it.y, 16.0) },
        "just before the slope it's still flat")

    // the house is drawn red (the area's building textures), on top of green ground
    val roof = scene.project(32 * 16 + 256.0, 64.0, 256.0 + 64)
    check(roof != null && roof[0] in 0f..w.toFloat() && roof[1] in 0f..h.toFloat(), "the house is in the picture")
    if (roof != null && roof[0] in 0f..(w - 1).toFloat() && roof[1] in 0f..(h - 1).toFloat()) {
        val c = scene.image[roof[1].toInt() * w + roof[0].toInt()]
        check(red(c) > 120 && green(c) < 90, "the roof has the wall texture's red (%06x)".format(c and 0xFFFFFF))
    }
    // and stands on the ground, not above it: over its north edge, a little higher than the roof, is sky... well, grass behind
    val overRoof = scene.project(32 * 16 + 256.0, 84.0, 256.0 + 32)
    if (overRoof != null && overRoof[0] in 0f..(w - 1).toFloat() && overRoof[1] in 0f..(h - 1).toFloat()) {
        val c = scene.image[overRoof[1].toInt() * w + overRoof[0].toInt()]
        check(!(red(c) > 120 && green(c) < 90), "the house is as tall as its model, standing on the ground (%06x)".format(c and 0xFFFFFF))
    }
    val ground = scene.project(32 * 16 + 8.0 + 16 * 10, 16.0, 8.0 + 16 * 10)
    if (ground != null && ground[0] in 0f..(w - 1).toFloat() && ground[1] in 0f..(h - 1).toFloat()) {
        val c = scene.image[ground[1].toInt() * w + ground[0].toInt()]
        check(green(c) > red(c) && green(c) > blue(c), "the ground has the map texture's green (%06x)".format(c and 0xFFFFFF))
    }
    // the box that isn't in the area's building list isn't drawn (it would be blue)
    val boxTop = scene.project(32 * 16 + 256.0 - 96, 48.0, 256.0 - 48)
    check(boxTop != null && boxTop[0] in 0f..(w - 1).toFloat() && boxTop[1] in 0f..(h - 1).toFloat(), "where that box would be is in the picture")
    if (boxTop != null && boxTop[0] in 0f..(w - 1).toFloat() && boxTop[1] in 0f..(h - 1).toFloat()) {
        val c = scene.image[boxTop[1].toInt() * w + boxTop[0].toInt()]
        check(!(blue(c) > 150 && red(c) < 100), "a building the area doesn't load isn't drawn (%06x)".format(c and 0xFFFFFF))
    }
    // the floating layer (white, 160 up) isn't drawn: no white pixels anywhere
    check(scene.image.none { red(it) > 240 && green(it) > 240 && blue(it) > 240 }, "the floating effect layer is left out")
    // ground right behind the house is hidden by it: those tiles aren't offered
    check(scene.tile(32 + 15, 17) == null && scene.tile(32 + 16, 17) == null, "tiles hidden behind the house aren't walkable spots")
    check(scene.tile(32 + 13, 19) != null && scene.tile(32 + 18, 20) != null, "tiles beside the house are")

    // ---- the game's moving textures ----
    val swaps = FieldAnimations.readSwaps(src)
    val swap = swaps["sea_on"]
    check(swap != null && swap.frames.size == 2 && swap.timeline.contentEquals(intArrayOf(0, 0, 0, 1, 1, 1)), "the sea's texture swaps: 2 frames, 3 game frames each")
    val scrolls = FieldAnimations.readScrolls(src, 0)
    val scroll = scrolls["sea_on"]
    check(scroll?.s != null && scroll.t == null && near(scroll.s!![3].toDouble(), 3 / 8.0), "the sea's texture scrolls an eighth a frame across")
    check(FieldAnimations.readScrolls(src, 0xFFFF).isEmpty(), "no animation number, no scrolls")
    val anim = scene.animation
    check(anim != null && anim.size > 500, "the sea moves (${anim?.size} pixels)")
    val seaAt = scene.project(32 * 16 + 22 * 16 + 8.0, 16.5, 13 * 16 + 8.0)
    if (anim != null && seaAt != null) {
        val i = seaAt[1].toInt() * w + seaAt[0].toInt()
        anim.update(0)
        val c0 = scene.image[i]
        check(blue(c0) > red(c0) + 40, "the sea is blue (%06x)".format(c0 and 0xFFFFFF))
        check(anim.update(1), "a frame later the texture has moved on")
        check(!anim.update(1), "and the same frame again changes nothing")
        val seen = HashSet<Int>()
        for (t in 0L until 8L) { anim.update(t); seen.add(scene.image[i]) }
        check(seen.size >= 2, "the sea's pixel changes colour as it moves (${seen.size} colours)")
        anim.update(0)
        check(scene.image[i] == c0, "and comes back round")
    }
    check(scene.waterSpots.size >= 10, "the water's in sight for glints (${scene.waterSpots.size / 2} spots)")
    check(scene.tiles.values.any { it.tallGrass } && scene.tiles.values.any { !it.tallGrass }, "tiles know their tall grass")

    // ---- little things moving about ----
    val amb = MapAmbience(scene, Random(4))
    val dots = ArrayList<MapAmbience.Dot>()
    val clouds = ArrayList<MapAmbience.Cloud>()
    var dayDots = 0
    var dayClouds = 0
    for (k in 0 until 600) { amb.frame(k * 33L, 12 * 60, dots, clouds); dayDots += dots.size; dayClouds += clouds.size }
    check(dayDots > 600, "by day things drift by ($dayDots dots in 20 s)")
    check(dayClouds > 0 && clouds.all { it.alpha in 0.01f..0.5f }, "cloud shadows pass over by day")
    val night = MapAmbience(scene, Random(4))
    var glow = 0
    for (k in 0 until 600) {
        night.frame(k * 33L, 22 * 60, dots, clouds)
        check(clouds.isEmpty(), "no cloud shadows at night")
        glow += dots.count { (it.color and 0xFFFFFF) == 0xD8FF70 }
    }
    check(glow > 0, "fireflies at night")
    amb.rustle(100f, 100f, 30_000L)
    amb.frame(30_033L, 12 * 60, dots, clouds)
    check(dots.count { it.x in 90f..110f && it.y in 85f..105f } >= 4, "walking into tall grass shakes it")

    // ---- the follower sprite ----
    val idx = FollowerSprites.spriteFor(25, 0, false)
    check(idx == FollowerSprites.spriteFor(25, 0, true) - 1, "Pikachu has a female sprite right after it")
    check(FollowerSprites.spriteFor(201, 5, false) == FollowerSprites.spriteFor(201, 0, false) + 5, "Unown forms")
    check(FollowerSprites.spriteFor(1, 3, true) == FollowerSprites.spriteFor(1, 0, false), "Bulbasaur has one sprite")
    val sprite = FollowerSprites.load(src, 25, 0, false, false)
    val shiny = FollowerSprites.load(src, 25, 0, false, true)
    check(sprite != null && sprite.size == 32 && sprite.frames.size == 8, "eight 32x32 frames")
    if (sprite == null || shiny == null) {
        println("$checks checks, $failures failed")
        exitProcess(1)
    }
    val f0 = sprite.frame(FollowerSprites.DOWN, 0)
    check(f0[0] ushr 24 == 0, "colour 0 is see-through")
    check(f0[18 * 32 + 16] != shiny.frame(FollowerSprites.DOWN, 0)[18 * 32 + 16], "the shiny palette differs")
    check(!sprite.frame(FollowerSprites.UP, 0).contentEquals(sprite.frame(FollowerSprites.LEFT, 0)), "each direction has its own frames")
    check(!sprite.frame(FollowerSprites.UP, 0).contentEquals(sprite.frame(FollowerSprites.UP, 1)), "two steps per direction")

    // ---- the walking Pokémon from the walker's memory ----
    val image = ByteArray(0x10000)
    image[0x8F00] = 25
    image[0x8F00 + 0x0D] = (3 or (1 shl 5)).toByte()
    image[0x8F00 + 0x0E] = 2
    check(WalkingPokemon.fromImage(image) == WalkingPokemon(25, 3, true, true), "species, form, female and shiny from the route info")
    image[0x8F00 + 0x0D] = (2 shl 5).toByte()
    image[0x8F00 + 0x0E] = 1
    check(WalkingPokemon.fromImage(image) == WalkingPokemon(25, 0, false, false), "genderless isn't female; bit 0 isn't shiny")
    check(WalkingPokemon.fromImage(ByteArray(100)) == null, "no route info, no Pokémon")

    // ---- drawing it, hidden behind nearer things ----
    val painter = MapSpritePainter(scene, sprite)
    val opaque = f0.count { it ushr 24 != 0 }
    val front = scene.tile(32 + 7, 11)
    check(front != null, "an open spot to stand on")
    if (front == null) exitProcess(1)
    painter.paint(MapWanderer.Pose(front.x, front.y, front.z, FollowerSprites.DOWN, 0, 0.0, false))
    check(painter.buffer.count { it ushr 24 == 0xFF } == opaque, "out in the open it's all there")
    check(painter.buffer.count { it == 0x48000000 } > 10, "with a shadow")
    val a = scene.project(front.x, front.y, front.z)!!
    check(painter.hit(a[0], a[1] - 10), "a tap on it hits it")
    check(!painter.hit(a[0] + 40, a[1] - 10), "a tap beside it doesn't")
    // just north of the house (the ground there is hidden, but say it stood there)
    val bx = 32 * 16 + 256.0
    painter.paint(MapWanderer.Pose(bx, 16.0, 256.0 + 16 * 1 + 8, FollowerSprites.DOWN, 0, 0.0, false))
    val behind = painter.buffer.count { it ushr 24 == 0xFF }
    check(behind < opaque / 2, "behind the house it's hidden ($behind of $opaque shown)")
    painter.paint(MapWanderer.Pose(front.x, front.y, front.z, FollowerSprites.DOWN, 0, MapWanderer.HOP_HEIGHT, false))
    check(painter.buffer.count { it ushr 24 == 0xFF } == opaque, "a hop keeps it whole")
    painter.paint(MapWanderer.Pose(front.x, front.y, front.z, FollowerSprites.DOWN, 0, 0.0, false, grass = true))
    val inGrass = painter.buffer.count { it ushr 24 == 0xFF }
    check(inGrass in 1 until opaque && painter.buffer.none { it == 0x48000000 }, "in tall grass its feet and shadow are hidden ($inGrass of $opaque)")
    painter.paint(MapWanderer.Pose(front.x, front.y, front.z, FollowerSprites.DOWN, 0, MapWanderer.HOP_HEIGHT, false, grass = true))
    check(painter.buffer.count { it ushr 24 == 0xFF } == opaque, "hopping out of the grass it's all there")

    // ---- wandering ----
    val wanderer = MapWanderer(scene, Random(1))
    var t = 0L
    var prev = wanderer.at
    val seen = HashSet<Long>()
    var jumps = 0
    var outside = 0
    while (t < 180_000) {
        wanderer.update(t)
        val pose = wanderer.pose(t)
        val at = wanderer.at
        if (scene.tile(at.gx, at.gy) == null) outside++
        if (at !== prev) {
            val d = abs(at.gx - prev.gx) + abs(at.gy - prev.gy)
            // a step, or a hop down the ledge (row 20, x 52-57) from just above it to just below
            val ledge = d == 2 && at.gx == prev.gx && prev.gy == 19 && at.gy == 21 && at.gx - 32 in 20..25
            if ((d != 1 || !FieldMap.stepOk(prev, at)) && !ledge) jumps++
            prev = at
        }
        seen.add(FieldMap.Scene.key(at.gx, at.gy))
        check(pose.hop in 0.0..MapWanderer.JUMP_HEIGHT, "hop height")
        t += 50
    }
    check(outside == 0, "it never stands off the walkable tiles")
    check(jumps == 0, "it only ever steps to a neighbouring tile or hops down the ledge ($jumps jumps)")
    check(seen.size > 15, "it gets around (${seen.size} tiles in 3 minutes)")

    // sent somewhere: it gets there by walking
    val here = wanderer.at
    val target = scene.tiles.values.filter { abs(it.gx - here.gx) + abs(it.gy - here.gy) in 5..8 }
        .firstOrNull { wanderer.goTo(it, t) }
    check(target != null, "it can be sent to a tile 5-8 steps away")
    if (target != null) {
        val until = t + 40 * MapWanderer.STEP_MS
        while (t < until && (wanderer.at.gx != target.gx || wanderer.at.gy != target.gy)) { t += 20; wanderer.update(t) }
        check(wanderer.at.gx == target.gx && wanderer.at.gy == target.gy, "it arrives where it was sent")
    }
    check(!wanderer.goTo(FieldMap.Tile(9999, 9999, 0.0, 0.0, 0.0), t), "it can't be sent off the map")

    // walked by hand into the water: it turns to face it but stays on the shore
    // a shore tile with ground to walk on west of it
    val shore = scene.tiles.values.firstOrNull { s -> s.gx - 32 == 19 && s.gy in 12..15 && (1..4).all { scene.tile(s.gx - it, s.gy) != null } }
    if (shore != null) {
        val hand = MapWanderer(scene, Random(2))
        hand.placeAt(shore)
        hand.update(t)
        hand.walk(FollowerSprites.RIGHT, false, t)
        repeat(30) { t += 50; hand.update(t) }
        check(hand.at.gx == shore.gx && hand.at.gy == shore.gy, "it doesn't walk into the water")
        check(hand.facing == FollowerSprites.RIGHT, "but turns to look at it")
        hand.walk(FollowerSprites.LEFT, true, t)
        repeat(40) { t += 50; hand.update(t) }
        check(hand.at.gx < shore.gx - 2, "held left, it keeps walking left (${shore.gx} -> ${hand.at.gx})")
        hand.release()
    } else {
        check(false, "the shore west of the water is in sight")
    }

    // ---- the people of the place ----
    check(FieldPeople.eventsBank(33) == 30 && FieldPeople.eventsBank(60) == 57, "Route 29's and New Bark Town's events")
    check(FieldPeople.modelFor(0x145) == 123 && FieldPeople.modelFor(101) == -1, "the sprite table: a woman, and none for a sprite the story picks")
    val people = scene.people
    fun personAt(gx: Int, gy: Int) = people.firstOrNull { it.home.gx == gx && it.home.gy == gy }
    val woman = personAt(44, 16)
    val man = personAt(40, 20)
    val swimmer = personAt(54, 13)
    check(woman != null && woman.model == 123 && woman.movement == 3 && woman.xRange == 1 && woman.yRange == 1 && !woman.trainer && !woman.swimming,
        "the woman who wanders a tile either way")
    check(personAt(45, 16) == null, "someone hidden by a flag isn't there")
    check(man != null && man.model == 130 && man.trainer && !man.swimming && man.movement == 15, "the trainer facing down")
    check(swimmer != null && swimmer.swimming && swimmer.model == 138 && swimmer.facing == FollowerSprites.LEFT, "the swimmer in the water, facing left")
    check(personAt(43, 8) == null, "an apricorn tree isn't a person")
    check(personAt(34, 2) == null, "nobody up on the rocks, where there's nowhere to stand")
    check(people.size == 3, "three people in sight (${people.size})")

    val ws = FollowerSprites.loadModel(src, 123)
    check(ws != null && ws.perDirection == 4 && ws.frames.size == 16 && ws.size == 32, "a person's sprite: sixteen 32x32 frames, four a way")
    fun dots(f: IntArray) = (0 until 32).count { f[2 * 32 + it] ushr 24 != 0 }
    if (ws != null) {
        check(dots(ws.frame(FollowerSprites.UP, 0)) == 1 && dots(ws.frame(FollowerSprites.DOWN, 0)) == 5 &&
            dots(ws.frame(FollowerSprites.LEFT, 1)) == 10 && dots(ws.frame(FollowerSprites.RIGHT, 3)) == 16,
            "frames in number order (.1 ... .16), though the file has them by name")
    }
    check(FollowerSprites.loadModel(src, 251) == null, "the tree's sprite isn't anyone's")
    check(FollowerSprites.loadModel(src, 300)?.perDirection == 2, "a Pokémon's sprite: eight frames, two a way")

    if (woman != null && man != null && swimmer != null && ws != null) {
        // five minutes of them going about, with the Pokémon wandering among them
        val crowd = MapCrowd(scene, people, Random(5))
        val walker = MapWanderer(scene, Random(6))
        walker.blocked = { gx, gy -> crowd.occupied(gx, gy) }
        check(!crowd.occupied(walker.at.gx, walker.at.gy), "the Pokémon doesn't start on someone")
        val womanSeen = HashSet<Long>()
        var strayed = 0; var overlap = 0; var manMoved = 0; var manTurned = 0; var swimmerMoved = 0; var hops = 0; var offTiles = 0
        val last = crowd.walkers.map { it.at }.toMutableList()
        var tt = 0L
        while (tt < 300_000) {
            walker.update(tt)
            crowd.update(tt, walker.at, walker.next, walker.facing, walker.next == null)
            val spots = crowd.walkers.flatMap { listOfNotNull(it.at, it.next) }.map { it.gx to it.gy } + listOfNotNull(walker.at, walker.next).map { it.gx to it.gy }
            if (spots.size != spots.toSet().size) overlap++
            crowd.walkers.forEachIndexed { i, w ->
                if (w.at !== last[i]) {
                    if (abs(w.at.gx - last[i].gx) + abs(w.at.gy - last[i].gy) != 1) hops++
                    last[i] = w.at
                }
                when (w.person) {
                    woman -> {
                        womanSeen.add(FieldMap.Scene.key(w.at.gx, w.at.gy))
                        if (abs(w.at.gx - 44) > 1 || abs(w.at.gy - 16) > 1) strayed++
                        if (scene.tile(w.at.gx, w.at.gy) == null) offTiles++
                    }
                    man -> {
                        if (w.at.gx != 40 || w.at.gy != 20 || w.next != null) manMoved++
                        val beside = abs(walker.at.gx - 40) + abs(walker.at.gy - 20) == 1
                        if (w.facing != FollowerSprites.DOWN && !beside) manTurned++
                    }
                    swimmer -> if (w.at.gx != 54 || w.at.gy != 13 || w.next != null) swimmerMoved++
                }
            }
            tt += 40
        }
        check(womanSeen.size >= 3, "the woman walks about (${womanSeen.size} tiles)")
        check(strayed == 0 && offTiles == 0, "but never more than a tile from home, always on ground")
        check(hops == 0, "people only ever step to the next tile")
        check(overlap == 0, "nobody walks into anyone, the Pokémon included ($overlap times)")
        check(manMoved == 0 && manTurned == 0, "the trainer stays put facing down ($manMoved, $manTurned)")
        check(swimmerMoved == 0, "the swimmer stays put")
        val wp = crowd.walkers.first { it.person === woman }.pose(tt)
        check(wp.grass, "standing in tall grass, the woman's feet are hidden too")

        // a crowd of wanderers on a patch of ground: they never share a tile
        val patch = scene.tiles.values.filter { it.gx in 38..43 && it.gy in 10..14 }.take(6)
        check(patch.size == 6, "a patch of ground for a crowd")
        val busy = MapCrowd(scene, patch.map { FieldPeople.Person(123, it, 3, 1, 2, 2, false, false) }, Random(9))
        var crowded = 0
        var moves = 0
        var tb = 0L
        val was = busy.walkers.map { it.at }.toMutableList()
        while (tb < 120_000) {
            busy.update(tb, null, null, 0, false)
            val spots = busy.walkers.flatMap { listOfNotNull(it.at, it.next) }.map { it.gx to it.gy }
            if (spots.size != spots.toSet().size) crowded++
            busy.walkers.forEachIndexed { i, w -> if (w.at !== was[i]) { moves++; was[i] = w.at } }
            tb += 40
        }
        check(moves > 20 && crowded == 0, "six wanderers close together never share a tile ($moves steps, $crowded clashes)")

        // the Pokémon walks up to the trainer and looks at him: he turns to it
        val talk = MapCrowd(scene, listOf(man), Random(1))
        val west = scene.tile(39, 20)
        val pk = MapWanderer(scene, Random(2))
        pk.blocked = { gx, gy -> talk.occupied(gx, gy) }
        check(west != null, "a tile beside the trainer")
        if (west != null) {
            var t2 = 1_000L
            pk.placeAt(west)
            pk.update(t2); talk.update(t2, pk.at, pk.next, pk.facing, pk.next == null)
            pk.walk(FollowerSprites.RIGHT, false, t2)
            repeat(10) { t2 += 50; pk.update(t2); talk.update(t2, pk.at, pk.next, pk.facing, pk.next == null) }
            check(pk.at === west && pk.facing == FollowerSprites.RIGHT, "it can't walk through him, it turns to him")
            check(talk.walkers[0].facing == FollowerSprites.LEFT, "and he turns to it, as if talked to")
            check(talk.walkers[0].at.gx == 40, "without moving")
            // it goes away: he looks back down
            pk.placeAt(scene.tiles.values.first { abs(it.gx - 40) + abs(it.gy - 20) > 4 })
            repeat(80) { t2 += 50; pk.update(t2); talk.update(t2, pk.at, pk.next, pk.facing, pk.next == null) }
            check(talk.walkers[0].facing == FollowerSprites.DOWN, "left alone, he faces the way he did")

            // sent over to him (a tap on him), it ends up beside him looking at him
            val east = scene.tile(41, 20)
            check(east != null, "the tile on his other side")
            if (east != null) {
                val sent = pk.goTo(east, t2, man.home)
                check(sent, "it can be sent beside him")
                val until = t2 + 60 * MapWanderer.STEP_MS
                while (t2 < until && (pk.at !== east || pk.next != null)) { t2 += 20; pk.update(t2); talk.update(t2, pk.at, pk.next, pk.facing, pk.next == null) }
                repeat(5) { t2 += 20; pk.update(t2); talk.update(t2, pk.at, pk.next, pk.facing, pk.next == null) }
                check(pk.at === east && pk.facing == FollowerSprites.LEFT, "it arrives and looks at him (${pk.at.gx},${pk.at.gy} facing ${pk.facing})")
                check(talk.walkers[0].facing == FollowerSprites.RIGHT, "and he looks at it")
            }
        }

        // drawn: a person with a shadow, the swimmer without
        val pp = MapSpritePainter(scene, ws)
        val home = woman.home
        pp.paint(MapWanderer.Pose(home.x, home.y, home.z, FollowerSprites.DOWN, 0, 0.0, false))
        check(pp.buffer.count { it == 0x48000000 } > 10 && pp.buffer.any { it ushr 24 == 0xFF }, "a person is drawn with a shadow")
        val sp = MapSpritePainter(scene, FollowerSprites.loadModel(src, 138)!!).apply { shadow = false }
        sp.paint(MapWanderer.Pose(swimmer.home.x, swimmer.home.y, swimmer.home.z, swimmer.facing, 0, 0.0, false))
        check(sp.buffer.none { it == 0x48000000 } && sp.buffer.any { it ushr 24 == 0xFF }, "the swimmer has no shadow on the water")
        check(scene.water.containsKey(FieldMap.Scene.key(54, 13)) && scene.tile(54, 13) == null, "the swimmer's tile is water, not ground")
    }
    check(FieldPeople.readObjects(ByteArray(8)).isEmpty(), "an event file with nothing in it")
    try {
        FieldPeople.readObjects(ByteArray(4) + byteArrayOf(9, 0, 0, 0) + ByteArray(40))
        check(false, "a cut-short event file: an error")
    } catch (e: Exception) {
        check(e.message?.contains("9 objects") == true, "a cut-short event file: an error (${e.message})")
    }

    // ---- types, the course's Pokémon and the ones caught, from the walker's memory ----
    check(PokemonTypes.of(src, 25) == setOf(13) && PokemonTypes.of(src, 16) == setOf(0, 2) && PokemonTypes.of(src, 92) == setOf(7, 3),
        "types from the personal data: Pikachu Electric, Pidgey Normal/Flying, Gastly Ghost/Poison")
    check(PokemonTypes.of(source(args[1]), 25).isEmpty(), "no personal data, no types")
    val walkerImage = ByteArray(0x10000)
    fun summary(o: Int, species: Int, form: Int = 0, female: Boolean = false, shiny: Boolean = false) {
        walkerImage[o] = (species and 0xFF).toByte(); walkerImage[o + 1] = (species shr 8).toByte()
        walkerImage[o + 0x0D] = (form or (if (female) 1 shl 5 else 2 shl 5)).toByte()
        walkerImage[o + 0x0E] = (if (shiny) 2 else 0).toByte()
    }
    summary(0x8F52, 161); summary(0x8F62, 16, shiny = true); summary(0x8F72, 19, female = true)
    summary(0xCE8C, 19); summary(0xCE9C + 0x10, 152)
    check(WalkingPokemon.routePokemon(walkerImage) == listOf(WalkingPokemon(161, 0, false, false), WalkingPokemon(16, 0, false, true), WalkingPokemon(19, 0, true, false)),
        "the course's three Pokémon (route info +0x52)")
    check(WalkingPokemon.caught(walkerImage) == listOf(WalkingPokemon(19, 0, false, false), WalkingPokemon(152, 0, false, false)), "the ones caught, skipping an empty slot")

    // ---- ledges and signs ----
    check(scene.ledges[FieldMap.Scene.key(32 + 21, 20)] == FollowerSprites.DOWN, "a ledge, jumped down southwards")
    check(scene.signs.size == 1 && scene.signs[0].gx == 47 && scene.signs[0].gy == 21, "the sign on the house (not the hidden item)")
    val above = scene.tile(32 + 21, 19)
    val below = scene.tile(32 + 21, 21)
    check(above != null && below != null && scene.tile(32 + 21, 20) == null, "ground above and below the ledge, the ledge itself not")
    if (above != null && below != null) {
        val jumper = MapWanderer(scene, Random(4))
        var tj = 500_000L
        jumper.placeAt(above); jumper.update(tj)
        jumper.walk(FollowerSprites.DOWN, false, tj)
        check(jumper.next?.gy == 21 && jumper.move == MapWanderer.Move.JUMP, "walked down at the ledge, it hops over it")
        val mid = jumper.pose(tj + MapWanderer.JUMP_MS / 2)
        check(mid.hop > 8.0, "high in the middle of the hop (${mid.hop})")
        repeat(20) { tj += 50; jumper.update(tj) }
        check(jumper.at === below, "and lands two tiles down")
        jumper.walk(FollowerSprites.UP, false, tj)
        repeat(10) { tj += 50; jumper.update(tj) }
        check(jumper.at === below && jumper.facing == FollowerSprites.UP, "it can't climb back up the ledge")
        check(jumper.goTo(above, tj), "but it can walk round to the top")
    }
    // reading the sign: it walks up to it and stands looking at it
    run {
        val reader = MapWanderer(scene, Random(21))
        var tr = 0L
        var reading = 0L
        while (tr < 600_000) {
            reader.update(tr)
            if (reader.idle && reader.at.gx == 47 && reader.at.gy == 22 && reader.facing == FollowerSprites.UP) reading += 100
            tr += 100
        }
        check(reading >= MapWanderer.READ_MS, "now and then it goes and reads the sign ($reading ms in 10 minutes)")
    }

    // ---- moves by type ----
    run {
        // a Water type swims
        val swimmer = MapWanderer(scene, Random(8)).apply { swims = true }
        var tw = 0L
        var inWater = 0
        var nowhere = 0
        while (tw < 300_000) {
            swimmer.update(tw)
            val p = swimmer.pose(tw)
            if (p.inWater) inWater++
            if (scene.tile(swimmer.at.gx, swimmer.at.gy) == null && !scene.water.containsKey(FieldMap.Scene.key(swimmer.at.gx, swimmer.at.gy))) nowhere++
            tw += 100
        }
        check(inWater > 50, "a Water type goes for a swim ($inWater tenths of a second in 5 minutes)")
        check(nowhere == 0, "only ever on the ground or in the water")
        val stays = MapWanderer(scene, Random(8))
        var ts = 0L
        var wet = 0
        while (ts < 120_000) { stays.update(ts); if (stays.pose(ts).inWater) wet++; ts += 100 }
        check(wet == 0, "others stay out of the water")

        // a Flying type flies over to places
        val flier = MapWanderer(scene, Random(9)).apply { flies = true }
        var tf = 0L
        var flights = 0
        var aloft = 0
        var offGround = 0
        while (tf < 300_000) {
            flier.update(tf)
            val p = flier.pose(tf)
            if (p.aloft) aloft++
            if (flier.move == MapWanderer.Move.FLY && flier.next != null && p.hop > 40.0) flights++
            if (scene.tile(flier.at.gx, flier.at.gy) == null) offGround++
            tf += 50
        }
        check(aloft > 10 && flights > 0, "a Flying type flies over to somewhere else ($aloft frames up)")
        check(offGround == 0, "and lands on the ground")

        // a Ghost type fades out and back in
        val ghost = MapWanderer(scene, Random(10)).apply { fades = true }
        var tg = 0L
        var faded = 0
        while (tg < 300_000) {
            ghost.update(tg)
            if (ghost.pose(tg).alpha < 0.5f) faded++
            tg += 50
        }
        check(faded > 5, "a Ghost type fades out and back in ($faded faded frames)")

        // drawn: swimming only its top shows, flying it shows over the house, fading it's see-through
        val pp = MapSpritePainter(scene, sprite)
        val wet0 = scene.water.values.first()
        pp.paint(MapWanderer.Pose(wet0.x, wet0.y, wet0.z, FollowerSprites.DOWN, 0, 0.0, false, inWater = true))
        val swimShown = pp.buffer.count { it ushr 24 == 0xFF && (it and 0xFFFFFF) != 0xE8F6FF }
        check(swimShown in 1 until opaque, "swimming, only its top half shows ($swimShown of $opaque)")
        check(pp.buffer.none { it == 0x48000000 }, "and no shadow on the water")
        pp.paint(MapWanderer.Pose(bx, 16.0, 256.0 + 16 * 1 + 8, FollowerSprites.DOWN, 0, 40.0, false, aloft = true))
        check(pp.buffer.count { it ushr 24 == 0xFF } == opaque, "up in the air it shows over the house")
        pp.paint(MapWanderer.Pose(front.x, front.y, front.z, FollowerSprites.DOWN, 0, 0.0, false, alpha = 0.5f))
        check(pp.buffer.count { it ushr 24 in 100..160 } == opaque, "half faded, it's half see-through")
    }

    // ---- a Pokémon caught on the walk follows along ----
    run {
        val leader = MapWanderer(scene, Random(12))
        val startBeside = listOf(0 to 1, 1 to 0, -1 to 0, 0 to -1).mapNotNull { (dx, dy) -> scene.tile(leader.at.gx + dx, leader.at.gy + dy) }.first()
        val buddy = MapBuddy(scene, startBeside)
        var tb = 0L
        var close = 0
        var total = 0
        var bad = 0
        var lastAt = buddy.at
        var leaps = 0
        while (tb < 300_000) {
            leader.update(tb)
            buddy.update(tb, leader)
            if (scene.tile(buddy.at.gx, buddy.at.gy) == null) bad++
            if (buddy.at !== lastAt) {
                if (abs(buddy.at.gx - lastAt.gx) + abs(buddy.at.gy - lastAt.gy) != 1) leaps++
                lastAt = buddy.at
            }
            if (leader.idle && buddy.next == null) {
                total++
                if (abs(buddy.at.gx - leader.at.gx) + abs(buddy.at.gy - leader.at.gy) <= 1) close++
            }
            tb += 50
        }
        check(bad == 0, "the one following stays on the ground")
        check(total > 0 && close * 10 >= total * 9, "and is right behind it when it stops ($close of $total)")
        check(leaps <= 3, "walking after it, not jumping about ($leaps)")
    }

    // ---- wild Pokémon come out of the tall grass ----
    run {
        val crowd = MapCrowd(scene, emptyList(), Random(13))
        crowd.wildKinds = listOf(MapCrowd.WildKind(FollowerSprites.spriteFor(161, 0, false), 161, false))
        val pk = MapWanderer(scene, Random(14))
        pk.blocked = { gx, gy -> crowd.occupied(gx, gy) }
        var tw = 0L
        var out = 0
        var offGrass = 0
        var clash = 0
        var seenMax = 0
        var firstAt = -1L
        var appeared = 0
        var wentBack = 0
        while (tw < 240_000) {
            pk.update(tw)
            crowd.update(tw, pk.at, pk.next, pk.facing, pk.idle)
            for ((tile, appear) in crowd.popped) { if (appear) appeared++ else wentBack++; if (!tile.tallGrass) offGrass++ }
            crowd.popped.clear()
            val wild = crowd.wildOut()
            if (wild.isNotEmpty() && firstAt < 0) firstAt = tw
            seenMax = maxOf(seenMax, wild.size)
            for (w in crowd.walkers) {
                if (!w.at.tallGrass) offGrass++
                if ((w.at.gx == pk.at.gx && w.at.gy == pk.at.gy) || (pk.next?.let { it.gx == w.at.gx && it.gy == w.at.gy } == true)) clash++
            }
            out += wild.size
            tw += 50
        }
        check(firstAt in 0..10_000, "a wild Pokémon comes out within ten seconds ($firstAt ms)")
        check(appeared >= 6 && wentBack >= 4, "they come and go ($appeared out, $wentBack back in)")
        check(seenMax <= 2, "no more than two at once ($seenMax)")
        check(offGrass == 0, "always in the tall grass ($offGrass)")
        check(clash == 0, "never on the walking Pokémon's tile")
        val grassless = scene.tiles.values.count { !it.tallGrass }
        check(grassless >= 20, "ground without tall grass in sight too ($grassless tiles)")
        var spawnedOff = 0
        repeat(80) {
            val one = crowd.spawnWild(crowd.wildKinds[0], tw, 5_000)
            if (one != null && !one.at.tallGrass) spawnedOff++
            if (one != null) crowd.walkers.remove(one)
        }
        check(spawnedOff == 0, "they only ever come out of the tall grass ($spawnedOff of 80 elsewhere)")
        val w = crowd.spawnWild(crowd.wildKinds[0], tw, 5_000)
        check(w != null && w.pose(tw).hop == 0.0 && w.pose(tw + MapCrowd.APPEAR_MS / 2).hop > 4.0, "popping out, it hops up out of the grass")
    }

    // ---- the weather ----
    run {
        check(MapAmbience.weatherFor(FieldMap.COURSES[6], 100, 600) == MapAmbience.Weather.RAIN, "it always rains at the Lake of Rage, as in the game")
        check(MapAmbience.weatherFor(FieldMap.COURSES[12], 100, 600) == MapAmbience.Weather.DARK, "Dark Cave is dark")
        check(MapAmbience.weatherFor(FieldMap.COURSES[21], 100, 600) == MapAmbience.Weather.SNOW, "it snows on Mt. Silver")
        check(MapAmbience.weatherFor(FieldMap.COURSES[5], 100, 600) == MapAmbience.Weather.CLEAR, "no weather in Union Cave")
        val showers = (0 until 365).sumOf { d -> (0 until 24).count { h -> MapAmbience.weatherFor(FieldMap.COURSES[0], d, h * 60) == MapAmbience.Weather.RAIN } }
        check(showers in 365 * 24 / 25..365 * 24 / 6, "now and then a shower on Route 29 ($showers hours a year)")
        check(MapAmbience.weatherFor(FieldMap.COURSES[0], 7, 13 * 60 + 5) == MapAmbience.weatherFor(FieldMap.COURSES[0], 7, 13 * 60 + 50), "the weather lasts the hour")
        val rain = MapAmbience(scene, Random(3)).apply { weather = MapAmbience.Weather.RAIN }
        val dd = ArrayList<MapAmbience.Dot>()
        val cc = ArrayList<MapAmbience.Cloud>()
        var drops = 0
        for (k in 0 until 300) { rain.frame(k * 33L, 12 * 60, dd, cc); drops += dd.count { it.tall == 4 } }
        check(drops > 1_000 && rain.tint != 0 && cc.isEmpty(), "rain falls in streaks, darker, no cloud shadows ($drops)")
        val mist = MapAmbience(scene, Random(3)).apply { weather = MapAmbience.Weather.MIST }
        mist.frame(0, 7 * 60, dd, cc)
        check(cc.isNotEmpty() && cc.all { it.mist }, "mist drifts across in banks")
        val dark = MapAmbience(scene, Random(3)).apply { weather = MapAmbience.Weather.DARK }
        dark.frame(0, 12 * 60, dd, cc)
        check(dark.darkness > 0.5f, "dark")
        val fire = MapAmbience(scene, Random(3))
        fire.frame(49_967, 12 * 60, dd, cc)
        fire.sparks(100f, 100f, 50_000)
        fire.frame(50_033, 12 * 60, dd, cc)
        check(dd.count { it.x in 90f..110f && it.y in 85f..105f } >= 2, "a Fire type leaves sparks")
    }

    // ---- the games ----
    run {
        val crowd = MapCrowd(scene, emptyList(), Random(15))
        crowd.wildKinds = listOf(MapCrowd.WildKind(FollowerSprites.spriteFor(161, 0, false), 161, false))
        val pk = MapWanderer(scene, Random(16))
        pk.blocked = { gx, gy -> crowd.occupied(gx, gy) }
        val games = MapGames(scene, pk, crowd, Random(17))
        val fishModel = FollowerSprites.spriteFor(129, 0, false)
        games.fishKinds = listOf(MapCrowd.WildKind(fishModel, 129, true))
        games.bugKinds = listOf(MapCrowd.WildKind(FollowerSprites.spriteFor(123, 0, false), 123, false))
        games.unownModels = IntArray(26) { FollowerSprites.spriteFor(201, it, false) }
        val store = object : MapGames.BerryStore { override var planted = -1L; override var kind = 0; override var picked = 0 }
        val patch = scene.tiles.values.firstOrNull { t -> !t.tallGrass && listOf(0 to 1, 0 to -1, 1 to 0, -1 to 0).all { (dx, dy) -> scene.tile(t.gx + dx, t.gy + dy) != null } }
        check(patch != null, "a spot for the berry patch")
        games.berryTile = patch
        games.berryStore = store
        check(games.available() == MapGames.Game.values().toList(), "all five games here (${games.available()})")
        var tg = 1_000_000L
        fun run(ms: Long, each: () -> Unit = {}) {
            val until = tg + ms
            while (tg < until) {
                pk.update(tg)
                crowd.update(tg, pk.at, pk.next, pk.facing, pk.idle)
                games.update(tg)
                each()
                tg += 40
            }
        }
        // fishing: to the water, cast, a bite, reel it in
        check(games.start(MapGames.Game.FISHING, tg), "fishing starts")
        var bit = false
        var fishedFrom: FieldMap.Tile? = null
        run(25_000) { if (games.biting && !bit) { bit = true; fishedFrom = pk.at; games.tap(null, null, false, tg) } }
        check(bit, "something bites")
        check(games.leap != null || games.lineAt(tg)?.what == MapGames.What.FISH_CAUGHT || games.cries.contains(129), "and it's reeled in: a Magikarp")
        check(games.fishCaught == 1 && games.cries.contains(129), "one fish caught, and it cries")
        check(fishedFrom?.let { a -> listOf(0 to 1, 0 to -1, 1 to 0, -1 to 0).any { (dx, dy) -> scene.water.containsKey(FieldMap.Scene.key(a.gx + dx, a.gy + dy)) && scene.tile(a.gx + dx, a.gy + dy) == null } } == true,
            "it fished from the water's edge")
        games.cries.clear()
        run(3_000)
        check(games.game == null, "and that's the end of it")
        // a bite missed
        games.start(MapGames.Game.FISHING, tg)
        var missed = false
        run(25_000) { if (games.lineAt(tg)?.what == MapGames.What.FISH_AWAY) missed = true }
        check(missed && games.fishCaught == 1, "a bite not reeled in gets away")

        // tag: a wild one runs off, the Pokémon chases it
        check(games.start(MapGames.Game.TAG, tg), "tag starts")
        val target = crowd.wildOut().firstOrNull { it.flee }
        check(target != null && crowd.wildOut().count { it.flee } == 1, "with a wild Pokémon running away")
        var ended: MapGames.What? = null
        run(30_000) { val w = games.lineAt(tg)?.what; if (games.game == null && ended == null) ended = w }
        check(ended == MapGames.What.TAG_CAUGHT || ended == MapGames.What.TAG_AWAY, "tag ends tagged or got away ($ended)")
        run(2_000)

        // the Bug-Catching Contest
        check(games.start(MapGames.Game.CONTEST, tg), "the contest starts")
        check(games.game == MapGames.Game.CONTEST, "the contest is on")
        var caughtBugs = 0
        run(61_000) {
            if (games.game == MapGames.Game.CONTEST) crowd.walkers.firstOrNull { it.wild && !it.leaving && tg - it.bornAt > 600 }?.let { if (games.tap(it, null, false, tg)) caughtBugs++ }
        }
        check(caughtBugs >= 5, "bugs come out to be caught ($caughtBugs)")
        val end = games.lineAt(tg)
        check(games.game == null && end?.what == MapGames.What.CONTEST_END && end.species == 123 && end.m in 1..3, "after a minute: the best catch and a place ($end)")
        check(crowd.wildOut().none { it.person.species == 123 }, "the bugs go back in")

        // the Unown hunt: tap each faint Unown
        check(games.start(MapGames.Game.UNOWN, tg), "the Unown hunt starts")
        var faintSeen = false
        var done: MapGames.Line? = null
        run(120_000) {
            crowd.walkers.firstOrNull { it.faint && !it.leaving && tg - it.bornAt > 800 }?.let {
                faintSeen = faintSeen || it.pose(tg).alpha < 0.7f
                games.tap(it, null, false, tg)
            }
            if (done == null && games.lineAt(tg)?.what == MapGames.What.UNOWN_DONE) done = games.lineAt(tg)
        }
        check(faintSeen, "the Unown hide, faint")
        check(done != null && done!!.word in MapGames.UNOWN_WORDS && done!!.n == done!!.word.length, "found them all, they spell a word (${done?.word})")

        // the berry patch: plant, grow with steps, pick
        games.steps = 10_000
        check(games.berryStage() == -1, "nothing planted yet")
        fun visit(): MapGames.Line? {
            games.start(MapGames.Game.BERRY, tg)
            var said: MapGames.Line? = null
            run(15_000) { if (said == null && games.game == null) said = games.lineAt(tg) }
            return said
        }
        val planted = visit()
        check(store.planted == 10_000L && planted?.what == MapGames.What.BERRY_PLANTED, "it walks over and plants a berry ($planted)")
        games.steps = 10_350
        check(games.berryStage() == 1, "it grows with the steps (350 steps: a plant)")
        val growing = visit()
        check(growing?.let { it.what == MapGames.What.BERRY_GROWING && it.m == 550 } == true, "not ready yet: 550 more steps ($growing)")
        games.steps = 11_000
        check(games.berryStage() == 3, "900 steps: berries")
        val picked = visit()
        check(picked?.what == MapGames.What.BERRY_PICKED && store.picked in 2..4 && store.planted == 11_000L, "picked, and planted again (${store.picked})")
        val art = BerryArt.sprite(3, 0).frames[0]
        check(art.count { it == 0xFF3C64D8.toInt() } > 10 && BerryArt.sprite(-1, 0).frames[0].none { it == 0xFF3C64D8.toInt() }, "the plant has its berries drawn on it")

        // taps start games: on a wild one, tag; on the water, fishing
        games.stop(tg)
        val wild = crowd.spawnWild(crowd.wildKinds[0], tg, 20_000)
        run(1_000)
        if (wild != null) check(games.tap(wild, null, false, tg) && games.game == MapGames.Game.TAG, "a tap on a wild Pokémon is a game of tag")
        games.stop(tg)
        check(games.tap(null, scene.water.values.first(), false, tg) && games.game == MapGames.Game.FISHING, "a tap on the water is fishing")
        games.stop(tg)
        check(!pk.held, "stopping lets it go about again")
    }

    buildingsThatMove(src, scene, w, h)
    effectsAndLife(src, scene)

    // ---- the cave course: only the real cave floor ----
    val cave = FieldMap.render(src, FieldMap.COURSES[5], w, h, 900)
    check(cave.tiles.isNotEmpty(), "cave tiles to walk on")
    check(cave.tiles.values.all { it.gx in 4..12 && it.gy in 4..12 }, "in a cave only the floor joined to the cave counts, not the space around it")
    check(cave.lightKey == -1, "the cave's light doesn't change with the time")
    check(cave.tiles.values.all { near(it.y, 0.0) }, "the cave floor is at height 0")

    // ---- a ROM without the map files ----
    try {
        FieldMap.render(source(args[1]), course, w, h, 900)
        check(false, "no map files: an error")
    } catch (e: FieldMap.MapException) {
        check(e.wholeRom, "no map files: nothing can come from this ROM (${e.message})")
    }
    check(FollowerSprites.load(source(args[1]), 25, 0, false, false) == null, "no sprites in that ROM")
    check(FieldPeople.spots(source(args[1]), listOf(33)).isEmpty(), "no people in that ROM")
    check(FieldEffectArt.load(source(args[1])) == null, "no field effects in that ROM")

    println("$checks checks, $failures failed")
    exitProcess(if (failures == 0) 0 else 1)
}

private fun luma(c: Int) = (red(c) * 3 + green(c) * 6 + blue(c)) / 10

private fun pixel(scene: FieldMap.Scene, x: Double, y: Double, z: Double): Int? {
    val p = scene.project(x, y, z) ?: return null
    val px = p[0].toInt()
    val py = p[1].toInt()
    if (px !in 0 until scene.width || py !in 0 until scene.height) return null
    return scene.image[py * scene.width + px]
}

/** The windmill, the door, the house by the time of day, and the lights after dark. */
private fun buildingsThatMove(src: CryArchive.Source, scene: FieldMap.Scene, w: Int, h: Int) {
    check(PropAnimations.timeOfDay(300) == 0 && PropAnimations.timeOfDay(900) == 1 && PropAnimations.timeOfDay(1100) == 2 &&
        PropAnimations.timeOfDay(1320) == 3 && PropAnimations.timeOfDay(120) == 3, "the game's times of day")
    val props = scene.props
    check(props != null && props.props.size == 2, "two buildings move: the windmill and the door (${props?.props?.size})")
    if (props == null) return
    val mill = props.props.firstOrNull { !it.isDoor }
    val door = props.props.firstOrNull { it.isDoor }
    check(mill?.loop?.frames == 16, "the windmill turns round in 16 frames")
    check(door?.opening?.frames == 8 && door.closing?.frames == 8, "the door slides open in 8 frames, and shut in 8")
    if (mill == null || door == null) return
    // joint animations: a quarter turn about z at frame 4; the door 14 across when open
    val m4 = mill.loop!!.nodes(4, mill.model.nodes)[0]
    check(abs(m4[0]) < 0.01 && near(m4[1], -1.0) && near(m4[4], 1.0) && abs(m4[5]) < 0.01 && near(m4[10], 1.0), "frame 4: a quarter turn about z (${m4.take(11)})")
    check(near(door.opening!!.nodes(7, door.model.nodes)[0][3], 14.0) && near(door.opening!!.nodes(0, door.model.nodes)[0][3], 0.0) &&
        near(door.closing!!.nodes(7, door.model.nodes)[0][3], 0.0), "the door slides 14 across, and back")
    fun yellow(c: Int) = c ushr 24 != 0 && red(c) > 150 && green(c) > 130 && blue(c) < 110
    fun purple(c: Int) = c ushr 24 != 0 && red(c) > 90 && blue(c) > 120 && green(c) < 90
    val sails0 = props.overlay.count { yellow(it) }
    check(sails0 > 10, "the sails are drawn over the picture ($sails0 pixels)")
    check(scene.image.none { yellow(it) }, "and not in the picture itself, where they'd stand still")
    val before = props.overlay.copyOf()
    check(props.update(6, scene.image), "a few frames on, the sails have turned")
    check(!before.contentEquals(props.overlay), "and look different")
    check(!props.update(6, scene.image), "the same frame again: nothing to do")
    props.update(16 * 7, scene.image)
    check(props.overlay.count { yellow(it) } in sails0 - 3..sails0 + 3, "a whole number of turns later they're as they were")
    // the door, opening and shutting
    fun doorX(): Double {
        var n = 0; var sum = 0.0
        for (y in door.box[1]..door.box[3]) for (x in door.box[0]..door.box[2]) if (purple(props.overlay[y * w + x])) { n++; sum += x }
        return if (n == 0) -1.0 else sum / n
    }
    props.update(32, scene.image)
    val shut = doorX()
    check(shut > 0, "the door is drawn, shut")
    props.open(door, 40)
    check(props.doorBusy(door, 41) && !props.doorBusy(door, 39), "opened at frame 40")
    props.update(49, scene.image)
    val open = doorX()
    check(open > shut + 8, "it slides open ($shut -> $open)")
    props.open(door, 50)
    check(props.doorBusy(door, 40L + 8 + SceneProps.DOOR_HOLD + 4), "opening it again while it's open changes nothing")
    props.update(40L + 8 + SceneProps.DOOR_HOLD + 8 + 2, scene.image)
    check(abs(doorX() - shut) < 1.0 && !props.doorBusy(door, 40L + 8 + SceneProps.DOOR_HOLD + 8 + 2), "and shuts again after a while")
    // where the door is: the warp in the house's front, ground in front of it
    check(scene.doors.size == 1 && scene.doors[0].first.gx == 48 && scene.doors[0].first.gy == 21 && scene.doors[0].second === door, "the door's warp at (48, 21)")
    check(scene.tile(48, 22) != null && scene.tile(48, 21) == null, "with ground in front of it, the door itself not")
    // the Pokémon goes up to the door now and then (and stands looking at it)
    val pk = MapWanderer(scene, Random(31))
    var t = 0L
    var atDoor = 0L
    while (t < 900_000) {
        pk.update(t)
        if (pk.idle && pk.at.gx == 48 && pk.at.gy == 22 && pk.facing == FollowerSprites.UP) atDoor += 100
        t += 100
    }
    check(atDoor >= 2_000, "now and then it goes up to the door ($atDoor ms in 15 minutes)")

    // the house by the time of day, and its window and lamp lit after dark
    check(FieldMap.lightKind("h_mado", null) == FieldMap.WINDOW && FieldMap.lightKind("ybc_wd", null) == FieldMap.WINDOW &&
        FieldMap.lightKind("mat0", "si_mado.2") == FieldMap.WINDOW, "windows by their names")
    check(FieldMap.lightKind("ko_light", null) == FieldMap.LAMP && FieldMap.lightKind("kk_tourou", null) == FieldMap.LAMP &&
        FieldMap.lightKind("mat0", "wall") == 0 && FieldMap.lightKind(null, null) == 0, "lamps by theirs, and the rest neither")
    check(scene.glows.isEmpty(), "by day nothing is lit")
    val roofAt = doubleArrayOf(32 * 16 + 256.0, 64.0, 256.0 + 64)
    val evening = FieldMap.render(src, FieldMap.COURSES[0], w, h, 18 * 60 + 10)
    check(evening.timeOfDay == 2 && evening.glows.size == 4 && evening.glows[3] == FieldMap.LAMP.toFloat(), "in the evening the lamp is lit (${evening.glows.toList()})")
    val night = FieldMap.render(src, FieldMap.COURSES[0], w, h, 22 * 60)
    check(night.timeOfDay == 3 && night.glows.size == 8 && (0 until 2).map { night.glows[it * 4 + 3] }.toSet() == setOf(1f, 2f),
        "at night the window too (${night.glows.toList()})")
    check(night.lightKey != evening.lightKey && evening.lightKey != scene.lightKey, "each drawn with its own light")
    val roof = pixel(night, roofAt[0], roofAt[1], roofAt[2])
    check(roof != null && green(roof) > red(roof) * 6 / 10 && green(roof) > blue(roof), "at night the house has its night texture, yellow (%06x)".format((roof ?: 0) and 0xFFFFFF))
    val dayRoof = pixel(scene, roofAt[0], roofAt[1], roofAt[2])
    check(dayRoof != null && red(dayRoof) > green(dayRoof) * 2, "by day its own red (%06x)".format((dayRoof ?: 0) and 0xFFFFFF))
    val window = pixel(night, 768.0 + 19, 28.0, 256.0 + 96.3)
    val wall = pixel(night, 768.0 - 14, 28.0, 256.0 + 96.3)
    val dayWindow = pixel(scene, 768.0 + 19, 28.0, 256.0 + 96.3)
    check(window != null && wall != null && luma(window) > luma(wall) + 40, "the lit window shines in the night (%06x, the wall %06x)".format((window ?: 0) and 0xFFFFFF, (wall ?: 0) and 0xFFFFFF))
    check(window != null && dayWindow != null && red(window) - blue(window) > red(dayWindow) - blue(dayWindow) + 50,
        "warm (%06x, by day %06x)".format((window ?: 0) and 0xFFFFFF, (dayWindow ?: 0) and 0xFFFFFF))
    check(wall != null && luma(wall) < luma(0xF0D830) * 8 / 10, "while the walls are in the night's light")
    val wp = night.project(768.0 + 19, 28.0, 256.0 + 96.3)
    check(wp != null && (0 until night.glows.size / 4).any { k -> abs(night.glows[k * 4] - wp[0]) < 6 && abs(night.glows[k * 4 + 1] - wp[1]) < 6 }, "a glow round the window")
    // the item ball the map keeps, in sight
    check(scene.itemSpots.size == 1 && scene.itemSpots[0].gx == 36 && scene.itemSpots[0].gy == 11, "the map's item ball spot (${scene.itemSpots.map { "${it.gx},${it.gy}" }})")
    check(FieldPeople.itemBalls(src, listOf(33)) == listOf(36 to 11) && FieldPeople.warps(src, listOf(33)) == listOf(48 to 21), "item balls and warps from the event file")
}

/** The game's field effects, and life on the map. */
private fun effectsAndLife(src: CryArchive.Source, scene: FieldMap.Scene) {
    // ---- the effects' pictures from the ROM ----
    val art = FieldEffectArt.load(src)
    check(art != null, "the field effects are read")
    if (art == null) return
    check(Emote.values().all { art.emote(it).size == 2 }, "every emote bubble, two frames each")
    check(art.frames("shibuki").size == 3 && art.frames("hero_kemu").size == 4 && art.frames("ripple").size == 4 && art.frames("n_ripple").size == 4,
        "splash, dust and ripples, frame by frame")
    val kira = art.frames("seed_kira")
    check(kira.size == 2 && kira[0].width == 16 && kira[0].height == 32, "sparkles, 16x32")
    val ballArt = art.frames("monstarball")
    check(ballArt.size == 1 && ballArt[0].width == 16, "the item ball, found though it isn't where the game keeps it")
    val heart = art.emote(Emote.HEART)[0]
    check(heart.top == 5 && heart.bottom == 25, "a bubble's top and point (${heart.top}, ${heart.bottom})")
    val sandPrint = art.footprint(FollowerSprites.UP, false)
    val snowPrint = art.footprint(FollowerSprites.LEFT, true)
    check(sandPrint != null && snowPrint != null && sandPrint !== snowPrint && art.footprint(FollowerSprites.RIGHT, false) !== sandPrint, "footprints each way, in sand and in snow")
    check(sandPrint!!.pixels.any { red(it) in 130..170 && it ushr 24 != 0 } && snowPrint!!.pixels.any { blue(it) > 220 && it ushr 24 != 0 }, "sand's brown, snow's white")

    // ---- footprints and splashes ----
    val fx = FieldEffects(scene, art, Random(3))
    val sand = scene.tile(37, 16)
    val puddle = scene.tile(37, 19)
    val grass = scene.tile(44, 12)
    check(sand?.behavior == FieldEffects.SAND && puddle?.behavior == FieldEffects.PUDDLE && grass?.tallGrass == true, "sand, a puddle and grass in sight")
    if (sand == null || puddle == null || grass == null) return
    val drawn = ArrayList<FieldEffects.Draw>()
    fx.stepped(grass, FollowerSprites.UP, 1_000)
    check(fx.all.isEmpty(), "nothing left in the grass")
    fx.stepped(sand, FollowerSprites.UP, 1_000)
    fx.ground(1_000, drawn)
    check(fx.all.size == 1 && drawn.size == 1 && drawn[0].image === sandPrint && drawn[0].alpha == 1f, "a footprint in the sand")
    val sp = scene.project(sand.x, sand.y, sand.z)!!
    check(abs(drawn[0].left + drawn[0].width / 2 - sp[0]) < 2 && abs(drawn[0].top + drawn[0].height / 2 - sp[1]) < 2 &&
        drawn[0].width in 15f..19f && drawn[0].height in 8f..16f, "flat on its tile (${drawn[0].left},${drawn[0].top} ${drawn[0].width}x${drawn[0].height})")
    fx.ground(1_000 + FieldEffects.FOOTPRINT_MS - 1_500, drawn)
    check(drawn.size == 1 && drawn[0].alpha in 0.3f..0.7f, "slowly fading (${drawn.firstOrNull()?.alpha})")
    fx.ground(1_000 + FieldEffects.FOOTPRINT_MS, drawn)
    check(drawn.isEmpty() && fx.all.isEmpty(), "and gone")
    fx.stepped(sand, FollowerSprites.UP, 20_000)
    fx.stepped(sand, FollowerSprites.DOWN, 20_300)
    check(fx.all.size == 1 && fx.all[0].direction == FollowerSprites.DOWN, "a footprint trodden over")
    for (k in 0 until 50) fx.stepped(FieldMap.Tile(37, 16 + k, sand.x, sand.y, sand.z + k * 16, FieldEffects.SAND), FollowerSprites.DOWN, 21_000L + k)
    check(fx.all.count { it.kind == FieldEffects.Kind.FOOTPRINT } == FieldEffects.MAX_FOOTPRINTS, "no more than ${FieldEffects.MAX_FOOTPRINTS} footprints")
    fx.ground(40_000, drawn)
    // in a puddle: a splash and a ring
    fx.stepped(puddle, FollowerSprites.DOWN, 50_000)
    fx.air(50_050, drawn)
    val shibuki = art.frames("shibuki")
    check(drawn.size == 1 && drawn[0].image === shibuki[0], "a splash in the puddle")
    val pp = scene.project(puddle.x, puddle.y, puddle.z)!!
    check(abs(drawn[0].left + drawn[0].width / 2 - pp[0]) < 1 && drawn[0].top + shibuki[0].bottom in pp[1] - 2..pp[1] + 6, "round its feet")
    fx.air(50_000 + FieldEffects.SPLASH_MS - 20, drawn)
    check(drawn.size == 1 && drawn[0].image === shibuki[2], "rising, frame by frame")
    fx.ground(50_100, drawn)
    check(drawn.size == 1 && drawn[0].image === art.frames("ripple")[0], "and a ring spreading")
    fx.ground(50_000 + FieldEffects.RIPPLE_MS - 10, drawn)
    check(drawn.size == 1 && drawn[0].image === art.frames("ripple")[3] && drawn[0].alpha < 0.5f, "wider and fading")
    // dust, sparkles
    fx.dust(sand.x, sand.y, sand.z, 60_000)
    fx.sparkles(sand.x, sand.y, sand.z, 32, 60_000)
    fx.air(60_100, drawn)
    check(drawn.count { it.image in art.frames("hero_kemu") } == 2, "two puffs of dust")
    check(drawn.count { it.image in kira } == 1, "a sparkle, the next ones a moment after")
    fx.air(60_300, drawn)
    check(drawn.count { it.image in kira } == 3, "three sparkles")
    // behind the house nothing shows
    fx.stepped(FieldMap.Tile(47, 17, 47 * 16 + 8.0, 16.0, 17 * 16 + 8.0, FieldEffects.SAND), FollowerSprites.UP, 70_000)
    fx.ground(70_000, drawn)
    check(drawn.none { it.image === sandPrint }, "a footprint behind the house is hidden")
    check(FieldEffects(scene, null).let { f -> f.stepped(sand, 0, 0); f.ground(0, drawn); drawn.isEmpty() }, "without the pictures, nothing is drawn")

    // ---- emote bubbles ----
    val emotes = Emotes()
    val a = Any()
    emotes.show(a, Emote.HEART, 1_000, 1_000)
    check(emotes.at(a, 1_500)?.emote == Emote.HEART && emotes.at(a, 999) == null && emotes.at(a, 2_000) == null && emotes.at(Any(), 1_500) == null,
        "a bubble shows for its time, over whoever it's for")
    emotes.prune(3_000)
    check(emotes.at(a, 1_500) == null, "and is forgotten once it's over")
    check(Emotes.frame(0, 2) == 0 && Emotes.frame(320, 2) == 1 && Emotes.frame(640, 2) == 0 && Emotes.frame(500, 1) == 0, "its two pictures take turns")
    check(Emotes.rise(0) == 4f && Emotes.rise(60) in 1.5f..2.5f && Emotes.rise(200) == 0f, "it pops up")

    // ---- the walker's items ----
    val image = ByteArray(0x10000)
    for ((k, item) in listOf(17, 0, 155, 600).withIndex()) { image[0x8F8C + k * 2] = (item and 0xFF).toByte(); image[0x8F8D + k * 2] = (item shr 8).toByte() }
    check(WalkParty.routeItems(image) == listOf(17, 155), "the walk's items (route info +0x8C), leaving out empty ones")
    check(WalkParty.routeItems(ByteArray(10)).isEmpty() && WalkParty.fromImage(image).items == listOf(17, 155), "none without the route info")

    // ---- the Pokémon: moods, eating, running, sleep, what it did ----
    run {
        val pk = MapWanderer(scene, Random(41))
        var t = 0L
        pk.update(t)
        check(pk.canReach(scene.tile(40, 12)!!) && !pk.canReach(FieldMap.Tile(9999, 9999, 0.0, 0.0, 0.0)), "it knows where it can get to")
        // resting, it sits down when it stops
        pk.mood = -1
        val at0 = pk.at
        pk.placeAt(at0)
        t += MapWanderer.SIT_AFTER_MS + 600
        check(pk.pose(t).sink == 2, "resting, it sits down (${pk.pose(t).sink})")
        pk.mood = 0
        check(pk.pose(t).sink == 0, "otherwise it stands")
        // eating: little bobs
        pk.nibble(t, 1_000)
        check(pk.pose(t + 160).hop in 1.0..2.6 && pk.pose(t + 1_100).hop == 0.0, "eating, it bobs")
        // running
        val far = scene.tiles.values.filter { abs(it.gx - pk.at.gx) + abs(it.gy - pk.at.gy) in 5..7 }.firstOrNull { pk.canReach(it) }
        check(far != null, "somewhere to run to")
        if (far != null) {
            pk.rush(t + 10_000)
            check(pk.goTo(far, t), "it's sent there")
            val start = t
            while (t < start + 10_000 && (pk.at !== far || !pk.idle)) { t += 10; pk.update(t) }
            val steps = abs(far.gx - at0.gx) + abs(far.gy - at0.gy)
            check(pk.at === far && t - start <= steps * MapWanderer.RUN_MS + 400, "running, a step takes ${MapWanderer.RUN_MS} ms (${t - start} ms for $steps+ steps)")
        }
        pk.rush(Long.MIN_VALUE)
        // asleep: it lies facing the front and stays put
        pk.asleep = true
        val sleepAt = pk.at
        check(pk.facing == FollowerSprites.DOWN, "falling asleep it faces the front")
        var moved = false
        var sinks = HashSet<Int>()
        repeat(600) { t += 100; pk.update(t); if (pk.at !== sleepAt || pk.next != null) moved = true; sinks.add(pk.pose(t).sink); check(pk.pose(t).frame == 0, "asleep, no stepping in place") }
        check(!moved && sinks == setOf(3, 4), "asleep it doesn't go anywhere, lying down, breathing ($sinks)")
        pk.asleep = false
        repeat(300) { t += 100; pk.update(t); if (pk.at !== sleepAt) moved = true }
        check(moved, "awake again, it goes about")
        // what it did, for the map to show
        val explorer = MapWanderer(scene, Random(42))
        var te = 0L
        val seen = HashSet<MapWanderer.Happening>()
        while (te < 900_000) { explorer.update(te); seen.addAll(explorer.happenings); explorer.happenings.clear(); te += 100 }
        check(seen == MapWanderer.Happening.values().toSet(), "it sniffs, reads and lands from jumps ($seen)")
        val left = MapWanderer(scene, Random(42))
        var tl = 0L
        while (tl < 900_000) { left.update(tl); tl += 100 }
        check(left.happenings.size in 1..32, "left untaken, they don't pile up (${left.happenings.size})")
        // drawn sitting: lowered, its feet hidden, with its shadow
        val sprite = FollowerSprites.load(src, 25, 0, false, false)!!
        val painter = MapSpritePainter(scene, sprite)
        val spot = scene.tile(40, 12)!!
        painter.paint(MapWanderer.Pose(spot.x, spot.y, spot.z, FollowerSprites.DOWN, 0, 0.0, false))
        val standing = painter.buffer.count { it ushr 24 == 0xFF }
        val standTop = painter.spriteTop
        painter.paint(MapWanderer.Pose(spot.x, spot.y, spot.z, FollowerSprites.DOWN, 0, 0.0, false, sink = 3))
        check(painter.buffer.count { it ushr 24 == 0xFF } in standing / 2 until standing && painter.spriteTop == standTop + 3 && painter.buffer.any { it == 0x48000000 },
            "sitting it's lower, its feet hidden, its shadow there")
        // the one following, asleep
        val buddy = MapBuddy(scene, scene.tile(38, 12)!!)
        buddy.asleep = true
        val leader = MapWanderer(scene, Random(43))
        var tb = 0L
        val bAt = buddy.at
        repeat(300) { tb += 100; leader.update(tb); buddy.update(tb, leader) }
        check(buddy.at === bAt && buddy.pose(tb).sink >= 3 && buddy.pose(tb).facing == FollowerSprites.DOWN, "the one following sleeps where it is")
    }

    // ---- life on the map ----
    val people = scene.people
    class Life(val l: MapLife, val pk: MapWanderer, val crowd: MapCrowd, val g: MapGames?) {
        var now = 1_000_000L
        /** Moves everything on [ms], calling [each] with the time after every step. */
        fun run(ms: Long, minute: Int, each: (Long) -> Unit = {}) {
            val until = now + ms
            while (now < until) {
                pk.update(now)
                crowd.update(now, pk.at, pk.next, pk.facing, pk.idle, l.tiles())
                g?.update(now)
                l.update(now, minute)
                crowd.popped.clear()
                each(now)
                now += 40
            }
        }
    }
    fun life(seed: Int, minute: Int = 12 * 60, games: Boolean = false, body: Life.() -> Unit) {
        val crowd = MapCrowd(scene, people, Random(seed))
        val pk = MapWanderer(scene, Random(seed + 1))
        val effects = FieldEffects(scene, art, Random(seed + 2))
        val l = MapLife(scene, pk, crowd, effects, Emotes(), Random(seed + 3))
        val g = if (games) MapGames(scene, pk, crowd, Random(seed + 4)) else null
        l.games = g
        pk.blocked = { gx, gy -> crowd.occupied(gx, gy) || l.blocks(gx, gy) }
        l.free = { tile -> !crowd.occupied(tile.gx, tile.gy) }
        Life(l, pk, crowd, g).body()
    }
    val noon = 12 * 60
    val night = 22 * 60
    // an item ball turns up where the map keeps one, glints, and is fetched
    life(51) {
        l.items = intArrayOf(17, 155)
        pk.placeAt(scene.tile(40, 12)!!)
        pk.held = true
        val t0 = now
        var shownAt = -1L
        var glints = 0
        run(45_000, noon) { t ->
            if (l.ball != null && shownAt < 0) shownAt = t - t0
            glints = maxOf(glints, l.effects.all.count { it.kind == FieldEffects.Kind.SPARKLE })
        }
        val ball = l.ball
        check(shownAt in 19_000..41_000 && ball != null, "an item ball turns up after a while ($shownAt ms)")
        if (ball == null) return@life
        check(ball.tile.gx == 36 && ball.tile.gy == 11 && ball.item in listOf(17, 155), "where the map keeps one, with one of the walk's items (${ball.tile.gx},${ball.tile.gy}: ${ball.item})")
        check(glints >= 1 && l.blocks(36, 11), "glinting, and nobody steps on it")
        check(ball.spottedAt == Long.MIN_VALUE, "held, the Pokémon doesn't go for it")
        pk.held = false
        var exclaimed = false
        var onBall = 0
        var found: MapGames.Line? = null
        run(30_000, noon) { t ->
            if (l.emotes.at(pk, t)?.emote == Emote.EXCLAIM) exclaimed = true
            if (l.ball != null && pk.at.gx == 36 && pk.at.gy == 11) onBall++
            if (found == null) found = l.lines.firstOrNull { it.what == MapGames.What.ITEM_FOUND }
        }
        check(exclaimed && ball.spottedAt != Long.MIN_VALUE, "it spots it: \"!\"")
        check(found != null && found!!.n == ball.item && l.ball == null, "goes over and picks it up ($found)")
        check(onBall == 0, "without walking onto it")
    }
    // with none of its own, everyday items; one nobody spots goes again
    life(52) {
        pk.held = true
        run(45_000, noon)
        val ball = l.ball
        check(ball != null && ball.item in MapLife.COMMON_ITEMS, "with no items of the walk's own: everyday ones (${ball?.item})")
        run(MapLife.BALL_MS + 1_000, noon)
        check(l.ball !== ball, "one nobody spots goes again")
    }
    // a berry tossed: it runs over and eats it
    life(53) {
        pk.placeAt(scene.tile(40, 12)!!)
        run(1_000, noon)
        val spot = scene.tile(43, 12)!!
        check(l.toss(spot, 192f, 224f, now), "a berry is tossed")
        check(!l.toss(spot, 192f, 224f, now + 100), "one at a time")
        val treat = l.treat!!
        check(treat.tile === spot && l.blocks(43, 12), "it lands where it was thrown")
        var exclaimed = false
        val steps = ArrayList<Long>()
        var lastAt = pk.at
        var nibbles = 0
        var eaten: MapGames.Line? = null
        var heart = false
        run(12_000, noon) { t ->
            if (l.emotes.at(pk, t)?.emote == Emote.EXCLAIM) exclaimed = true
            if (pk.at !== lastAt) { if (t > treat.landsAt && treat.eatenAt == Long.MIN_VALUE) steps.add(t); lastAt = pk.at }
            if (treat.eatenAt != Long.MIN_VALUE && pk.pose(t).hop in 0.5..2.6) nibbles++
            if (eaten == null) eaten = l.lines.firstOrNull { it.what == MapGames.What.TREAT_EATEN }
            if (eaten != null && l.emotes.at(pk, t)?.emote == Emote.HEART) heart = true
        }
        check(exclaimed, "it sees it land: \"!\"")
        // (the step it was on when the berry landed it finishes at its own pace)
        check(steps.size >= 3 && steps.zipWithNext().drop(1).all { (x, y) -> y - x <= MapWanderer.RUN_MS + 50 }, "it runs over (${steps.zipWithNext().map { it.second - it.first }} ms a step)")
        check(nibbles > 5 && eaten != null && eaten!!.n == treat.kind && l.treat == null, "eats it, nibbling ($nibbles, $eaten)")
        check(heart && l.cries.contains(MapLife.OWN_CRY), "and loves it: a heart and a happy cry")
        // thrown at the water: it lands on the ground nearest
        val water = scene.water.values.first { scene.tile(it.gx, it.gy) == null }
        check(l.toss(water, 192f, 224f, now) && l.treat?.tile?.let { scene.tile(it.gx, it.gy) != null } == true, "thrown at the water, it lands on the shore")
        run(15_000, noon)
        check(l.treat == null, "and is eaten too")
        val from = pk.at
        check(l.tossNear(192f, 224f, now) && l.treat!!.tile.let { abs(it.gx - from.gx) + abs(it.gy - from.gy) in 2..4 }, "tossed from the menu: a few steps away")
    }
    life(54, games = true) {
        run(500, noon)
        g!!.fishKinds = listOf(MapCrowd.WildKind(FollowerSprites.spriteFor(129, 0, false), 129, true))
        check(g.start(MapGames.Game.FISHING, now), "a game starts")
        check(!l.toss(scene.tile(43, 12)!!, 0f, 0f, now), "no berries while a game is on")
    }
    // at night, left alone, it falls asleep; it wakes when tapped or when there are new steps
    life(55) {
        val buddy = MapBuddy(scene, listOf(0 to 1, 1 to 0, -1 to 0, 0 to -1).mapNotNull { (dx, dy) -> scene.tile(pk.at.gx + dx, pk.at.gy + dy) }.first())
        l.buddy = buddy
        val t0 = now
        var asleepAt = -1L
        run(40_000, night) { t -> buddy.update(t, pk); if (l.asleep && asleepAt < 0) asleepAt = t - t0 }
        check(asleepAt in 29_000..36_000, "at night, left alone half a minute, it falls asleep ($asleepAt ms)")
        val at = pk.at
        var moved = false
        var zz = 0
        var buddyZz = 0
        run(60_000, night) { t ->
            if (pk.at !== at) moved = true
            if (l.emotes.at(pk, t)?.emote == Emote.SLEEP) zz++
            if (l.emotes.at(buddy, t)?.emote == Emote.SLEEP) buddyZz++
        }
        check(!moved && l.asleep && pk.asleep && pk.pose(now).facing == FollowerSprites.DOWN && pk.pose(now).sink >= 3, "and sleeps where it is, lying down")
        check(zz > 1_400 && buddyZz > 1_000 && buddy.asleep, "\"Zz\" over it, and over the one following ($zz, $buddyZz)")
        l.interacted(now)
        check(!l.asleep && !pk.asleep && !buddy.asleep && l.emotes.at(pk, now + 50)?.emote == Emote.EXCLAIM, "a tap wakes it: \"!\"")
        val t1 = now
        var again = -1L
        run(40_000, night) { t -> if (l.asleep && again < 0) again = t - t1 }
        check(again in 29_000..36_000, "left alone again, it goes back to sleep ($again)")
        l.setSteps(1_000, 500, 10_000, now)
        check(l.asleep, "the steps there were don't wake it")
        l.setSteps(1_050, 550, 10_000, now + 1_000)
        check(!l.asleep, "new steps do")
    }
    life(57) {
        var slept = false
        run(120_000, noon) { if (l.asleep) slept = true }
        check(!slept, "by day it doesn't sleep")
    }
    life(58, games = true) {
        run(500, night)
        g!!.fishKinds = listOf(MapCrowd.WildKind(FollowerSprites.spriteFor(129, 0, false), 129, true))
        check(g.start(MapGames.Game.FISHING, now), "fishing at night")
        var slept = false
        run(24_000, night) { if (l.asleep) slept = true }
        check(!slept, "nor while playing a game")
    }
    // its mood follows today's steps
    life(59) {
        l.setSteps(20_000, 12_000, 10_000, now)
        run(200, noon)
        check(l.mood == 2 && pk.pace == 240L && pk.mood == 2, "lots of steps today: very lively, quick steps (${l.mood}, ${pk.pace})")
        l.setSteps(20_001, 6_000, 10_000, now)
        run(200, noon)
        check(l.mood == 1 && pk.pace == 265L, "a good few: lively")
        l.setSteps(20_002, 100, 10_000, now)
        run(200, noon)
        check(l.mood == 0 && pk.pace == MapWanderer.STEP_MS, "a few: as usual")
        var sat = 0
        run(MapLife.REST_AFTER_MS + 30_000, noon) { t -> if (pk.pose(t).sink == 2) sat++ }
        check(l.mood == -1 && pk.pace == 380L && sat > 100, "no steps for a while: it rests, sitting down ($sat)")
        l.setSteps(20_100, 200, 10_000, now)
        run(200, noon)
        check(l.mood == 0, "new steps get it going again")
    }
    // birds fly over, a Magikarp leaps out of the water, a shiny one sparkles
    life(60) {
        l.birds = listOf(MapCrowd.WildKind(FollowerSprites.spriteFor(16, 0, false), 16, false))
        l.magikarp = MapCrowd.WildKind(FollowerSprites.spriteFor(129, 0, false), 129, true)
        l.shiny = true
        val t0 = now
        var birdAt = -1L
        var leapAt = -1L
        var aloft = true
        val xs = ArrayList<Float>()
        var leapTile: FieldMap.Tile? = null
        var sparkles = 0
        var splashes = 0
        run(30_000, noon) { t ->
            val fl = l.flyers.firstOrNull()
            if (fl != null) {
                if (birdAt < 0) birdAt = t - t0
                val p = fl.pose(t)
                if (p != null && birdAt >= 0 && xs.size < 400) {
                    if (!p.aloft || p.hop < 30.0) aloft = false
                    if (t - t0 - birdAt < fl.ms - 100) scene.project(p.x, p.y, p.z)?.let { xs.add(it[0]) }
                }
            }
            if (l.leap != null && leapAt < 0) { leapAt = t - t0; leapTile = l.leap!!.tile }
            splashes = maxOf(splashes, l.effects.all.count { it.kind == FieldEffects.Kind.SPLASH })
            if (t - t0 < 12_000) sparkles = maxOf(sparkles, l.effects.all.count { it.kind == FieldEffects.Kind.SPARKLE })
        }
        check(birdAt in 11_000..28_000, "a bird flies over ($birdAt ms)")
        check(aloft && xs.size > 20 && (xs.first() < 0f || xs.first() > scene.width) && xs.zipWithNext().all { (x, y) -> (y - x) * (xs.last() - xs.first()) >= 0 },
            "high up, from beyond one side across (${xs.firstOrNull()} -> ${xs.lastOrNull()})")
        check(leapAt in 7_000..24_000 && leapTile?.let { scene.tile(it.gx, it.gy) == null && scene.water.containsKey(FieldMap.Scene.key(it.gx, it.gy)) } == true,
            "a Magikarp leaps out of the water ($leapAt ms)")
        check(splashes >= 1, "with a splash")
        check(sparkles >= 2, "a shiny Pokémon sparkles now and then ($sparkles)")
    }
    life(61) {
        var sparkles = 0
        run(15_000, noon) { sparkles = maxOf(sparkles, l.effects.all.count { it.kind == FieldEffects.Kind.SPARKLE }) }
        check(sparkles == 0 && l.flyers.isEmpty() && l.leap == null, "a plain one doesn't, and with no birds or fish none come")
    }
    // emote bubbles: people, the Pokémon spotting things
    life(62) {
        val man = crowd.walkers.first { it.person.trainer }
        pk.placeAt(scene.tile(40, 22)!!)
        pk.held = true
        var spotted = false
        run(200, noon) { t -> if (l.emotes.at(man, t)?.emote == Emote.EXCLAIM) spotted = true }
        check(spotted, "a trainer spots it in front of him: \"!\"")
        var again = 0
        val from = now
        run(10_000, noon) { t -> if (t > from + 2_000 && l.emotes.at(man, t) != null) again++ }
        check(again == 0, "just the once")
        pk.held = false
        pk.placeAt(scene.tile(39, 20)!!)
        pk.walk(FollowerSprites.RIGHT, false, now)
        var greeted: Emote? = null
        run(1_000, noon) { t -> if (greeted == null) greeted = l.emotes.at(man, t)?.emote }
        check(greeted == Emote.EXCLAIM, "walking up to him, he turns to it with a \"!\" (${greeted})")
        // a wild Pokémon pops out close by: it notices
        pk.placeAt(scene.tile(44, 14)!!)
        pk.held = true
        run(2_000, noon)
        crowd.popped.add(scene.tile(46, 14)!! to true)
        l.update(now, noon)
        check(l.emotes.at(pk, now)?.emote == Emote.EXCLAIM && pk.facing == FollowerSprites.RIGHT, "a wild Pokémon pops out near it: it turns to look, \"!\"")
        // tapped: happy; the step goal reached: hearts and hops; at a door: it peeks in
        l.patted(now + 1_000)
        check(l.emotes.at(pk, now + 1_000)?.emote in setOf(Emote.HEART, Emote.SMILE, Emote.HAPPY, Emote.NOTE, Emote.GLAD), "tapped, it's happy")
        l.celebrate(now + 2_000)
        check(l.emotes.at(pk, now + 2_500)?.emote == Emote.HEART && pk.pose(now + 2_100).hop > 3.0, "the step goal reached: a heart and a hop")
        l.update(now + 2_500, noon)
        check(pk.pose(now + 2_600).hop > 3.0, "and another")
        l.atDoor(now + 6_000)
        check(l.emotes.at(pk, now + 6_100) == null, "at a door it waits a moment")
        check(l.emotes.at(pk, now + 6_600)?.emote in setOf(Emote.QUESTION, Emote.NOTE, Emote.SILENT, Emote.EXCLAIM), "then peeks in")
    }
    // how the games go shows on its face; what it does too
    life(63, games = true) {
        run(500, noon)
        g!!.announce(MapGames.Line(MapGames.What.FISH_CAUGHT, 129, 30), now)
        check(g.lineAt(now + 100)?.what == MapGames.What.FISH_CAUGHT && g.lineAt(now + 6_000) == null, "something to say shows a while")
        l.update(now + 40, noon)
        check(l.emotes.at(pk, now + 100)?.emote == Emote.HAPPY, "a catch: it's delighted")
        g.announce(MapGames.Line(MapGames.What.TAG_AWAY, 16), now + 200)
        l.update(now + 240, noon)
        check(l.emotes.at(pk, now + 300)?.emote == Emote.SAD, "one that got away: sad")
        val seen = HashSet<Emote>()
        var dust = 0
        run(900_000, noon) { t ->
            l.emotes.at(pk, t)?.let { seen.add(it.emote) }
            dust = maxOf(dust, l.effects.all.count { it.kind == FieldEffects.Kind.DUST })
        }
        check(Emote.QUESTION in seen && Emote.SILENT in seen && dust > 0, "sniffing \"?\", reading \"...\", dust landing from a jump ($seen, $dust)")
        check(pk.happenings.isEmpty(), "what it did is taken each time")
    }
}
