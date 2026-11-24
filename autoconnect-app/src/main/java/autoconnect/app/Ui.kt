package autoconnect.app

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.min

/**
 * The app's single visual system (placeholder UI pass, 2026-11-21):
 * named color tokens, an 8-pt spacing rhythm, and small builders for the
 * surfaces every screen shares — screen root, titles, section headers,
 * cards, card buttons and the round power button. All colors honor the
 * existing light/dark/system theme preference (the same decision
 * [BaseActivity] makes when picking the window theme), so screens stay
 * readable in both modes. Semantic state colors live here exactly once;
 * text always duplicates color meaning (existing accessibility rule).
 */
object Ui {

    // ---- spacing rhythm (dp) ----
    const val SCREEN_PADDING = 20
    const val SPACE_XS = 4
    const val SPACE_S = 8
    const val SPACE_M = 16
    const val SPACE_L = 24
    const val SPACE_XL = 32

    // ---- type scale (sp) ----
    const val TEXT_TITLE = 22f
    const val TEXT_SECTION = 16f
    const val TEXT_BODY = 15f
    const val TEXT_DETAIL = 13f
    const val TEXT_SMALL = 12f
    const val TEXT_STATUS = 18f

    class Palette(
        val bg: Int,
        val card: Int,
        val cardStroke: Int,
        val textPrimary: Int,
        val textSecondary: Int,
        val green: Int,
        val red: Int,
        val amber: Int,
        val accent: Int,
        val gold: Int,
        val powerIdle: Int,
        val powerBusy: Int,
        val powerConnected: Int,
        val onPower: Int,
    )

    // Reference-design restyle (2026-11-24, Farzad's chosen UI): a
    // near-black green-tinted theme, mint accent, gold highlights and
    // a glowing power button. The dark palette is the reference
    // itself; the light palette adapts the same language.
    private val LIGHT = Palette(
        bg = 0xFFF0F5F1.toInt(),
        card = 0xFFFFFFFF.toInt(),
        cardStroke = 0xFFD9E4DC.toInt(),
        textPrimary = 0xFF0E1A13.toInt(),
        textSecondary = 0xFF5B7267.toInt(),
        green = 0xFF0E9F6E.toInt(),
        red = 0xFFDC2626.toInt(),
        amber = 0xFFB45309.toInt(),
        accent = 0xFF0E9F6E.toInt(),
        gold = 0xFFB45309.toInt(),
        powerIdle = 0xFF14301F.toInt(),
        powerBusy = 0xFF4A3208.toInt(),
        powerConnected = 0xFF0C2318.toInt(),
        onPower = 0xFFE9F2ED.toInt(),
    )

    private val DARK = Palette(
        bg = 0xFF070B09.toInt(),
        card = 0xFF0F1512.toInt(),
        cardStroke = 0xFF1E2B23.toInt(),
        textPrimary = 0xFFE9F2ED.toInt(),
        textSecondary = 0xFF8CA398.toInt(),
        green = 0xFF2EE6A8.toInt(),
        red = 0xFFF87171.toInt(),
        amber = 0xFFF2C14E.toInt(),
        accent = 0xFF2EE6A8.toInt(),
        gold = 0xFFF2C14E.toInt(),
        powerIdle = 0xFF131B16.toInt(),
        powerBusy = 0xFF171208.toInt(),
        powerConnected = 0xFF0A140F.toInt(),
        onPower = 0xFFE9F2ED.toInt(),
    )

    /** Mirrors [BaseActivity]'s theme decision so tokens match the window. */
    fun isDark(context: Context): Boolean =
        when (Prefs(context).theme) {
            "dark" -> true
            "light" -> false
            else -> {
                val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                night == Configuration.UI_MODE_NIGHT_YES
            }
        }

    fun palette(context: Context): Palette = if (isDark(context)) DARK else LIGHT

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    // ---- shared surfaces ----

    /** Full-screen vertical root: screen background + uniform padding. */
    fun screenRoot(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val p = dp(context, SCREEN_PADDING)
        setPadding(p, p, p, p)
        setBackgroundColor(palette(context).bg)
    }

    fun screenTitle(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = TEXT_TITLE
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(palette(context).textPrimary)
    }

    fun sectionHeader(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = TEXT_SECTION
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(palette(context).textPrimary)
    }

    /** Rounded-rectangle card surface (vertical container, 16dp padding). */
    fun card(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val p = dp(context, SPACE_M)
        setPadding(p, p, p, p)
        background = cardBackground(context)
    }

    fun cardBackground(context: Context): GradientDrawable {
        val pal = palette(context)
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(context, 12).toFloat()
            setColor(pal.card)
            setStroke(dp(context, 1), pal.cardStroke)
        }
    }

    /** Full-width tappable row styled as a card; label start-aligned. */
    fun cardButton(context: Context, label: String, action: () -> Unit): Button = Button(context).apply {
        text = label
        textSize = TEXT_BODY
        isAllCaps = false
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        val hp = dp(context, SPACE_M)
        setPadding(hp, dp(context, 14), hp, dp(context, 14))
        setTextColor(palette(context).textPrimary)
        background = cardBackground(context)
        setOnClickListener { action() }
    }

    /** Solid circle used as the power button's surface. */
    fun circleBackground(context: Context, fill: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fill)
            setStroke(dp(context, 3), Color.argb(38, 255, 255, 255))
        }

    /** Power-button surface in the reference design: dark fill with a
     *  colored rim that carries the phase (mint = connected). */
    fun powerBackground(context: Context, fill: Int, stroke: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fill)
            setStroke(dp(context, 2), stroke)
        }

    /** Full-width mint pill — the reference design's primary action. */
    fun pillButton(context: Context, label: String, action: () -> Unit): Button = Button(context).apply {
        text = label
        textSize = TEXT_BODY
        typeface = Typeface.DEFAULT_BOLD
        isAllCaps = false
        gravity = Gravity.CENTER
        setPadding(dp(context, SPACE_M), dp(context, 15), dp(context, SPACE_M), dp(context, 15))
        setTextColor(0xFF06231A.toInt())
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(context, 28).toFloat()
            setColor(palette(context).accent)
        }
        setOnClickListener { action() }
    }
}

/**
 * The reference design's signature element: a soft radial glow with
 * two concentric rings behind the power button. [glowLevel] runs
 * 0 (invisible) to 1 (full); the activity pulses it while connecting
 * and holds it at full while connected.
 */
class GlowRingView(context: Context) : View(context) {

    var glowColor: Int = Color.GREEN

    var glowLevel: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (glowLevel <= 0.01f) return
        val cx = width / 2f
        val cy = height / 2f
        val maxR = min(width, height) / 2f
        if (maxR <= 0f) return
        val r = Color.red(glowColor)
        val g = Color.green(glowColor)
        val b = Color.blue(glowColor)

        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                cx, cy, maxR,
                Color.argb((115 * glowLevel).toInt(), r, g, b),
                Color.argb(0, r, g, b),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawCircle(cx, cy, maxR, glowPaint)

        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * resources.displayMetrics.density
        }
        ringPaint.color = Color.argb((70 * glowLevel).toInt(), r, g, b)
        canvas.drawCircle(cx, cy, maxR * 0.80f, ringPaint)
        ringPaint.color = Color.argb((38 * glowLevel).toInt(), r, g, b)
        canvas.drawCircle(cx, cy, maxR * 0.94f, ringPaint)
    }
}

/**
 * Power-symbol glyph drawn with canvas primitives (arc with a gap at the
 * top + a vertical stem) — framework-only, so it renders identically on
 * every device/font, unlike a unicode glyph.
 */
class PowerGlyphView(context: Context) : View(context) {

    var glyphColor: Int = Color.WHITE

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = min(width, height).toFloat()
        if (size <= 0f) return
        val cx = width / 2f
        val cy = height / 2f + size * 0.05f
        val r = size * 0.33f
        paint.color = glyphColor
        paint.strokeWidth = size * 0.075f
        // 300° arc starting at -60° leaves a 60° gap centered at the top.
        canvas.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), -60f, 300f, false, paint)
        canvas.drawLine(cx, cy - r * 1.28f, cx, cy - r * 0.12f, paint)
    }
}
