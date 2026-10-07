package x.vladgba.sheaflauncher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import x.vladgba.sheaflauncher.model.GridItem
import x.vladgba.sheaflauncher.model.HomeItem
import x.vladgba.sheaflauncher.model.LauncherConfig
import x.vladgba.sheaflauncher.model.WidgetItem
import kotlin.math.roundToInt

/**
 * One home screen page. Children are positioned absolutely from their
 * [HomeItem] (stored in the view's tag): grid items fill their cell, widgets
 * use their free dp rect. Drawing order — and therefore touch order — follows
 * item z, so overlapping items behave consistently.
 *
 * Children without a HomeItem tag (e.g. the resize frame) are overlays: they
 * fill the content area and always sit on top.
 */
@SuppressLint("ViewConstructor")
class PageView(context: Context, private val config: LauncherConfig, val pageX: Int, val pageY: Int) :
    ViewGroup(context) {

    private var order = IntArray(0)
    private val tmp = Rect()
    private val density = resources.displayMetrics.density

    var cellW = 0f; private set
    var cellH = 0f; private set
    var contentW = 0; private set
    var contentH = 0; private set

    init {
        isChildrenDrawingOrderEnabled = true
        clipChildren = false
        clipToPadding = false
    }

    private fun updateMetrics(w: Int, h: Int) {
        contentW = (w - paddingLeft - paddingRight).coerceAtLeast(0)
        contentH = (h - paddingTop - paddingBottom).coerceAtLeast(0)
        cellW = contentW.toFloat() / config.columns
        cellH = contentH.toFloat() / config.rows
    }

    /** Item bounds in this page's coordinates (padding included). */
    fun rectFor(item: HomeItem, out: Rect) {
        when (item) {
            is GridItem -> out.set(
                (item.cellX * cellW).roundToInt(), (item.cellY * cellH).roundToInt(),
                ((item.cellX + 1) * cellW).roundToInt(), ((item.cellY + 1) * cellH).roundToInt()
            )

            is WidgetItem -> out.set(
                (item.x * density).roundToInt(), (item.y * density).roundToInt(),
                ((item.x + item.w) * density).roundToInt(), ((item.y + item.h) * density).roundToInt()
            )
        }
        out.offset(paddingLeft, paddingTop)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        updateMetrics(w, h)
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val item = c.tag as? HomeItem
            if (item != null) {
                rectFor(item, tmp)
                c.measure(exact(tmp.width()), exact(tmp.height()))
            } else {
                c.measure(exact(contentW), exact(contentH))
            }
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        updateMetrics(r - l, b - t)
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val item = c.tag as? HomeItem
            if (item != null) {
                rectFor(item, tmp)
                c.layout(tmp.left, tmp.top, tmp.left + c.measuredWidth, tmp.top + c.measuredHeight)
            } else {
                c.layout(paddingLeft, paddingTop, paddingLeft + contentW, paddingTop + contentH)
            }
        }
    }

    // ------------------------------------------------------------ z-order

    private fun zOf(v: View): Int = (v.tag as? HomeItem)?.z ?: Int.MAX_VALUE

    /** Re-sorts drawing/touch order after any z change. */
    fun updateOrder() {
        order = (0 until childCount).sortedWith(compareBy({ zOf(getChildAt(it)) }, { it })).toIntArray()
        invalidate()
    }

    override fun getChildDrawingOrder(childCount: Int, drawingPosition: Int): Int =
        if (order.size == childCount) order[drawingPosition] else drawingPosition

    override fun onViewAdded(child: View?) { super.onViewAdded(child); updateOrder() }
    override fun onViewRemoved(child: View?) { super.onViewRemoved(child); updateOrder() }

    /** Topmost item whose view contains the point (page coordinates). */
    fun itemAt(x: Float, y: Float): HomeItem? {
        if (order.size != childCount) updateOrder()
        for (i in childCount - 1 downTo 0) {
            val c = getChildAt(order[i])
            val item = c.tag as? HomeItem ?: continue
            if (c.visibility == VISIBLE && x >= c.left && x < c.right && y >= c.top && y < c.bottom) return item
        }
        return null
    }

    private fun exact(s: Int) = MeasureSpec.makeMeasureSpec(s.coerceAtLeast(0), MeasureSpec.EXACTLY)
}
