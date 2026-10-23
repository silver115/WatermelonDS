package me.magnum.melonds.pokewalker

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.util.Log
import android.widget.RemoteViews
import me.magnum.melonds.R
import java.text.NumberFormat
import java.util.Calendar

/**
 * Home-screen widget: the route's landscape (with the sky of the time of day) behind the
 * walker's own screen (walking Pokémon, route, steps), today's steps against the goal,
 * watts, and a sync button. Tapping it opens the walker.
 * Refreshed every 30 minutes, by the hourly job, after syncs and when the walker or the
 * emulator is left.
 */
class PokewalkerWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        update(context, manager, ids)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        update(context, manager, intArrayOf(id))
    }

    companion object {
        private const val TAG = "PokewalkerWidget"

        fun updateAll(context: Context) {
            try {
                val manager = AppWidgetManager.getInstance(context) ?: return
                val ids = manager.getAppWidgetIds(ComponentName(context, PokewalkerWidget::class.java))
                if (ids.isNotEmpty()) update(context, manager, ids)
            } catch (e: Exception) {
                Log.w(TAG, "Could not update the widgets", e)
            }
        }

        private fun update(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val views = build(context.applicationContext)
            for (id in ids) manager.updateAppWidget(id, views)
        }

        private fun build(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.pokewalker_widget)
            val numbers = NumberFormat.getIntegerInstance()

            val frame = ByteArray(WalkerDeviceView.LCD_WIDTH * WalkerDeviceView.LCD_HEIGHT)
            val status = runCatching { Pokewalker.status() }.getOrNull()
            runCatching { scene(status) }.getOrNull()?.let { views.setImageViewBitmap(R.id.pokewalker_widget_scene, it) }
            if (runCatching { PokewalkerNative.widgetFrame(frame) }.getOrDefault(false)) {
                views.setImageViewBitmap(R.id.pokewalker_widget_lcd, LcdPalette.render(frame, LcdPalette.MODERN, 3))
            }

            val title = when {
                status == null || !status.open || !status.registered -> context.getString(R.string.pokewalker_modern_not_paired)
                !status.hasPokemon -> context.getString(R.string.pokewalker_modern_no_stroll)
                status.route.isNotBlank() -> "${status.pokemonName} · ${status.route}"
                else -> status.pokemonName
            }
            val steps = status?.todaySteps ?: 0L
            val goal = Pokewalker.stepGoal(context)
            views.setTextViewText(R.id.pokewalker_widget_title, title)
            views.setTextViewText(R.id.pokewalker_widget_steps, numbers.format(steps))
            views.setProgressBar(R.id.pokewalker_widget_goal, 1000, ((steps * 1000) / goal.coerceAtLeast(1)).coerceIn(0, 1000).toInt(), false)
            views.setTextViewText(R.id.pokewalker_widget_watts, "${numbers.format(status?.watts ?: 0)} W")

            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, PokewalkerDeviceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                flags,
            )
            val sync = PendingIntent.getActivity(
                context, 1,
                Intent(context, PokewalkerSyncActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                flags,
            )
            views.setOnClickPendingIntent(R.id.pokewalker_widget_root, open)
            views.setOnClickPendingIntent(R.id.pokewalker_widget_lcd, open)
            views.setOnClickPendingIntent(R.id.pokewalker_widget_sync, sync)
            return views
        }

        /** The route's landscape at this time of day, for the widget's background. */
        private fun scene(status: WalkerStatus?): Bitmap {
            val w = 480
            val h = 220
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val scene = RouteScene()
            if (status != null && status.registered && status.hasPokemon) scene.setRoute(status.routeImage, status.route)
            val cal = Calendar.getInstance()
            val canvas = AndroidSceneCanvas().apply { canvas = Canvas(bitmap) }
            scene.paint(canvas, w.toFloat(), h.toFloat(), cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE), 0L)
            return bitmap
        }
    }
}
