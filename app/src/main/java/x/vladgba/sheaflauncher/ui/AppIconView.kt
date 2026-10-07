package x.vladgba.sheaflauncher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import kotlin.math.min
import kotlin.math.roundToInt

fun Context.dp(v: Float): Float = v * resources.displayMetrics.density
fun Context.dpi(v: Float): Int = dp(v).roundToInt()

/** Icon + label, drawn directly (no nested views). Also renders folders as a 2×2 preview. */
class AppIconView(context: Context) : View(context) {

    var icon: Drawable? = null
        set(value) { field = value; invalidate() }
    var label: String = ""
        set(value) { field = value; contentDescription = value; invalidate() }
    /** Non-null → draw as folder with these previews. */
    var folderIcons: List<Drawable>? = null
        set(value) { field = value; invalidate() }
    var showLabel = true
        set(value) { field = value; invalidate() }
    var iconScale = 1f
        set(value) { field = value; invalidate() }
    var labelColor: Int
        get() = textPaint.color
        set(value) { textPaint.color = value; invalidate() }
    var labelShadow = true
        set(value) {
            field = value
            applyShadow()
            invalidate()
        }
    var shadowColor = 0xB0000000.toInt()
        set(value) { field = value; applyShadow(); invalidate() }

    private fun applyShadow() {
        if (labelShadow) textPaint.setShadowLayer(context.dp(3f), 0f, context.dp(1f), shadowColor)
        else textPaint.clearShadowLayer()
    }

    /** Home-screen look for the current day/night mode. */
    fun applyHomePalette() {
        textPaint.color = Palette.label
        folderPaint.color = Palette.folderBg
        shadowColor = Palette.labelShadow
        labelShadow = true
    }

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = context.dp(12f)
        textAlign = Paint.Align.CENTER
    }
    private val folderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FFFFFF }
    private val folderRect = RectF()

    init {
        isClickable = true
        isFocusable = true
        labelShadow = true
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        animate().scaleX(if (pressed) 0.9f else 1f).scaleY(if (pressed) 0.9f else 1f)
            .setDuration(100).start()
    }

    override fun onDraw(canvas: Canvas) {
        val labelH = if (showLabel) textPaint.textSize * 1.5f else 0f
        val avail = min(width * 0.78f, (height - labelH) * 0.82f)
        val size = min(avail * iconScale, min(width.toFloat(), height - labelH))
        if (size <= 0) return
        val left = (width - size) / 2f
        val top = (height - labelH - size) / 2f

        val previews = folderIcons
        if (previews != null) {
            folderRect.set(left, top, left + size, top + size)
            val r = size * 0.28f
            canvas.drawRoundRect(folderRect, r, r, folderPaint)
            val pad = size * 0.1f
            val cell = (size - pad * 3) / 2f
            previews.take(4).forEachIndexed { i, d ->
                val l = left + pad + (i % 2) * (cell + pad)
                val t = top + pad + (i / 2) * (cell + pad)
                d.setBounds(l.roundToInt(), t.roundToInt(), (l + cell).roundToInt(), (t + cell).roundToInt())
                d.draw(canvas)
            }
        } else {
            icon?.let {
                it.setBounds(left.roundToInt(), top.roundToInt(), (left + size).roundToInt(), (top + size).roundToInt())
                it.draw(canvas)
            }
        }

        if (showLabel && label.isNotEmpty()) {
            val text = TextUtils.ellipsize(label, textPaint, width - context.dp(4f), TextUtils.TruncateAt.END)
            canvas.drawText(text, 0, text.length, width / 2f, top + size + textPaint.textSize * 1.25f, textPaint)
        }
    }
}
