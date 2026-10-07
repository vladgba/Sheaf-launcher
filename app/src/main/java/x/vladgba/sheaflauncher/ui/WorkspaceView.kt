package x.vladgba.sheaflauncher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.OverScroller
import x.vladgba.sheaflauncher.model.AppItem
import x.vladgba.sheaflauncher.model.FolderItem
import x.vladgba.sheaflauncher.model.GridItem
import x.vladgba.sheaflauncher.model.HomeItem
import x.vladgba.sheaflauncher.model.WidgetItem
import x.vladgba.sheaflauncher.model.WorkspaceModel
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The home screen: a 2D matrix of [PageView]s.
 *
 *  - Swipe left/right → previous/next page in the row.
 *  - Swipe up/down    → page above/below.
 *    A swipe that starts over a widget which can still scroll in that
 *    direction (lists, etc.) is left to the widget.
 *  - Long-press an item and drag → move it (hold at a screen edge to flip pages).
 *  - Long-press an item and release → item menu.
 *  - Swipe on an app icon in a direction that has an action → run it
 *    (directions without an action still change page).
 *  - Long-press empty space → workspace menu.
 *
 * While dragging, the item's view is re-parented into [dragLayer] so it can
 * travel across pages.
 */
@SuppressLint("ViewConstructor")
class WorkspaceView(context: Context, private val dragLayer: FrameLayout) : ViewGroup(context) {

    interface Callbacks {
        fun createItemView(item: HomeItem): View
        fun bindItemView(item: HomeItem, view: View)
        fun onItemLongPressReleased(item: HomeItem, view: View)
        fun onEmptyLongPress(x: Float, y: Float)
        /** Item was dropped at a new place. [intoFolder] is set when an app was dropped onto a folder. */
        fun onItemDropped(item: HomeItem, intoFolder: FolderItem?)
        fun onPageChanged(px: Int, py: Int)
        /** Does this app have a swipe action for [dir] (up/right/down/left)? */
        fun hasIconGesture(item: AppItem, dir: String): Boolean
        /** Layout locked → long-press opens the menu right away instead of starting a drag. */
        fun isLayoutLocked(): Boolean
        fun onIconGesture(item: AppItem, dir: String, view: View)
        /**
         * Drop of an item dragged in from outside the workspace (folder panel,
         * widget picker). The item is not in the model; position fields are set.
         * [cancelled] = dropped nowhere / drag aborted.
         */
        fun onExternalDrop(item: HomeItem, intoFolder: FolderItem?, cancelled: Boolean)
    }

    lateinit var model: WorkspaceModel
    lateinit var callbacks: Callbacks

    private val pages = HashMap<Int, PageView>()
    val itemViews = HashMap<Long, View>()

    var curX = 0; private set
    var curY = 0; private set

    /** Content insets (status/nav bars + indicator) applied as page padding. */
    private val insets = Rect()

    /** Set while a modal overlay (resize frame) owns the touches. */
    var locked = false

    // ---------------------------------------------------------------- touch state
    private enum class Mode { IDLE, SWIPE_H, SWIPE_V, DRAG, BLOCKED, ICON_SWIPE, MENU }

    private var mode = Mode.IDLE
    private val vc = ViewConfiguration.get(context)
    private val touchSlop = vc.scaledTouchSlop
    private val flingVelocity = vc.scaledMinimumFlingVelocity * 4
    private val scroller = OverScroller(context, DecelerateInterpolator(1.6f))
    private var velocity: VelocityTracker? = null
    private var downX = 0f
    private var downY = 0f
    private var downTime = -1L
    private var startScrollX = 0
    private var startScrollY = 0
    private var pressedItem: HomeItem? = null
    private val longPress = Runnable { onLongPress() }

    // drag state
    private var dragItem: HomeItem? = null
    private var dragView: View? = null
    private var dragOffX = 0f
    private var dragOffY = 0f
    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var dragMoved = false
    private val dragLayerLoc = IntArray(2)
    private var edgeDir = 0          // 1=left 2=right 3=up 4=down
    private val edgeFlip = Runnable { flipFromEdge() }
    private var pendingSync = false
    private var cancelling = false
    /** Current drag came from outside (folder panel / widget picker). */
    private var external = false

    // icon swipe state
    private var swipeDir = ""
    private var swipeView: View? = null
    private val gestureDistance = context.dp(36f)
    private val gestureNudge = context.dp(14f)

    val isDragging get() = mode == Mode.DRAG

    // ================================================================= model binding

    fun pageAt(px: Int, py: Int): PageView? = pages[key(px, py)]
    val currentPage: PageView? get() = pageAt(curX, curY)

    /** Brings views in line with the model (creates / reuses / removes views, rebuilds pages). */
    fun sync() {
        if (mode == Mode.DRAG) { pendingSync = true; return }
        val c = model.config

        // Pages: recreate when matrix or grid size changed.
        val wanted = HashSet<Int>()
        for (py in 0 until c.pagesY) for (px in 0 until c.pagesX) wanted += key(px, py)
        val stale = pages.keys.filter { it !in wanted }
        for (k in stale) {
            val p = pages.remove(k)!!
            p.removeAllViews()
            removeView(p)
        }
        for (k in wanted) if (k !in pages) {
            val p = PageView(context, c, k % 1000, k / 1000)
            p.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            pages[k] = p
            addView(p)
        }

        // Items
        val ids = model.items.mapTo(HashSet()) { it.id }
        val gone = itemViews.keys.filter { it !in ids }
        for (id in gone) itemViews.remove(id)?.let { (it.parent as? ViewGroup)?.removeView(it) }
        for (item in model.items) {
            val v = itemViews.getOrPut(item.id) { callbacks.createItemView(item) }
            v.tag = item
            callbacks.bindItemView(item, v)
            val page = pageAt(item.pageX, item.pageY) ?: continue
            if (v.parent !== page) {
                (v.parent as? ViewGroup)?.removeView(v)
                page.addView(v)
            }
        }
        for (p in pages.values) { p.updateOrder(); p.requestLayout() }

        if (curX >= c.pagesX || curY >= c.pagesY) {
            snapTo(curX.coerceAtMost(c.pagesX - 1), curY.coerceAtMost(c.pagesY - 1), animate = false)
        } else {
            callbacks.onPageChanged(curX, curY)
        }
    }

    /** Re-applies z-order of one page after bring-to-front / send-to-back. */
    fun refreshOrder(item: HomeItem) {
        pageAt(item.pageX, item.pageY)?.updateOrder()
    }

    fun setContentInsets(l: Int, t: Int, r: Int, b: Int) {
        insets.set(l, t, r, b)
        for (p in pages.values) p.setPadding(l, t, r, b)
    }

    /** Page content area in dp — used for placing apps around widgets. */
    fun contentSizeDp(): Pair<Float, Float> {
        val dm = resources.displayMetrics
        val w = if (width > 0) width else dm.widthPixels
        val h = if (height > 0) height else dm.heightPixels
        return Pair(
            (w - insets.left - insets.right) / dm.density,
            (h - insets.top - insets.bottom) / dm.density
        )
    }

    // ================================================================= layout & scrolling

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val ws = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY)
        val hs = MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
        for (p in pages.values) p.measure(ws, hs)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val h = b - t
        for (p in pages.values) p.layout(p.pageX * w, p.pageY * h, (p.pageX + 1) * w, (p.pageY + 1) * h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        scroller.forceFinished(true)
        scrollTo(curX * w, curY * h)
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTo(scroller.currX, scroller.currY)
            postInvalidateOnAnimation()
        }
    }

    /** True when the given page is fully shown and not moving. */
    fun isSettledOn(px: Int, py: Int): Boolean =
        curX == px && curY == py && scroller.isFinished && mode != Mode.DRAG &&
                scrollX == px * width && scrollY == py * height

    fun snapTo(px: Int, py: Int, animate: Boolean = true) {
        val c = model.config
        curX = px.coerceIn(0, c.pagesX - 1)
        curY = py.coerceIn(0, c.pagesY - 1)
        val tx = curX * width
        val ty = curY * height
        scroller.forceFinished(true)
        if (animate && width > 0) {
            val dist = hypot((tx - scrollX).toFloat(), (ty - scrollY).toFloat())
            val dur = (180 + 220 * (dist / width.coerceAtLeast(1))).toInt().coerceAtMost(420)
            scroller.startScroll(scrollX, scrollY, tx - scrollX, ty - scrollY, dur)
            postInvalidateOnAnimation()
        } else {
            scrollTo(tx, ty)
        }
        callbacks.onPageChanged(curX, curY)
    }

    private fun finishScrollNow() {
        if (!scroller.isFinished) {
            scroller.abortAnimation()
            scrollTo(curX * width, curY * height)
        }
    }

    // ================================================================= touch handling

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE ->
                if (mode == Mode.IDLE && hypot(ev.x - downX, ev.y - downY) > touchSlop) removeCallbacks(longPress)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (mode != Mode.DRAG) removeCallbacks(longPress)
        }
        return super.dispatchTouchEvent(ev)
    }

    /** A child (scrolling widget) claimed the gesture → no long-press drag. */
    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        if (disallowIntercept && mode == Mode.IDLE) removeCallbacks(longPress)
        super.requestDisallowInterceptTouchEvent(disallowIntercept)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (locked) return false
        track(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> handleDown(ev)
            MotionEvent.ACTION_MOVE -> {
                if (mode == Mode.DRAG) { moveDrag(ev.rawX, ev.rawY); return true }
                return checkStartSwipe(ev)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (mode == Mode.DRAG) { drop(); return true }
                if (mode == Mode.MENU) { endGesture(); return true } // cancels the icon's click
                endGesture()
            }
            MotionEvent.ACTION_POINTER_DOWN -> removeCallbacks(longPress)
        }
        return mode == Mode.SWIPE_H || mode == Mode.SWIPE_V || mode == Mode.DRAG ||
                mode == Mode.ICON_SWIPE || mode == Mode.MENU
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (locked) return false
        track(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> if (ev.downTime != downTime) handleDown(ev)
            MotionEvent.ACTION_MOVE -> when (mode) {
                Mode.IDLE -> checkStartSwipe(ev)
                Mode.SWIPE_H -> scrollTo(resist(startScrollX - (ev.x - downX), (model.config.pagesX - 1) * width), scrollY)
                Mode.SWIPE_V -> scrollTo(scrollX, resist(startScrollY - (ev.y - downY), (model.config.pagesY - 1) * height))
                Mode.DRAG -> moveDrag(ev.rawX, ev.rawY)
                Mode.ICON_SWIPE -> nudgeIcon(ev)
                Mode.BLOCKED, Mode.MENU -> {}
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> when (mode) {
                Mode.SWIPE_H, Mode.SWIPE_V -> { settle(); endGesture() }
                Mode.ICON_SWIPE -> finishIconSwipe(ev, ev.actionMasked == MotionEvent.ACTION_UP)
                Mode.DRAG -> drop()
                else -> endGesture()
            }
            MotionEvent.ACTION_POINTER_DOWN -> removeCallbacks(longPress)
        }
        return true
    }

    private fun handleDown(ev: MotionEvent) {
        downTime = ev.downTime
        finishScrollNow()
        downX = ev.x; downY = ev.y
        mode = Mode.IDLE
        val page = currentPage
        pressedItem = page?.itemAt(ev.x + scrollX - page.left, ev.y + scrollY - page.top)
        removeCallbacks(longPress)
        postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
    }

    /** Starts a page swipe once the finger passes touch slop, unless a widget wants to scroll. */
    private fun checkStartSwipe(ev: MotionEvent): Boolean {
        if (mode != Mode.IDLE) return mode == Mode.SWIPE_H || mode == Mode.SWIPE_V ||
                mode == Mode.ICON_SWIPE || mode == Mode.MENU
        val dx = ev.x - downX
        val dy = ev.y - downY
        if (hypot(dx, dy) < touchSlop) return false
        removeCallbacks(longPress)
        val horizontal = abs(dx) > abs(dy)
        val delta = if (horizontal) dx else dy

        // Swipe on an app icon with an action for this direction → icon gesture, not a page swipe.
        val pi = pressedItem
        if (pi is AppItem) {
            val dir = if (horizontal) (if (dx > 0) "right" else "left") else (if (dy < 0) "up" else "down")
            if (callbacks.hasIconGesture(pi, dir)) {
                mode = Mode.ICON_SWIPE
                swipeDir = dir
                swipeView = itemViews[pi.id]
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
        }
        val page = currentPage
        if (page != null && childCanScroll(page, horizontal, delta,
                (downX + scrollX - page.left).toInt(), (downY + scrollY - page.top).toInt())
        ) {
            mode = Mode.BLOCKED
            return false
        }
        mode = if (horizontal) Mode.SWIPE_H else Mode.SWIPE_V
        downX = ev.x; downY = ev.y
        startScrollX = scrollX; startScrollY = scrollY
        parent?.requestDisallowInterceptTouchEvent(true)
        return true
    }

    private fun childCanScroll(v: View, horizontal: Boolean, delta: Float, x: Int, y: Int): Boolean {
        if (v is ViewGroup) {
            for (i in v.childCount - 1 downTo 0) {
                val c = v.getChildAt(i)
                if (c.visibility != VISIBLE) continue
                val cx = x + v.scrollX - c.left
                val cy = y + v.scrollY - c.top
                if (cx >= 0 && cy >= 0 && cx < c.width && cy < c.height &&
                    childCanScroll(c, horizontal, delta, cx, cy)
                ) return true
            }
        }
        if (v is PageView) return false
        val dir = if (delta > 0) -1 else 1
        return if (horizontal) v.canScrollHorizontally(dir) else v.canScrollVertically(dir)
    }

    /** Rubber-band past the first/last page. */
    private fun resist(pos: Float, max: Int): Int = when {
        pos < 0 -> (pos / 3).roundToInt()
        pos > max -> (max + (pos - max) / 3).roundToInt()
        else -> pos.roundToInt()
    }

    private fun settle() {
        val vt = velocity ?: return
        vt.computeCurrentVelocity(1000)
        if (mode == Mode.SWIPE_H) {
            val offset = scrollX - curX * width
            var t = curX
            if (vt.xVelocity < -flingVelocity || (offset > width / 3 && vt.xVelocity <= flingVelocity)) t++
            else if (vt.xVelocity > flingVelocity || (offset < -width / 3 && vt.xVelocity >= -flingVelocity)) t--
            snapTo(t, curY)
        } else {
            val offset = scrollY - curY * height
            var t = curY
            if (vt.yVelocity < -flingVelocity || (offset > height / 4 && vt.yVelocity <= flingVelocity)) t++
            else if (vt.yVelocity > flingVelocity || (offset < -height / 4 && vt.yVelocity >= -flingVelocity)) t--
            snapTo(curX, t)
        }
    }

    private fun endGesture() {
        removeCallbacks(longPress)
        mode = Mode.IDLE
        pressedItem = null
        velocity?.recycle(); velocity = null
    }

    private fun track(ev: MotionEvent) {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) { velocity?.recycle(); velocity = null }
        val vt = velocity ?: VelocityTracker.obtain().also { velocity = it }
        vt.addMovement(ev)
    }

    private fun onLongPress() {
        if (mode != Mode.IDLE || locked) return
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        val item = pressedItem
        if (item == null) {
            mode = Mode.BLOCKED
            callbacks.onEmptyLongPress(downX, downY)
        } else if (callbacks.isLayoutLocked()) {
            // MENU: swallow the rest of this touch so the icon doesn't also get a click.
            mode = Mode.MENU
            itemViews[item.id]?.let { v -> v.isPressed = false; callbacks.onItemLongPressReleased(item, v) }
        } else {
            startDrag(item)
        }
    }

    // ================================================================= drag & drop

    private fun startDrag(item: HomeItem) {
        val v = itemViews[item.id] ?: return
        val page = v.parent as? PageView ?: return
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        dragLayer.getLocationOnScreen(dragLayerLoc)
        val w = v.width
        val h = v.height

        // Clear the pressed state first so its shrink-back animation doesn't fight the lift below.
        v.isPressed = false
        v.animate().cancel()
        v.scaleX = 1f; v.scaleY = 1f
        // Removing the view cancels its touch; the next event lands in our intercept.
        page.removeView(v)
        dragLayer.addView(v, FrameLayout.LayoutParams(w, h))
        // Lay it out in the drag layer right now. Otherwise the view keeps its old
        // page-relative left/top until the next layout pass, the x/y below are
        // computed against those stale values, and the icon flashes in the
        // top-left corner for a frame (or until the finger moves).
        v.layout(0, 0, w, h)
        v.translationX = (loc[0] - dragLayerLoc[0]).toFloat()
        v.translationY = (loc[1] - dragLayerLoc[1]).toFloat()
        v.animate().scaleX(1.06f).scaleY(1.06f).alpha(0.85f).setDuration(120).start()

        val screenDownX = downX + locOnScreenX()
        val screenDownY = downY + locOnScreenY()
        dragOffX = screenDownX - loc[0]
        dragOffY = screenDownY - loc[1]
        dragStartRawX = screenDownX
        dragStartRawY = screenDownY
        dragItem = item
        dragView = v
        dragMoved = false
        mode = Mode.DRAG
        parent?.requestDisallowInterceptTouchEvent(true)
    }

    private fun locOnScreenX(): Int { val l = IntArray(2); getLocationOnScreen(l); return l[0] }
    private fun locOnScreenY(): Int { val l = IntArray(2); getLocationOnScreen(l); return l[1] }

    private fun moveDrag(rawX: Float, rawY: Float) {
        val v = dragView ?: return
        if (!dragMoved && hypot(rawX - dragStartRawX, rawY - dragStartRawY) > touchSlop) dragMoved = true
        val x = rawX - dragLayerLoc[0]
        val y = rawY - dragLayerLoc[1]
        v.x = x - dragOffX
        v.y = y - dragOffY
        if (dragMoved) updateEdge(x, y)
    }

    private fun updateEdge(x: Float, y: Float) {
        val edge = context.dp(28f)
        val c = model.config
        val dir = when {
            x < edge && curX > 0 -> 1
            x > dragLayer.width - edge && curX < c.pagesX - 1 -> 2
            y < insets.top + edge && curY > 0 -> 3
            y > dragLayer.height - insets.bottom - edge && curY < c.pagesY - 1 -> 4
            else -> 0
        }
        if (dir != edgeDir) {
            edgeDir = dir
            removeCallbacks(edgeFlip)
            if (dir != 0) postDelayed(edgeFlip, 550)
        }
    }

    private fun flipFromEdge() {
        when (edgeDir) {
            1 -> snapTo(curX - 1, curY)
            2 -> snapTo(curX + 1, curY)
            3 -> snapTo(curX, curY - 1)
            4 -> snapTo(curX, curY + 1)
            else -> return
        }
        edgeDir = 0 // must leave and re-enter the edge (or keep holding → re-armed on next move)
    }

    private fun drop() {
        removeCallbacks(edgeFlip)
        edgeDir = 0
        finishScrollNow()
        val v = dragView
        val item = dragItem
        dragView = null; dragItem = null
        endGesture()
        if (v == null || item == null) return

        var folder: FolderItem? = null
        if (dragMoved) {
            val page = pageAt(curX, curY)
            if (page != null) {
                // Page content origin in drag-layer coordinates (target page is fully on screen).
                val wsLoc = IntArray(2)
                getLocationOnScreen(wsLoc)
                val ox = wsLoc[0] - dragLayerLoc[0] + page.paddingLeft
                val oy = wsLoc[1] - dragLayerLoc[1] + page.paddingTop
                val lx = v.x - ox
                val ly = v.y - oy
                val c = model.config
                when (item) {
                    is GridItem -> {
                        val cx = floor((lx + v.width / 2f) / page.cellW).toInt().coerceIn(0, c.columns - 1)
                        val cy = floor((ly + v.height / 2f) / page.cellH).toInt().coerceIn(0, c.rows - 1)
                        if (item is AppItem) folder = model.folderAt(curX, curY, cx, cy)
                        item.cellX = cx; item.cellY = cy
                    }
                    is WidgetItem -> {
                        val d = resources.displayMetrics.density
                        val maxX = (page.contentW - v.width).coerceAtLeast(0)
                        val maxY = (page.contentH - v.height).coerceAtLeast(0)
                        item.x = lx.coerceIn(0f, maxX.toFloat()) / d
                        item.y = ly.coerceIn(0f, maxY.toFloat()) / d
                    }
                }
                item.pageX = curX; item.pageY = curY
            }
        }

        v.animate().cancel()
        v.scaleX = 1f; v.scaleY = 1f; v.alpha = 1f
        dragLayer.removeView(v)
        v.translationX = 0f; v.translationY = 0f

        if (external) {
            external = false
            callbacks.onExternalDrop(item, folder, cancelled = cancelling || !dragMoved)
        } else if (!dragMoved) {
            // Long-press without moving → put it back and show its menu.
            putBack(item, v)
            if (!cancelling) v.post { callbacks.onItemLongPressReleased(item, v) }
        } else if (folder != null) {
            callbacks.onItemDropped(item, folder)
        } else {
            putBack(item, v)
            callbacks.onItemDropped(item, null)
        }
        if (pendingSync) { pendingSync = false; sync() }
    }

    /** Re-adds a view to its page and positions it immediately (no stale-frame flash). */
    private fun putBack(item: HomeItem, v: View) {
        val page = pageAt(item.pageX, item.pageY) ?: return
        page.addView(v)
        val r = Rect()
        page.rectFor(item, r)
        v.measure(MeasureSpec.makeMeasureSpec(r.width(), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(r.height(), MeasureSpec.EXACTLY))
        v.layout(r.left, r.top, r.right, r.bottom)
    }

    // ================================================================= external drags

    /**
     * Starts dragging [v] (not yet attached anywhere) for [item], which is not in the model.
     * The caller owns the touch stream and forwards it via [externalDragMove] / [externalDragEnd].
     */
    fun beginExternalDrag(item: HomeItem, v: View, w: Int, h: Int, rawX: Float, rawY: Float): Boolean {
        if (mode == Mode.DRAG || locked) return false
        removeCallbacks(longPress)
        finishScrollNow()
        dragLayer.getLocationOnScreen(dragLayerLoc)
        dragLayer.addView(v, FrameLayout.LayoutParams(w, h))
        v.layout(0, 0, w, h)
        dragOffX = w / 2f
        dragOffY = h / 2f
        v.translationX = rawX - dragLayerLoc[0] - dragOffX
        v.translationY = rawY - dragLayerLoc[1] - dragOffY
        v.alpha = 0.9f
        dragStartRawX = rawX; dragStartRawY = rawY
        dragItem = item
        dragView = v
        dragMoved = true
        external = true
        mode = Mode.DRAG
        return true
    }

    fun externalDragMove(rawX: Float, rawY: Float) {
        if (mode == Mode.DRAG && external) moveDrag(rawX, rawY)
    }

    fun externalDragEnd(cancel: Boolean) {
        if (mode != Mode.DRAG || !external) return
        if (cancel) { dragMoved = false; cancelling = true }
        drop()
        cancelling = false
    }

    // ================================================================= icon swipes

    /** Small visual nudge of the icon in the swipe direction. */
    private fun nudgeIcon(ev: MotionEvent) {
        val v = swipeView ?: return
        val d = (along(ev) * 0.4f).coerceIn(0f, gestureNudge)
        when (swipeDir) {
            "up" -> v.translationY = -d
            "down" -> v.translationY = d
            "left" -> v.translationX = -d
            "right" -> v.translationX = d
        }
    }

    /** Finger travel along the swipe direction (negative = backwards). */
    private fun along(ev: MotionEvent): Float = when (swipeDir) {
        "up" -> downY - ev.y
        "down" -> ev.y - downY
        "left" -> downX - ev.x
        else -> ev.x - downX
    }

    private fun finishIconSwipe(ev: MotionEvent, up: Boolean) {
        val v = swipeView
        val item = pressedItem as? AppItem
        val fire = up && item != null && v != null && along(ev) >= gestureDistance
        v?.animate()?.translationX(0f)?.translationY(0f)?.setDuration(120)?.start()
        val dir = swipeDir
        swipeView = null
        endGesture()
        if (fire) {
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            callbacks.onIconGesture(item!!, dir, v!!)
        }
    }

    /** Cancels a drag in progress (e.g. Home pressed). */
    fun cancelDrag() {
        if (mode == Mode.DRAG && external) {
            externalDragEnd(cancel = true)
        } else if (mode == Mode.DRAG) {
            dragMoved = false; cancelling = true
            drop()
            cancelling = false
        }
    }

    private fun key(px: Int, py: Int) = py * 1000 + px
}
