package me.magnum.melonds.pokewalker

import kotlin.math.abs
import kotlin.random.Random

/**
 * The people of the place: the townsfolk and trainers HeartGold/SoulSilver puts on each map,
 * read from the player's own ROM.
 *
 * Where the game keeps them (pret/pokeheartgold):
 *  - each map's header names its events (src/data/map_headers.h `eventsBank`), a member of
 *    `a/0/3/2` (fielddata/eventdata/zone_event.narc); a member is: u32 count + 20-byte bg
 *    events, then u32 count + 32-byte objects (files/fielddata/eventdata/zone_event.json.txt);
 *  - an object: u16 id, sprite, movement, type (1 = trainer), hide flag, script, facing,
 *    3 params, x range, y range, x, z (tiles across the whole matrix), u32 height;
 *  - the game hides an object while its flag is set; flag 0 never is, so those are the people
 *    who are always there (story characters and item balls all have flags of their own);
 *  - a sprite number picks an overworld sprite in `a/0/8/1` (the table in overlay 1,
 *    asm/overlay_01_sprite_data.s): people have 16 frames, four a way (stand, step, stand,
 *    other step; up, down, left, right), Pokémon the follower sprites' eight.
 */
object FieldPeople {
    const val ZONE_EVENTS = "a/0/3/2"

    /** Each map's events (its zone_event member), three hex digits per map number; fff: none. */
    private const val EVENTS =
        "00000000100000200300400500000600700800900a00b00c00d00e00f01001101201301401501601701801901a01b01c01d01e01f0200210" +
        "2202302402502602702802902a02b02c02d02e02f03003103203303403503603703803903a03b03c03d03e03f04004104204304404504604" +
        "704804904a04b04c04d04e04f05005105205305405505605705805905a05b05c05d05e05f06006106206306406506606706806906a06b06c" +
        "06d06e06f07007107207307407507607707807907a07b07c07d07e00007f08008108208308408508608708808908a08b08c08d08e08f0000" +
        "9009109209309409509609709809909a09b00009c09d09e09f0a00a10a20a30a40a50a60a70a80000a90aa0ab0ac0ad0ae0af0b00b10b20b" +
        "30b40b50b60b70b80b90ba0bb0bc0bd0be0bf0c00c10c20c30c40c50c60c70c80000000000000000000c90ca0cb0cc0000cd0ce0cf0d00d1" +
        "0d20d30d40d50d60d70d80d90da0db0dc0dd0de0df0e00e10e20e30e40e50e60e70e80e90ea0eb0ec0ed0ee0ef0000000000000000000000" +
        "000000000000f00f10000000000000f20f30f40f50f60f70f80f90fa0fb0fc0fd0fe0ff10010110210310410510610710810910a10b10c10" +
        "d10e10f11011111211311411511611711811911a11b11c11d11e11f12012112212312412512612712812912a12b12c12d12e12f130131132" +
        "13313413513613713813900000000000000000000000000000000000000000013a13b13c13d13e13f1401411421431441451461471481491" +
        "4a14b14c14d14e14f15015115215315415515615715815915a15b15c15d15e15f16016116216316416516616716816916a00016b16c16d16" +
        "e16f17017117217300017417517617717817917a17b17c17d17e17f18018118218318418518618718818918a18b18c18d18e18f190191192" +
        "19319419519619719819919a19b19c00019d19e19f1a01a11a21a31a41a50001a61a71a81a91aa1ab1ac1ad1ae1af1b01b11b21b31b41b51" +
        "b61b71b80001b91ba1bb1bc1bd1be1bf1c01c11c21c31c41c51c61c71c81c91ca1cb1cc1cd1ce1cf1d01d11d21d31d41d51d61d71d81d91d" +
        "a1db1dc1dd1de1df1e01e11e21e31e41e51e61e71e81e90001eafff"

    /** Sprite number -> overworld sprite (mmodel), three hex digits each, for every sprite the maps use. */
    private const val SPRITES =
        "00300200500400600500700600b00a00d00d00e00e01d01901f02402101602202502302602502802802902a02b03202f03303003e01b03f0" +
        "1c04602105405b05505c05605d05705e0630360800d90810da08d03e08e03f08f04009004109104209204309403a0a80390b70000c10380d" +
        "20ef0d30aa0da0ab0db0530dc0ac0dd0ad0de0ae0df0af0e00b00e10b10e30b20e50b30e80b40e90df0ea0f20eb0b50ec0b60ed0b70fb000" +
        "0fc0000fd0000fe0000ff0001000001010001060fb11a06c11b06d11c06e11e06611f06f1200001210001220701230001240001250f41260" +
        "f512b0fa13b07113c07213d07313e07413f07514007614107714207814307914407a14507b14607c14707d14807e14907f14a08014b08114" +
        "c08214d08314e08414f08515008615108715208815508915608a15708b15808c15908d15a08e15b08f15c09015d0f315e0e515f091160092" +
        "16109316209416309516409616509716609816709916809a16909b16a09c16b09d16c09e16d09f16e0a016f0a11700a21710a31720a41730" +
        "a51740a61750a71760a81770a91780e61790b817a0b917b0e717c0e017d0ba17e0bb17f0bc1800bd1810e11820e21830e31850be1860e418" +
        "80bf1890c018a0c118b0c218c0f118d0f018e0ed18f0ea1900eb1910ec1920ee1930e81940e91960c31990c819a0c619b0c519c0c719d0c4" +
        "19e0d11a503b1a603c1a90d21aa0d31ab0d43e21353e31393e413c3e513e3e613f3e71423e81473e914a3ea14b3eb14d3ec1513ed1533ee1" +
        "553ef15c3f015e3f115f3f21603f31683f41693f516c3f616d3f71793f817a3f917d3fa17f3fb18f3fe19d4001ba4011bb4021bc4031bd40" +
        "41bf4051c04061c34071e14081e34091ea40a1f240b23b40c23c40d23d40e23e40f23f4112c64122c74132c84142c94152ca4161d8"

    private val spriteModels: Map<Int, Int> by lazy {
        val m = HashMap<Int, Int>()
        var i = 0
        while (i + 6 <= SPRITES.length) {
            m[SPRITES.substring(i, i + 3).toInt(16)] = SPRITES.substring(i + 3, i + 6).toInt(16)
            i += 6
        }
        m
    }

    /** The zone_event member with map [map]'s people; -1 when there's none. */
    fun eventsBank(map: Int): Int {
        val i = map * 3
        if (map < 0 || i + 3 > EVENTS.length) return -1
        val v = EVENTS.substring(i, i + 3).toInt(16)
        return if (v == 0xFFF) -1 else v
    }

    /** The overworld sprite (mmodel) for a sprite number; -1 when it isn't one the maps use (or is picked by the story). */
    fun modelFor(sprite: Int): Int = spriteModels[sprite] ?: -1

    /** An object on a map, as in the event file. */
    class ObjectEvent(
        val sprite: Int, val movement: Int, val type: Int, val flag: Int, val facing: Int,
        val xRange: Int, val yRange: Int, val x: Int, val z: Int,
    ) {
        val trainer get() = type == 1
        /** Always there: no flag that could hide it. */
        val alwaysShown get() = flag == 0
    }

    /** The objects of an event file (src/map_events.c). */
    fun readObjects(b: ByteArray): List<ObjectEvent> {
        if (b.size < 8) throw Nitro.FormatException("event file too short")
        val bgs = Nitro.s32(b, 0)
        if (bgs < 0 || bgs > 4096) throw Nitro.FormatException("event file: $bgs bg events")
        var p = 4 + bgs * 20
        if (p + 4 > b.size) throw Nitro.FormatException("event file: no objects")
        val n = Nitro.s32(b, p)
        p += 4
        if (n < 0 || n > 1024 || p + n * 32 > b.size) throw Nitro.FormatException("event file: $n objects")
        return List(n) { i ->
            val o = p + i * 32
            fun s16(at: Int) = Nitro.u16(b, o + at).toShort().toInt()
            ObjectEvent(
                sprite = Nitro.u16(b, o + 2), movement = Nitro.u16(b, o + 4), type = Nitro.u16(b, o + 6),
                flag = Nitro.u16(b, o + 8), facing = Nitro.u16(b, o + 12),
                xRange = s16(20), yRange = s16(22), x = s16(24), z = s16(26),
            )
        }
    }

    /** Someone standing in the picture: their sprite, where they stand and how they move about. */
    class Person(
        val model: Int,
        val home: FieldMap.Tile,
        val movement: Int,
        val facing: Int,
        val xRange: Int,
        val yRange: Int,
        val trainer: Boolean,
        /** Out in the water (swimmers): moves over water, no shadow. */
        val swimming: Boolean,
    )

    /** Someone a map always has, with a sprite that's a person or a Pokémon (not a sign, tree or rock). */
    class Spot(val event: ObjectEvent, val model: Int)

    /** The people always on the maps in [maps] (each map's events once), who have a sprite to draw. */
    fun spots(src: CryArchive.Source, maps: Collection<Int>, log: (String) -> Unit = {}): List<Spot> {
        val narc = Nitro.narc(src, ZONE_EVENTS) ?: return emptyList()
        val mmodel = Nitro.narc(src, FollowerSprites.MMODEL) ?: return emptyList()
        val figures = HashMap<Int, Boolean>()
        val banks = HashSet<Int>()
        val out = ArrayList<Spot>()
        for (map in maps) {
            val bank = eventsBank(map)
            if (bank < 0 || bank >= narc.count || !banks.add(bank)) continue
            val objects = runCatching { readObjects(narc.read(bank)) }.getOrElse { log("No people for map $map: ${it.message}"); continue }
            for (o in objects) {
                if (!o.alwaysShown) continue
                val model = modelFor(o.sprite)
                if (model < 0 || model >= mmodel.count) continue
                val figure = figures.getOrPut(model) {
                    runCatching {
                        val n = Nitro.parse(mmodel.read(model)).texSet?.textures?.size ?: 0
                        n >= 16 || (n >= 8 && model >= FollowerSprites.FIRST)
                    }.getOrDefault(false)
                }
                if (figure) out.add(Spot(o, model))
            }
        }
        return out
    }

    /** The people of [spots] standing on a tile of [land] or [water] (one to a tile). */
    fun place(spots: List<Spot>, land: Map<Long, FieldMap.Tile>, water: Map<Long, FieldMap.Tile>): List<Person> {
        val out = ArrayList<Person>()
        val taken = HashSet<Long>()
        for (s in spots) {
            val o = s.event
            val key = FieldMap.Scene.key(o.x, o.z)
            val onLand = land[key]
            val tile = onLand ?: water[key] ?: continue
            if (!taken.add(key)) continue
            out.add(Person(s.model, tile, o.movement, o.facing.coerceIn(0, 3), abs(o.xRange), abs(o.yRange), o.trainer, onLand == null))
        }
        return out
    }
}

/**
 * The people of a [FieldMap.Scene] going about the way the game's people do (MapObject movement
 * types in overlay 5): standing still, turning to look around, or a step at a time back and forth
 * within the few tiles of home the map gives them. They never walk into each other or the
 * walking Pokémon, and when it comes up to someone and faces them, they turn to it as if talked to.
 * Pure Kotlin, so it can be tested on its own.
 */
class MapCrowd(val scene: FieldMap.Scene, people: List<FieldPeople.Person>, private val random: Random = Random.Default) {

    companion object {
        /** One tile's walk: the game's people walk a tile in 16 frames, a little over 260 ms. */
        const val STEP_MS = 280L
        private val DX = intArrayOf(0, 0, -1, 1) // UP, DOWN, LEFT, RIGHT
        private val DY = intArrayOf(-1, 1, 0, 0)
        private val VERTICAL = intArrayOf(FollowerSprites.UP, FollowerSprites.DOWN)
        private val ACROSS = intArrayOf(FollowerSprites.LEFT, FollowerSprites.RIGHT)
        private val ALL = intArrayOf(0, 1, 2, 3)

        private fun toward(from: FieldMap.Tile, gx: Int, gy: Int): Int = when {
            gx > from.gx -> FollowerSprites.RIGHT
            gx < from.gx -> FollowerSprites.LEFT
            gy > from.gy -> FollowerSprites.DOWN
            else -> FollowerSprites.UP
        }
    }

    enum class Kind { STILL, LOOK, WANDER }

    inner class Walker(val person: FieldPeople.Person) {
        var at: FieldMap.Tile = person.home
            internal set
        var next: FieldMap.Tile? = null
            internal set
        internal var stepStart = 0L
        internal var steps = 0
        var facing: Int = when (person.movement) {
            in 14..17 -> person.movement - 14
            else -> person.facing
        }
            internal set
        /** Which way they face on their own (STILL people go back to it after being talked to). */
        private val homeFacing = facing
        internal var waitUntil = 0L
        internal var talking = false

        val kind: Kind
        /** Directions they look or walk in. */
        val directions: IntArray
        /** How far from home they may walk, in tiles across and down. */
        val rangeX: Int
        val rangeY: Int

        init {
            val p = person
            var k: Kind
            var dirs = ALL
            var rx = p.xRange
            var ry = p.yRange
            when (p.movement) {
                0, 1, 14, 15, 16, 17 -> k = Kind.STILL
                3 -> k = Kind.WANDER
                4 -> { k = Kind.WANDER; dirs = VERTICAL; rx = 0 }
                5 -> { k = Kind.WANDER; dirs = ACROSS; ry = 0 }
                6 -> { k = Kind.LOOK; dirs = VERTICAL }
                7 -> { k = Kind.LOOK; dirs = ACROSS }
                in 2..13, 18, 19 -> k = Kind.LOOK
                else -> k = if (rx > 0 || ry > 0) Kind.WANDER else Kind.STILL
            }
            if (k == Kind.WANDER) {
                if (rx == 0 && ry == 0) k = Kind.LOOK
                else if (rx == 0) dirs = VERTICAL
                else if (ry == 0) dirs = ACROSS
            }
            kind = k
            directions = dirs
            rangeX = rx
            rangeY = ry
        }

        /** Where to draw them now. */
        fun pose(now: Long): MapWanderer.Pose {
            var x = at.x
            var y = at.y
            var z = at.z
            var grass = at.tallGrass
            val n = next
            var frame = 0
            if (n != null) {
                val t = ((now - stepStart).toDouble() / STEP_MS).coerceIn(0.0, 1.0)
                x += (n.x - at.x) * t
                y += (n.y - at.y) * t
                z += (n.z - at.z) * t
                if (t > 0.5) grass = n.tallGrass
                // the step frame for the first half of the step, one foot then the other
                if (t < 0.5) frame = if (steps % 2 == 0) 1 else 3
            }
            return MapWanderer.Pose(x, y, z, facing, frame, 0.0, n != null, grass && !person.swimming)
        }

        internal fun goHome() {
            if (kind == Kind.STILL) facing = homeFacing
        }
    }

    val walkers: List<Walker> = people.map { Walker(it) }
    private var lastNow = Long.MIN_VALUE

    /** Whether someone stands on, or is stepping onto, tile ([gx], [gy]). */
    fun occupied(gx: Int, gy: Int): Boolean = walkers.any { w ->
        (w.at.gx == gx && w.at.gy == gy) || w.next?.let { it.gx == gx && it.gy == gy } == true
    }

    /** The person standing on (or stepping onto) tile ([gx], [gy]), if anyone. */
    fun at(gx: Int, gy: Int): Walker? = walkers.firstOrNull { w ->
        (w.at.gx == gx && w.at.gy == gy) || w.next?.let { it.gx == gx && it.gy == gy } == true
    }

    /**
     * Moves everyone along to [now]. [pokemon] is where the walking Pokémon stands (and [pokemonNext]
     * where it's stepping to), [pokemonFacing] which way it looks, and whether it's standing still.
     */
    fun update(now: Long, pokemon: FieldMap.Tile?, pokemonNext: FieldMap.Tile?, pokemonFacing: Int, pokemonStill: Boolean) {
        val restart = lastNow == Long.MIN_VALUE || now < lastNow || now - lastNow > 5_000
        lastNow = now
        for (w in walkers) {
            if (restart) {
                w.next?.let { w.at = it }
                w.next = null
                w.waitUntil = now + 300 + random.nextInt(1500)
            }
            val n = w.next
            if (n != null) {
                if (now - w.stepStart < STEP_MS) continue
                w.at = n
                w.next = null
                w.steps++
                w.waitUntil = now + pause(w)
            }
            // the Pokémon right next to them, looking at them: they turn to it
            val talkedTo = pokemon != null && pokemonStill && !w.person.swimming &&
                abs(pokemon.gx - w.at.gx) + abs(pokemon.gy - w.at.gy) == 1 &&
                pokemon.gx + DX[pokemonFacing.coerceIn(0, 3)] == w.at.gx && pokemon.gy + DY[pokemonFacing.coerceIn(0, 3)] == w.at.gy
            if (talkedTo) {
                w.facing = toward(w.at, pokemon!!.gx, pokemon.gy)
                w.talking = true
                w.waitUntil = now + 1_800
                continue
            }
            if (w.talking) {
                w.talking = false
                w.goHome()
            }
            if (now < w.waitUntil) continue
            when (w.kind) {
                Kind.STILL -> w.waitUntil = now + 2_000
                Kind.LOOK -> {
                    w.facing = otherDirection(w)
                    w.waitUntil = now + 1_000 + random.nextInt(2_200)
                }
                Kind.WANDER -> {
                    if (random.nextInt(3) == 0) {
                        w.facing = otherDirection(w)
                        w.waitUntil = now + pause(w)
                    } else {
                        val dir = w.directions[random.nextInt(w.directions.size)]
                        val t = stepFor(w, dir, pokemon, pokemonNext)
                        w.facing = dir
                        if (t != null) {
                            w.next = t
                            w.stepStart = now
                        } else {
                            w.waitUntil = now + pause(w)
                        }
                    }
                }
            }
        }
    }

    private fun pause(w: Walker) = 900L + random.nextInt(2_300)

    private fun otherDirection(w: Walker): Int {
        val dirs = w.directions
        if (dirs.size == 1) return dirs[0]
        var d = dirs[random.nextInt(dirs.size)]
        if (d == w.facing) d = dirs[(dirs.indexOf(d) + 1 + random.nextInt(dirs.size - 1)) % dirs.size]
        return d
    }

    /** The tile a step [dir] takes [w] to, if they may go there. */
    private fun stepFor(w: Walker, dir: Int, pokemon: FieldMap.Tile?, pokemonNext: FieldMap.Tile?): FieldMap.Tile? {
        val gx = w.at.gx + DX[dir]
        val gy = w.at.gy + DY[dir]
        val home = w.person.home
        if (abs(gx - home.gx) > w.rangeX || abs(gy - home.gy) > w.rangeY) return null
        val key = FieldMap.Scene.key(gx, gy)
        val t = (if (w.person.swimming) scene.water[key] else scene.tiles[key]) ?: return null
        if (!FieldMap.stepOk(w.at, t)) return null
        if (pokemon != null && pokemon.gx == gx && pokemon.gy == gy) return null
        if (pokemonNext != null && pokemonNext.gx == gx && pokemonNext.gy == gy) return null
        if (occupied(gx, gy)) return null
        return t
    }
}
