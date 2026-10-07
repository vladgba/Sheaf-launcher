package x.vladgba.sheaflauncher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlin.math.hypot

/**
 * Full-window overlay (dimmed background + content [card]) whose items can be
 * long-pressed and dragged out onto the home screen.
 *
 *  - tap an item           → its own click listener
 *  - long-press + release  → [onItemMenu]
 *  - long-press + drag     → [onDragStart]; the panel hides its content but stays
 *                            attached so it keeps receiving the touch stream, and
 *                            forwards it through [onDragMove] / [onDragEnd]
 *  - tap outside the card  → close
 *
 * Lives in the launcher's own window (not a Dialog), which is what makes the
 * hand-over to the workspace drag possible.
 */
@SuppressLint("ViewConstructor")
class DragSourcePanel(context: Context, val card: View) : FrameLayout(context) {

    /** Views that can be long-pressed / dragged. */
    val draggables = mutableListOf<View>()

    var onItemMenu: (View) -> Unit = {}
    /** Return true if a drag was started by the host. */
    var onDragStart: (View, Float, Float) -> Boolean = { _, _, _ -> false }
    var onDragMove: (Float, Float) -> Unit = { _, _ -> }
    var onDragEnd: (cancelled: Boolean) -> Unit = {}
    var onClosed: () -> Unit = {}

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var candidate: View? = null
    private var longPressed = false
    private var dragging = false
    private var closed = false
    private val tmp = Rect()
    private val myLoc = IntArray(2)
    private val childLoc = IntArray(2)

    private val longPress = Runnable {
        val c = candidate ?: return@Runnable
        longPressed = true
        c.isPressed = false
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        c.animate().scaleX(1.08f).scaleY(1.08f).setDuration(120).start()
    }

    init {
        setBackgroundColor(Palette.scrim)
        isClickable = true
        isFocusable = true
        addView(card)
    }

    private fun itemAt(x: Float, y: Float): View? {
        getLocationOnScreen(myLoc)
        for (v in draggables) {
            if (!v.isShown) continue
            v.getLocationOnScreen(childLoc)
            val l = childLoc[0] - myLoc[0]
            val t = childLoc[1] - myLoc[1]
            if (x >= l && x < l + v.width && y >= t && y < t + v.height) return v
        }
        return null
    }

    private fun resetCandidate() {
        removeCallbacks(longPress)
        candidate?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(100)?.start()
    }

    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        // A scrolling child took the gesture → it's not a long-press.
        if (disallowIntercept && !longPressed) removeCallbacks(longPress)
        super.requestDisallowInterceptTouchEvent(disallowIntercept)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (dragging) return true
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y
                longPressed = false
                candidate = itemAt(ev.x, ev.y)
                if (candidate != null) postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                val moved = hypot(ev.x - downX, ev.y - downY) > slop
                if (!longPressed) {
                    if (moved) removeCallbacks(longPress)
                    return false
                }
                if (moved) {
                    startDragging(ev)
                    return true
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                if (longPressed) {
                    // Long-press released in place → menu; intercepting cancels the child's click.
                    val c = candidate
                    resetCandidate()
                    longPressed = false
                    if (c != null) post { onItemMenu(c) }
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> { resetCandidate(); longPressed = false }
        }
        return false
    }

    private fun startDragging(ev: MotionEvent) {
        val c = candidate ?: return
        resetCandidate()
        longPressed = false
        if (onDragStart(c, ev.rawX, ev.rawY)) {
            dragging = true
            card.visibility = INVISIBLE
            setBackgroundColor(0)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (dragging) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> onDragMove(ev.rawX, ev.rawY)
                MotionEvent.ACTION_UP -> finishDrag(false)
                MotionEvent.ACTION_CANCEL -> finishDrag(true)
            }
            return true
        }
        if (ev.actionMasked == MotionEvent.ACTION_UP) {
            card.getHitRect(tmp)
            if (!tmp.contains(ev.x.toInt(), ev.y.toInt())) close()
        }
        return true
    }

    private fun finishDrag(cancelled: Boolean) {
        dragging = false
        onDragEnd(cancelled)
        close()
    }

    /** Ends an in-progress drag as cancelled (e.g. Home pressed) and closes. */
    fun cancel() {
        if (dragging) finishDrag(true) else close()
    }

    fun close() {
        if (closed) return
        closed = true
        removeCallbacks(longPress)
        // Detach after the current touch dispatch finishes.
        post { (parent as? ViewGroup)?.removeView(this) }
        onClosed()
    }

    val isClosed get() = closed

    /** Convenience for the host. */
    fun View.registerDraggable(): View { draggables += this; return this }
}
