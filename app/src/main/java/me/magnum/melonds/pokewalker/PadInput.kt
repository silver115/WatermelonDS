package me.magnum.melonds.pokewalker

import kotlin.math.abs

/** Reading game controls for steering the walking Pokémon (see [Handheld]). Pure Kotlin. */
object PadInput {
    /** Whether a device's names say it's an AYN Thor. */
    fun isThor(manufacturer: String?, brand: String?, model: String?, device: String?, product: String?): Boolean {
        val maker = listOfNotNull(manufacturer, brand).joinToString(" ").lowercase()
        val names = listOfNotNull(model, device, product).joinToString(" ").lowercase()
        return ("ayn" in maker || "ayn" in names) && "thor" in names
    }

    /**
     * Which way a hat or stick at ([x], [y]) points (FollowerSprites.UP, DOWN, LEFT, RIGHT; y
     * grows downwards), or -1 when it's within [dead] of the middle.
     */
    fun direction(x: Float, y: Float, dead: Float = 0.5f): Int = when {
        x.isNaN() || y.isNaN() -> -1
        abs(x) < dead && abs(y) < dead -> -1
        abs(x) >= abs(y) -> if (x > 0) FollowerSprites.RIGHT else FollowerSprites.LEFT
        else -> if (y > 0) FollowerSprites.DOWN else FollowerSprites.UP
    }
}
