package me.magnum.melonds.pokewalker

import android.app.WallpaperManager
import android.graphics.Canvas
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The route map as a live wallpaper: the place of the walk from the player's own HeartGold or
 * SoulSilver, with the walking Pokémon roaming about it and life going on, as on the walker
 * screen. Swiping between home screens pans across the route; a tap on the Pokémon and it hops
 * (and cries), a tap elsewhere and it walks over. Without a walk or a game to read, the painted
 * landscape of the route shows.
 */
class RouteWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = RouteEngine()

    private inner class RouteEngine : Engine() {
        private val handler = Handler(Looper.getMainLooper())
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        private val scene = RouteSceneDrawable(resources.displayMetrics.density)
        private var visible = false
        private var width = 0
        private var height = 0
        private var updates: Job? = null
        private var walkerKey: String? = null
        private var party = WalkParty(null)
        private var pan = 0.5f
        private val frame = Runnable { drawFrame() }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            scene.explore = true
            Pokewalker.applySettings(this@RouteWallpaperService)
        }

        override fun onDestroy() {
            handler.removeCallbacks(frame)
            scope.cancel()
            RouteMaps.forget(RouteMaps.WALLPAPER)
            super.onDestroy()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            if (visible) {
                startUpdates()
                drawFrame()
            } else {
                handler.removeCallbacks(frame)
                updates?.cancel()
                updates = null
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            this.width = width
            this.height = height
            scene.setBounds(0, 0, width, height)
            if (visible) startUpdates()
            drawFrame()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            visible = false
            handler.removeCallbacks(frame)
            super.onSurfaceDestroyed(holder)
        }

        override fun onOffsetsChanged(xOffset: Float, yOffset: Float, xOffsetStep: Float, yOffsetStep: Float, xPixelOffset: Int, yPixelOffset: Int) {
            // home screens side by side: the view pans across the route with them
            pan = if (xOffsetStep > 0f && xOffsetStep < 1f) xOffset.coerceIn(0f, 1f) else 0.5f
            scene.map?.pan = pan
        }

        override fun onCommand(action: String?, x: Int, y: Int, z: Int, extras: Bundle?, resultRequested: Boolean): Bundle? {
            if (action == WallpaperManager.COMMAND_TAP) {
                scene.map?.let { map ->
                    if (map.tap(x.toFloat(), y.toFloat(), SystemClock.uptimeMillis()) && map.species > 0) {
                        PokemonCries.play(this@RouteWallpaperService, map.species, 1) {}
                    }
                }
            }
            return super.onCommand(action, x, y, z, extras, resultRequested)
        }

        /** What's on the walker, now and again while it can be seen. */
        private fun startUpdates() {
            if (updates?.isActive == true || width <= 0) return
            updates = scope.launch {
                while (isActive) {
                    val status = withContext(Dispatchers.Default) { runCatching { Pokewalker.status() }.getOrNull() }
                    update(status)
                    delay(8_000)
                }
            }
        }

        private suspend fun update(status: WalkerStatus?) {
            val walking = status != null && status.registered && status.hasPokemon
            if (walking) scene.scene.setRoute(status!!.routeImage, status.route) else scene.scene.setRoute(0, "")
            val key = if (walking) "${status!!.species}:${status.strollStartUnix}:${status.route}:${status.caught}" else ""
            if (key != walkerKey) {
                walkerKey = key
                val image = if (!walking) null else withContext(Dispatchers.Default) { runCatching { PokewalkerNative.exportImage() }.getOrNull() }
                party = WalkParty.fromImage(image)
            }
            val map = if (!walking || width <= 0) null else
                runCatching { RouteMaps.get(this@RouteWallpaperService, status!!.route, status.routeImage, party, width, height, RouteMaps.WALLPAPER) }.getOrNull()
            if (map != null && status != null) {
                map.setSteps(status.totalSteps, status.todaySteps, Pokewalker.stepGoal(this@RouteWallpaperService), SystemClock.uptimeMillis())
                val prefs = PreferenceManager.getDefaultSharedPreferences(this@RouteWallpaperService)
                map.reveal = if (prefs.getBoolean(RouteMaps.KEY_REVEAL, true)) {
                    RouteMaps.revealed(this@RouteWallpaperService, status.strollStartUnix, status.totalSteps, status.todaySteps)
                } else 1f
                map.pan = pan
            }
            scene.map = map
        }

        private fun drawFrame() {
            handler.removeCallbacks(frame)
            if (!visible) return
            val holder = surfaceHolder
            var canvas: Canvas? = null
            try {
                canvas = if (Build.VERSION.SDK_INT >= 26) holder.lockHardwareCanvas() else holder.lockCanvas()
                if (canvas != null) scene.draw(canvas)
            } catch (e: Exception) {
                Log.w("RouteWallpaper", "Could not draw", e)
            } finally {
                if (canvas != null) runCatching { holder.unlockCanvasAndPost(canvas) }
            }
            // the map at 20 frames a second, the painted landscape at 10
            handler.postDelayed(frame, if (scene.map != null) 50L else 100L)
        }
    }
}
