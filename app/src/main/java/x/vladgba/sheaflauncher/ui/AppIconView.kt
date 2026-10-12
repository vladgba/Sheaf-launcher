package x.vladgba.sheaflauncher.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import java.util.WeakHashMap
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
    /** "system" (the icon as the app ships it), "square" or "rounded" (tile look). */
    var shape = "system"
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
    private val platePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val clipRect = RectF()
    private val clipPath = android.graphics.Path()

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
            val r = cornerRadius(size) ?: (size * 0.28f)
            canvas.drawRoundRect(folderRect, r, r, folderPaint)
            val pad = size * 0.1f
            val cell = (size - pad * 3) / 2f
            previews.take(4).forEachIndexed { i, d ->
                val l = left + pad + (i % 2) * (cell + pad)
                val t = top + pad + (i / 2) * (cell + pad)
                drawIcon(canvas, d, l, t, cell)
            }
        } else {
            icon?.let { drawIcon(canvas, it, left, top, size) }
        }

        if (showLabel && label.isNotEmpty()) {
            val text = TextUtils.ellipsize(label, textPaint, width - context.dp(4f), TextUtils.TruncateAt.END)
            canvas.drawText(text, 0, text.length, width / 2f, top + size + textPaint.textSize * 1.25f, textPaint)
        }
    }

    /** Corner radius of the tile shape, or null for the icon's own shape. */
    private fun cornerRadius(size: Float): Float? = when (shape) {
        "square" -> 0f
        "rounded" -> size * 0.18f
        else -> null
    }

    /**
     * Draws [d] in the square ([l], [t], [size]) using the current [shape]:
     *  - adaptive icons: background + foreground layers, full-bleed, clipped to the tile
     *    (the system mask is skipped);
     *  - full-bleed bitmaps (opaque corners, e.g. cropped custom icons): fill the tile;
     *  - other icons: centred on a plate tinted from the icon's colour.
     */
    private fun drawIcon(canvas: Canvas, d: Drawable, l: Float, t: Float, size: Float) {
        val r = cornerRadius(size)
        if (r == null) {
            d.setBounds(l.roundToInt(), t.roundToInt(), (l + size).roundToInt(), (t + size).roundToInt())
            d.draw(canvas)
            return
        }
        clipRect.set(l, t, l + size, t + size)
        val save = canvas.save()
        if (r > 0f) {
            clipPath.reset()
            clipPath.addRoundRect(clipRect, r, r, android.graphics.Path.Direction.CW)
            canvas.clipPath(clipPath)
        } else {
            canvas.clipRect(clipRect)
        }
        if (d is AdaptiveIconDrawable) {
            // Layers are 108dp for a 72dp visible icon: extend by 1/4 of the size on each side.
            val e = size * 0.25f
            val bl = (l - e).roundToInt(); val bt = (t - e).roundToInt()
            val br = (l + size + e).roundToInt(); val bb = (t + size + e).roundToInt()
            d.background?.let { it.setBounds(bl, bt, br, bb); it.draw(canvas) }
                ?: canvas.drawColor(Color.WHITE)
            d.foreground?.let { it.setBounds(bl, bt, br, bb); it.draw(canvas) }
        } else {
            val info = tileInfo(d)
            if (info.fullBleed) {
                canvas.drawBitmap(info.bitmap, null, clipRect, bitmapPaint)
            } else {
                platePaint.color = info.color
                canvas.drawRect(clipRect, platePaint)
                val inset = size * 0.16f
                clipRect.inset(inset, inset)
                canvas.drawBitmap(info.bitmap, null, clipRect, bitmapPaint)
            }
        }
        canvas.restoreToCount(save)
    }

    private class TileInfo(val bitmap: Bitmap, val fullBleed: Boolean, val color: Int)

    private companion object {
        /** Per-drawable analysis, computed once (icons are shared between views). */
        val tileCache = WeakHashMap<Drawable, TileInfo>()
        const val TILE_PX = 192

        fun tileInfo(d: Drawable): TileInfo = tileCache.getOrPut(d) {
            val bmp = (d as? BitmapDrawable)?.bitmap?.takeIf { it.width > 0 && it.height > 0 }
                // Hardware bitmaps can't be read pixel by pixel.
                ?.let { if (it.config == Bitmap.Config.HARDWARE) it.copy(Bitmap.Config.ARGB_8888, false) else it }
                ?: run {
                val b = Bitmap.createBitmap(TILE_PX, TILE_PX, Bitmap.Config.ARGB_8888)
                val c = Canvas(b)
                val old = d.copyBounds()
                d.setBounds(0, 0, TILE_PX, TILE_PX)
                d.draw(c)
                d.bounds = old
                b
            }
            TileInfo(bmp, opaqueCorners(bmp), averageColor(bmp))
        }

        private fun opaqueCorners(b: Bitmap): Boolean {
            val w = b.width - 1; val h = b.height - 1
            val inset = (min(w, h) * 0.03f).toInt() // ignore anti-aliased edge pixels
            return listOf(inset to inset, w - inset to inset, inset to h - inset, w - inset to h - inset)
                .all { (x, y) -> Color.alpha(b.getPixel(x, y)) > 200 }
        }

        /** Plate colour for a non-full-bleed icon, derived from the average of its visible pixels. */
        private fun averageColor(b: Bitmap): Int {
            val step = (min(b.width, b.height) / 24).coerceAtLeast(1)
            var r = 0L; var g = 0L; var bl = 0L; var n = 0L
            var y = 0
            while (y < b.height) {
                var x = 0
                while (x < b.width) {
                    val p = b.getPixel(x, y)
                    if (Color.alpha(p) > 128) { r += Color.red(p); g += Color.green(p); bl += Color.blue(p); n++ }
                    x += step
                }
                y += step
            }
            if (n == 0L) return 0xFF607D8B.toInt()
            val ar = (r / n).toInt(); val ag = (g / n).toInt(); val ab = (bl / n).toInt()
            // Tint of the icon's colour that contrasts with it: dark icons get a light plate,
            // light icons a dark one, so the glyph never disappears into its background.
            val lum = (0.299f * ar + 0.587f * ag + 0.114f * ab) / 255f
            val (target, amount) = if (lum < 0.5f) 255 to 0.82f else 0 to 0.72f
            fun mix(c: Int) = (c + (target - c) * amount).toInt()
            return Color.rgb(mix(ar), mix(ag), mix(ab))
        }
    }
}
