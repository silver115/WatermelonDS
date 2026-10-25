import me.magnum.melonds.pokewalker.CryArchive
import me.magnum.melonds.pokewalker.FieldAnimations
import me.magnum.melonds.pokewalker.FieldMap
import me.magnum.melonds.pokewalker.MapAmbience
import me.magnum.melonds.pokewalker.FollowerSprites
import me.magnum.melonds.pokewalker.MapSpritePainter
import me.magnum.melonds.pokewalker.MapWanderer
import me.magnum.melonds.pokewalker.WalkingPokemon
import java.io.RandomAccessFile
import kotlin.math.abs
import kotlin.random.Random
import kotlin.system.exitProcess

/**
 * The route map on the fake ROM from gen_map_rom.py: which spot is picked, what is drawn, which
 * tiles the Pokémon may walk on and how high, the follower sprite, the wandering and the drawing
 * of the sprite behind things. Usage: MapTest <fake.nds> <fake_without_maps.nds>
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
    check(scene.lightKey == FieldMap.lightIndex(900), "drawn with the 15:00 light")
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
            if (abs(at.gx - prev.gx) + abs(at.gy - prev.gy) != 1 || !FieldMap.stepOk(prev, at)) jumps++
            prev = at
        }
        seen.add(FieldMap.Scene.key(at.gx, at.gy))
        check(pose.hop in 0.0..MapWanderer.HOP_HEIGHT, "hop height")
        t += 50
    }
    check(outside == 0, "it never stands off the walkable tiles")
    check(jumps == 0, "it only ever steps to a neighbouring tile ($jumps jumps)")
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

    println("$checks checks, $failures failed")
    exitProcess(if (failures == 0) 0 else 1)
}
