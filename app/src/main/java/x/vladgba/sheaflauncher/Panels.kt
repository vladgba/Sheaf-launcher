package x.vladgba.sheaflauncher

import android.annotation.SuppressLint
import android.appwidget.AppWidgetHostView
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
    fun defaultWidgetSizeDp(a: LauncherActivity, info: AppWidgetProviderInfo): Pair<Float, Float> {
        val d = a.resources.displayMetrics.density
        val (pw, ph) = a.pageContentSizeDp()
        var w = info.minWidth / d
        var h = info.minHeight / d
        if (Build.VERSION.SDK_INT >= 31) {
            val cw = pw / a.config.columns
            val ch = ph / a.config.rows
            if (info.targetCellWidth > 0) w = maxOf(w, info.targetCellWidth * cw)
            if (info.targetCellHeight > 0) h = maxOf(h, info.targetCellHeight * ch)
        }
        return Pair(w.coerceIn(110f, pw), h.coerceIn(LauncherActivity.MIN_WIDGET_DP, ph))
    }

    @SuppressLint("SetTextI18n")
    fun pickWidget(a: LauncherActivity): DragSourcePanel? {
        val awm = AppWidgetManager.getInstance(a)
        val pm = a.packageManager
        val providers = awm.installedProviders
        if (providers.isEmpty()) { a.toast("No widgets installed"); return null }

        data class Row(val info: AppWidgetProviderInfo, val label: String, val app: String)
        val rows = providers.map { info ->
            val app = try {
                pm.getApplicationLabel(pm.getApplicationInfo(info.provider.packageName, 0)).toString()
            } catch (_: Exception) { info.provider.packageName }
            Row(info, info.loadLabel(pm), app)
        }.sortedWith(compareBy({ it.app.lowercase() }, { it.label.lowercase() }))

        val dm = a.resources.displayMetrics
        val d = dm.density
        val boxH = a.dpi(130f)
        val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(a).apply { addView(list); isVerticalScrollBarEnabled = false }
        val title = TextView(a).apply {
            text = "Long-press a widget and drag it onto the home screen"
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
        // Keep clear of the status/navigation bars.
        val ins = a.systemInsets
        card.layoutParams = FrameLayout.LayoutParams(-1, -1).apply {
            setMargins(ins.left + a.dpi(8f), ins.top + a.dpi(8f), ins.right + a.dpi(8f), ins.bottom + a.dpi(8f))
        }
        val panel = DragSourcePanel(a, card)
        val io = Executors.newSingleThreadExecutor()
        var lastApp: String? = null
        for (r in rows) {
            if (r.app != lastApp) {
                lastApp = r.app
                list.addView(TextView(a).apply {
                    text = r.app; textSize = 15f; setTextColor(Palette.cardText)
                    setPadding(a.dpi(20f), a.dpi(14f), a.dpi(20f), a.dpi(4f))
                })
            }
            val (wDp, hDp) = defaultWidgetSizeDp(a, r.info)
            val box = PreviewBox(a, (wDp * d).roundToInt(), (hDp * d).roundToInt())
            box.tag = r.info
            box.setOnClickListener { a.toast("Long-press and drag to place") }
            val item = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(a.dpi(20f), a.dpi(6f), a.dpi(20f), a.dpi(10f))
                addView(box, LinearLayout.LayoutParams(-1, boxH))
                addView(TextView(a).apply {
                    text = "${r.label}  ·  ${wDp.roundToInt()}×${hDp.roundToInt()} dp"
                    setTextColor(Palette.cardTextDim); textSize = 12f
                    setPadding(0, a.dpi(4f), 0, 0)
                })
            }
            list.addView(item)
            panel.draggables += box
            loadPreview(a, r.info, box, io)
        }
        panel.onClosed = { io.shutdownNow() }
        panel.onItemMenu = { a.toast("Drag it onto the home screen") }
        panel.onDragStart = { v, rx, ry ->
            val box = v as PreviewBox
            a.startWidgetDrag(box.tag as AppWidgetProviderInfo, box.snapshot(), rx, ry)
        }
        panel.onDragMove = { rx, ry -> a.externalDragMove(rx, ry) }
        panel.onDragEnd = { c -> a.externalDragEnd(c) }
        a.showPanel(panel)
        return panel
    }

    /**
     * Preview priority: previewLayout (Android 12+, real RemoteViews), previewImage, app icon.
     * Images load off the main thread; RemoteViews must inflate on it.
     */
    private fun loadPreview(a: LauncherActivity, info: AppWidgetProviderInfo, box: PreviewBox,
                            io: java.util.concurrent.ExecutorService) {
        if (Build.VERSION.SDK_INT >= 31 && info.previewLayout != 0) {
            box.post {
                val v = try {
                    AppWidgetHostView(a).apply {
                        setAppWidget(-1, info)
                        updateAppWidget(RemoteViews(info.provider.packageName, info.previewLayout))
                    }
                } catch (_: Throwable) { null }
                if (v != null) box.setContent(v) else loadImage(a, info, box, io)
            }
            return
        }
        loadImage(a, info, box, io)
    }

    private fun loadImage(a: LauncherActivity, info: AppWidgetProviderInfo, box: PreviewBox,
                          io: java.util.concurrent.ExecutorService) {
        try {
            io.execute {
                val dpi = a.resources.displayMetrics.densityDpi
                val dr: Drawable? = try { info.loadPreviewImage(a, dpi) } catch (_: Throwable) { null }
                    ?: try { info.loadIcon(a, dpi) } catch (_: Throwable) { null }
                box.post {
                    box.setContent(ImageView(a).apply {
                        setImageDrawable(dr)
                        scaleType = ImageView.ScaleType.FIT_CENTER
                    })
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { /* panel closed */ }
    }

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
