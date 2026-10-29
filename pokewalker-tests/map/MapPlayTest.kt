import me.magnum.melonds.pokewalker.CritterArt
import me.magnum.melonds.pokewalker.CryArchive
import me.magnum.melonds.pokewalker.DailyVisitor
import me.magnum.melonds.pokewalker.Emote
import me.magnum.melonds.pokewalker.Emotes
import me.magnum.melonds.pokewalker.FieldEffectArt
import me.magnum.melonds.pokewalker.FieldEffects
import me.magnum.melonds.pokewalker.FieldMap
import me.magnum.melonds.pokewalker.FieldPeople
import me.magnum.melonds.pokewalker.FollowerSprites
import me.magnum.melonds.pokewalker.MapAmbience
import me.magnum.melonds.pokewalker.MapCritters
import me.magnum.melonds.pokewalker.MapCrowd
import me.magnum.melonds.pokewalker.MapGames
import me.magnum.melonds.pokewalker.MapLife
import me.magnum.melonds.pokewalker.MapPlay
import me.magnum.melonds.pokewalker.MapSpritePainter
import me.magnum.melonds.pokewalker.MapVisitor
import me.magnum.melonds.pokewalker.MapWanderer
import me.magnum.melonds.pokewalker.PadInput
import me.magnum.melonds.pokewalker.SpecialDates
import kotlin.math.abs
import kotlin.random.Random

/**
 * The route map's bigger picture (a view in the middle of more of the route), trees, rocks and
 * places to hide; the Pokémon walked about by hand and its little tricks; the newer games
 * (rustling grass and dowsing with the walker's say, hide and seek, headbutting trees, smashing
 * rocks, catching what falls); the little lives about the map and the special dates; today's
 * visitor; reading the AYN Thor and its controls. Run from MapTest's main.
 */
fun playAndCharm(src: CryArchive.Source, scene: FieldMap.Scene, w: Int, h: Int) {
    // the app draws the bigger picture, the view in its middle: the games are played on it
    val big = bigPicture(src, scene, w, h)
    tricksAndSteering(src, scene)
    newGames(big)
    lifeTricks(src, scene)
    critters(big)
    datesAndVisitor(scene)
    pad()
}

private fun dist(a: FieldMap.Tile, b: FieldMap.Tile) = abs(a.gx - b.gx) + abs(a.gy - b.gy)
private val DX = intArrayOf(0, 0, -1, 1)
private val DY = intArrayOf(-1, 1, 0, 0)

/** The picture twice the view each way: the view in its middle looks the same; trees, rocks, hiding places. */
private fun bigPicture(src: CryArchive.Source, scene: FieldMap.Scene, w: Int, h: Int): FieldMap.Scene {
    val big = FieldMap.render(src, FieldMap.COURSES[0], w * 2, h * 2, 900, w, h)
    check(big.width == w * 2 && big.height == h * 2, "a picture twice the view each way")
    check(big.view[0] == w / 2f && big.view[1] == h / 2f && big.view[2] == w * 1.5f && big.view[3] == h * 1.5f, "the view is its middle (${big.view.toList()})")
    check(big.start?.gx == scene.start?.gx && big.start?.gy == scene.start?.gy, "the same spot is picked for the view")
    // the same place in the same pixels, the picture's middle as the view
    var off = 0
    var compared = 0
    for (t in scene.tiles.values) {
        val a = scene.project(t.x, t.y, t.z) ?: continue
        if (a[0] !in 20f..(w - 20f) || a[1] !in 30f..(h - 20f)) continue
        val b = big.project(t.x, t.y, t.z) ?: continue
        compared++
        if (abs(b[0] - w / 2f - a[0]) > 0.6f || abs(b[1] - h / 2f - a[1]) > 0.6f) off++
    }
    check(compared > 50 && off == 0, "the view looks just as the picture did on its own ($off of $compared tiles moved)")
    val px = big.project(scene.start!!.x, scene.start!!.y, scene.start!!.z)!!
    check(big.inView(px[0], px[1]) && !big.inView(5f, 5f), "where the view is")
    check(big.tiles.size > scene.tiles.size, "more of the route around it to walk on (${big.tiles.size} > ${scene.tiles.size})")
    check(big.tiles.values.any { t -> big.project(t.x, t.y, t.z)?.let { !big.inView(it[0], it[1]) } == true }, "out past the view")

    // trees to headbutt: the game's tree tiles at the edge of ground in sight (up past the view here)
    check(scene.trees.isEmpty(), "none in the plain picture: they're further up")
    val tree = big.trees.firstOrNull { it.tile.gx == 48 && it.tile.gy == 6 }
    check(tree != null && tree.front.gx == 48 && tree.front.gy == 7 && tree.tile.behavior == FieldMap.HEADBUTT,
        "a tree with the ground right in front of it (${big.trees.map { "${it.tile.gx},${it.tile.gy} from ${it.front.gx},${it.front.gy}" }})")
    check(big.trees.size == 1, "a tree every other tile")
    if (tree != null) {
        val base = big.project(tree.front.x, tree.front.y, tree.front.z - 8.0)!!
        check(abs((tree.left + tree.right) / 2f - base[0]) < 3f && tree.bottom.toFloat() in base[1] - 3f..base[1] + 3f && tree.bottom - tree.top in 40..50,
            "its box over the trunk and leaves (${tree.left}..${tree.right} x ${tree.top}..${tree.bottom}, base ${base.toList()})")
    }
    check(big.trees.all { big.tile(it.tile.gx, it.tile.gy) == null && big.tile(it.front.gx, it.front.gy) != null }, "trees stand off the ground, their fronts on it")
    // rocks: the map's cracked rocks
    check(big.rocks.size == 1 && big.rocks[0].gx == 38 && big.rocks[0].gy == 7, "the map's cracked rock (${big.rocks.map { "${it.gx},${it.gy}" }})")
    check(FieldPeople.rocks(src, listOf(33)) == listOf(38 to 7), "rocks from the event file")
    check(FieldPeople.spots(src, listOf(33)).none { it.event.sprite == FieldPeople.BREAKROCK_SPRITE }, "a rock isn't one of the people")
    val rockSprite = FollowerSprites.loadStill(src, FieldPeople.modelFor(FieldPeople.BREAKROCK_SPRITE))
    check(rockSprite != null && rockSprite.frames.size == 1 && rockSprite.size == 32, "the rock's picture")
    // places to hide: ground behind the house
    for (s in listOf(scene, big)) {
        check(s.hiding.isNotEmpty() && s.hiding.all { s.tile(it.gx, it.gy) == null }, "ground hidden behind something (${s.hiding.size})")
        check(s.hiding.all { t -> (0 until 4).any { d -> s.tile(t.gx + DX[d], t.gy + DY[d]) != null } }, "each beside ground in sight")
        check(s.hiding.all { it.gx in 46..49 && it.gy in 15..17 }, "behind the house's roof (${s.hiding.map { "${it.gx},${it.gy}" }})")
    }
    // the grass shaking, from the game's effects
    val art = FieldEffectArt.load(src)
    check(art != null && art.frames("kusaeff").size == 4, "the grass shaking, four frames")
    return big
}

/** Walked about by hand, back to roaming on its own; its tricks; drawn stretched and shaken. */
private fun tricksAndSteering(src: CryArchive.Source, scene: FieldMap.Scene) {
    val pk = MapWanderer(scene, Random(71))
    var t = 1_000L
    pk.update(t)
    val start = pk.at
    // walked about by hand: it stays put for the D-pad, then roams again on its own
    pk.walk(FollowerSprites.LEFT, false, t)
    check(pk.steered(t), "walked about by hand")
    while (!pk.idle) { t += 20; pk.update(t) }
    val handAt = pk.at
    check(handAt !== start || pk.facing == FollowerSprites.LEFT, "a step the way it was walked")
    var moved = false
    while (t < 1_000 + MapWanderer.MANUAL_MS - 500) { t += 50; pk.update(t); if (pk.at !== handAt) moved = true }
    check(!moved && pk.steered(t), "it waits for more while being walked about")
    while (t < 1_000 + MapWanderer.MANUAL_MS + 30_000) { t += 50; pk.update(t); if (pk.at !== handAt) moved = true }
    check(moved && !pk.steered(t), "and roams on its own again ${MapWanderer.MANUAL_MS / 1000} s after")
    pk.walk(FollowerSprites.UP, false, t)
    pk.letGo(t)
    check(!pk.steered(t), "let go: it's on its own straight away")
    // tricks (kept where it is, so it doesn't stroll off between them)
    while (!pk.idle) { t += 20; pk.update(t) }
    pk.held = true
    t += 100
    pk.update(t)
    check(pk.perform(MapWanderer.Trick.STRETCH, t), "a stretch")
    val tall = pk.pose(t + 550).stretch
    check(tall > 1.1f && pk.pose(t + 2_000).stretch == 1f, "it stretches up, then it's itself ($tall)")
    check(pk.trickAt(t + 500) == MapWanderer.Trick.STRETCH && pk.trickAt(t + 5_000) == null, "while it does it")
    t += 2_000
    pk.update(t)
    pk.happenings.clear()
    check(pk.perform(MapWanderer.Trick.SHAKE, t), "a shake")
    check(pk.happenings.contains(MapWanderer.Happening.SHOOK), "the map's told it shook (drops fly)")
    val dxs = (0 until 12).map { pk.pose(t + it * 40L).dx }.toSet()
    check(dxs == setOf(-1, 1), "shaking side to side ($dxs)")
    t += 1_500
    pk.update(t)
    check(pk.perform(MapWanderer.Trick.SPIN, t), "a spin")
    val faces = (0 until 8).map { pk.pose(t + it * 110L).facing }.toSet()
    check(faces.size == 4, "it turns round, every way ($faces)")
    t += 1_500
    pk.update(t)
    pk.happenings.clear()
    check(pk.perform(MapWanderer.Trick.DIG, t) && pk.happenings.contains(MapWanderer.Happening.DUG), "digging")
    t += 2_000
    pk.update(t)
    check(pk.perform(MapWanderer.Trick.LOOK_UP, t) && pk.pose(t + 300).facing == FollowerSprites.UP, "looking up at the sky")
    pk.walk(FollowerSprites.DOWN, false, t + 400)
    check(pk.trickAt(t + 500) == null, "walking stops a trick")
    pk.asleep = true
    check(!pk.perform(MapWanderer.Trick.BOUNCE, t + 600), "no tricks asleep")
    pk.asleep = false
    // drawn stretched and shaken
    val sprite = FollowerSprites.load(src, 25, 0, false, false)!!
    val painter = MapSpritePainter(scene, sprite)
    val spot = scene.tile(40, 12)!!
    fun rows(): Int = (0 until painter.bufferHeight).count { y -> (0 until painter.bufferWidth).any { x -> painter.buffer[y * painter.bufferWidth + x] ushr 24 == 0xFF } }
    painter.paint(MapWanderer.Pose(spot.x, spot.y, spot.z, FollowerSprites.DOWN, 0, 0.0, false))
    val plainRows = rows()
    val plainLeft = painter.spriteLeft
    painter.paint(MapWanderer.Pose(spot.x, spot.y, spot.z, FollowerSprites.DOWN, 0, 0.0, false, stretch = 1.16f))
    check(rows() > plainRows, "stretched, it's drawn taller (${rows()} rows, else $plainRows)")
    painter.paint(MapWanderer.Pose(spot.x, spot.y, spot.z, FollowerSprites.DOWN, 0, 0.0, false, dx = 1))
    check(painter.spriteLeft == plainLeft + 1 && rows() == plainRows, "shaken, a pixel to the side")
}

/** The newer games, each played through. */
private fun newGames(scene: FieldMap.Scene) {
    val pikachu = MapCrowd.WildKind(FollowerSprites.spriteFor(25, 0, false), 25, false)
    val ledyba = MapCrowd.WildKind(FollowerSprites.spriteFor(165, 0, false), 165, false)
    val geodude = MapCrowd.WildKind(FollowerSprites.spriteFor(74, 0, false), 74, false)
    class Play(seed: Int) {
        val crowd = MapCrowd(scene, scene.people, Random(seed))
        val pk = MapWanderer(scene, Random(seed + 1))
        val g = MapGames(scene, pk, crowd, Random(seed + 2))
        val p = g.play
        var now = 2_000_000L
        val lines = ArrayList<MapGames.Line>()
        var berry: Pair<FieldMap.Tile, Int>? = null
        init {
            p.wildKinds = listOf(pikachu)
            p.headbuttKinds = listOf(ledyba)
            p.smashKinds = listOf(geodude)
            pk.blocked = { gx, gy -> crowd.occupied(gx, gy) || p.blocks(gx, gy, now) }
            p.onBerry = { t, k, _ -> berry = t to k }
            p.placeRocks()
            pk.update(now)
        }
        fun run(ms: Long, each: (Long) -> Unit = {}) {
            val until = now + ms
            while (now < until) {
                pk.update(now)
                crowd.update(now, pk.at, pk.next, pk.facing, pk.idle)
                g.update(now)
                g.lineAt(now)?.let { if (lines.lastOrNull() !== it) lines.add(it) }
                each(now)
                now += 40
            }
        }
        /** Whether [w] was said (or is being said now). */
        fun said(w: MapGames.What) = lines.any { it.what == w } || g.lineAt(now)?.what == w
    }

    // ---- what there is ----
    run {
        val s = Play(80)
        val av = s.g.available(s.now)
        check(av.containsAll(listOf(MapGames.Game.RUSTLING, MapGames.Game.DOWSING, MapGames.Game.HIDE_SEEK, MapGames.Game.HEADBUTT, MapGames.Game.ROCK_SMASH, MapGames.Game.CATCH)),
            "the newer games here ($av)")
        check(!s.g.start(MapGames.Game.RUSTLING, s.now) && !s.g.start(MapGames.Game.DOWSING, s.now), "rustling grass and dowsing need the walker's say first")
        check(s.p.rocks.size == 1 && !s.p.rocks[0].own && s.p.blocks(38, 7, s.now), "the map's rock, in the way")
    }

    // ---- rustling grass: the right patch, a Poké Ball, caught or not ----
    var caught = 0
    var brokeFree = 0
    for (seed in 0 until 24) {
        val s = Play(100 + seed * 7)
        val e = MapPlay.Encounter(1, 25, 12)
        check(s.g.startRustling(e, s.now) && s.g.game == MapGames.Game.RUSTLING, "the grass rustles")
        val patches = s.p.patches.toList()
        if (seed == 0) {
            val from = s.pk.at
            check(patches.size in 2..4 && patches.all { it.tile.tallGrass && dist(it.tile, from) in 2..9 }, "patches of grass near it (${patches.size})")
            check(patches.zipWithNext().all { (a, b) -> dist(a.tile, b.tile) >= 2 } && s.pk.held, "apart from each other; the Pokémon waits")
            check(s.said(MapGames.What.RUSTLE_ON) || s.g.lineAt(s.now)?.what == MapGames.What.RUSTLE_ON, "watch the grass")
            var light = false
            s.run(MapPlay.RUSTLE_WATCH_MS - 200) { t -> if (patches.any { s.p.rustle(it, t) > 0f }) light = true }
            check(light && patches.none { s.p.alert(it) }, "all of them rustle a little first")
            // too early: wait
            s.g.tap(null, null, false, s.now, patches[0].tile)
            check(s.g.lineAt(s.now)?.what == MapGames.What.RUSTLE_WAIT && s.g.game == MapGames.Game.RUSTLING, "too early: wait")
            s.run(400)
            val right = patches.single { s.p.alert(it) }
            check(s.p.rustle(right, s.now) >= 0.75f && s.g.lineAt(s.now)?.what == MapGames.What.RUSTLE_PICK, "then one shakes hard, with a \"!\"")
        } else s.run(MapPlay.RUSTLE_WATCH_MS + 200)
        val right = s.p.patches.firstOrNull { s.p.alert(it) } ?: continue
        if (seed == 1) {
            // the wrong one: it got away (the walker's watts are spent)
            val wrong = s.p.patches.first { it !== right }
            s.g.tap(null, null, false, s.now, wrong.tile)
            check(s.g.game == null && s.said(MapGames.What.RUSTLE_WRONG) && s.g.rewards.isEmpty(), "the wrong patch: it got away")
            continue
        }
        if (seed == 2) {
            // too slow
            s.run(MapPlay.RUSTLE_WINDOW_MS + 200)
            check(s.g.game == null && s.said(MapGames.What.RUSTLE_MISSED), "too slow: it slipped away")
            continue
        }
        if (seed == 3) {
            // walked into the right one with the D-pad: that's the pick too
            s.pk.placeAt((0 until 4).mapNotNull { d -> scene.tile(right.tile.gx + DX[d], right.tile.gy + DY[d]) }.first { !s.crowd.occupied(it.gx, it.gy) })
            s.pk.walk(MapWanderer.towards(s.pk.at, right.tile), false, s.now)
            s.run(800)
            val wild = s.p.radarWild
            check(s.said(MapGames.What.RUSTLE_APPEARED) && wild != null && dist(wild.at, right.tile) == 1 && s.pk.at === right.tile,
                "stepped into the shaking grass: a wild Pokémon jumps out in front of it (${wild?.at?.let { "${it.gx},${it.gy}" }})")
            check(wild != null && wild.at.gx == right.tile.gx + DX[s.pk.facing] && wild.at.gy == right.tile.gy + DY[s.pk.facing], "the way it was going")
            continue
        }
        s.g.tap(null, null, false, s.now, right.tile)
        val wild = s.p.radarWild
        check(wild != null && wild.at === right.tile && wild.person.species == 25 && s.said(MapGames.What.RUSTLE_APPEARED), "the right one: Pikachu jumps out of it")
        s.run(600)
        check(s.g.action(s.now) && s.p.ball != null && s.said(MapGames.What.RUSTLE_THROW), "A throws a Poké Ball")
        val ball = s.p.ball!!
        check(s.p.ballAt(s.now + 50)?.let { it[2] > 0f } == true, "it flies")
        s.run(MapPlay.THROW_MS + MapPlay.ABSORB_MS)
        check(wild != null && s.p.inBall(wild, s.now), "Pikachu goes into the ball")
        val leans = (0 until 20).mapNotNull { s.p.ballAt(s.now + it * 40L)?.get(3) }.map { if (it > 0.2f) 1 else if (it < -0.2f) -1 else 0 }.toSet()
        check(leans.size >= 2, "the ball rocks ($leans)")
        s.run(ball.doneAt - s.now + 1_200)
        if (ball.caught) {
            caught++
            val r = s.g.rewards.singleOrNull() as? MapGames.Reward.Caught
            check(r != null && r.slot == 1 && r.species == 25 && s.said(MapGames.What.RUSTLE_CAUGHT) && s.g.game == null && !s.pk.held,
                "caught: for the walker to keep (${s.lines.map { it.what }})")
            check(wild != null && wild !in s.crowd.walkers, "and it's gone from the map")
        } else {
            brokeFree++
            check(s.g.rewards.isEmpty() && s.said(MapGames.What.RUSTLE_BROKE_FREE) && s.g.game == null, "it broke free: nothing to keep")
            check(wild != null && (wild.leaving || wild.flee), "and runs off")
        }
    }
    check(caught > 0 && brokeFree > 0, "sometimes caught, sometimes not (route slot 1: 60% catch) ($caught caught, $brokeFree broke free)")
    check(MapPlay.catchChance(0) == 0.5 && MapPlay.catchChance(1) == 0.6 && MapPlay.catchChance(2) == 0.75, "the walker's catch chances")

    // ---- dowsing: warmer and colder, it digs it up ----
    run {
        val s = Play(200)
        check(s.g.startDowsing(17, s.now) && s.g.game == MapGames.Game.DOWSING, "dowsing")
        val spot = s.p.dowseSpot!!
        check(dist(spot, s.pk.at) in 4..10 && s.pk.canReach(spot, 30) && s.pk.held, "something buried a few steps away (${dist(spot, s.pk.at)})")
        check(s.g.lineAt(s.now)?.what == MapGames.What.DOWSE_ON, "walk about: warmer or colder?")
        // away from it first: colder
        val away = scene.tiles.values.filter { dist(it, spot) > dist(s.pk.at, spot) + 1 && dist(it, s.pk.at) in 1..2 }.firstOrNull { s.pk.canReach(it, 10) }
        if (away != null) {
            s.pk.goTo(away, s.now)
            s.run(2_000)
            check(s.said(MapGames.What.DOWSE_COLDER), "colder going away")
        }
        // then to it: warmer, hot, and dug up
        s.pk.goTo(spot, s.now)
        s.run(12_000)
        check(s.said(MapGames.What.DOWSE_WARMER) && s.said(MapGames.What.DOWSE_HOT), "warmer, then hot (${s.lines.map { it.what }})")
        check(s.said(MapGames.What.DOWSE_DIG), "standing on it, it digs")
        val r = s.g.rewards.singleOrNull() as? MapGames.Reward.Dowsed
        check(r != null && r.item == 17 && s.said(MapGames.What.DOWSE_FOUND) && s.g.game == null && !s.pk.held, "dug up: for the walker to keep")
    }
    run {
        val s = Play(210)
        s.g.startDowsing(17, s.now)
        val spot = s.p.dowseSpot!!
        // walking about everywhere but there, until it's out of steps
        var steps = 0
        while (s.g.game == MapGames.Game.DOWSING && steps < 80) {
            val next = (0 until 4).mapNotNull { d -> scene.tile(s.pk.at.gx + intArrayOf(0, 0, -1, 1)[d], s.pk.at.gy + intArrayOf(-1, 1, 0, 0)[d]) }
                .filter { dist(it, spot) >= 2 && !s.crowd.occupied(it.gx, it.gy) }.randomOrNull(Random(steps))
            if (next != null) s.pk.goTo(next, s.now)
            s.run(400)
            steps++
        }
        check(s.g.game == null && s.said(MapGames.What.DOWSE_NONE) && s.g.rewards.isEmpty(), "out of steps: it stays buried")
    }

    // ---- hide and seek ----
    run {
        val s = Play(300)
        check(s.g.start(MapGames.Game.HIDE_SEEK, s.now) && s.g.game == MapGames.Game.HIDE_SEEK, "hide and seek")
        val h = s.p.hider!!
        check(h.kind.species == 25 && (h.tile in scene.hiding || h.tile.tallGrass), "Pikachu hides behind something (or in the grass)")
        check(s.g.lineAt(s.now)?.what == MapGames.What.HIDE_ON && s.pk.held, "find it!")
        var peeks = 0
        var shown = false
        s.run(12_000) { t -> val p = s.p.hiderPose(t); if (p != null && !shown) peeks++; shown = p != null }
        check(peeks >= 1, "it peeks out now and then ($peeks)")
        val beside = (0 until 4).mapNotNull { d -> scene.tile(h.tile.gx + intArrayOf(0, 0, -1, 1)[d], h.tile.gy + intArrayOf(-1, 1, 0, 0)[d]) }
            .filter { !s.crowd.occupied(it.gx, it.gy) }.sortedBy { dist(it, s.pk.at) }.firstOrNull { s.pk.canReach(it, 60) }
        check(beside != null, "somewhere beside it to look")
        if (beside != null) {
            s.g.tap(null, null, false, s.now, beside)
            s.run(20_000)
            check(s.said(MapGames.What.HIDE_FOUND) && h.found, "walked up beside it: found! (${s.lines.map { it.what }})")
            check(s.g.cries.contains(25) && s.g.game == null && !s.pk.held, "a happy cry, and the game's over")
        }
    }
    run {
        val s = Play(310)
        s.g.start(MapGames.Game.HIDE_SEEK, s.now)
        s.run(MapPlay.HIDE_MS + 4_000)
        check(s.said(MapGames.What.HIDE_OVER) && s.g.game == null, "time's up: it comes out")
    }

    // ---- headbutt: the tree shakes, something might drop ----
    val drops = HashSet<MapGames.What>()
    for (seed in 0 until 16) {
        val s = Play(400 + seed * 3)
        val tree = scene.trees.first { it.tile.gx == 48 && it.tile.gy == 6 }
        check(s.g.headbutt(tree, s.now), "off to headbutt the tree")
        var shook = false
        var fell = false
        var from: FieldMap.Tile? = null
        var facing = -1
        s.run(12_000) { t ->
            if (s.p.treeShake(t)?.let { it != 0 } == true) { if (!shook) { from = s.pk.at; facing = s.pk.facing }; shook = true }
            if (s.p.drops.any { s.p.dropAt(it, t)?.let { d -> d[2] > 1f } == true }) fell = true
        }
        check(shook && from?.let { it.gx == tree.front.gx && it.gy == tree.front.gy } == true && facing == FollowerSprites.UP,
            "it goes up to the tree, faces it, and the tree shakes (from ${from?.let { "${it.gx},${it.gy}" }})")
        val what = s.lines.lastOrNull { it.what.name.startsWith("HEADBUTT") }?.what
        if (what != null) drops.add(what)
        when (what) {
            MapGames.What.HEADBUTT_POKEMON -> check(fell && s.crowd.walkers.any { it.wild && it.person.species == 165 }, "a Ledyba dropped out and lands")
            MapGames.What.HEADBUTT_BERRY -> check(fell && s.berry != null && dist(s.berry!!.first, tree.front) == 1, "a berry dropped out, to eat")
            else -> {}
        }
        check(s.g.game == null, "and it's over")
    }
    check(drops == setOf(MapGames.What.HEADBUTT_POKEMON, MapGames.What.HEADBUTT_BERRY, MapGames.What.HEADBUTT_NOTHING), "a Pokémon, a berry or nothing ($drops)")

    // ---- rock smash ----
    val found = HashSet<MapGames.What>()
    for (seed in 0 until 14) {
        val s = Play(500 + seed * 5)
        val rock = s.p.rocks.first()
        check(s.g.smash(rock, s.now), "off to smash the rock")
        var shook = false
        var chips = false
        s.run(12_000) { t -> if (rock.dx(t) != 0) shook = true; if (s.p.chips.isNotEmpty()) chips = true }
        check(shook && chips && !rock.standing(s.now) && !s.p.blocks(38, 7, s.now), "it shakes and breaks into bits (and isn't in the way)")
        s.lines.lastOrNull { it.what.name.startsWith("SMASH") }?.let { found.add(it.what) }
        if (seed == 0) {
            check(rock.backAt - rock.brokenAt >= MapPlay.ROCK_BACK_MS, "it comes back later")
            check(rock.standing(rock.backAt + 1) && rock.alpha(rock.backAt + 400) in 0.3f..0.7f && rock.alpha(rock.backAt + 900) == 1f, "fading back in")
        }
    }
    check(found == setOf(MapGames.What.SMASH_ITEM, MapGames.What.SMASH_POKEMON, MapGames.What.SMASH_NOTHING), "an item, a Pokémon or nothing ($found)")
    check(MapPlay.SMASH_ITEMS.contains(238), "Hard Stones in rocks")

    // ---- catch the falling ----
    run {
        val s = Play(600)
        val store = object : MapGames.ScoreStore { override var best = 7 }
        s.p.bestScore = store
        check(s.g.start(MapGames.Game.CATCH, s.now) && s.pk.held, "catch the falling")
        // walk under whatever's coming down
        var target: MapPlay.Drop? = null
        var seen = 0
        s.run(MapPlay.CATCH_MS + 1_000) { t ->
            val d = target
            if (d == null || d.caughtAt != Long.MIN_VALUE || t > d.landsAt) {
                target = s.p.drops.filter { it.kind >= MapPlay.FALL_LEAF && it.caughtAt == Long.MIN_VALUE && it.landsAt > t + 600 }.minByOrNull { it.landsAt }
                target?.let { seen++; s.pk.goTo(it.tile, t) }
            }
        }
        check(seen > 10, "lots coming down ($seen)")
        check(s.p.score > 7 && s.said(MapGames.What.CATCH_END), "caught some: ${s.p.score} points")
        check(store.best == s.p.score && s.lines.last { it.what == MapGames.What.CATCH_END }.word == "record", "a new best, kept")
        check(s.g.game == null && !s.pk.held && s.p.drops.none { it.kind >= MapPlay.FALL_LEAF }, "and it's over")
        check(MapPlay.points(MapPlay.FALL_LEAF) == 1 && MapPlay.points(MapPlay.FALL_BERRY) == 3 && MapPlay.points(MapPlay.FALL_GOLDEN) == 5, "points for leaves, berries, golden berries")
    }
    run {
        val s = Play(610)
        s.g.start(MapGames.Game.CATCH, s.now)
        var falling = 0
        s.run(10_000) { t -> falling = maxOf(falling, s.p.drops.count { d -> s.p.dropAt(d, t)?.let { it[2] > 20f } == true }) }
        val d = s.p.drops.firstOrNull()
        check(falling >= 1 && d != null && dist(d.tile, s.pk.at) <= 6, "things fall near it ($falling at once)")
        s.g.stop(s.now)
        check(s.g.game == null && !s.pk.held, "stopped")
    }
}

/** The Pokémon's little tricks about the map, looking up at a shooting star, a berry out of a tree, people humming. */
private fun lifeTricks(src: CryArchive.Source, scene: FieldMap.Scene) {
    val art = FieldEffectArt.load(src)!!
    class Life(seed: Int) {
        val crowd = MapCrowd(scene, scene.people, Random(seed))
        val pk = MapWanderer(scene, Random(seed + 1))
        val effects = FieldEffects(scene, art, Random(seed + 2))
        val l = MapLife(scene, pk, crowd, effects, Emotes(), Random(seed + 3))
        var now = 3_000_000L
        init {
            pk.blocked = { gx, gy -> crowd.occupied(gx, gy) || l.blocks(gx, gy) }
            l.free = { t -> !crowd.occupied(t.gx, t.gy) }
        }
        fun run(ms: Long, minute: Int, weather: MapAmbience.Weather = MapAmbience.Weather.CLEAR, each: (Long) -> Unit = {}) {
            val until = now + ms
            while (now < until) {
                pk.update(now)
                crowd.update(now, pk.at, pk.next, pk.facing, pk.idle, l.tiles())
                l.update(now, minute, weather)
                crowd.popped.clear()
                // kept awake (a tap now and then)
                if (now % 20_000 == 0L) l.interacted(now)
                each(now)
                now += 40
            }
        }
    }
    fun tricks(seed: Int, minute: Int, weather: MapAmbience.Weather = MapAmbience.Weather.CLEAR, each: (Life, Long) -> Unit = { _, _ -> }): Set<MapWanderer.Trick> {
        val s = Life(seed)
        val seen = HashSet<MapWanderer.Trick>()
        s.run(5 * 60_000L, minute, weather) { t -> s.pk.trickAt(t)?.let { seen.add(it) }; each(s, t) }
        return seen
    }
    val morning = tricks(70, 7 * 60)
    check(MapWanderer.Trick.STRETCH in morning && morning.size >= 2, "standing about in the morning it stretches, and does other little things ($morning)")
    val night = tricks(71, 22 * 60)
    check(MapWanderer.Trick.LOOK_UP in night, "on a clear night it looks up at the sky ($night)")
    val rain = tricks(72, 12 * 60, MapAmbience.Weather.RAIN)
    check(MapWanderer.Trick.SHAKE in rain, "in the rain it shakes itself off ($rain)")
    val day = tricks(73, 12 * 60)
    check((MapWanderer.Trick.DIG in day || MapWanderer.Trick.SPIN in day) && MapWanderer.Trick.LOOK_UP !in day && MapWanderer.Trick.SHAKE !in day,
        "by day it digs (not in tall grass), turns round ($day)")
    run {
        val s = Life(78)
        s.pk.held = true
        s.run(1_000, 12 * 60)
        check(s.pk.perform(MapWanderer.Trick.DIG, s.now), "digging")
        var dust = 0
        s.run(1_500, 12 * 60) { dust = maxOf(dust, s.effects.all.count { it.kind == FieldEffects.Kind.DUST }) }
        check(dust > 0, "dirt flying as it digs")
    }
    // out of the water: a good shake, drops flying
    run {
        val s = Life(74)
        val water = scene.water.values.firstOrNull { wt -> scene.tile(wt.gx, wt.gy) == null && (0 until 4).any { d -> scene.tile(wt.gx + DX[d], wt.gy + DY[d]) != null } }
        check(water != null, "water beside the ground")
        if (water != null) {
            s.pk.swims = true
            s.pk.placeAt(water)
            s.run(200, 12 * 60)
            val d = (0 until 4).first { d -> scene.tile(water.gx + DX[d], water.gy + DY[d]) != null }
            s.pk.walk(d, false, s.now)
            var shook = false
            var splashes = 0
            var sweat = false
            s.run(2_000, 12 * 60) { t ->
                if (s.pk.trickAt(t) == MapWanderer.Trick.SHAKE) shook = true
                splashes = maxOf(splashes, s.effects.all.count { it.kind == FieldEffects.Kind.SPLASH })
                if (s.l.emotes.at(s.pk, t)?.emote == Emote.SWEAT) sweat = true
            }
            check(shook && splashes >= 1 && sweat, "out of the water it shakes itself off, drops flying ($shook, $splashes, $sweat)")
        }
    }
    // a shooting star went over: it looks up, amazed
    run {
        val s = Life(75)
        s.pk.held = true
        s.run(2_000, 22 * 60)
        s.pk.held = false
        s.l.lookUp(s.now)
        check(s.pk.trickAt(s.now + 100) == MapWanderer.Trick.LOOK_UP && s.pk.pose(s.now + 100).facing == FollowerSprites.UP &&
            s.l.emotes.at(s.pk, s.now)?.emote == Emote.EXCLAIM, "a shooting star: it looks up, \"!\"")
    }
    // a berry dropped out of a tree: there to eat
    run {
        val s = Life(76)
        s.pk.placeAt(scene.tile(40, 12)!!)
        s.run(1_000, 12 * 60)
        val spot = scene.tile(43, 12)!!
        s.l.drop(spot, 2, s.now)
        val treat = s.l.treat
        check(treat != null && treat.tile === spot && treat.kind == 2 && s.l.blocks(43, 12), "a berry from a tree lies there")
        var eaten = false
        s.run(12_000, 12 * 60) { if (s.l.lines.any { it.what == MapGames.What.TREAT_EATEN }) eaten = true }
        check(eaten && s.l.treat == null, "and it goes over and eats it")
    }
    // people about hum a tune, have a think, smile
    run {
        val s = Life(77)
        s.pk.placeAt(scene.tile(56, 22) ?: s.pk.at)
        s.pk.held = true
        val folk = s.crowd.walkers.filter { !it.wild && !it.person.swimming }
        val seen = HashSet<Emote>()
        s.run(60_000, 12 * 60) { t -> for (w in folk) s.l.emotes.at(w, t)?.let { seen.add(it.emote) } }
        check(seen.any { it in setOf(Emote.NOTE, Emote.SILENT, Emote.QUESTION, Emote.SMILE) }, "people about hum, have a think, smile ($seen)")
    }
}

/** The little lives about the map, and by the date. */
private fun critters(scene: FieldMap.Scene) {
    val noon = 12 * 60
    val nightMinute = 22 * 60
    fun run(c: MapCritters, ms: Long, minute: Int, from: Long = 0L, pokemon: FloatArray? = null, each: (Long, List<MapAmbience.Dot>, List<MapCritters.Pic>) -> Unit = { _, _, _ -> }) {
        val dots = ArrayList<MapAmbience.Dot>()
        val pics = ArrayList<MapCritters.Pic>()
        var t = from
        while (t < from + ms) {
            c.frame(t, minute, pokemon, dots, pics)
            each(t, dots, pics)
            t += 33
        }
    }
    // butterflies by day, fluttering off when the Pokémon comes close
    fun butterfly(p: MapCritters.Pic) = !p.ground && p.art.width == 5 && p.art.height == 4
    run {
        val c = MapCritters(scene, Random(1))
        var most = 0
        var last: MapCritters.Pic? = null
        run(c, 90_000, noon) { _, _, pics -> val b = pics.filter(::butterfly); most = maxOf(most, b.size); last = b.firstOrNull() }
        check(most in 1..3, "butterflies by day ($most at most)")
        val b = last
        check(b != null, "one about")
        if (b != null) {
            // the Pokémon right by one: off it goes, quicker than it flutters about (15 pixels a second)
            var after: MapCritters.Pic? = null
            run(c, 1_000, noon, 90_000, floatArrayOf(b.x, b.y + 8f)) { _, _, pics -> after = pics.filter(::butterfly).firstOrNull() }
            val moved = after?.let { kotlin.math.hypot(it.x - b.x, it.y - b.y) } ?: 99f
            check(moved > 22f, "and flutters off as the Pokémon comes ($moved pixels in a second)")
        }
    }
    // little birds hopping about the ground, flying off
    run {
        val c = MapCritters(scene, Random(2))
        var ground = 0
        var birdAt: MapCritters.Pic? = null
        run(c, 90_000, noon) { _, _, pics -> val g = pics.filter { it.ground && it.art.width == 5 && (it.art.height == 4 || it.art.height == 5) }; ground = maxOf(ground, g.size); if (g.isNotEmpty()) birdAt = g[0] }
        check(ground >= 2, "small birds on the ground ($ground)")
        birdAt?.let { b ->
            var flying = 0
            run(c, 1_000, noon, 90_000, floatArrayOf(b.x + 4f, b.y)) { _, _, pics -> flying = maxOf(flying, pics.count { !it.ground && it.art.width == 5 && it.art.height == 3 }) }
            check(flying >= 1, "they fly off when it comes ($flying)")
        }
    }
    // fish under the water
    run {
        val c = MapCritters(scene, Random(3))
        var fish = 0
        run(c, 20_000, noon) { _, _, pics -> fish = maxOf(fish, pics.count { it.ground && it.art.width == 6 && it.art.height == 3 }) }
        check(scene.waterSpots.size < 16 || fish >= 1, "fish moving under the water ($fish)")
    }
    // a rainbow after the rain
    run {
        val c = MapCritters(scene, Random(4))
        c.weather = MapAmbience.Weather.RAIN
        run(c, 2_000, noon)
        check(c.rainbow == 0f, "no rainbow in the rain")
        c.weather = MapAmbience.Weather.CLEAR
        run(c, 8_000, noon, 2_000)
        check(c.rainbow > 0.2f, "a rainbow after it (${c.rainbow})")
        run(c, MapCritters.RAINBOW_MS + 1_000, noon, 10_000)
        check(c.rainbow == 0f, "for a while")
    }
    // shooting stars at night
    run {
        val c = MapCritters(scene, Random(5))
        run(c, 240_000, nightMinute)
        check(c.shootingStars >= 1, "a shooting star at night (${c.shootingStars})")
        val d = MapCritters(scene, Random(5))
        run(d, 240_000, noon)
        check(d.shootingStars == 0, "not by day")
    }
    // moths round the lamps at night (no lamps lit at 15:00 in the test picture), dew in the morning
    run {
        val c = MapCritters(scene, Random(6))
        var dew = 0
        run(c, 10_000, 7 * 60) { _, dots, _ -> dew = maxOf(dew, dots.size) }
        check(dew > 0, "dew glinting in the morning")
    }
    // by the date: Halloween's pumpkins (glowing after dark), in the way
    run {
        val c = MapCritters(scene, Random(7))
        c.occasions = setOf(SpecialDates.Occasion.HALLOWEEN)
        val p = c.pumpkins
        check(p.size in 1..3 && p.all { scene.tile(it.gx, it.gy) != null }, "pumpkins on the ground (${p.size})")
        check(p.all { c.blocks(it.gx, it.gy) }, "in the way")
        var pumpkins = 0
        var wisps = 0
        run(c, 20_000, nightMinute) { _, dots, pics -> pumpkins = maxOf(pumpkins, pics.count { it.ground && it.art.width == 9 }); wisps = maxOf(wisps, dots.count { it.size == 7 && it.tall == 7 }) }
        check(pumpkins == p.size && wisps >= 1, "lit up at night, and wisps about ($pumpkins pumpkins, $wisps wisps)")
        c.occasions = emptySet()
        check(p.none { c.blocks(it.gx, it.gy) }, "only at Halloween")
    }
    // autumn leaves lying about, blown up when walked through
    run {
        val c = MapCritters(scene, Random(8))
        c.occasions = setOf(SpecialDates.Occasion.AUTUMN)
        var lying: MapCritters.Pic? = null
        run(c, 1_000, noon) { _, _, pics -> pics.firstOrNull { it.ground && it.art.width == 3 }?.let { lying = it } }
        check(lying != null, "leaves lying on the ground")
        lying?.let { l ->
            c.disturb(l.x, l.y, 1_000)
            var up = false
            run(c, 600, noon, 1_000) { _, _, pics -> if (pics.any { !it.ground && it.art.width == 3 }) up = true }
            check(up, "walked through, they're blown up")
        }
        var falling = 0
        run(c, 6_000, noon, 2_000) { _, dots, _ -> falling = maxOf(falling, dots.size) }
        check(falling > 3, "autumn leaves falling ($falling)")
    }
    // New Year's fireworks at night, snow at Christmas, hearts on Valentine's Day
    run {
        val c = MapCritters(scene, Random(9))
        c.occasions = setOf(SpecialDates.Occasion.NEW_YEAR)
        var sparks = 0
        run(c, 8_000, 23 * 60) { _, dots, _ -> sparks = maxOf(sparks, dots.size) }
        check(sparks > 20, "fireworks ($sparks sparks)")
        val x = MapCritters(scene, Random(10))
        x.occasions = setOf(SpecialDates.Occasion.CHRISTMAS)
        var snow = 0
        run(x, 6_000, noon) { _, dots, _ -> snow = maxOf(snow, dots.size) }
        check(snow > 20, "snow falling at Christmas ($snow)")
        val v = MapCritters(scene, Random(11))
        v.occasions = setOf(SpecialDates.Occasion.VALENTINE)
        var hearts = 0
        run(v, 6_000, noon) { _, _, pics -> hearts = maxOf(hearts, pics.count { !it.ground && it.art.width == 5 && it.art.height == 5 }) }
        check(hearts >= 1, "hearts on Valentine's Day ($hearts)")
    }
    // our own little pictures
    val a = CritterArt.of(mapOf('x' to 0xFF112233.toInt()), "x.", ".x")
    check(a.width == 2 && a.height == 2 && a.pixels.toList() == listOf(0xFF112233.toInt(), 0, 0, 0xFF112233.toInt()), "a picture from rows of characters")
}

/** The special dates, today's visitor, and its visit. */
private fun datesAndVisitor(scene: FieldMap.Scene) {
    val noon = 12 * 60
    check(SpecialDates.on(10, 29, noon) == setOf(SpecialDates.Occasion.HALLOWEEN, SpecialDates.Occasion.AUTUMN), "29 October: Halloween, autumn")
    check(SpecialDates.Occasion.CHRISTMAS in SpecialDates.on(12, 25, noon) && SpecialDates.Occasion.CHRISTMAS !in SpecialDates.on(12, 27, noon), "Christmas")
    check(SpecialDates.Occasion.NEW_YEAR in SpecialDates.on(1, 1, noon) && SpecialDates.Occasion.NEW_YEAR in SpecialDates.on(12, 31, 19 * 60) &&
        SpecialDates.Occasion.NEW_YEAR !in SpecialDates.on(12, 31, 17 * 60), "New Year's Eve evening and New Year's Day")
    check(SpecialDates.on(4, 10, noon) == setOf(SpecialDates.Occasion.SPRING) && SpecialDates.on(3, 10, noon).isEmpty(), "spring blossoms")
    check(SpecialDates.Occasion.VALENTINE in SpecialDates.on(2, 14, noon) && SpecialDates.Occasion.POKEMON_DAY in SpecialDates.on(2, 27, noon), "Valentine's Day, Pokémon Day")
    check(SpecialDates.on(7, 1, noon) == setOf(SpecialDates.Occasion.SUMMER_NIGHTS) && SpecialDates.on(6, 1, noon).isEmpty(), "summer nights")
    // today's visitor
    val day = 20_755L
    val v = DailyVisitor.species(day, emptySet())
    check(v == DailyVisitor.species(day, emptySet()) && (v in DailyVisitor.GUESTS || v == DailyVisitor.MEW), "the same visitor all day")
    check((0 until 60L).map { DailyVisitor.species(day + it, emptySet()) }.toSet().size >= 25, "a different one most days")
    check(DailyVisitor.species(day, setOf(SpecialDates.Occasion.CHRISTMAS)) == DailyVisitor.DELIBIRD, "Delibird at Christmas")
    check(DailyVisitor.species(day, setOf(SpecialDates.Occasion.HALLOWEEN)) in DailyVisitor.SPOOKY, "a ghost at Halloween")
    check(DailyVisitor.species(day, setOf(SpecialDates.Occasion.POKEMON_DAY)) == DailyVisitor.PIKACHU, "Pikachu on Pokémon Day")
    check((0 until 3000L).any { DailyVisitor.species(it, emptySet()) == DailyVisitor.MEW }, "once in a long while, Mew")

    // the visit: it comes, says hello, plays, goes
    val crowd = MapCrowd(scene, scene.people, Random(90))
    val pk = MapWanderer(scene, Random(91))
    pk.blocked = { gx, gy -> crowd.occupied(gx, gy) }
    val emotes = Emotes()
    val kind = MapCrowd.WildKind(FollowerSprites.spriteFor(183, 0, false), 183, false)
    val visitor = MapVisitor(scene, kind, pk, emotes, Random(92))
    pk.blocked = { gx, gy -> crowd.occupied(gx, gy) || visitor.tiles().any { it.gx == gx && it.gy == gy } }
    visitor.blocked = { gx, gy -> crowd.occupied(gx, gy) }
    var t = 5_000_000L
    val states = LinkedHashSet<MapVisitor.State>()
    var greeted = false
    var played = false
    var met = false
    while (t < 5_000_000L + 6 * 60_000) {
        pk.update(t)
        crowd.update(t, pk.at, pk.next, pk.facing, pk.idle, visitor.tiles())
        visitor.update(t)
        states.add(visitor.state)
        if (visitor.lines.isNotEmpty()) { greeted = greeted || visitor.lines.any { it.what == MapGames.What.VISITOR_HI && it.species == 183 }; visitor.lines.clear() }
        visitor.walker?.let { vw -> if (abs(vw.at.gx - pk.at.gx) + abs(vw.at.gy - pk.at.gy) == 1 && visitor.state == MapVisitor.State.GREETING) met = true }
        if (emotes.at(pk, t)?.emote == Emote.NOTE && visitor.state == MapVisitor.State.STAYING) played = true
        t += 50
    }
    check(states.containsAll(listOf(MapVisitor.State.AWAY, MapVisitor.State.ARRIVING, MapVisitor.State.GREETING, MapVisitor.State.STAYING, MapVisitor.State.LEAVING)),
        "it comes, says hello, stays and goes ($states)")
    check(met && greeted && visitor.cry, "it walks up beside the Pokémon and says hello, with its cry")
    check(played, "they play together a while")
    check(visitor.state == MapVisitor.State.AWAY || visitor.state == MapVisitor.State.LEAVING, "then it's off (${visitor.state})")
    // quiet (a game on): it doesn't come
    val quiet = MapVisitor(scene, kind, pk, Emotes(), Random(93))
    quiet.quiet = true
    var q = 9_000_000L
    while (q < 9_000_000L + 120_000) { quiet.update(q); q += 100 }
    check(quiet.state == MapVisitor.State.AWAY, "not while a game's on")
}

/** The AYN Thor, and which way a hat or stick points. */
private fun pad() {
    check(PadInput.isThor("AYN", "AYN", "AYN Thor", "thor", "thor"), "an AYN Thor")
    check(PadInput.isThor("ayn", null, "Thor", null, null), "whatever the case")
    check(!PadInput.isThor("AYN", "AYN", "Odin2", "odin2", "odin2"), "an AYN Odin isn't a Thor")
    check(!PadInput.isThor("Ayaneo", "AYANEO", "Pocket S", "pockets", "pockets") && !PadInput.isThor("Google", "google", "Pixel 8", "shiba", "shiba"), "nor anything else")
    check(PadInput.direction(0f, -1f) == FollowerSprites.UP && PadInput.direction(0f, 1f) == FollowerSprites.DOWN &&
        PadInput.direction(-1f, 0f) == FollowerSprites.LEFT && PadInput.direction(1f, 0f) == FollowerSprites.RIGHT, "the hat's four ways")
    check(PadInput.direction(0.2f, -0.3f) == -1 && PadInput.direction(Float.NaN, 1f) == -1, "near the middle: nowhere")
    check(PadInput.direction(0.9f, 0.6f) == FollowerSprites.RIGHT && PadInput.direction(-0.4f, 0.8f) == FollowerSprites.DOWN, "a stick: the way it's pushed most")
}
