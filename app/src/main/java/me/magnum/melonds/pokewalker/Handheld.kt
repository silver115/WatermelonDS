package me.magnum.melonds.pokewalker

import android.os.Build

/**
 * Handhelds with their own game controls. On the AYN Thor the D-pad and the sticks steer the
 * walking Pokémon about the route map (it goes back to roaming on its own a few seconds after
 * they're let go), and the walker's own buttons move to the shoulder buttons and A.
 */
object Handheld {
    /** Whether this is an AYN Thor. */
    val isAynThor: Boolean by lazy {
        PadInput.isThor(Build.MANUFACTURER, Build.BRAND, Build.MODEL, Build.DEVICE, Build.PRODUCT)
    }
}
