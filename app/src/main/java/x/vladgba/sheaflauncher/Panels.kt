package x.vladgba.sheaflauncher

import android.annotation.SuppressLint
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RemoteViews
import android.widget.ScrollView
import android.widget.TextView
import x.vladgba.sheaflauncher.model.FolderItem
import x.vladgba.sheaflauncher.ui.AppIconView
import x.vladgba.sheaflauncher.ui.DragSourcePanel
import x.vladgba.sheaflauncher.ui.Palette
import x.vladgba.sheaflauncher.ui.dp
import x.vladgba.sheaflauncher.ui.dpi
import java.util.concurrent.Executors
import kotlin.math.min
import kotlin.math.roundToInt

/** In-window panels (folder, widget picker) whose items can be dragged onto the home screen. */
object Panels {

    private fun cardBackground(ctx: Context) = GradientDrawable().apply {
        setColor(Palette.card)
        cornerRadius = ctx.dp(24f)
    }

    // ================================================================= folder

    fun openFolder(a: LauncherActivity, folder: FolderItem): DragSourcePanel {
        val cfg = a.config
        val cols = cfg.folderColumns
        val dm = a.resources.displayMetrics
        val pad = a.dpi(14f)
        val maxW = (dm.widthPixels * 0.92f).toInt().coerceAtMost(a.dpi(560f))
        val cellW = ((maxW - pad * 2) / cols).coerceIn(a.dpi(48f), a.dpi(96f))
        val cellH = (cellW * 1.2f).toInt()
        val rowsNeeded = (folder.apps.size + cols - 1) / cols
        val visibleRows = rowsNeeded.coerceIn(1, cfg.folderRows)

        val grid = GridLayout(a).apply { columnCount = cols }
        val scroll = object : ScrollView(a) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(visibleRows * cellH, MeasureSpec.AT_MOST))
            }
        }
        scroll.isVerticalScrollBarEnabled = false
        scroll.addView(grid)
        val card = FrameLayout(a).apply {
            background = cardBackground(a)
            setPadding(pad, pad, pad, pad)
            addView(scroll)
            isClickable = true // taps on the card's padding don't close the panel
        }
        card.layoutParams = FrameLayout.LayoutParams(cols.coerceAtMost(folder.apps.size.coerceAtLeast(1)) * cellW + pad * 2, -2, Gravity.CENTER)

        val panel = DragSourcePanel(a, card)
        for (component in folder.apps.toList()) {
            val icon = AppIconView(a).apply {
                icon = a.iconFor(component)
                label = a.labelFor(component)
                showLabel = cfg.showLabels
                iconScale = cfg.iconScale / 100f
                labelColor = Palette.cardText
                labelShadow = false
                tag = component
                setOnClickListener { a.launch(component, it); panel.close() }
            }
            grid.addView(icon, GridLayout.LayoutParams().apply { width = cellW; height = cellH })
            panel.draggables += icon
        }
        panel.onItemMenu = { v -> a.showAppMenu(v.tag as String, v, homeItem = null) { panel.close() } }
        panel.onDragStart = { v, rx, ry -> a.startFolderAppDrag(folder, v.tag as String, rx, ry) }
        panel.onDragMove = { rx, ry -> a.externalDragMove(rx, ry) }
        panel.onDragEnd = { c -> a.externalDragEnd(c) }
        a.showPanel(panel)
        return panel
    }

    // ================================================================= widgets

    /** Default size (dp) for a newly placed widget. */
    fun defaultWidgetSizeDp(a: LauncherActivity, info: AppWidgetProviderInfo): Pair<Float, Float> =
        defaultWidgetSizeDp(info, a.resources.displayMetrics.density, a.pageContentSizeDp(), a.config.columns, a.config.rows)

    /**
     * Widget picker. Opens instantly; everything slow happens off the main thread
     * or lazily:
     *  1. provider list, labels and app names load on a background thread
     *  2. rows are added a few per frame
     *  3. previews load only for rows near the visible area (images on the
     *     background thread, preview layouts one per frame on the main thread)
     */
    @SuppressLint("SetTextI18n")
    fun pickWidget(a: LauncherActivity): DragSourcePanel {
        val pm = a.packageManager
        val d = a.resources.displayMetrics.density
        val boxH = a.dpi(130f)
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val io = Executors.newSingleThreadExecutor()

        val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(a).apply { addView(list); isVerticalScrollBarEnabled = false }
        val title = TextView(a).apply {
            text = "Loading widgets…"
            setTextColor(Palette.cardTextDim); textSize = 13f
            setPadding(a.dpi(20f), a.dpi(16f), a.dpi(20f), a.dpi(8f))
        }
        val card = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBackground(a)
            isClickable = true
            addView(title)
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        val ins = a.systemInsets
        card.layoutParams = FrameLayout.LayoutParams(-1, -1).apply {
            setMargins(ins.left + a.dpi(8f), ins.top + a.dpi(8f), ins.right + a.dpi(8f), ins.bottom + a.dpi(8f))
        }
        val panel = DragSourcePanel(a, card)
        var closed = false
        panel.onClosed = { closed = true; io.shutdownNow(); main.removeCallbacksAndMessages(null) }
        panel.onItemMenu = { a.toast("Drag it onto the home screen") }
        panel.onDragStart = { v, rx, ry ->
            val box = v as PreviewBox
            a.startWidgetDrag(box.tag as AppWidgetProviderInfo, box.snapshot(), rx, ry)
        }
        panel.onDragMove = { rx, ry -> a.externalDragMove(rx, ry) }
        panel.onDragEnd = { c -> a.externalDragEnd(c) }
        a.showPanel(panel)

        // ---- lazy previews
        val boxes = ArrayList<PreviewBox>()
        val requested = HashSet<PreviewBox>()
        val layoutQueue = ArrayDeque<PreviewBox>()
        val tmp = android.graphics.Rect()

        var inflating = false
        fun inflateNextLayout() {
            if (closed) return
            val box = layoutQueue.removeFirstOrNull()
            if (box == null) { inflating = false; return }
            val info = box.tag as AppWidgetProviderInfo
            val v = inflatePreviewLayout(a, info)
            if (v != null) box.setContent(v) else loadImage(a, info, box, io, main)
            if (layoutQueue.isNotEmpty()) main.post { inflateNextLayout() } else inflating = false
        }

        fun loadVisible() {
            if (closed || scroll.height == 0) return
            val top = scroll.scrollY - scroll.height        // one screen above
            val bottom = scroll.scrollY + scroll.height * 2 // one screen below
            var queued = false
            for (box in boxes) {
                if (box in requested) continue
                // box position inside the list (box → row → list)
                val row = box.parent as View
                val y = row.top + box.top
                if (y + box.height < top || y > bottom) continue
                requested += box
                val info = box.tag as AppWidgetProviderInfo
                if (Build.VERSION.SDK_INT >= 31 && info.previewLayout != 0) {
                    layoutQueue.addLast(box); queued = true
                } else {
                    loadImage(a, info, box, io, main)
                }
            }
            // One preview layout per frame, single chain.
            if (queued && !inflating) { inflating = true; main.post { inflateNextLayout() } }
        }
        scroll.viewTreeObserver.addOnScrollChangedListener { loadVisible() }
        scroll.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> loadVisible() }

        // ---- 1. query in the background
        data class Row(val info: AppWidgetProviderInfo, val label: String, val app: String, val w: Float, val h: Float)
        val pageSize = a.pageContentSizeDp()
        val cols = a.config.columns
        val rowsCfg = a.config.rows
        try {
            io.execute {
                val providers = try { AppWidgetManager.getInstance(a).installedProviders } catch (_: Exception) { emptyList() }
                val appNames = HashMap<String, String>()
                val rows = providers.map { info ->
                    val pkg = info.provider.packageName
                    val app = appNames.getOrPut(pkg) {
                        try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }
                    }
                    val (w, h) = defaultWidgetSizeDp(info, d, pageSize, cols, rowsCfg)
                    Row(info, try { info.loadLabel(pm) } catch (_: Exception) { pkg }, app, w, h)
                }.sortedWith(compareBy({ it.app.lowercase() }, { it.label.lowercase() }))

                // ---- 2. build rows a few per frame
                main.post {
                    if (closed) return@post
                    if (rows.isEmpty()) { title.text = "No widgets installed"; return@post }
                    title.text = "Long-press a widget and drag it onto the home screen"
                    var i = 0
                    var lastApp: String? = null
                    fun addChunk() {
                        if (closed) return
                        val end = min(i + ROWS_PER_FRAME, rows.size)
                        while (i < end) {
                            val r = rows[i++]
                            if (r.app != lastApp) {
                                lastApp = r.app
                                list.addView(TextView(a).apply {
                                    text = r.app; textSize = 15f; setTextColor(Palette.cardText)
                                    setPadding(a.dpi(20f), a.dpi(14f), a.dpi(20f), a.dpi(4f))
                                })
                            }
                            val box = PreviewBox(a, (r.w * d).roundToInt(), (r.h * d).roundToInt())
                            box.tag = r.info
                            box.setOnClickListener { a.toast("Long-press and drag to place") }
                            val item = LinearLayout(a).apply {
                                orientation = LinearLayout.VERTICAL
                                setPadding(a.dpi(20f), a.dpi(6f), a.dpi(20f), a.dpi(10f))
                                addView(box, LinearLayout.LayoutParams(-1, boxH))
                                addView(TextView(a).apply {
                                    text = "${r.label}  ·  ${r.w.roundToInt()}×${r.h.roundToInt()} dp"
                                    setTextColor(Palette.cardTextDim); textSize = 12f
                                    setPadding(0, a.dpi(4f), 0, 0)
                                })
                            }
                            list.addView(item)
                            boxes += box
                            panel.draggables += box
                        }
                        if (i < rows.size) main.post { addChunk() }
                        // previews for whatever is on screen once laid out
                        list.post { loadVisible() }
                    }
                    addChunk()
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { }
        return panel
    }

    /** Default size (dp) computed from plain values so it can run off the main thread. */
    private fun defaultWidgetSizeDp(info: AppWidgetProviderInfo, density: Float, page: Pair<Float, Float>,
                                    columns: Int, rows: Int): Pair<Float, Float> {
        val (pw, ph) = page
        var w = info.minWidth / density
        var h = info.minHeight / density
        if (Build.VERSION.SDK_INT >= 31) {
            if (info.targetCellWidth > 0) w = maxOf(w, info.targetCellWidth * pw / columns)
            if (info.targetCellHeight > 0) h = maxOf(h, info.targetCellHeight * ph / rows)
        }
        return Pair(w.coerceIn(110f, pw), h.coerceIn(LauncherActivity.MIN_WIDGET_DP, ph))
    }

    /**
     * Android 12+ preview layout. Inflated with RemoteViews.apply(), which throws on
     * failure (unlike AppWidgetHostView, which silently shows "Can't load widget"),
     * so we can fall back to the preview image / icon.
     */
    private fun inflatePreviewLayout(a: LauncherActivity, info: AppWidgetProviderInfo): View? {
        if (Build.VERSION.SDK_INT < 31 || info.previewLayout == 0) return null
        return try {
            val rv = RemoteViews(info.provider.packageName, info.previewLayout)
            val parent = FrameLayout(a)
            rv.apply(a, parent)
        } catch (_: Throwable) { null }
    }

    private fun loadImage(a: LauncherActivity, info: AppWidgetProviderInfo, box: PreviewBox,
                          io: java.util.concurrent.ExecutorService, main: android.os.Handler) {
        try {
            io.execute {
                val dpi = a.resources.displayMetrics.densityDpi
                val dr: Drawable? = try { info.loadPreviewImage(a, dpi) } catch (_: Throwable) { null }
                    ?: try { info.loadIcon(a, dpi) } catch (_: Throwable) { null }
                main.post {
                    box.setContent(ImageView(a).apply {
                        setImageDrawable(dr)
                        scaleType = ImageView.ScaleType.FIT_CENTER
                    })
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { /* panel closed */ }
    }

    private const val ROWS_PER_FRAME = 6

    /**
     * Shows one child laid out at the widget's real size ([natW]×[natH] px),
     * scaled down to fit the box. [snapshot] renders it at real size for dragging.
     */
    @SuppressLint("ViewConstructor")
    class PreviewBox(ctx: Context, private val natW: Int, private val natH: Int) : ViewGroup(ctx) {
        init {
            isClickable = true
            background = GradientDrawable().apply { setColor(Palette.previewBg); cornerRadius = ctx.dp(12f) }
        }

        fun setContent(v: View) {
            removeAllViews()
            addView(v)
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
            for (i in 0 until childCount) getChildAt(i).measure(
                MeasureSpec.makeMeasureSpec(natW, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(natH, MeasureSpec.EXACTLY))
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val w = r - l; val h = b - t
            val pad = context.dp(8f)
            val s = min(1f, min((w - pad * 2) / natW, (h - pad * 2) / natH)).coerceAtLeast(0.05f)
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                val left = ((w - natW) / 2f).roundToInt()
                val top = ((h - natH) / 2f).roundToInt()
                c.layout(left, top, left + natW, top + natH)
                c.pivotX = natW / 2f; c.pivotY = natH / 2f
                c.scaleX = s; c.scaleY = s
            }
        }

        /** Preview rendered at real widget size, wrapped in an ImageView for the drag layer. */
        fun snapshot(): View {
            val bmp = Bitmap.createBitmap(natW.coerceAtLeast(1), natH.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            val c = getChildAt(0)
            if (c != null) {
                try { c.draw(Canvas(bmp)) } catch (_: Throwable) { }
            }
            return ImageView(context).apply {
                setImageBitmap(bmp)
                scaleType = ImageView.ScaleType.FIT_CENTER
                background = GradientDrawable().apply { setColor(0x33FFFFFF); cornerRadius = context.dp(12f) }
            }
        }

        val naturalWidth get() = natW
        val naturalHeight get() = natH
    }
}
