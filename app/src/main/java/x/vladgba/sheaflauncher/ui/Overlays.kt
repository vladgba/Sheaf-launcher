package x.vladgba.sheaflauncher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Free-form resize/move frame for a widget. Sits on top of a page (fills its
 * content area) and owns every touch while active:
 *  - drag an edge or corner → resize (any size, no grid snapping)
 *  - drag inside the frame  → move
 *  - tap outside the frame  → done
 * Coordinates are relative to the page content area.
 */
@SuppressLint("ViewConstructor")
class ResizeFrame(
    context: Context,
    initial: Rect,
    private val onChange: (Rect) -> Unit,
    private val onDone: () -> Unit,
) : View(context) {

    private val rect = RectF(initial)
    private val touchR = context.dp(26f)
    private val handleR = context.dp(7f)
    private val minSize = context.dp(32f)
    private var mode = 0
    private var lastX = 0f
    private var lastY = 0f
    private val out = Rect()

    private val dim = Paint().apply { color = 0x55000000 }
    private val dimPath = Path()
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = context.dp(2f); color = 0xFFFFFFFF.toInt()
        pathEffect = DashPathEffect(floatArrayOf(context.dp(8f), context.dp(5f)), 0f)
    }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        setShadowLayer(context.dp(3f), 0f, context.dp(1f), 0x80000000.toInt())
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textSize = context.dp(13f); textAlign = Paint.Align.CENTER
        setShadowLayer(context.dp(3f), 0f, context.dp(1f), 0xC0000000.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        dimPath.reset()
        dimPath.fillType = Path.FillType.EVEN_ODD
        dimPath.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        dimPath.addRect(rect, Path.Direction.CW)
        canvas.drawPath(dimPath, dim)
        canvas.drawRect(rect, border)
        val xs = floatArrayOf(rect.left, rect.centerX(), rect.right)
        val ys = floatArrayOf(rect.top, rect.centerY(), rect.bottom)
        for (x in xs) for (y in ys) if (x != rect.centerX() || y != rect.centerY()) canvas.drawCircle(x, y, handleR, handle)
        val d = resources.displayMetrics.density
        val text = "${(rect.width() / d).roundToInt()} × ${(rect.height() / d).roundToInt()} dp"
        val ty = if (rect.top > label.textSize * 2) rect.top - label.textSize * 0.8f else rect.bottom + label.textSize * 1.6f
        canvas.drawText(text, rect.centerX(), ty, label)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = e.x; lastY = e.y
                mode = hit(e.x, e.y)
                if (mode == 0) post(onDone)
            }

            MotionEvent.ACTION_MOVE -> if (mode != 0) {
                val dx = e.x - lastX
                val dy = e.y - lastY
                lastX = e.x; lastY = e.y
                apply(dx, dy)
                invalidate()
                rect.round(out)
                onChange(out)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> mode = 0
        }
        return true
    }

    private fun apply(dx: Float, dy: Float) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (mode == MOVE) {
            val ox = dx.coerceIn(-rect.left, w - rect.right)
            val oy = dy.coerceIn(-rect.top, h - rect.bottom)
            rect.offset(ox, oy)
            return
        }
        if (mode and L != 0) rect.left = (rect.left + dx).coerceIn(0f, rect.right - minSize)
        if (mode and R != 0) rect.right = (rect.right + dx).coerceIn(rect.left + minSize, w)
        if (mode and T != 0) rect.top = (rect.top + dy).coerceIn(0f, rect.bottom - minSize)
        if (mode and B != 0) rect.bottom = (rect.bottom + dy).coerceIn(rect.top + minSize, h)
    }

    private fun hit(x: Float, y: Float): Int {
        val inY = y > rect.top - touchR && y < rect.bottom + touchR
        val inX = x > rect.left - touchR && x < rect.right + touchR
        var m = 0
        val dl = abs(x - rect.left); val dr = abs(x - rect.right)
        val dt = abs(y - rect.top); val db = abs(y - rect.bottom)
        if (inY && (dl < touchR || dr < touchR)) m = m or if (dl <= dr) L else R
        if (inX && (dt < touchR || db < touchR)) m = m or if (dt <= db) T else B
        if (m == 0 && rect.contains(x, y)) m = MOVE
        return m
    }

    private companion object {
        const val L = 1; const val T = 2; const val R = 4; const val B = 8; const val MOVE = 16
    }
}

/** Dot matrix showing the current page within the 2D page grid. */
class PageIndicator(context: Context) : View(context) {
    var pagesX = 1; var pagesY = 1
    var curX = 0; var curY = 0
    var homeX = 0; var homeY = 0
    private val dot = context.dp(6f)
    private val gap = context.dp(5f)
    private val on = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val off = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FFFFFF }
    /** Ring around the default (home) page dot. */
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = context.dp(1.2f)
    }

    fun update(px: Int, py: Int, cx: Int, cy: Int, hx: Int = homeX, hy: Int = homeY) {
        homeX = hx; homeY = hy
        val resize = px != pagesX || py != pagesY
        pagesX = px; pagesY = py; curX = cx; curY = cy
        if (resize) requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            (pagesX * dot + (pagesX - 1) * gap + gap * 2).roundToInt(),
            (pagesY * dot + (pagesY - 1) * gap + gap * 2).roundToInt()
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (pagesX * pagesY <= 1) return
        on.color = Palette.dotOn; ring.color = Palette.dotOn; off.color = Palette.dotOff
        for (y in 0 until pagesY) for (x in 0 until pagesX) {
            val cx = gap + x * (dot + gap) + dot / 2
            val cy = gap + y * (dot + gap) + dot / 2
            canvas.drawCircle(cx, cy, dot / 2, if (x == curX && y == curY) on else off)
            if (x == homeX && y == homeY) canvas.drawCircle(cx, cy, dot / 2 + gap / 2, ring)
        }
    }
}
