package me.magnum.melonds.pokewalker

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * A real piece of the Johto/Kanto map for each Pokéwalker course, drawn from the player's own
 * HeartGold/SoulSilver ROM the way the game draws its field: the area's 3D blocks, its buildings
 * and trees, the field camera and the area light of the time of day. Also says which tiles the
 * walking Pokémon can wander on, and how high the ground is there.
 *
 * How the game keeps its field (pret/pokeheartgold: src/map_matrix.c, src/field/map_prop_manager.c,
 * the area data manager and map loader in overlay 1):
 *  - a map matrix (`a/0/4/1`) lays 32x32-tile blocks out on a grid; each cell names a block
 *    ("land data", `a/0/6/5`) and, for the big overworld matrix, which map it belongs to;
 *  - a block has the movement permissions of its 1024 tiles, its buildings (model and
 *    position; the game draws them unturned), its terrain model and the surfaces people
 *    stand on (BDHC);
 *  - a map's area data (`a/0/4/2`) picks the texture set for the terrain (`a/0/4/4`), the list
 *    of buildings the area has (`a/0/4/3`) and their texture set (`a/0/7/0`); the building
 *    models are in `a/0/4/0` (outside) or `a/1/4/8` (inside), and the area light comes with it;
 *  - a block sits at (column x 512 + 256, altitude x 8, row x 512 + 256), its buildings at
 *    (column x 512 + 256 + x, y, row x 512 + 256 + z); a tile is 16 units.
 */
object FieldMap {
    const val MATRICES = "a/0/4/1"
    const val LAND_DATA = "a/0/6/5"
    const val AREA_DATA = "a/0/4/2"
    const val BUILDING_LISTS = "a/0/4/3"
    const val MAP_TEXTURES = "a/0/4/4"
    const val BUILDING_TEXTURES = "a/0/7/0"
    const val OUTDOOR_BUILDINGS = "a/0/4/0"
    const val INDOOR_BUILDINGS = "a/1/4/8"

    const val TILE = 16.0
    const val BLOCK = 512.0

    /** The field camera (FieldCamera presets in overlay 1): its lens and how far it looks down. */
    val FOVY: Double = Math.toRadians(16.16)
    val PITCH: Double = Math.toRadians(48.68)

    // ---- what the courses look like -------------------------------------------------------------

    enum class Kind { FIELD, FOREST, MOUNTAIN, BEACH, TOWN, CAVE, LAKE, SEA, VOLCANO, ICE }

    /** A course and the place in the game that looks like it: a map (constants/maps.h), its matrix and what to look for. */
    class Course(val name: String, val place: String, val map: Int, val matrix: Int, val kind: Kind)

    // map numbers and matrices from pret/pokeheartgold include/constants/maps.h, src/data/map_headers.h
    val COURSES = arrayOf(
        Course("Refreshing Field", "Route 29", 33, 0, Kind.FIELD),
        Course("Noisy Forest", "Ilex Forest", 117, 56, Kind.FOREST),
        Course("Rugged Road", "Route 45", 47, 0, Kind.MOUNTAIN),
        Course("Beautiful Beach", "Route 40", 94, 0, Kind.BEACH),
        Course("Suburban Area", "New Bark Town", 60, 0, Kind.TOWN),
        Course("Dim Cave", "Union Cave", 99, 42, Kind.CAVE),
        Course("Blue Lake", "Lake of Rage", 88, 0, Kind.LAKE),
        Course("Town Outskirts", "Route 31", 35, 0, Kind.FIELD),
        Course("Hoenn Field", "Route 39", 43, 0, Kind.FIELD),
        Course("Warm Beach", "Cianwood City", 75, 0, Kind.BEACH),
        Course("Volcano Path", "Cinnabar Island", 57, 0, Kind.VOLCANO),
        Course("Treehouse", "Route 36", 40, 0, Kind.FOREST),
        Course("Scary Cave", "Dark Cave", 176, 99, Kind.CAVE),
        Course("Sinnoh Field", "Route 48", 152, 0, Kind.FIELD),
        Course("Icy Mountain Rd.", "Ice Path", 120, 132, Kind.ICE),
        Course("Big Forest", "Viridian Forest", 147, 86, Kind.FOREST),
        Course("White Lake", "Route 44", 46, 0, Kind.LAKE),
        Course("Stormy Beach", "Route 19", 91, 0, Kind.BEACH),
        Course("Resort", "Olivine City", 77, 0, Kind.TOWN),
        Course("Quiet Cave", "Mt. Moon", 107, 3, Kind.CAVE),
        Course("Beyond the Sea", "Route 41", 95, 0, Kind.SEA),
        Course("Night Sky's Edge", "Mt. Silver", 90, 0, Kind.MOUNTAIN),
        Course("Yellow Forest", "Route 37", 41, 0, Kind.FOREST),
        Course("Rally", "National Park", 96, 27, Kind.FIELD),
        Course("Sightseeing", "Ecruteak City", 78, 0, Kind.TOWN),
        Course("Winner's Path", "Route 26", 30, 0, Kind.MOUNTAIN),
        Course("Amity Meadow", "Route 30", 34, 0, Kind.FIELD),
    )

    /** One course per route picture (0 field ... 7 beach), for course names this doesn't know. */
    private val BY_IMAGE = intArrayOf(0, 1, 4, 24, 2, 5, 6, 3)

    /** The course for a route name on the walker (any punctuation), else by its route picture. */
    fun courseFor(routeName: String, imageIndex: Int): Course {
        val key = norm(routeName)
        COURSES.firstOrNull { norm(it.name) == key }?.let { return it }
        return COURSES[BY_IMAGE.getOrElse(imageIndex) { 0 }]
    }

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /** Each map's area data bank (src/data/map_headers.h), indexed by map number. */
    private const val AREA_BANKS =
        "00001a001a1a584e03120a110d0d0b0f0e0d0d0b0e0e0e0e1010100a0d0d02020a02020203040506060607070808080909120a110d0e0b0f" +
        "10120a0c02191919191919021b1a1919190304070507061b1f1a193d19193d0808090a1212120707473f3f573f3f3f3f3f3f5246534b4d4d" +
        "2414542f1b4a3e4f4c555057465646191a1a1a1a3f193f1c1d1e202122233f3d1a494667193f3f131357574d4d1b1a3d1919193d3d1b1a1a" +
        "1a1a1a3f3f271319575446461d541b1b1b1a24242424243e3e3e3e3e3e30303e3e3e3e1919192a2d3f3f3f3f3f3f2a2a3d4e021a2f4a2f2f" +
        "2f591a1b1919192a191b19191a4c4c4c3f1a5555623f1a4040404f4f4f560202020202020202020202382c02020202265c2e38393a3b3c46" +
        "5a252525252525252a1919191b1a1919192a463f1a2929292941412a2a2a2a2a4848486948484848483d1969484848482a2a5e1b4d4d4d4d" +
        "4d4d4d4d5b4d464b4b4b4b4b4b4b4b4b4b4b4b4b4b4b1a1a1b19281919313f1a1a1a2727272727272727275f282828281919275e0e3f3f3f" +
        "3f1a1a3422222819303027272727281a1a1b325d2d2d111910193f3f3f3f0f3f3f3f1b331a1919191b1b1a28192a28272b2c2c2c2c2c2c2c" +
        "4661494953686868685146606650505050601a2c2c2c2c2c19361b1a1a191b2c35191a191a1a6247472c6969693f1a1a37192b2b1b1a1a2b" +
        "2b2b2b2b1a1a191a1a611a1a2d191b303064652a6363633f1a1a1b192c191a1a4242041a00"

    fun areaBank(map: Int): Int {
        val i = map * 2
        return if (map < 0 || i + 2 > AREA_BANKS.length) -1 else AREA_BANKS.substring(i, i + 2).toInt(16)
    }

    // ---- the game's files ---------------------------------------------------------------------------

    class Matrix(val width: Int, val height: Int, private val headers: IntArray?, private val mapNo: Int,
                 private val altitudes: IntArray?, private val models: IntArray) {
        fun model(x: Int, y: Int): Int = if (x in 0 until width && y in 0 until height) models[y * width + x] else 0xFFFF
        fun header(x: Int, y: Int): Int = if (x in 0 until width && y in 0 until height) headers?.get(y * width + x) ?: mapNo else -1
        fun altitude(x: Int, y: Int): Int = if (x in 0 until width && y in 0 until height) altitudes?.get(y * width + x) ?: 0 else 0
    }

    /** src/map_matrix.c MapMatrix_MapMatrixData_Load. */
    fun readMatrix(b: ByteArray, mapNo: Int): Matrix {
        if (b.size < 5) throw Nitro.FormatException("matrix too short")
        val w = Nitro.u8(b, 0)
        val h = Nitro.u8(b, 1)
        val hasHeaders = b[2].toInt() != 0
        val hasAltitudes = b[3].toInt() != 0
        var p = 5 + Nitro.u8(b, 4)
        val n = w * h
        if (n == 0 || n > 64 * 64) throw Nitro.FormatException("matrix size ${w}x$h")
        fun need(bytes: Int) { if (p + bytes > b.size) throw Nitro.FormatException("matrix data") }
        var headers: IntArray? = null
        if (hasHeaders) { need(n * 2); headers = IntArray(n) { Nitro.u16(b, p + it * 2) }; p += n * 2 }
        var altitudes: IntArray? = null
        if (hasAltitudes) { need(n); altitudes = IntArray(n) { Nitro.u8(b, p + it) }; p += n }
        need(n * 2)
        val models = IntArray(n) { Nitro.u16(b, p + it * 2) }
        return Matrix(w, h, headers, mapNo, altitudes, models)
    }

    /** A building or tree of a block: [x], [z] from the block's middle, [y] from the ground (not the block's altitude). */
    class Building(val model: Int, val x: Double, val y: Double, val z: Double, val sx: Double, val sy: Double, val sz: Double)

    /**
     * The surfaces people stand on in a block (BDHC): rectangles, flat or sloped, each with its
     * plane. Coordinates from the block's middle, like the game's height lookup.
     */
    class Surfaces(private val r: FloatArray) {
        val count get() = r.size / 8

        /** The highest surface over block-local (x, z), or null where there is none. */
        fun height(x: Double, z: Double): Double? {
            var best = Double.NaN
            var i = 0
            while (i < r.size) {
                if (x >= r[i] && x <= r[i + 1] && z >= r[i + 2] && z <= r[i + 3]) {
                    val h = -(r[i + 4] * x + r[i + 6] * z + r[i + 7]) / r[i + 5]
                    if (best.isNaN() || h > best) best = h
                }
                i += 8
            }
            return if (best.isNaN()) null else best
        }
    }

    /** BDHC: counts of points, normals, plane constants and plates, then those (fx32), plates as point/point/normal/constant. */
    fun readSurfaces(b: ByteArray, o: Int, size: Int): Surfaces? {
        if (size < 16 || o < 0 || o + size > b.size || String(b, o, 4, Charsets.ISO_8859_1) != "BDHC") return null
        val np = Nitro.u16(b, o + 4)
        val nn = Nitro.u16(b, o + 6)
        val nc = Nitro.u16(b, o + 8)
        val npl = Nitro.u16(b, o + 10)
        val pts = o + 16
        val nrm = pts + np * 8
        val con = nrm + nn * 12
        val pl = con + nc * 4
        if (pl + npl * 8 > o + size) return null
        val out = ArrayList<Float>(npl * 8)
        for (i in 0 until npl) {
            val q = pl + i * 8
            val a = Nitro.u16(b, q)
            val c = Nitro.u16(b, q + 2)
            val n = Nitro.u16(b, q + 4)
            val k = Nitro.u16(b, q + 6)
            if (a >= np || c >= np || n >= nn || k >= nc) continue
            val ny = Nitro.fx32(b, nrm + n * 12 + 4)
            if (abs(ny) < 0.05) continue // a wall, nobody stands on it
            val xa = Nitro.fx32(b, pts + a * 8); val za = Nitro.fx32(b, pts + a * 8 + 4)
            val xc = Nitro.fx32(b, pts + c * 8); val zc = Nitro.fx32(b, pts + c * 8 + 4)
            out.add(min(xa, xc).toFloat()); out.add(max(xa, xc).toFloat())
            out.add(min(za, zc).toFloat()); out.add(max(za, zc).toFloat())
            out.add(Nitro.fx32(b, nrm + n * 12).toFloat()); out.add(ny.toFloat()); out.add(Nitro.fx32(b, nrm + n * 12 + 8).toFloat())
            out.add(Nitro.fx32(b, con + k * 4).toFloat())
        }
        return if (out.isEmpty()) null else Surfaces(out.toFloatArray())
    }

    /** One 32x32 block ("land data"). */
    class Land(val behaviors: IntArray, val collisions: IntArray, val buildings: List<Building>, val model: ByteArray, val surfaces: Surfaces?) {
        fun behavior(x: Int, y: Int) = behaviors[y * 32 + x]
        fun blocked(x: Int, y: Int) = collisions[y * 32 + x] and 0x80 != 0

        /** Height of the surface at tile (x, y)'s middle, from the block's own ground (null: none there). */
        fun surfaceAt(x: Int, y: Int): Double? = surfaces?.height(x * TILE + TILE / 2 - BLOCK / 2, y * TILE + TILE / 2 - BLOCK / 2)
    }

    /** HG/SS land data: four section sizes, the background sound section, then the sections. */
    fun readLand(b: ByteArray, withModel: Boolean = true): Land {
        if (b.size < 0x14) throw Nitro.FormatException("block too short")
        val permSize = Nitro.u32(b, 0).toInt()
        val bldSize = Nitro.u32(b, 4).toInt()
        val mdlSize = Nitro.u32(b, 8).toInt()
        val bdhcSize = Nitro.u32(b, 12).toInt()
        var p = 0x10
        if (Nitro.u16(b, p) == 0x1234) p += 4 + Nitro.u16(b, p + 2) // HG/SS background sounds
        if (permSize < 2048 || bldSize < 0 || mdlSize < 0 || p + permSize + bldSize > b.size) throw Nitro.FormatException("block sections")
        val behaviors = IntArray(1024) { Nitro.u8(b, p + it * 2) }
        val collisions = IntArray(1024) { Nitro.u8(b, p + it * 2 + 1) }
        p += permSize
        // MapPropArcData: model, translation, rotation (not used when the game draws it), scale, padding
        val buildings = ArrayList<Building>()
        for (i in 0 until bldSize / 0x30) {
            val o = p + i * 0x30
            fun scl(at: Int) = (Nitro.s32(b, o + at) / 4096.0).takeIf { it > 0.01 && it < 64 } ?: 1.0
            buildings.add(Building(
                model = Nitro.s32(b, o),
                x = Nitro.s32(b, o + 4) / 4096.0, y = Nitro.s32(b, o + 8) / 4096.0, z = Nitro.s32(b, o + 0x0C) / 4096.0,
                sx = scl(0x1C), sy = scl(0x20), sz = scl(0x24),
            ))
        }
        p += bldSize
        val model = if (withModel && mdlSize > 0 && p + mdlSize <= b.size) b.copyOfRange(p, p + mdlSize) else ByteArray(0)
        val surfaces = if (bdhcSize > 0 && mdlSize >= 0 && p + mdlSize + bdhcSize <= b.size) readSurfaces(b, p + mdlSize, bdhcSize) else null
        return Land(behaviors, collisions, buildings, model, surfaces)
    }

    /**
     * An area: its building list and building textures ([buildingSet]), terrain textures,
     * outside or inside, and which light it has ([lightType]: 1 the day's light, 0 a steady
     * light for caves and inside, 2 a dim one for some dungeons).
     */
    class Area(val buildingSet: Int, val mapTextures: Int, val outdoor: Boolean, val lightType: Int, val animation: Int = 0xFFFF)

    /** Area data (AreaDataManager_Alloc/Load in overlay 1). */
    fun readArea(b: ByteArray): Area {
        if (b.size < 8) throw Nitro.FormatException("area data")
        return Area(Nitro.u16(b, 0), Nitro.u16(b, 2), b[6].toInt() != 0, Nitro.u8(b, 7), Nitro.u16(b, 4))
    }

    /** An area's building list (`a/0/4/3`): a count, then the building model numbers. */
    fun readBuildingList(b: ByteArray): Set<Int> {
        if (b.size < 2) return emptySet()
        val n = Nitro.u16(b, 0).coerceAtMost((b.size - 2) / 2)
        return HashSet<Int>(n * 2).also { s -> for (i in 0 until n) s.add(Nitro.u16(b, 2 + i * 2)) }
    }

    // ---- tiles ------------------------------------------------------------------------------------

    /** Surfable water (sMetatileBehaviorFlags in src/metatile_behavior.c). */
    fun isWater(behavior: Int) = behavior in 0x10..0x15 || behavior == 0x19 || behavior == 0x2A || behavior in 0x50..0x53 ||
        behavior == 0x73 || behavior == 0x78 || behavior == 0x7C

    fun isGrass(behavior: Int) = behavior == 0x02 || behavior == 0x03
    fun isLedge(behavior: Int) = behavior in 0x38..0x3B

    /** Where the walking Pokémon may go: open ground; not water, ledges, ladders, slides, rock climbs, warps or doors. */
    fun isWalkable(land: Land, x: Int, y: Int): Boolean {
        if (land.blocked(x, y)) return false
        val b = land.behavior(x, y)
        return !(isWater(b) || isLedge(b) || b in 0x3C..0x43 || b == 0x4B || b == 0x4C || b in 0x5E..0x6F)
    }

    // ---- the area light ------------------------------------------------------------------------------

    /**
     * HG/SS's outdoor area light through the day (files/data/area00light.txt): minute it starts,
     * light 0 colour and direction, light 2 colour, then diffuse, ambient, specular and emission.
     */
    private val AREA_LIGHT = arrayOf(
        intArrayOf(0, 11, 11, 16, -1914, 18, 10, 0, 14, 14, 16, 10, 10, 10, 14, 14, 16, 8, 8, 11),
        intArrayOf(240, 11, 11, 16, -1914, 0, 0, 6, 14, 14, 16, 10, 10, 10, 14, 14, 16, 8, 8, 11),
        intArrayOf(270, 12, 12, 18, -1934, 0, 1, 6, 10, 10, 14, 13, 13, 13, 8, 8, 14, 10, 10, 12),
        intArrayOf(300, 12, 12, 22, -1934, 0, 1, 6, 11, 11, 13, 10, 10, 10, 10, 10, 14, 13, 13, 14),
        intArrayOf(480, 15, 15, 22, -1914, 0, 1, 6, 12, 12, 12, 8, 8, 8, 12, 12, 14, 14, 14, 18),
        intArrayOf(690, 18, 18, 21, -1914, 0, 4, 9, 13, 13, 13, 9, 9, 9, 14, 14, 15, 14, 14, 16),
        intArrayOf(720, 22, 22, 20, -1914, 0, 4, 9, 15, 15, 15, 9, 11, 11, 16, 16, 16, 14, 14, 14),
        intArrayOf(900, 24, 24, 20, -1914, 0, 4, 9, 16, 16, 16, 10, 12, 12, 18, 18, 18, 14, 14, 14),
        intArrayOf(930, 22, 22, 18, -1934, 4, 4, 10, 15, 15, 15, 11, 12, 12, 17, 17, 17, 13, 13, 13),
        intArrayOf(1020, 20, 18, 16, -1934, 3, 4, 10, 15, 15, 15, 11, 12, 12, 17, 17, 17, 12, 11, 11),
        intArrayOf(1080, 19, 16, 12, -1914, 16, 6, 0, 15, 15, 15, 11, 12, 12, 17, 17, 17, 8, 8, 7),
        intArrayOf(1110, 17, 13, 10, -1934, 14, 6, 0, 14, 14, 14, 12, 12, 12, 16, 16, 16, 9, 7, 7),
        intArrayOf(1140, 16, 13, 10, -1934, 12, 4, 0, 14, 14, 14, 12, 12, 12, 14, 14, 16, 9, 8, 7),
        intArrayOf(1200, 11, 12, 15, -1934, 18, 10, 0, 14, 14, 14, 12, 12, 12, 14, 14, 16, 8, 8, 9),
    )

    /** The steady light of caves and other inside areas (files/data/area01light.txt), in the same layout. */
    private val INSIDE_LIGHT = intArrayOf(0, 23, 23, 25, -2043, 4, 4, 16, 16, 16, 16, 14, 14, 14, 20, 20, 20, 16, 16, 16)

    /** Which area light applies at [minute] of the day (an index into the table). */
    fun lightIndex(minute: Int): Int {
        var k = 0
        for (i in AREA_LIGHT.indices) if (AREA_LIGHT[i][0] <= minute) k = i
        return k
    }

    /** Light type 1 (and unknown ones) follows the time of day (AreaDataManager_GetAreaLightArchiveID). */
    fun followsTheDay(lightType: Int) = lightType != 0 && lightType != 2

    /** Which of the day's lights a picture drawn at [minute] has, or -1 when the area's light never changes. */
    fun lightKey(minute: Int, lightType: Int) = if (followsTheDay(lightType)) lightIndex(minute) else -1

    fun lighting(minute: Int, lightType: Int): Nitro.Lighting = when (lightType) {
        0 -> lighting(INSIDE_LIGHT, 110)
        2 -> lighting(AREA_LIGHT[0], -296) // files/data/dun20_01light.txt: the night light, all day
        else -> lighting(AREA_LIGHT[lightIndex(minute)], -296)
    }

    private fun lighting(e: IntArray, dirZ: Int): Nitro.Lighting {
        fun c5(i: Int) = doubleArrayOf(e[i] / 31.0, e[i + 1] / 31.0, e[i + 2] / 31.0)
        val d = doubleArrayOf(e[4] / 4096.0, -3548 / 4096.0, dirZ / 4096.0)
        val l = sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2])
        for (k in 0 until 3) d[k] /= l
        val black = doubleArrayOf(0.0, 0.0, 0.0)
        return Nitro.Lighting(
            arrayOf(d, null, doubleArrayOf(0.0, 0.0, 1.0), null),
            arrayOf(c5(1), black, c5(5), black),
            arrayOf(c5(8), c5(11), c5(14), c5(17)),
        )
    }

    // ---- choosing the spot ------------------------------------------------------------------------------

    /**
     * Among the windows that show the place nearly as well as the best one (within this share of
     * its score), the one with the most of its people in view (up to [PEOPLE_WANTED]) is taken.
     */
    private const val PEOPLE_LEEWAY = 0.25
    private const val PEOPLE_WANTED = 3

    /** What a window of tiles has in it. */
    private class Census {
        var walk = 0; var grass = 0; var water = 0; var sand = 0; var blocked = 0; var ledge = 0; var ice = 0
        var buildings = 0; var total = 0; var people = 0
    }

    /** How well a window of tiles shows a [kind] of place. */
    private fun score(kind: Kind, c: Census): Double {
        val open = c.walk.toDouble()
        val s = when (kind) {
            Kind.FIELD -> open + c.grass * 2.0 - c.water * 0.5 - c.buildings * 2.0
            Kind.FOREST -> open * 0.8 + c.grass * 2.0 + c.blocked * 0.6
            Kind.MOUNTAIN -> open + c.ledge * 4.0 + c.blocked * 0.4
            Kind.BEACH -> min(open + c.sand, c.water.toDouble()) * 3.0 + c.sand * 2.0
            Kind.LAKE -> min(open, c.water.toDouble()) * 3.0 + c.water * 0.5
            Kind.SEA -> c.water + open * 2.0
            Kind.TOWN -> open + c.buildings * 14.0
            Kind.CAVE -> open + c.blocked * 0.3
            Kind.VOLCANO -> open + c.blocked * 0.5 + c.buildings * 4.0
            Kind.ICE -> open + c.ice * 3.0
        }
        // the Pokémon needs room to walk around
        val minOpen = c.total / 4
        return if (c.walk >= minOpen) s else s - (minOpen - c.walk) * 20.0
    }

    // ---- the picture ---------------------------------------------------------------------------------

    /** A walkable tile: its world position at the middle of the tile, on the ground, and its tile behaviour. */
    class Tile(val gx: Int, val gy: Int, val x: Double, val y: Double, val z: Double, val behavior: Int = 0) {
        val tallGrass get() = isGrass(behavior)
    }

    /** The drawn area and what the walking Pokémon needs to move around it. */
    class Scene(
        val course: Course,
        val width: Int,
        val height: Int,
        /** ARGB, [width] x [height], drawn at the DS's own scale (a tile is about 16 pixels). */
        val image: IntArray,
        /** 1/w per pixel (0 = nothing drawn), to hide the Pokémon behind things in front of it. */
        val depth: FloatArray,
        /** world -> clip space of the camera the picture was drawn with. */
        val viewProj: DoubleArray,
        /** Walkable tiles in sight, by [key]. */
        val tiles: Map<Long, Tile>,
        /** Picture pixels per tile at the middle of the picture. */
        val tilePixels: Float,
        /** Where the Pokémon starts: in the middle of the biggest stretch it can walk around. */
        val start: Tile?,
        /** [lightKey] of the light it was drawn with (-1: the area's light never changes). */
        val lightKey: Int,
        /** The game's moving textures (sea, waves, flowers, rivers), recoloured into [image]; null when nothing moves. */
        val animation: SceneAnimation? = null,
        /** Picture positions (x, y pairs) of the water in sight, for sparkles. */
        val waterSpots: FloatArray = FloatArray(0),
        /** Lit by the day's light (outside), not a cave's steady one. */
        val outdoor: Boolean = true,
        /** Water tiles in sight, by [key], with room to draw someone swimming there. */
        val water: Map<Long, Tile> = emptyMap(),
        /** The townsfolk and trainers standing in sight ([FieldPeople]). */
        val people: List<FieldPeople.Person> = emptyList(),
        /** Ledges in sight, by [key]: the way they're jumped down (FollowerSprites.UP ... RIGHT). */
        val ledges: Map<Long, Int> = emptyMap(),
        /** Signs and other things to read in sight (tiles you face to read them), with ground beside them. */
        val signs: List<Tile> = emptyList(),
    ) {
        fun tile(gx: Int, gy: Int): Tile? = tiles[key(gx, gy)]

        /** Picture position (x, y) and 1/w of a world point. */
        fun project(x: Double, y: Double, z: Double): FloatArray? {
            val m = viewProj
            val cx = m[0] * x + m[1] * y + m[2] * z + m[3]
            val cy = m[4] * x + m[5] * y + m[6] * z + m[7]
            val cw = m[12] * x + m[13] * y + m[14] * z + m[15]
            if (cw <= 1e-6) return null
            return floatArrayOf(((cx / cw + 1) * 0.5 * width).toFloat(), ((1 - cy / cw) * 0.5 * height).toFloat(), (1 / cw).toFloat())
        }

        /** The walkable tile whose ground is drawn nearest to picture position ([px], [py]), within [maxDistance] pixels. */
        fun tileNear(px: Float, py: Float, maxDistance: Float = tilePixels * 1.5f): Tile? {
            var best: Tile? = null
            var bestD = maxDistance * maxDistance
            for (t in tiles.values) {
                val p = project(t.x, t.y, t.z) ?: continue
                val dx = p[0] - px
                val dy = p[1] - py
                val d = dx * dx + dy * dy
                if (d < bestD) { bestD = d; best = t }
            }
            return best
        }

        companion object {
            fun key(gx: Int, gy: Int): Long = (gx.toLong() shl 32) or (gy.toLong() and 0xFFFFFFFFL)
        }
    }

    /** What went wrong, for the log; [wholeRom] when no area can come from this ROM. */
    class MapException(message: String, val wholeRom: Boolean = false) : Exception(message)

    private val STEPS = arrayOf(intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1))

    /** Neighbouring tiles further apart in height than this aren't a step (stairs climb 16 a tile). */
    const val MAX_STEP = 17.0

    /**
     * Draws the place in the game that looks like [course] into a [width] x [height] picture at
     * the DS's own scale (one pixel a unit where the camera looks, so about 16 pixels a tile),
     * lit for [minute] of the day. [log] gets a line about what was chosen.
     */
    fun render(src: CryArchive.Source, course: Course, width: Int, height: Int, minute: Int, log: (String) -> Unit = {}): Scene {
        if (width < 32 || height < 32 || width > 2048 || height > 2048) throw MapException("picture size ${width}x$height")
        val matrices = Nitro.narc(src, MATRICES) ?: throw MapException("no map matrices in the ROM", wholeRom = true)
        val lands = Nitro.narc(src, LAND_DATA) ?: throw MapException("no map blocks in the ROM", wholeRom = true)
        val areas = Nitro.narc(src, AREA_DATA) ?: throw MapException("no area data in the ROM", wholeRom = true)
        if (course.matrix >= matrices.count) throw MapException("no map matrix ${course.matrix} in the ROM", wholeRom = true)
        val matrix = readMatrix(matrices.read(course.matrix), course.map)

        val landCache = HashMap<Int, Land?>()
        fun land(id: Int): Land? = if (id == 0xFFFF || id >= lands.count) null
            else landCache.getOrPut(id) { runCatching { readLand(lands.read(id)) }.getOrNull() }
        fun landAt(bx: Int, by: Int): Land? = land(matrix.model(bx, by))

        // the blocks that belong to the course's map
        val cells = ArrayList<IntArray>()
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            if (matrix.header(x, y) == course.map && landAt(x, y) != null) cells.add(intArrayOf(x, y))
        }
        if (cells.isEmpty()) throw MapException("map ${course.map} has no blocks in matrix ${course.matrix}")

        val areaCache = HashMap<Int, Area?>()
        fun area(bank: Int) = areaCache.getOrPut(bank) {
            if (bank < 0 || bank >= areas.count) null else runCatching { readArea(areas.read(bank)) }.getOrNull()
        }
        val centerArea = area(areaBank(course.map))
        val lightType = centerArea?.lightType ?: 1

        // open tiles with a surface to stand on
        fun open(gx: Int, gy: Int): Boolean {
            val bx = Math.floorDiv(gx, 32)
            val by = Math.floorDiv(gy, 32)
            val l = landAt(bx, by) ?: return false
            val x = gx - bx * 32
            val y = gy - by * 32
            return isWalkable(l, x, y) && (l.surfaces == null || l.surfaceAt(x, y) != null)
        }
        // In caves the space around the cave is open too, though nobody can get there: there,
        // only tiles joined up with ones that have a ground type (cave floor, ice, sand...) count.
        val reach: HashSet<Long>? = if (followsTheDay(lightType)) null else HashSet<Long>().also { set ->
            val queue = ArrayDeque<Long>()
            for (cell in cells) {
                val l = landAt(cell[0], cell[1]) ?: continue
                for (y in 0 until 32) for (x in 0 until 32) {
                    val gx = cell[0] * 32 + x
                    val gy = cell[1] * 32 + y
                    if (l.behavior(x, y) != 0 && open(gx, gy) && set.add(Scene.key(gx, gy))) queue.add(Scene.key(gx, gy))
                }
            }
            while (queue.isNotEmpty() && set.size < 200_000) {
                val k = queue.removeFirst()
                val gx = (k shr 32).toInt()
                val gy = k.toInt()
                for (s in STEPS) {
                    val nx = gx + s[0]
                    val ny = gy + s[1]
                    if (matrix.header(Math.floorDiv(nx, 32), Math.floorDiv(ny, 32)) != course.map) continue
                    val nk = Scene.key(nx, ny)
                    if (nk !in set && open(nx, ny)) { set.add(nk); queue.add(nk) }
                }
            }
        }
        fun ground(gx: Int, gy: Int) = open(gx, gy) && (reach == null || Scene.key(gx, gy) in reach)

        // the people the map always has, to have some of them in view
        val ownPeople = runCatching { FieldPeople.spots(src, listOf(course.map), log) }.getOrDefault(emptyList())

        // the window that shows the course best: what the picture shows, in tiles, kept inside one block
        val across = (width / TILE).roundToInt().coerceIn(6, 30)
        val down = (height / TILE * 1.25).roundToInt().coerceIn(6, 30)
        val halfX = across / 2
        val halfY = down / 2
        var best = Double.NEGATIVE_INFINITY
        var bestGx = cells[0][0] * 32 + 16
        var bestGy = cells[0][1] * 32 + 16
        // every window: its score, how many people it has in view, where it is
        val windows = ArrayList<DoubleArray>()
        for (cell in cells.take(32)) {
            val l = landAt(cell[0], cell[1]) ?: continue
            val walk = BooleanArray(1024) { ground(cell[0] * 32 + it % 32, cell[1] * 32 + it / 32) }
            val bld = IntArray(1024)
            for (bd in l.buildings) {
                val tx = floor(bd.x / TILE + 16).toInt()
                val ty = floor(bd.z / TILE + 16).toInt()
                if (tx in 0 until 32 && ty in 0 until 32) bld[ty * 32 + tx]++
            }
            val folk = IntArray(1024)
            for (sp in ownPeople) {
                val tx = sp.event.x - cell[0] * 32
                val ty = sp.event.z - cell[1] * 32
                if (tx in 0 until 32 && ty in 0 until 32 && (walk[ty * 32 + tx] || isWater(l.behaviors[ty * 32 + tx]))) folk[ty * 32 + tx]++
            }
            val c = Census()
            for (cy in halfY..(32 - halfY)) for (cx in halfX..(32 - halfX)) {
                c.walk = 0; c.grass = 0; c.water = 0; c.sand = 0; c.blocked = 0; c.ledge = 0; c.ice = 0; c.buildings = 0; c.people = 0
                c.total = halfX * halfY * 4
                for (y in cy - halfY until cy + halfY) for (x in cx - halfX until cx + halfX) {
                    val i = y * 32 + x
                    val b = l.behaviors[i]
                    if (walk[i]) c.walk++
                    if (isGrass(b)) c.grass++
                    if (isWater(b)) c.water++
                    if (b == 0x21) c.sand++
                    if (l.blocked(x, y)) c.blocked++
                    if (isLedge(b)) c.ledge++
                    if (b == 0x20 || b == 0xA8) c.ice++
                    c.buildings += bld[i]
                    c.people += folk[i]
                }
                // a little preference for the middle of the block (less of the neighbours in view)
                val s = score(course.kind, c) - (abs(cx - 16) + abs(cy - 16)) * 0.05
                if (s > best) { best = s; bestGx = cell[0] * 32 + cx; bestGy = cell[1] * 32 + cy }
                if (c.people > 0) windows.add(doubleArrayOf(s, min(c.people, PEOPLE_WANTED).toDouble(), (cell[0] * 32 + cx).toDouble(), (cell[1] * 32 + cy).toDouble()))
            }
        }
        // nearly as good, and with some of the people who live there about
        val floor = best - abs(best) * PEOPLE_LEEWAY
        windows.filter { it[0] >= floor }.maxWithOrNull(compareBy<DoubleArray> { it[1] }.thenBy { it[0] })?.let {
            bestGx = it[2].toInt()
            bestGy = it[3].toInt()
        }
        log("Map for ${course.name}: ${course.place} (map ${course.map}), tiles around $bestGx,$bestGy")

        // the camera: the field camera's angle and lens at the DS's scale
        val aspect = width.toDouble() / height
        val tx = bestGx * TILE
        val tz = bestGy * TILE
        val dist = height / 2.0 / tan(FOVY / 2)

        // the blocks it can see: the ground the view covers, and room for tall things just outside
        val bx0 = floor((tx - width * 0.6 - 64) / BLOCK).toInt()
        val bx1 = floor((tx + width * 0.6 + 64) / BLOCK).toInt()
        val by0 = floor((tz - height * 0.8 - 112) / BLOCK).toInt()
        val by1 = floor((tz + height * 0.65 + 112) / BLOCK).toInt()

        val listNarc = Nitro.narc(src, BUILDING_LISTS)
        val mapTexNarc = Nitro.narc(src, MAP_TEXTURES)
        val bldTexNarc = Nitro.narc(src, BUILDING_TEXTURES)
        val outdoorNarc = Nitro.narc(src, OUTDOOR_BUILDINGS)
        val indoorNarc = Nitro.narc(src, INDOOR_BUILDINGS)
        val mapTexCache = HashMap<Int, Nitro.TexSet?>()
        val bldTexCache = HashMap<Int, Nitro.TexSet?>()
        val listCache = HashMap<Int, Set<Int>?>()
        val modelCache = HashMap<Long, Nitro.Container?>()
        fun texSet(narc: Nitro.Narc?, cache: HashMap<Int, Nitro.TexSet?>, i: Int) = cache.getOrPut(i) {
            if (narc == null || i < 0 || i >= narc.count) null else runCatching { Nitro.parse(narc.read(i)).texSet }.getOrNull()
        }
        val light = lighting(minute, lightType)

        // the game's moving textures: swaps for everyone, scrolls per area
        val swaps = runCatching { FieldAnimations.readSwaps(src) }.getOrDefault(emptyMap())
        val scrollCache = HashMap<Int, Map<String, FieldAnimations.Scroll>>()
        val slotCache = HashMap<List<Any?>, SceneAnimation.Slot>()
        val layers = ArrayList<Pair<Nitro.Batch, SceneAnimation.Slot>>()

        val batches = ArrayList<Nitro.Batch>()
        val terrain = ArrayList<Pair<IntArray, List<Nitro.Batch>>>() // block -> its terrain triangles, for ground heights
        for (by in by0..by1) for (bx in bx0..bx1) {
            val l = landAt(bx, by) ?: continue
            // each block with its own map's area (the textures it was made with), else the course's
            val area = area(areaBank(matrix.header(bx, by))) ?: centerArea
            val mapTex = area?.let { texSet(mapTexNarc, mapTexCache, it.mapTextures) }
            val bldTex = area?.let { texSet(bldTexNarc, bldTexCache, it.buildingSet) }
            val list = area?.let { a ->
                listCache.getOrPut(a.buildingSet) {
                    if (listNarc == null || a.buildingSet >= listNarc.count) null else runCatching { readBuildingList(listNarc.read(a.buildingSet)) }.getOrNull()
                }
            }
            val cxw = bx * BLOCK + BLOCK / 2
            val cyw = matrix.altitude(bx, by) * 8.0
            val czw = by * BLOCK + BLOCK / 2
            if (l.model.isNotEmpty()) {
                val c = runCatching { Nitro.parse(l.model) }.getOrNull()
                if (c != null) {
                    val own = ArrayList<Nitro.Batch>()
                    val sets = listOfNotNull(mapTex, c.texSet)
                    for (m in c.models) Nitro.buildMesh(m, Mat4.translate(cxw, cyw, czw), sets, light, own)
                    val scrolls = area?.let { a -> scrollCache.getOrPut(a.animation) { runCatching { FieldAnimations.readScrolls(src, a.animation) }.getOrDefault(emptyMap()) } }
                    for (b in own) {
                        val img = b.image
                        val swap = b.textureName?.let { swaps[it] }?.takeIf { img != null && it.width == img.width && it.height == img.height }
                        val scroll = b.materialName?.let { scrolls?.get(it) }
                        if (img == null || (swap == null && scroll == null)) { batches.add(b); continue }
                        val blend = b.alpha in 1..30 || img.translucent
                        val slot = slotCache.getOrPut(listOf(img, swap, scroll, b.alpha, b.mode, blend)) {
                            SceneAnimation.Slot(img, swap, scroll, if (b.alpha == 0) 1f else b.alpha / 31f, b.mode == 1, blend)
                        }
                        layers.add(b to slot)
                    }
                    terrain.add(intArrayOf(bx, by) to own)
                }
            }
            val bnarc = if (area?.outdoor != false) outdoorNarc else indoorNarc
            for (bd in l.buildings) {
                if (bnarc == null || bd.model < 0 || bd.model >= bnarc.count) continue
                // the game only has the area's own buildings loaded
                if (list != null && bd.model !in list) continue
                val key = (if (bnarc === outdoorNarc) 0L else 1L shl 40) or bd.model.toLong()
                val c = modelCache.getOrPut(key) { runCatching { Nitro.parse(bnarc.read(bd.model)) }.getOrNull() } ?: continue
                // MapPropManager: moved with the block across, not up; drawn unturned
                val place = Mat4.mul(Mat4.translate(cxw + bd.x, bd.y, czw + bd.z), Mat4.scale(bd.sx, bd.sy, bd.sz))
                // the area's building textures are what the game binds to the model
                val sets = listOfNotNull(bldTex, c.texSet)
                val own = ArrayList<Nitro.Batch>()
                for (m in c.models) Nitro.buildMesh(m, place, sets, light, own)
                if (!floatingLayer(own, bd.y)) batches.addAll(own)
            }
        }
        if (batches.isEmpty()) throw MapException("nothing to draw for ${course.place}")

        // how high the ground is under the walkable tiles: the surfaces people stand on, else the terrain
        val fallback = HashMap<Long, Double>()
        for ((cell, own) in terrain) groundHeights(own, cell[0], cell[1], fallback)
        fun groundY(gx: Int, gy: Int): Double? {
            val bx = Math.floorDiv(gx, 32)
            val by = Math.floorDiv(gy, 32)
            val l = landAt(bx, by) ?: return null
            l.surfaceAt(gx - bx * 32, gy - by * 32)?.let { return it + matrix.altitude(bx, by) * 8.0 }
            return fallback[Scene.key(gx, gy)]
        }
        var centerY = groundY(bestGx, bestGy) ?: groundY(bestGx - 1, bestGy - 1)
        if (centerY == null) {
            // the middle is a wall or water: the nearest ground's height
            search@ for (r in 1..8) for (dy in -r..r) for (dx in -r..r) {
                if (abs(dx) != r && abs(dy) != r) continue
                if (!ground(bestGx + dx, bestGy + dy)) continue
                centerY = groundY(bestGx + dx, bestGy + dy) ?: continue
                break@search
            }
        }
        val cy = centerY ?: 0.0

        val eye = doubleArrayOf(tx, cy + dist * sin(PITCH), tz + dist * cos(PITCH))
        val viewProj = Mat4.mul(SoftRenderer.perspective(FOVY, aspect, dist * 0.4, dist * 2.5), SoftRenderer.lookAt(eye, doubleArrayOf(tx, cy, tz)))
        val r = SoftRenderer(width, height)
        r.clear(0xFF000000.toInt())
        r.viewProj = viewProj
        r.draw(batches)
        val animation = runCatching { SceneAnimation.capture(r, layers) }.getOrNull()

        // tiles the Pokémon can walk on and that are in sight, with room for it to be drawn
        val tiles = HashMap<Long, Tile>()
        for (by in by0..by1) for (bx in bx0..bx1) {
            if (landAt(bx, by) == null) continue
            for (y in 0 until 32) for (x in 0 until 32) {
                val gx = bx * 32 + x
                val gy = by * 32 + y
                if (!ground(gx, gy)) continue
                val gh = groundY(gx, gy) ?: continue
                val wx = gx * TILE + TILE / 2
                val wz = gy * TILE + TILE / 2
                val pr = r.project(wx, gh, wz) ?: continue
                if (pr[0] < 10f || pr[0] > width - 10f || pr[1] < 30f || pr[1] > height - 4f) continue
                // something drawn in front of the ground here (a roof, a tree top): not in sight
                val d = r.depth[pr[1].toInt().coerceIn(0, height - 1) * width + pr[0].toInt().coerceIn(0, width - 1)]
                if (d > pr[2] * 1.004f) continue
                val l = landAt(bx, by)!!
                tiles[Scene.key(gx, gy)] = Tile(gx, gy, wx, gh, wz, l.behavior(x, y))
            }
        }
        // the water in sight
        val water = ArrayList<Float>()
        val waterTiles = HashMap<Long, Tile>()
        for (by in by0..by1) for (bx in bx0..bx1) {
            val l = landAt(bx, by) ?: continue
            for (y in 0 until 32) for (x in 0 until 32) {
                if (!isWater(l.behavior(x, y))) continue
                val gx = bx * 32 + x
                val gy = by * 32 + y
                val gh = groundY(gx, gy) ?: (matrix.altitude(bx, by) * 8.0)
                val wx = gx * TILE + TILE / 2
                val wz = gy * TILE + TILE / 2
                val pr = r.project(wx, gh, wz) ?: continue
                if (pr[0] < 0f || pr[0] >= width || pr[1] < 0f || pr[1] >= height) continue
                val d = r.depth[pr[1].toInt() * width + pr[0].toInt()]
                if (d > pr[2] * 1.02f) continue
                water.add(pr[0]); water.add(pr[1])
                if (pr[0] >= 10f && pr[0] <= width - 10f && pr[1] >= 30f && pr[1] <= height - 4f && !l.blocked(x, y)) {
                    waterTiles[Scene.key(gx, gy)] = Tile(gx, gy, wx, gh, wz, l.behavior(x, y))
                }
            }
        }
        val start = startTile(tiles, bestGx, bestGy)
        log("${tiles.size} tiles in sight to walk on, starting at ${start?.let { "${it.gx},${it.gy}" }}")
        log("${animation?.size ?: 0} moving pixels, ${water.size / 2} water tiles in sight")

        // the people of the maps in sight
        val mapsInSight = LinkedHashSet<Int>()
        mapsInSight.add(course.map)
        for (by in by0..by1) for (bx in bx0..bx1) {
            if (landAt(bx, by) != null) matrix.header(bx, by).takeIf { it >= 0 }?.let { mapsInSight.add(it) }
        }
        val people = runCatching {
            val others = FieldPeople.spots(src, mapsInSight.filter { it != course.map }, log)
            FieldPeople.place(ownPeople + others, tiles, waterTiles)
        }.getOrElse { log("No people: ${it.message}"); emptyList() }
        log("${people.size} people in sight")

        // ledges to hop down (tile behaviours JUMP_EAST/WEST/NORTH/SOUTH, include/constants/metatile_behavior.h)
        val ledges = HashMap<Long, Int>()
        for (by in by0..by1) for (bx in bx0..bx1) {
            val l = landAt(bx, by) ?: continue
            for (y in 0 until 32) for (x in 0 until 32) {
                val dir = when (l.behavior(x, y)) {
                    0x38 -> FollowerSprites.RIGHT
                    0x39 -> FollowerSprites.LEFT
                    0x3A -> FollowerSprites.UP
                    0x3B -> FollowerSprites.DOWN
                    else -> continue
                }
                ledges[Scene.key(bx * 32 + x, by * 32 + y)] = dir
            }
        }
        // things to read: the maps' bg events (not hidden items) with ground in sight beside them
        val signs = runCatching { FieldPeople.signs(src, mapsInSight) }.getOrDefault(emptyList()).mapNotNull { (gx, gy) ->
            if (Scene.key(gx, gy) in tiles) return@mapNotNull null
            val beside = listOf(gx to gy + 1, gx - 1 to gy, gx + 1 to gy, gx to gy - 1).any { (x, y) -> Scene.key(x, y) in tiles }
            if (!beside) return@mapNotNull null
            val bx = Math.floorDiv(gx, 32)
            val by = Math.floorDiv(gy, 32)
            if (landAt(bx, by) == null) return@mapNotNull null
            val gh = groundY(gx, gy) ?: listOf(gx to gy + 1, gx - 1 to gy, gx + 1 to gy).firstNotNullOfOrNull { (x, y) -> tiles[Scene.key(x, y)]?.y } ?: return@mapNotNull null
            Tile(gx, gy, gx * TILE + TILE / 2, gh, gy * TILE + TILE / 2)
        }.distinctBy { Scene.key(it.gx, it.gy) }
        log("${ledges.size} ledge tiles, ${signs.size} things to read")
        return Scene(course, width, height, r.color, r.depth, viewProj, tiles, TILE.toFloat(), start, lightKey(minute, lightType),
            animation, water.toFloatArray(), followsTheDay(lightType), waterTiles, people, ledges, signs)
    }

    /** Whether two neighbouring tiles are a step apart (not a cliff). */
    fun stepOk(a: Tile, b: Tile) = abs(a.y - b.y) <= MAX_STEP

    /** The tile nearest the middle among the biggest stretch of tiles joined up by steps. */
    private fun startTile(tiles: Map<Long, Tile>, gx0: Int, gy0: Int): Tile? {
        val seen = HashSet<Long>()
        var bestGroup: List<Tile> = emptyList()
        for (t in tiles.values) {
            if (!seen.add(Scene.key(t.gx, t.gy))) continue
            val group = ArrayList<Tile>()
            val queue = ArrayDeque<Tile>()
            queue.add(t)
            while (queue.isNotEmpty()) {
                val a = queue.removeFirst()
                group.add(a)
                for (s in STEPS) {
                    val k = Scene.key(a.gx + s[0], a.gy + s[1])
                    val b = tiles[k] ?: continue
                    if (k in seen || !stepOk(a, b)) continue
                    seen.add(k)
                    queue.add(b)
                }
            }
            if (group.size > bestGroup.size) bestGroup = group
        }
        return bestGroup.minByOrNull { abs(it.gx - gx0) + abs(it.gy - gy0) }
    }

    /**
     * A flat layer floating high over its spot: the game's animated effects (New Bark Town's
     * wind streaks), which don't make sense as a still picture.
     */
    private fun floatingLayer(batches: List<Nitro.Batch>, baseY: Double): Boolean {
        var minY = Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        for (b in batches) for (i in 0 until b.count) {
            val y = b.v[i * 8 + 1].toDouble()
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        return minY != Double.MAX_VALUE && maxY - minY < 2.0 && minY - baseY > 48.0
    }

    /** The terrain's height at each tile centre of block (bx, by): the highest upward-facing triangle there. */
    private fun groundHeights(batches: List<Nitro.Batch>, bx: Int, by: Int, out: HashMap<Long, Double>) {
        for (b in batches) {
            val v = b.v
            for (t in 0 until b.triangles) {
                val o = t * 24
                val ax = v[o].toDouble(); val ay = v[o + 1].toDouble(); val az = v[o + 2].toDouble()
                val bxw = v[o + 8].toDouble(); val byw = v[o + 9].toDouble(); val bzw = v[o + 10].toDouble()
                val cx = v[o + 16].toDouble(); val cy = v[o + 17].toDouble(); val cz = v[o + 18].toDouble()
                // facing up?
                val ux = bxw - ax; val uy = byw - ay; val uz = bzw - az
                val wx = cx - ax; val wy = cy - ay; val wz = cz - az
                val ny = uz * wx - ux * wz
                val nx = uy * wz - uz * wy
                val nz = ux * wy - uy * wx
                val nl = sqrt(nx * nx + ny * ny + nz * nz)
                if (nl < 1e-9 || abs(ny) / nl < 0.35) continue
                val minX = min(ax, min(bxw, cx)); val maxX = max(ax, max(bxw, cx))
                val minZ = min(az, min(bzw, cz)); val maxZ = max(az, max(bzw, cz))
                val tx0 = floor((minX - TILE / 2) / TILE).toInt()
                val tx1 = floor((maxX - TILE / 2) / TILE).toInt() + 1
                val tz0 = floor((minZ - TILE / 2) / TILE).toInt()
                val tz1 = floor((maxZ - TILE / 2) / TILE).toInt() + 1
                val det = (bzw - cz) * (ax - cx) + (cx - bxw) * (az - cz)
                if (abs(det) < 1e-9) continue
                for (gz in tz0..tz1) for (gx in tx0..tx1) {
                    if (gx < bx * 32 || gx >= bx * 32 + 32 || gz < by * 32 || gz >= by * 32 + 32) continue
                    val px = gx * TILE + TILE / 2
                    val pz = gz * TILE + TILE / 2
                    val l1 = ((bzw - cz) * (px - cx) + (cx - bxw) * (pz - cz)) / det
                    val l2 = ((cz - az) * (px - cx) + (ax - cx) * (pz - cz)) / det
                    val l3 = 1 - l1 - l2
                    if (l1 < -1e-6 || l2 < -1e-6 || l3 < -1e-6) continue
                    val h = l1 * ay + l2 * byw + l3 * cy
                    val k = Scene.key(gx, gz)
                    val prev = out[k]
                    if (prev == null || h > prev) out[k] = h
                }
            }
        }
    }
}
