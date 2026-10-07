package x.vladgba.sheaflauncher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Square crop selector over a bitmap that is fitted to the view.
 *  - drag inside the square → move it
 *  - drag a corner          → resize (stays square, opposite corner fixed)
 * [cropRect] returns the selection in bitmap pixels.
 */
class CropView(context: Context) : View(context) {

    var bitmap: Bitmap? = null
        set(value) { field = value; reset() }

    private val imgRect = RectF()          // where the bitmap is drawn (view coords)
    private val crop = RectF()             // selection (view coords)
    private val drawMatrix = Matrix()
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val shade = Paint().apply { color = 0x99000000.toInt() }
    private val shadePath = Path()
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = context.dp(2f); color = 0xFFFFFFFF.toInt()
    }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = context.dp(1f); color = 0x66FFFFFF
    }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val handleR = context.dp(8f)
    private val touchR = context.dp(32f)
    private val minSide = context.dp(48f)
    private val pad = context.dp(24f)

    private var mode = NONE
    private var lastX = 0f
    private var lastY = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = reset()

    private fun reset() {
        val b = bitmap
        if (b == null || width == 0 || height == 0) { invalidate(); return }
        val availW = width - pad * 2
        val availH = height - pad * 2
        val s = min(availW / b.width, availH / b.height)
        val w = b.width * s
        val h = b.height * s
        imgRect.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
        drawMatrix.setRectToRect(RectF(0f, 0f, b.width.toFloat(), b.height.toFloat()), imgRect, Matrix.ScaleToFit.FILL)
        // Start with the largest centred square.
        val side = min(w, h) * 0.9f
        crop.set(imgRect.centerX() - side / 2, imgRect.centerY() - side / 2,
            imgRect.centerX() + side / 2, imgRect.centerY() + side / 2)
        invalidate()
    }

    /** Selection in bitmap pixel coordinates. */
    fun cropRect(): Rect? {
        val b = bitmap ?: return null
        val s = b.width / imgRect.width()
        val r = Rect(
            ((crop.left - imgRect.left) * s).roundToInt(), ((crop.top - imgRect.top) * s).roundToInt(),
            ((crop.right - imgRect.left) * s).roundToInt(), ((crop.bottom - imgRect.top) * s).roundToInt())
        if (!r.intersect(0, 0, b.width, b.height)) return null
        return if (r.width() > 0 && r.height() > 0) r else null
    }

    override fun onDraw(canvas: Canvas) {
        val b = bitmap ?: return
        canvas.drawBitmap(b, drawMatrix, bmpPaint)
        shadePath.reset()
        shadePath.fillType = Path.FillType.EVEN_ODD
        shadePath.addRect(imgRect, Path.Direction.CW)
        shadePath.addRect(crop, Path.Direction.CW)
        canvas.drawPath(shadePath, shade)
        // rule-of-thirds grid
        val tw = crop.width() / 3; val th = crop.height() / 3
        for (i in 1..2) {
            canvas.drawLine(crop.left + tw * i, crop.top, crop.left + tw * i, crop.bottom, grid)
            canvas.drawLine(crop.left, crop.top + th * i, crop.right, crop.top + th * i, grid)
        }
        canvas.drawRect(crop, frame)
        for (x in floatArrayOf(crop.left, crop.right)) for (y in floatArrayOf(crop.top, crop.bottom))
            canvas.drawCircle(x, y, handleR, handle)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (bitmap == null) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = e.x; lastY = e.y
                mode = hit(e.x, e.y)
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - lastX
                val dy = e.y - lastY
                lastX = e.x; lastY = e.y
                when (mode) {
                    MOVE -> move(dx, dy)
                    TL, TR, BL, BR -> resize(e.x, e.y)
                }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> mode = NONE
        }
        return true
    }

    private fun hit(x: Float, y: Float): Int {
        fun near(px: Float, py: Float) = abs(x - px) < touchR && abs(y - py) < touchR
        return when {
            near(crop.left, crop.top) -> TL
            near(crop.right, crop.top) -> TR
            near(crop.left, crop.bottom) -> BL
            near(crop.right, crop.bottom) -> BR
            crop.contains(x, y) -> MOVE
            else -> NONE
        }
    }

    private fun move(dx: Float, dy: Float) {
        val ox = dx.coerceIn(imgRect.left - crop.left, imgRect.right - crop.right)
        val oy = dy.coerceIn(imgRect.top - crop.top, imgRect.bottom - crop.bottom)
        crop.offset(ox, oy)
    }

    /** Opposite corner stays fixed; side follows the finger, square, clamped to the image. */
    private fun resize(x: Float, y: Float) {
        val ax = if (mode == TL || mode == BL) crop.right else crop.left   // anchor
        val ay = if (mode == TL || mode == TR) crop.bottom else crop.top
        val dirX = if (mode == TL || mode == BL) -1 else 1
        val dirY = if (mode == TL || mode == TR) -1 else 1
        val maxX = if (dirX < 0) ax - imgRect.left else imgRect.right - ax
        val maxY = if (dirY < 0) ay - imgRect.top else imgRect.bottom - ay
        val want = max(abs(x - ax), abs(y - ay))
        val side = want.coerceIn(min(minSide, min(maxX, maxY)), min(maxX, maxY))
        val l = if (dirX < 0) ax - side else ax
        val t = if (dirY < 0) ay - side else ay
        crop.set(l, t, l + side, t + side)
    }

    private companion object {
        const val NONE = 0; const val MOVE = 1; const val TL = 2; const val TR = 3; const val BL = 4; const val BR = 5
    }
}
