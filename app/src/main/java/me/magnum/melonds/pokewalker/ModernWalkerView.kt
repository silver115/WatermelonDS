package me.magnum.melonds.pokewalker

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import me.magnum.melonds.R
import me.magnum.melonds.pokewalker.WalkerTheme.BLUE
import me.magnum.melonds.pokewalker.WalkerTheme.BLUE_LIGHT
import me.magnum.melonds.pokewalker.WalkerTheme.CARD
import me.magnum.melonds.pokewalker.WalkerTheme.CARD_EDGE
import me.magnum.melonds.pokewalker.WalkerTheme.CONTROL
import me.magnum.melonds.pokewalker.WalkerTheme.CREAM
import me.magnum.melonds.pokewalker.WalkerTheme.GOLD
import me.magnum.melonds.pokewalker.WalkerTheme.INK
import me.magnum.melonds.pokewalker.WalkerTheme.RED
import me.magnum.melonds.pokewalker.WalkerTheme.SCREEN_FRAME
import me.magnum.melonds.pokewalker.WalkerTheme.TEXT
import me.magnum.melonds.pokewalker.WalkerTheme.TEXT_DIM
import me.magnum.melonds.pokewalker.WalkerTheme.TEXT_FAINT
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.text.DecimalFormat
import java.util.Date
import java.util.Locale
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The modern look, in Lucario colours over the route's landscape (drawn behind it by the
 * screen): the walker's screen large and crisp in a card (tap it, or swipe it sideways), a
 * three-way control under it, today's steps against a goal with the distance and the goal
 * streak, watts and what the walker holds, the last week as a chart, and one-tap actions.
 * Cards are slightly see-through so the landscape shows. The walker logic is native.
 *
 * It arranges itself for the space it gets:
 *  - tall (phones held upright): screen card, steps and watts, the week, actions, stacked;
 *  - wide (landscape, like the AYN Thor's main screen): screen card left, the rest right;
 *  - compact (small or squarish windows): the screen card with steps and watts in its
 *    header, and the actions beside or under it.
 * The walker's screen is always drawn at a whole-pixel scale so its pixels stay crisp.
 */
@SuppressLint("ViewConstructor")
class ModernWalkerView(
    context: Context,
    private val onButton: (Int) -> Unit,
    private val onAction: (Int) -> Unit,
    onScreenTap: (x: Int, y: Int) -> Unit,
) : View(context), WalkerSkin {

    companion object {
        const val ACTION_SYNC = 0
        const val ACTION_RADAR = 1
        const val ACTION_DOWSING = 2
        const val ACTION_PLAY = 3

        private const val LCD_W = 96f
        private const val LCD_H = 64f

        private val ACTION_LABELS = intArrayOf(
            R.string.pokewalker_action_sync, R.string.pokewalker_action_radar,
            R.string.pokewalker_action_dowsing, R.string.pokewalker_action_play,
        )
        private val ACTION_ICONS = intArrayOf(R.drawable.ic_refresh, R.drawable.ic_pw_radar, R.drawable.ic_pw_dowsing, R.drawable.ic_pw_game)
    }

    override val view: View get() = this
    override var shade = 4
        private set

    private val lcd = Bitmap.createBitmap(WalkerDeviceView.LCD_WIDTH, WalkerDeviceView.LCD_HEIGHT, Bitmap.Config.ARGB_8888)
    private val lcdPixels = IntArray(WalkerDeviceView.LCD_WIDTH * WalkerDeviceView.LCD_HEIGHT)
    private val shades = ByteArray(WalkerDeviceView.LCD_WIDTH * WalkerDeviceView.LCD_HEIGHT)
    private var palette = LcdPalette.forShade(LcdPalette.MODERN, 4)

    private var status: WalkerStatus? = null
    private var goal = 10000
    var hasGame = false
        set(value) { field = value; invalidate() }

    private val numbers = NumberFormat.getIntegerInstance()
    private val dayNames = SimpleDateFormat("EEE", Locale.getDefault())

    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val lcdPaint = Paint().apply { isFilterBitmap = false }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
    private val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif-medium", Typeface.BOLD) }

    // layout
    private val lcdCard = RectF()
    private val lcdRect = Rect()
    private val lcdFrame = RectF()
    private val header = RectF()
    private val control = RectF()
    private val segments = Array(3) { RectF() }
    private val ringCard = RectF()
    private val wattsCard = RectF()
    private val weekCard = RectF()
    private val chips = Array(4) { RectF() }
    private var radius = 0f
    private var compact = false
    private val clip = Path()
    private val confetti = Confetti(this, context.getString(R.string.pokewalker_goal_reached))
    private val oneDecimal = DecimalFormat("0.0")
    private val miles = Locale.getDefault().country in setOf("US", "LR", "MM")

    // touch
    private val pressedButton = BooleanArray(3)
    private var pressedChip = -1
    private val pointerSegment = HashMap<Int, Int>()
    private val gestures = LcdGestures(this, onScreenTap) { button -> onButton(button) }.apply { rippleColor = BLUE }

    private val icons = HashMap<Int, Drawable?>()

    init {
        isFocusable = true
        redrawLcd()
    }

    // ---- data ------------------------------------------------------------------------------

    override fun setFrame(frame: ByteArray, newShade: Int) {
        if (newShade != shade) {
            shade = newShade
            palette = LcdPalette.forShade(LcdPalette.MODERN, newShade)
        }
        System.arraycopy(frame, 0, shades, 0, min(frame.size, shades.size))
        redrawLcd()
        invalidate(lcdRect)
    }

    override fun showPressed(button: Int, down: Boolean) {
        if (button in 0..2 && pressedButton[button] != down) {
            pressedButton[button] = down
            invalidate()
        }
    }

    override fun setStatus(status: WalkerStatus?, stepGoal: Int) {
        if (status == this.status && stepGoal == goal) return
        this.status = status
        goal = stepGoal
        invalidate()
    }

    override fun celebrate() = confetti.start()

    private fun redrawLcd() {
        for (i in lcdPixels.indices) lcdPixels[i] = palette[shades[i].toInt() and 3]
        lcd.setPixels(lcdPixels, 0, WalkerDeviceView.LCD_WIDTH, 0, 0, WalkerDeviceView.LCD_WIDTH, WalkerDeviceView.LCD_HEIGHT)
    }

    private fun icon(res: Int): Drawable? = icons.getOrPut(res) { ContextCompat.getDrawable(context, res)?.mutate() }

    // ---- layout ----------------------------------------------------------------------------

    /** Screen card padding and the frame round the screen, left and right together. */
    private val chromeW get() = dp(38f)

    /** One line: the name, then level and route; taller when the system font is larger. */
    private val headerH get() = max(sp(22f), dp(26f)) + dp(10f)

    /** Screen card top, header, frame, gap, the three buttons and the bottom, together. */
    private val chromeH get() = dp(96f) + headerH

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutFor(w.toFloat(), h.toFloat())
    }

    private fun layoutFor(w: Float, h: Float) {
        val pad = dp(16f)
        val gap = dp(12f)
        radius = dp(22f)
        ringCard.setEmpty()
        wattsCard.setEmpty()
        weekCard.setEmpty()
        for (c in chips) c.setEmpty()
        compact = false
        // The biggest walker screen wins; between equals, the arrangement that shows more.
        val compactUnit = compactUnit(w, h, pad, gap)
        val placed = when {
            w >= h * 1.2f -> wideUnit(w, h, pad) >= compactUnit && layoutWide(w, h, pad, gap)
            h >= w * 1.2f -> {
                val withWeek = tallUnit(w, h, pad, gap, withWeek = true)
                val without = tallUnit(w, h, pad, gap, withWeek = false)
                when {
                    withWeek >= compactUnit && withWeek >= without -> layoutTall(w, h, pad, gap, withWeek = true)
                    without >= compactUnit -> layoutTall(w, h, pad, gap, withWeek = false)
                    else -> false
                }
            }
            else -> false
        }
        if (!placed) layoutCompact(w, h, pad, gap)
        layoutScreenCard()
    }

    /** Walker pixel size in each arrangement (0 when it doesn't fit or the screen would be too small). */
    private fun wideUnit(w: Float, h: Float, pad: Float): Float {
        val leftMax = w - pad * 3 - dp(260f)
        if (leftMax < dp(232f)) return 0f
        val unit = lcdUnit(leftMax - chromeW, h - pad * 2 - chromeH)
        return if (readable(unit)) unit else 0f
    }

    private fun tallBelow(gap: Float, withWeek: Boolean) = gap + dp(140f) + (if (withWeek) gap + dp(124f) else 0f) + gap + dp(68f)

    private fun tallUnit(w: Float, h: Float, pad: Float, gap: Float, withWeek: Boolean): Float {
        val cardW = min(w - pad * 2, dp(560f))
        val unit = lcdUnit(cardW - chromeW, h - pad * 2 - tallBelow(gap, withWeek) - chromeH)
        return if (readable(unit)) unit else 0f
    }

    private fun compactUnit(w: Float, h: Float, pad: Float, gap: Float): Float =
        if (w >= h) lcdUnit(w - pad * 3 - dp(88f) - chromeW, h - pad * 2 - chromeH)
        else lcdUnit(w - pad * 2 - chromeW, h - pad * 2 - gap - dp(68f) - chromeH)

    /** Whole-pixel scale for the 96x64 screen in a maxW x maxH box, at most about 6dp a pixel. */
    private fun lcdUnit(maxW: Float, maxH: Float): Float {
        val fit = min(maxW / LCD_W, maxH / LCD_H)
        if (fit < 1f) return max(fit, 0.25f)
        return min(floor(fit), max(1f, floor(dp(6f))))
    }

    /** Big enough to read easily: 2dp or more per walker pixel. */
    private fun readable(unit: Float) = unit * LCD_H >= dp(128f)

    private fun layoutTall(w: Float, h: Float, pad: Float, gap: Float, withWeek: Boolean): Boolean {
        val cardW = min(w - pad * 2, dp(560f))
        val summaryH = dp(140f)
        val weekH = if (withWeek) dp(124f) else 0f
        val actionsH = dp(68f)
        val below = tallBelow(gap, withWeek)
        val unit = tallUnit(w, h, pad, gap, withWeek)
        if (unit <= 0f) return false
        val cardH = unit * LCD_H + chromeH
        val left = (w - cardW) / 2f
        var top = max(pad, (h - cardH - below) / 2f)
        lcdCard.set(left, top, left + cardW, top + cardH)
        top = lcdCard.bottom + gap
        val half = (cardW - gap) / 2f
        ringCard.set(left, top, left + half, top + summaryH)
        wattsCard.set(ringCard.right + gap, top, left + cardW, top + summaryH)
        top = ringCard.bottom + gap
        if (withWeek) {
            weekCard.set(left, top, left + cardW, top + weekH)
            top = weekCard.bottom + gap
        }
        chipRow(left, top, cardW, actionsH, gap)
        return true
    }

    private fun layoutWide(w: Float, h: Float, pad: Float, gap: Float): Boolean {
        val leftMax = w - pad * 3 - dp(260f)
        val unit = wideUnit(w, h, pad)
        if (unit <= 0f) return false
        val colH = min(h - pad * 2, unit * LCD_H + chromeH + dp(40f))
        var leftW = max(unit * LCD_W + chromeW, dp(272f))
        leftW = max(leftW, (w - pad * 3) * 0.42f)
        leftW = min(leftW, leftMax)
        val rightW = min(w - pad * 3 - leftW, dp(640f))
        val x = (w - leftW - pad - rightW) / 2f
        val y = (h - colH) / 2f
        lcdCard.set(x, y, x + leftW, y + colH)

        val rl = lcdCard.right + pad
        val oneRow = rightW >= dp(60f) * 4 + gap * 3
        val actionsH = if (oneRow) dp(68f) else dp(56f) * 2 + gap
        val rest = colH - actionsH - gap
        val withWeek = rest >= dp(128f) + gap + dp(96f)
        val summaryH = if (withWeek) (rest * 0.52f).coerceIn(dp(128f), dp(170f)) else rest
        val weekH = if (withWeek) rest - gap - summaryH else 0f
        var top = y
        val half = (rightW - gap) / 2f
        ringCard.set(rl, top, rl + half, top + summaryH)
        wattsCard.set(ringCard.right + gap, top, rl + rightW, top + summaryH)
        top = ringCard.bottom + gap
        if (withWeek) {
            weekCard.set(rl, top, rl + rightW, top + weekH)
            top = weekCard.bottom + gap
        }
        if (oneRow) chipRow(rl, top, rightW, actionsH, gap) else chipGrid(rl, top, rightW, actionsH, gap)
        return true
    }

    private fun layoutCompact(w: Float, h: Float, pad: Float, gap: Float) {
        compact = true
        if (w >= h) {
            // the actions in a column beside the screen card
            val colW = dp(88f)
            val colH = h - pad * 2
            lcdCard.set(pad, pad, w - pad * 2 - colW, pad + colH)
            val tileH = (colH - gap * 3) / 4f
            val l = lcdCard.right + pad
            for (i in 0..3) {
                val t = pad + i * (tileH + gap)
                chips[i].set(l, t, l + colW, t + tileH)
            }
        } else {
            val rowH = dp(68f)
            val gridH = dp(56f) * 2 + gap
            val needed = lcdUnit(w - pad * 2 - chromeW, h - pad * 2 - gap - rowH - chromeH) * LCD_H + chromeH
            val roomy = h - pad * 2 - gap - needed >= gridH
            val actionsH = if (roomy) gridH else rowH
            lcdCard.set(pad, pad, w - pad, h - pad - gap - actionsH)
            if (roomy) chipGrid(pad, lcdCard.bottom + gap, w - pad * 2, actionsH, gap)
            else chipRow(pad, lcdCard.bottom + gap, w - pad * 2, actionsH, gap)
        }
    }

    private fun chipRow(left: Float, top: Float, width: Float, height: Float, gap: Float) {
        val tileW = (width - gap * 3) / 4f
        for (i in 0..3) {
            val l = left + i * (tileW + gap)
            chips[i].set(l, top, l + tileW, top + height)
        }
    }

    private fun chipGrid(left: Float, top: Float, width: Float, height: Float, gap: Float) {
        val tileW = (width - gap) / 2f
        val tileH = (height - gap) / 2f
        for (i in 0..3) {
            val l = left + (i % 2) * (tileW + gap)
            val t = top + (i / 2) * (tileH + gap)
            chips[i].set(l, t, l + tileW, t + tileH)
        }
    }

    /** Inside the screen card: the header, the screen (whole-pixel scale) and the three buttons. */
    private fun layoutScreenCard() {
        val inner = dp(14f)
        val frame = dp(5f)
        val controlH = dp(56f)
        val bottom = dp(12f)
        val areaLeft = lcdCard.left + inner + frame
        val areaRight = lcdCard.right - inner - frame
        val areaBottom = lcdCard.bottom - bottom - controlH - dp(10f) - frame
        var headerH = this.headerH
        var areaTop = lcdCard.top + dp(8f) + headerH + frame
        var unit = lcdUnit(areaRight - areaLeft, areaBottom - areaTop)
        if (!readable(unit)) {
            // very little height: the screen matters more than the header
            val bareTop = lcdCard.top + inner + frame
            val bare = lcdUnit(areaRight - areaLeft, areaBottom - bareTop)
            if (bare > unit) {
                unit = bare
                headerH = 0f
                areaTop = bareTop
            }
        }
        header.set(lcdCard.left + inner + dp(2f), lcdCard.top + dp(8f), lcdCard.right - inner, lcdCard.top + dp(8f) + headerH)
        val lw = (LCD_W * unit).roundToInt()
        val lh = (LCD_H * unit).roundToInt()
        val lx = ((areaLeft + areaRight - lw) / 2f).roundToInt()
        val ly = ((areaTop + areaBottom - lh) / 2f).roundToInt()
        lcdRect.set(lx, ly, lx + lw, ly + lh)
        lcdFrame.set(lcdRect.left - frame, lcdRect.top - frame, lcdRect.right + frame, lcdRect.bottom + frame)
        clip.reset()
        clip.addRoundRect(RectF(lcdRect), dp(8f), dp(8f), Path.Direction.CW)

        val controlW = min(max(lcdFrame.width(), dp(240f)), lcdCard.width() - inner * 2)
        val cy = lcdCard.bottom - bottom - controlH / 2f
        control.set(lcdCard.centerX() - controlW / 2f, cy - controlH / 2f, lcdCard.centerX() + controlW / 2f, cy + controlH / 2f)
        val segW = control.width() / 3f
        for (i in 0..2) segments[i].set(control.left + i * segW, control.top, control.left + (i + 1) * segW, control.bottom)
    }

    // ---- drawing ---------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // the route's landscape is drawn behind this view (see RouteSceneDrawable)
        drawScreenCard(canvas)
        drawStepsCard(canvas)
        drawWattsCard(canvas)
        drawWeekCard(canvas)
        drawChips(canvas)
        confetti.draw(canvas)
    }

    private fun card(canvas: Canvas, r: RectF, opacity: Int = 0xE6) {
        fill.color = (CARD and 0x00FFFFFF) or (opacity shl 24)
        canvas.drawRoundRect(r, radius, radius, fill)
        stroke.color = CARD_EDGE
        stroke.strokeWidth = dp(1f)
        canvas.drawRoundRect(r, radius, radius, stroke)
    }

    private fun label(canvas: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, paint: Paint = text, align: Paint.Align = Paint.Align.LEFT, maxWidth: Float = 0f) {
        paint.textSize = sp(size)
        paint.color = color
        paint.textAlign = align
        var out = s
        if (maxWidth > 0f && paint.measureText(out) > maxWidth) {
            while (out.isNotEmpty() && paint.measureText("$out…") > maxWidth) out = out.dropLast(1)
            out = "$out…"
        }
        canvas.drawText(out, x, y, paint)
        paint.textAlign = Paint.Align.LEFT
    }

    /** The largest size from [preferred] down to [smallest] (sp) at which [s] fits in [maxWidth]. */
    private fun fitSp(paint: Paint, s: String, maxWidth: Float, preferred: Float, smallest: Float): Float {
        var size = preferred
        paint.textSize = sp(size)
        while (size > smallest && paint.measureText(s) > maxWidth) {
            size -= 0.5f
            paint.textSize = sp(size)
        }
        return size
    }

    private fun drawIcon(canvas: Canvas, res: Int, cx: Float, cy: Float, size: Float, color: Int) {
        val d = icon(res) ?: return
        d.setTint(color)
        d.setBounds((cx - size / 2).toInt(), (cy - size / 2).toInt(), (cx + size / 2).toInt(), (cy + size / 2).toInt())
        d.draw(canvas)
    }

    private fun drawScreenCard(canvas: Canvas) {
        card(canvas, lcdCard, 0xF0)
        if (header.height() > 0f) drawHeader(canvas)

        // the walker's screen, and where it was touched
        fill.color = SCREEN_FRAME
        canvas.drawRoundRect(lcdFrame, dp(11f), dp(11f), fill)
        canvas.save()
        canvas.clipPath(clip)
        canvas.drawBitmap(lcd, null, lcdRect, lcdPaint)
        gestures.draw(canvas, lcdRect)
        canvas.restore()

        // left / middle / right
        fill.color = CONTROL
        canvas.drawRoundRect(control, control.height() / 2, control.height() / 2, fill)
        for (i in 0..2) {
            val seg = segments[i]
            if (pressedButton[i]) {
                fill.color = if (i == 1) CREAM else BLUE
                val r = RectF(seg.left + dp(4f), seg.top + dp(4f), seg.right - dp(4f), seg.bottom - dp(4f))
                canvas.drawRoundRect(r, r.height() / 2, r.height() / 2, fill)
            }
            val color = if (pressedButton[i]) INK else TEXT
            when (i) {
                0 -> chevron(canvas, seg.centerX(), seg.centerY(), dp(9f), left = true, color = color)
                2 -> chevron(canvas, seg.centerX(), seg.centerY(), dp(9f), left = false, color = color)
                else -> {
                    fill.color = if (pressedButton[1]) color else CREAM
                    canvas.drawCircle(seg.centerX(), seg.centerY(), dp(9f), fill)
                }
            }
        }
        stroke.color = CARD_EDGE
        stroke.strokeWidth = dp(1f)
        canvas.drawLine(segments[0].right, control.top + dp(14f), segments[0].right, control.bottom - dp(14f), stroke)
        canvas.drawLine(segments[1].right, control.top + dp(14f), segments[1].right, control.bottom - dp(14f), stroke)
    }

    private fun drawHeader(canvas: Canvas) {
        val s = status
        val title: String
        var subtitle: String
        when {
            s == null || !s.open || !s.registered -> {
                title = context.getString(R.string.pokewalker_modern_not_paired)
                subtitle = context.getString(R.string.pokewalker_modern_not_paired_hint)
            }
            s.sessionActive -> {
                title = s.pokemonName.ifBlank { context.getString(R.string.pokewalker_device_label) }
                subtitle = context.getString(R.string.pokewalker_modern_busy)
            }
            !s.hasPokemon -> {
                title = context.getString(R.string.pokewalker_modern_no_stroll)
                subtitle = context.getString(R.string.pokewalker_modern_no_stroll_hint)
            }
            else -> {
                title = s.pokemonName
                subtitle = context.getString(R.string.pokewalker_modern_level_route, s.level, s.route)
            }
        }
        val shortSubtitle = if (s != null && s.hasPokemon && !s.sessionActive && s.route.isNotBlank()) s.route else subtitle
        // no stat cards in the compact arrangement: steps and watts go here instead
        if (compact && s != null && s.open && s.registered && !s.sessionActive) {
            subtitle = context.getString(R.string.pokewalker_modern_compact_stats, numbers.format(s.todaySteps), numbers.format(s.watts.toLong()))
        }
        // which day of the stroll, on the right
        var textRight = header.right
        if (!compact && s != null && s.hasPokemon && s.strollStartUnix > 0) {
            val days = (LocalDays.today() - LocalDays.dayOf(s.strollStartUnix * 1000)).toInt() + 1
            val pill = context.getString(R.string.pokewalker_modern_stroll_day, max(1, days))
            bold.textSize = sp(12f)
            val iconSize = dp(14f)
            val pw = dp(10f) + iconSize + dp(5f) + bold.measureText(pill) + dp(12f)
            val ph = dp(26f)
            val pr = RectF(header.right - pw, header.centerY() - ph / 2, header.right, header.centerY() + ph / 2)
            fill.color = CONTROL
            canvas.drawRoundRect(pr, ph / 2, ph / 2, fill)
            drawIcon(canvas, R.drawable.ic_pw_steps, pr.left + dp(10f) + iconSize / 2, pr.centerY(), iconSize, CREAM)
            label(canvas, pill, pr.left + dp(10f) + iconSize + dp(5f), pr.centerY() + sp(12f) * 0.35f, 12f, CREAM, bold)
            textRight = pr.left - dp(8f)
        }
        // one line: the name, then the details as far as they fit
        val baseline = header.centerY() + sp(18f) * 0.35f
        val room = textRight - header.left
        bold.textSize = sp(18f)
        val titleW = min(bold.measureText(title), room)
        label(canvas, title, header.left, baseline, 18f, TEXT, bold, maxWidth = room)
        val restX = header.left + titleW + dp(8f)
        if (subtitle.isNotEmpty() && textRight - restX >= dp(48f)) {
            text.textSize = sp(14f)
            val details = if (!compact && text.measureText("· $subtitle") > textRight - restX) shortSubtitle else subtitle
            label(canvas, "· $details", restX, baseline, 14f, TEXT_DIM, maxWidth = textRight - restX)
        }
    }

    private fun chevron(canvas: Canvas, cx: Float, cy: Float, size: Float, left: Boolean, color: Int) {
        stroke.color = color
        stroke.strokeWidth = dp(3f)
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.strokeJoin = Paint.Join.ROUND
        val dx = if (left) size * 0.5f else -size * 0.5f
        val p = Path().apply {
            moveTo(cx + dx, cy - size)
            lineTo(cx - dx, cy)
            lineTo(cx + dx, cy + size)
        }
        canvas.drawPath(p, stroke)
        stroke.strokeCap = Paint.Cap.BUTT
    }

    private fun drawStepsCard(canvas: Canvas) {
        if (ringCard.isEmpty) return
        card(canvas, ringCard)
        val s = status
        val steps = s?.todaySteps ?: 0L
        val titleY = ringCard.top + dp(26f)
        val today = context.getString(R.string.pokewalker_modern_today)
        label(canvas, today, ringCard.left + dp(16f), titleY, 13f, TEXT_DIM, bold)
        // the goal streak on the right: days in a row at the goal
        var right = ringCard.right - dp(14f)
        val streak = s?.let { goalStreak(it) } ?: 0
        if (streak >= 2) {
            val n = streak.toString()
            bold.textSize = sp(13f)
            val tw = bold.measureText(n)
            label(canvas, n, right - tw, titleY, 13f, CREAM, bold)
            flame(canvas, right - tw - dp(9f), titleY - sp(13f) * 0.35f, dp(13f))
            right -= tw + dp(22f)
        }
        // and how far that is
        bold.textSize = sp(13f)
        val distX = ringCard.left + dp(16f) + bold.measureText(today) + dp(6f)
        if (steps > 0 && right - distX > dp(40f)) {
            label(canvas, "· " + distance(steps), distX, titleY, 13f, TEXT_FAINT, maxWidth = right - distX)
        }
        val size = min(ringCard.width() - dp(32f), ringCard.height() - dp(48f))
        val cx = ringCard.centerX()
        val cy = ringCard.top + dp(38f) + (ringCard.height() - dp(38f)) / 2f
        val r = size / 2f - dp(6f)
        val oval = RectF(cx - r, cy - r, cx + r, cy + r)
        stroke.strokeWidth = dp(10f)
        stroke.color = CONTROL
        canvas.drawArc(oval, 0f, 360f, false, stroke)
        val progress = if (goal > 0) min(1f, steps.toFloat() / goal) else 0f
        if (progress > 0f) {
            // Lucario blue towards cream as the goal comes near
            val sweep = SweepGradient(cx, cy, intArrayOf(BLUE, BLUE_LIGHT, CREAM), floatArrayOf(0f, 0.55f, 1f))
            val m = Matrix().apply { setRotate(-100f, cx, cy) } // the round start cap stays blue
            sweep.setLocalMatrix(m)
            stroke.shader = sweep
            stroke.strokeCap = Paint.Cap.ROUND
            canvas.drawArc(oval, -90f, 360f * progress, false, stroke)
            stroke.shader = null
            stroke.strokeCap = Paint.Cap.BUTT
        }
        // the count and "of goal" inside the ring, as large as fits
        val hole = r - dp(9f)
        val count = numbers.format(steps)
        val countSp = fitSp(bold, count, hole * 1.75f, min(26f, r / resources.displayMetrics.scaledDensityCompat() * 0.42f), 8f)
        val countPx = sp(countSp)
        val of = context.getString(R.string.pokewalker_modern_of_goal, numbers.format(goal.toLong()))
        val ofPx = sp(11f)
        val space = countPx * 0.3f
        val block = countPx * 0.72f + space + ofPx * 0.72f
        val countY = cy - block / 2f + countPx * 0.72f
        val ofY = countY + space + ofPx * 0.72f
        label(canvas, count, cx, countY, countSp, TEXT, bold, Paint.Align.CENTER, maxWidth = hole * 1.8f)
        val dy = ofY - cy
        val chord = if (hole > dy) 2f * sqrt(hole * hole - dy * dy) else 0f
        if (chord >= dp(30f)) {
            val ofSp = fitSp(text, of, chord - dp(4f), 11f, 8f)
            label(canvas, of, cx, ofY, ofSp, TEXT_DIM, align = Paint.Align.CENTER, maxWidth = chord - dp(4f))
        }
    }

    /** Days in a row at the goal, counting today once it's reached. */
    private fun goalStreak(s: WalkerStatus): Int {
        if (goal <= 0) return 0
        var n = if (s.todaySteps >= goal) 1 else 0
        for (day in s.history) {
            if (day >= goal) n++ else break
        }
        return n
    }

    /** About how far the steps are (0.76 m a step), in km or miles. */
    private fun distance(steps: Long): String {
        val metres = steps * 0.762
        return if (miles) context.getString(R.string.pokewalker_distance_mi, oneDecimal.format(metres / 1609.344))
        else context.getString(R.string.pokewalker_distance_km, oneDecimal.format(metres / 1000.0))
    }

    private fun flame(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        val p = Path().apply {
            moveTo(cx, cy - size * 0.55f)
            cubicTo(cx + size * 0.45f, cy - size * 0.1f, cx + size * 0.4f, cy + size * 0.45f, cx, cy + size * 0.5f)
            cubicTo(cx - size * 0.4f, cy + size * 0.45f, cx - size * 0.45f, cy - size * 0.05f, cx - size * 0.1f, cy - size * 0.2f)
            cubicTo(cx - size * 0.05f, cy - size * 0.35f, cx + size * 0.02f, cy - size * 0.45f, cx, cy - size * 0.55f)
            close()
        }
        fill.color = RED
        canvas.drawPath(p, fill)
        fill.color = GOLD
        canvas.drawCircle(cx, cy + size * 0.22f, size * 0.2f, fill)
    }

    private fun drawWattsCard(canvas: Canvas) {
        if (wattsCard.isEmpty) return
        card(canvas, wattsCard)
        val s = status
        val x = wattsCard.left + dp(16f)
        val innerW = wattsCard.width() - dp(32f)
        label(canvas, context.getString(R.string.pokewalker_modern_watts), x, wattsCard.top + dp(26f), 13f, TEXT_DIM, bold)
        val iconSize = dp(30f)
        val rowY = wattsCard.top + dp(60f)
        drawIcon(canvas, R.drawable.ic_pw_bolt, x + iconSize / 2, rowY, iconSize, GOLD)
        label(canvas, numbers.format((s?.watts ?: 0).toLong()), x + iconSize + dp(6f), rowY + sp(28f) * 0.35f, 28f, TEXT, bold, maxWidth = innerW - iconSize - dp(6f))

        val caught = s?.caught ?: 0
        val dowsed = s?.dowsed ?: 0
        val held = context.getString(R.string.pokewalker_modern_held, caught, dowsed)
        text.textSize = sp(13f)
        if (text.measureText(held) <= innerW) {
            // on one line, with the costs under it when they fit
            val costs = context.getString(R.string.pokewalker_modern_costs)
            text.textSize = sp(11f)
            val showCosts = wattsCard.height() >= dp(132f) && text.measureText(costs) <= innerW
            label(canvas, held, x, wattsCard.bottom - if (showCosts) dp(40f) else dp(20f), 13f, TEXT, maxWidth = innerW)
            if (showCosts) label(canvas, costs, x, wattsCard.bottom - dp(18f), 11f, TEXT_FAINT, maxWidth = innerW)
        } else {
            // a narrow card: one per line
            label(canvas, context.getString(R.string.pokewalker_modern_caught, caught), x, wattsCard.bottom - dp(40f), 13f, TEXT, maxWidth = innerW)
            label(canvas, context.getString(R.string.pokewalker_modern_found, dowsed), x, wattsCard.bottom - dp(20f), 13f, TEXT, maxWidth = innerW)
        }
    }

    private fun drawWeekCard(canvas: Canvas) {
        if (weekCard.isEmpty) return
        card(canvas, weekCard)
        val s = status
        label(canvas, context.getString(R.string.pokewalker_modern_week), weekCard.left + dp(16f), weekCard.top + dp(26f), 13f, TEXT_DIM, bold)
        // oldest first: history[6] .. history[0] (yesterday), then today
        val values = LongArray(8)
        val history = s?.history ?: emptyList()
        for (k in 0 until 7) values[k] = history.getOrNull(6 - k) ?: 0L
        values[7] = s?.todaySteps ?: 0L
        val maxValue = max(values.maxOrNull() ?: 0L, goal.toLong()).coerceAtLeast(1L)
        val left = weekCard.left + dp(16f)
        val right = weekCard.right - dp(16f)
        val top = weekCard.top + dp(40f)
        val bottom = weekCard.bottom - dp(26f)
        val slot = (right - left) / 8f
        val barW = min(slot * 0.55f, dp(22f))
        // goal line
        val goalY = bottom - (bottom - top) * (goal.toFloat() / maxValue)
        stroke.color = RED
        stroke.alpha = 160
        stroke.strokeWidth = dp(1f)
        stroke.pathEffect = DashPathEffect(floatArrayOf(dp(4f), dp(4f)), 0f)
        canvas.drawLine(left, goalY, right, goalY, stroke)
        stroke.pathEffect = null
        stroke.alpha = 255
        val today = LocalDays.today()
        for (i in 0 until 8) {
            val cx = left + slot * (i + 0.5f)
            val hBar = ((bottom - top) * (values[i].toFloat() / maxValue)).coerceAtLeast(dp(3f))
            fill.color = if (i == 7) CREAM else BLUE
            fill.alpha = if (i == 7) 255 else 210
            canvas.drawRoundRect(RectF(cx - barW / 2, bottom - hBar, cx + barW / 2, bottom), barW / 2, barW / 2, fill)
            fill.alpha = 255
            val day = today - (7 - i)
            val name = dayNames.format(Date(LocalDays.startOfDay(day) + LocalDays.DAY_MS / 2)).take(2)
            label(canvas, name, cx, weekCard.bottom - dp(8f), 11f, if (i == 7) TEXT else TEXT_DIM, align = Paint.Align.CENTER)
        }
    }

    private fun drawChips(canvas: Canvas) {
        for (i in 0..3) {
            val r = chips[i]
            if (r.isEmpty) continue
            val pressed = pressedChip == i
            val dim = i == ACTION_PLAY && !hasGame
            val corner = min(dp(18f), r.height() / 2f)
            fill.color = if (pressed) BLUE else (CARD and 0x00FFFFFF) or (0xE6 shl 24)
            canvas.drawRoundRect(r, corner, corner, fill)
            stroke.color = CARD_EDGE
            stroke.strokeWidth = dp(1f)
            canvas.drawRoundRect(r, corner, corner, stroke)
            val textColor = if (pressed) INK else if (dim) TEXT_FAINT else TEXT
            val iconColor = if (pressed) INK else if (dim) TEXT_FAINT else if (i == ACTION_SYNC) CREAM else BLUE
            val name = context.getString(ACTION_LABELS[i])
            val iconSize = dp(22f)
            bold.textSize = sp(14f)
            val sideBySide = iconSize + dp(8f) + bold.measureText(name)
            if (r.width() >= r.height() * 1.8f && sideBySide <= r.width() - dp(24f)) {
                // a pill: icon and label side by side
                val startX = r.centerX() - sideBySide / 2f
                drawIcon(canvas, ACTION_ICONS[i], startX + iconSize / 2f, r.centerY(), iconSize, iconColor)
                label(canvas, name, startX + iconSize + dp(8f), r.centerY() + sp(14f) * 0.35f, 14f, textColor, bold)
            } else {
                // a tile: icon over label, the label a little smaller if it has to be
                val size = fitSp(bold, name, r.width() - dp(10f), 12.5f, 9.5f)
                val labelH = sp(size)
                val space = dp(6f)
                val iconCy = r.centerY() - (iconSize + space + labelH) / 2f + iconSize / 2f
                drawIcon(canvas, ACTION_ICONS[i], r.centerX(), iconCy, iconSize, iconColor)
                label(canvas, name, r.centerX(), iconCy + iconSize / 2f + space + labelH * 0.78f, size, textColor, bold, Paint.Align.CENTER, maxWidth = r.width() - dp(8f))
            }
        }
    }

    // ---- touch -----------------------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // taps and swipes on the walker's own screen
        if (gestures.onTouch(event, lcdRect) && event.actionMasked != MotionEvent.ACTION_CANCEL) return true

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                val x = event.getX(index)
                val y = event.getY(index)
                val seg = segments.indexOfFirst { it.contains(x, y) }
                if (seg >= 0) {
                    pointerSegment[event.getPointerId(index)] = seg
                    pressedButton[seg] = true
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onButton(seg)
                    invalidate()
                } else if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    pressedChip = chips.indexOfFirst { !it.isEmpty && it.contains(x, y) }
                    if (pressedChip >= 0) invalidate()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (pressedChip >= 0 && !chips[pressedChip].contains(event.x, event.y)) {
                    pressedChip = -1
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                pointerSegment.remove(event.getPointerId(event.actionIndex))?.let { pressedButton[it] = false; invalidate() }
                if (event.actionMasked == MotionEvent.ACTION_UP && pressedChip >= 0) {
                    val chip = pressedChip
                    pressedChip = -1
                    invalidate()
                    if (chips[chip].contains(event.x, event.y)) {
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        onAction(chip)
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                pointerSegment.clear()
                pressedButton.fill(false)
                pressedChip = -1
                invalidate()
            }
        }
        return true
    }
}

/** Font scale factor (scaledDensity is deprecated on newer Android versions). */
private fun android.util.DisplayMetrics.scaledDensityCompat(): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1f, this)
