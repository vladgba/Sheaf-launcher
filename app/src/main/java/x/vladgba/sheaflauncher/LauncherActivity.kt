package x.vladgba.sheaflauncher

import android.app.Activity
import android.app.ActivityOptions
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.util.SizeF
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import x.vladgba.sheaflauncher.model.AppInfo
import x.vladgba.sheaflauncher.model.AppItem
import x.vladgba.sheaflauncher.model.AppRepository
import x.vladgba.sheaflauncher.model.FolderItem
import x.vladgba.sheaflauncher.model.GridItem
import x.vladgba.sheaflauncher.model.HomeItem
import x.vladgba.sheaflauncher.model.IconCache
import x.vladgba.sheaflauncher.model.WidgetItem
import x.vladgba.sheaflauncher.model.WorkspaceModel
import x.vladgba.sheaflauncher.model.WorkspaceStore
import x.vladgba.sheaflauncher.ui.AppIconView
import x.vladgba.sheaflauncher.ui.CropView
import x.vladgba.sheaflauncher.ui.DragSourcePanel
import x.vladgba.sheaflauncher.ui.Palette
import x.vladgba.sheaflauncher.ui.PageIndicator
import x.vladgba.sheaflauncher.ui.PageView
import x.vladgba.sheaflauncher.ui.ResizeFrame
import x.vladgba.sheaflauncher.ui.WorkspaceView
import x.vladgba.sheaflauncher.ui.dpi
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

class LauncherActivity : Activity(), WorkspaceView.Callbacks {

    private lateinit var store: WorkspaceStore
    private lateinit var model: WorkspaceModel
    val config get() = model.config
    private lateinit var repo: AppRepository
    private lateinit var iconCache: IconCache
    private lateinit var widgetManager: AppWidgetManager
    private lateinit var widgetHost: AppWidgetHost
    private lateinit var launcherApps: LauncherApps

    private lateinit var root: FrameLayout
    private lateinit var workspace: WorkspaceView
    private lateinit var dragLayer: FrameLayout
    private lateinit var indicator: PageIndicator

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val reloadRunnable = Runnable { reloadApps() }

    /** Installed apps by flattened component name. */
    var apps: Map<String, AppInfo> = emptyMap(); private set

    private var pendingWidgetId = -1
    private var pendingWidgetInfo: AppWidgetProviderInfo? = null

    /** Status/navigation bar + cutout insets, for overlays. */
    val systemInsets = Rect()
    lateinit var shortcuts: Shortcuts; private set

    /** Open folder / widget panel, if any. */
    private var openPanel: DragSourcePanel? = null
    /** Folder an app is being dragged out of. */
    private var dragSourceFolder: FolderItem? = null
    /** Widget being dragged in from the picker. */
    private var dragWidgetInfo: AppWidgetProviderInfo? = null
    /** Where a dropped widget goes once bound/configured (null → page centre). */
    private var pendingPlacement: WidgetItem? = null

    private var resizeFrame: ResizeFrame? = null
    private var resizeItem: WidgetItem? = null
    /** True while the home screen is visible and focused (no app or dialog over it). */
    private var hasFocus = false

    // ================================================================= lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupWindow()

        store = WorkspaceStore(this)
        model = store.load()
        repo = AppRepository(this)
        iconCache = IconCache(this)
        // Show cached icons right away; live icons replace them after reloadApps().
        apps = iconCache.memory().ifEmpty { iconCache.loadDisk() }
        widgetManager = AppWidgetManager.getInstance(this)
        widgetHost = AppWidgetHost(this, WIDGET_HOST_ID)
        launcherApps = getSystemService(LauncherApps::class.java)
        shortcuts = Shortcuts(this)

        root = FrameLayout(this)
        dragLayer = FrameLayout(this)
        workspace = WorkspaceView(this, dragLayer).also {
            it.model = model
            it.callbacks = this
        }
        indicator = PageIndicator(this)
        root.addView(workspace, FrameLayout.LayoutParams(-1, -1))
        root.addView(indicator, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        root.addView(dragLayer, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        root.setOnApplyWindowInsetsListener { _, insets -> applyInsets(insets); insets }

        savedInstanceState?.let {
            pendingWidgetId = it.getInt(STATE_PENDING_WIDGET, -1)
            if (pendingWidgetId != -1) pendingWidgetInfo = widgetManager.getAppWidgetInfo(pendingWidgetId)
            pendingIconComponent = it.getString(STATE_ICON_COMPONENT)
            it.getFloatArray(STATE_PLACEMENT)?.takeIf { f -> f.size == 6 }?.let { f ->
                pendingPlacement = WidgetItem(-1, -1, "").apply {
                    pageX = f[0].toInt(); pageY = f[1].toInt(); x = f[2]; y = f[3]; w = f[4]; h = f[5]
                }
            }
        }

        cleanupOrphanWidgets()
        applyColorMode()
        workspace.sync()
        if (savedInstanceState == null) workspace.snapTo(model.config.homeX, model.config.homeY, animate = false)
        reloadApps()
        launcherApps.registerCallback(packageCallback, main)
    }

    override fun onStart() {
        super.onStart()
        try { widgetHost.startListening() } catch (_: Exception) { }
    }

    override fun onStop() {
        super.onStop()
        try { widgetHost.stopListening() } catch (_: Exception) { }
    }

    override fun onWindowFocusChanged(focus: Boolean) {
        super.onWindowFocusChanged(focus)
        hasFocus = focus
    }

    override fun onDestroy() {
        launcherApps.unregisterCallback(packageCallback)
        io.shutdown()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_PENDING_WIDGET, pendingWidgetId)
        pendingIconComponent?.let { outState.putString(STATE_ICON_COMPONENT, it) }
        pendingPlacement?.let {
            outState.putFloatArray(STATE_PLACEMENT,
                floatArrayOf(it.pageX.toFloat(), it.pageY.toFloat(), it.x, it.y, it.w, it.h))
        }
    }

    /**
     * Home key → default page.
     * Android pauses the activity before delivering this intent, so "already on home"
     * is detected via window focus (same approach as AOSP Launcher3), not onResume.
     *  - already on home screen → animated scroll to the default page
     *  - returning from an app  → jump there instantly (no visible animation)
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (Intent.ACTION_MAIN != intent.action) return
        val alreadyOnHome = hasFocus &&
                (intent.flags and Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT) == 0
        val c = model.config
        // Already resting on the default page → run the configured Home action.
        val onDefault = alreadyOnHome && resizeFrame == null && openPanel == null &&
                workspace.isSettledOn(c.homeX, c.homeY)
        openPanel?.cancel()
        finishResize()
        workspace.cancelDrag()
        if (onDefault) {
            HomeActions.perform(this, HomeAction.of(c.homeAction))
        } else {
            workspace.snapTo(c.homeX, c.homeY, animate = alreadyOnHome)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // A launcher never finishes on Back; just leave edit modes / close panels.
        if (cropScreen != null) { closeCropScreen(); return }
        openPanel?.let { it.cancel(); return }
        finishResize()
    }

    private fun setupWindow() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        }
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
    }

    private fun applyInsets(insets: WindowInsets) {
        val r = if (Build.VERSION.SDK_INT >= 30) {
            val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            Rect(i.left, i.top, i.right, i.bottom)
        } else {
            @Suppress("DEPRECATION")
            Rect(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
        }
        systemInsets.set(r)
        val lp = indicator.layoutParams as FrameLayout.LayoutParams
        lp.bottomMargin = r.bottom + dpi(4f)
        indicator.layoutParams = lp
        // Indicator floats over the pages (drawn on top, touches pass through),
        // so pages use the full height down to the navigation bar.
        workspace.setContentInsets(r.left, r.top, r.right, r.bottom)
    }

    // ================================================================= apps

    private val packageCallback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) = scheduleReload()
        override fun onPackageAdded(packageName: String, user: UserHandle) = scheduleReload()
        override fun onPackageChanged(packageName: String, user: UserHandle) = scheduleReload()
        override fun onPackagesAvailable(p: Array<out String>, user: UserHandle, replacing: Boolean) = scheduleReload()
        override fun onPackagesUnavailable(p: Array<out String>, user: UserHandle, replacing: Boolean) = scheduleReload()
    }

    private fun scheduleReload() {
        main.removeCallbacks(reloadRunnable)
        main.postDelayed(reloadRunnable, 400)
    }

    private fun reloadApps() {
        io.execute {
            val list = repo.query()
            main.post {
                if (isDestroyed) return@post
                apps = list.associateBy { it.component }
                removeDeadWidgets()
                val (w, h) = workspace.contentSizeDp()
                val pagesBefore = model.config.pagesX
                // Hidden apps are left out, so syncApps removes them from pages and folders.
                model.syncApps(list.map { it.component }.filter { it !in model.hidden }, w, h)
                if (model.config.pagesX > pagesBefore) {
                    toast("Added pages to fit all apps")
                }
                saveAndSync()
                io.execute { iconCache.store(list) }
            }
        }
    }

    fun launch(component: String, from: View) {
        val cn = ComponentName.unflattenFromString(component) ?: return
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(cn)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val opts = ActivityOptions.makeScaleUpAnimation(from, 0, 0, from.width, from.height).toBundle()
        try {
            startActivity(intent, opts)
        } catch (e: Exception) {
            toast("Can't open app")
        }
    }

    /** Icon shown for an app: custom icon if set, else the app's own. */
    fun iconFor(component: String): Drawable? = customIcon(component) ?: apps[component]?.icon
    fun originalIcon(component: String): Drawable? = apps[component]?.icon
    /** Label shown for an app: custom name if set, else the app's own. */
    fun labelFor(component: String): String = model.customLabels[component] ?: originalLabel(component)
    fun originalLabel(component: String): String = apps[component]?.label
        ?: if (apps.isEmpty()) "" else component.substringAfterLast('.')

    // ================================================================= custom icons & names

    private val customIconDir by lazy { File(filesDir, "custom_icons").apply { mkdirs() } }
    private val customIconCache = HashMap<String, Drawable>()
    /** App whose icon is being picked from the gallery (survives the picker activity). */
    private var pendingIconComponent: String? = null

    fun hasCustomIcon(component: String) = model.customIcons.containsKey(component)
    fun customLabelOf(component: String) = model.customLabels[component]

    private fun customIcon(component: String): Drawable? {
        val spec = model.customIcons[component] ?: return null
        return when {
            spec.startsWith("app:") -> apps[spec.removePrefix("app:")]?.icon
            spec.startsWith("file:") -> customIconCache[spec] ?: run {
                val bmp = BitmapFactory.decodeFile(File(customIconDir, spec.removePrefix("file:")).path)
                bmp?.let { BitmapDrawable(resources, it).also { d -> customIconCache[spec] = d } }
            }
            else -> null
        }
    }

    /** null → back to the app's own name. */
    fun setCustomLabel(component: String, label: String?) {
        if (label.isNullOrBlank() || label == originalLabel(component)) model.customLabels.remove(component)
        else model.customLabels[component] = label
        saveAndSync()
    }

    /** null → back to the app's own icon. Deletes the previous image file, if any. */
    fun setCustomIcon(component: String, spec: String?) {
        val old = model.customIcons[component]
        if (old != null && old != spec && old.startsWith("file:")) {
            customIconCache.remove(old)
            File(customIconDir, old.removePrefix("file:")).delete()
        }
        if (spec == null) model.customIcons.remove(component) else model.customIcons[component] = spec
        saveAndSync()
    }

    /** Opens the system image picker; the result becomes [component]'s icon. */
    fun pickIconImage(component: String) {
        pendingIconComponent = component
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_ICON_IMAGE)
        } catch (e: ActivityNotFoundException) {
            pendingIconComponent = null
            toast("No image picker available")
        }
    }

    /** Decodes the picked image (downsampled, EXIF-rotated) and opens the crop screen. */
    private fun onIconImagePicked(uri: Uri) {
        val component = pendingIconComponent ?: return
        pendingIconComponent = null
        io.execute {
            val bmp = try { decodeForCrop(uri) } catch (e: Exception) { null }
            main.post {
                if (isDestroyed) return@post
                if (bmp == null) { toast("Couldn't read that image"); return@post }
                showCropScreen(component, bmp)
            }
        }
    }

    private fun decodeForCrop(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= CROP_MAX_PX) sample *= 2
        val src = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        // Camera photos are often stored sideways with an EXIF rotation tag.
        val deg = try {
            contentResolver.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } ?: 0
        } catch (_: Exception) { 0 }
        return if (deg == 0) src else rotate(src, deg)
    }

    private fun rotate(b: Bitmap, deg: Int): Bitmap =
        Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(deg.toFloat()) }, true)

    private var cropScreen: View? = null

    /** Full-screen square crop: move/resize the frame, rotate, then Done saves the icon. */
    private fun showCropScreen(component: String, source: Bitmap) {
        closeCropScreen()
        openPanel?.cancel()
        val crop = CropView(this).apply { bitmap = source }
        val screen = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
            setPadding(systemInsets.left, systemInsets.top, systemInsets.right, systemInsets.bottom)
        }
        val barH = dpi(56f)
        screen.addView(crop, FrameLayout.LayoutParams(-1, -1).apply { bottomMargin = barH })
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        fun button(text: String, onClick: () -> Unit) = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            this.text = text
            setTextColor(Color.WHITE)
            setOnClickListener { onClick() }
        }
        bar.addView(button("Cancel") { closeCropScreen(); reopenEditor(component) },
            LinearLayout.LayoutParams(0, -1, 1f))
        bar.addView(button("Rotate") { crop.bitmap?.let { crop.bitmap = rotate(it, 90) } },
            LinearLayout.LayoutParams(0, -1, 1f))
        bar.addView(button("Done") {
            val b = crop.bitmap
            val r = crop.cropRect()
            closeCropScreen()
            if (b != null && r != null) saveCroppedIcon(component, b, r)
        }, LinearLayout.LayoutParams(0, -1, 1f))
        screen.addView(bar, FrameLayout.LayoutParams(-1, barH, Gravity.BOTTOM))
        cropScreen = screen
        root.addView(screen, FrameLayout.LayoutParams(-1, -1))
    }

    private fun closeCropScreen() {
        cropScreen?.let { root.removeView(it) }
        cropScreen = null
    }

    /** Cuts [r] out of [src], scales it to [ICON_PX]², stores it as PNG and applies it. */
    private fun saveCroppedIcon(component: String, src: Bitmap, r: Rect) {
        io.execute {
            val name = try {
                val cut = Bitmap.createBitmap(src, r.left, r.top, r.width(), r.height())
                val side = min(ICON_PX, max(r.width(), r.height()))
                val icon = Bitmap.createScaledBitmap(cut, side, side, true)
                val n = Integer.toHexString(component.hashCode()) + "_" + System.currentTimeMillis() + ".png"
                FileOutputStream(File(customIconDir, n)).use { icon.compress(Bitmap.CompressFormat.PNG, 100, it) }
                n
            } catch (e: Exception) { null }
            main.post {
                if (isDestroyed) return@post
                if (name == null) { toast("Couldn't save the icon"); return@post }
                setCustomIcon(component, "file:$name")
                reopenEditor(component)
            }
        }
    }

    // ================================================================= views for items

    override fun createItemView(item: HomeItem): View = when (item) {
        is AppItem -> AppIconView(this).apply { setOnClickListener { launch(item.component, it) } }
        is FolderItem -> AppIconView(this).apply {
            setOnClickListener { Panels.openFolder(this@LauncherActivity, tag as FolderItem) }
        }
        is WidgetItem -> createWidgetView(item)
    }

    override fun bindItemView(item: HomeItem, view: View) {
        val icon = view as? AppIconView ?: return
        icon.applyHomePalette()
        icon.showLabel = model.config.showLabels
        icon.iconScale = model.config.iconScale / 100f
        when (item) {
            is AppItem -> { icon.icon = iconFor(item.component); icon.label = labelFor(item.component) }
            is FolderItem -> { bindFolderIcon(item, icon); icon.label = item.title }
            else -> {}
        }
    }

    private fun createWidgetView(item: WidgetItem): View {
        val info = widgetManager.getAppWidgetInfo(item.appWidgetId)
            ?: return TextView(this).apply {
                text = "Widget unavailable"
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setBackgroundColor(0x44000000)
            }
        val v = widgetHost.createView(this, item.appWidgetId, info)
        v.setPadding(0, 0, 0, 0)
        v.post { updateWidgetSize(item, v) }
        return v
    }

    /** Tells the widget its real size so it can pick a layout. */
    private fun updateWidgetSize(item: WidgetItem, view: View? = workspace.itemViews[item.id]) {
        val v = view as? AppWidgetHostView ?: return
        if (Build.VERSION.SDK_INT >= 31) {
            v.updateAppWidgetSize(Bundle(), listOf(SizeF(item.w, item.h)))
        } else {
            @Suppress("DEPRECATION")
            v.updateAppWidgetSize(null, item.w.toInt(), item.h.toInt(), item.w.toInt(), item.h.toInt())
        }
    }

    // ================================================================= workspace callbacks

    override fun onPageChanged(px: Int, py: Int) {
        val c = model.config
        indicator.update(c.pagesX, c.pagesY, px, py, c.homeX, c.homeY)
    }

    override fun onItemDropped(item: HomeItem, intoFolder: FolderItem?) {
        if (intoFolder != null && item is AppItem) {
            intoFolder.apps += item.component
            model.items.remove(item)
        }
        saveAndSync()
    }

    override fun onEmptyLongPress(x: Float, y: Float) {
        val anchor = View(this)
        dragLayer.addView(anchor, FrameLayout.LayoutParams(1, 1).apply {
            leftMargin = x.toInt(); topMargin = y.toInt()
        })
        anchor.post {
            val pm = PopupMenu(Palette.menuContext(this), anchor)
            val locked = model.config.layoutLocked
            if (!locked) pm.menu.add(0, 1, 0, "Add widget")
            pm.menu.add(0, 2, 1, "Wallpaper")
            pm.menu.add(0, 3, 2, "Home screen settings")
            val c = model.config
            if (workspace.curX == c.homeX && workspace.curY == c.homeY) {
                pm.menu.add(0, 4, 3, "Default page ✓").isEnabled = false
            } else {
                pm.menu.add(0, 4, 3, "Set as default page")
            }
            pm.menu.add(0, 5, 4, if (locked) "Unlock layout" else "Lock layout")
            pm.setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> Panels.pickWidget(this)
                    2 -> try {
                        startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Wallpaper"))
                    } catch (_: ActivityNotFoundException) { toast("No wallpaper picker") }
                    3 -> Dialogs.settings(this, model.config) { applySettings() }
                    4 -> setDefaultPage(workspace.curX, workspace.curY)
                    5 -> {
                        model.config.layoutLocked = !model.config.layoutLocked
                        save()
                        toast(if (model.config.layoutLocked) "Layout locked" else "Layout unlocked")
                    }
                }
                true
            }
            pm.setOnDismissListener { dragLayer.removeView(anchor) }
            pm.show()
        }
    }

    override fun onItemLongPressReleased(item: HomeItem, view: View) {
        if (item is AppItem) { showAppMenu(item.component, view, item); return }
        val pm = PopupMenu(Palette.menuContext(this), view)
        val m = pm.menu
        when (item) {
            is AppItem -> {}
            is FolderItem -> {
                m.add(0, M_OPEN, 0, "Open"); m.add(0, M_RENAME, 1, "Edit icon & name…")
                if (!isLayoutLocked()) {
                    m.add(0, M_FRONT, 2, "Bring to front"); m.add(0, M_BACK, 3, "Send to back")
                    m.add(0, M_UNGROUP, 4, "Ungroup")
                }
            }
            is WidgetItem -> {
                val unlocked = !isLayoutLocked()
                if (unlocked) {
                    m.add(0, M_RESIZE, 0, "Resize / move")
                    m.add(0, M_SIZE, 1, "Set exact size…")
                    m.add(0, M_FRONT, 2, "Bring to front"); m.add(0, M_BACK, 3, "Send to back")
                }
                val info = widgetManager.getAppWidgetInfo(item.appWidgetId)
                if (info?.configure != null) m.add(0, M_RECONFIGURE, 4, "Widget settings")
                if (unlocked) m.add(0, M_REMOVE, 5, "Remove")
                if (m.size() == 0) { toast("Layout is locked"); return }
            }
        }
        pm.setOnMenuItemClickListener { onItemMenu(item, view, it.itemId); true }
        pm.show()
    }

    private fun onItemMenu(item: HomeItem, view: View, id: Int) {
        when (id) {
            M_FRONT -> { model.bringToFront(item); workspace.refreshOrder(item); save() }
            M_BACK -> { model.sendToBack(item); workspace.refreshOrder(item); save() }
            M_FOLDER -> Dialogs.chooseFolder(this, model.folders()) { folder -> moveToFolder(item as AppItem, folder) }
            M_INFO -> openAppInfo((item as AppItem).component)
            M_UNINSTALL -> uninstall((item as AppItem).component)
            M_OPEN -> Panels.openFolder(this, item as FolderItem)
            M_RENAME -> Dialogs.editFolder(this, item as FolderItem)
            M_UNGROUP -> ungroup(item as FolderItem)
            M_RESIZE -> startResize(item as WidgetItem)
            M_SIZE -> {
                val w = item as WidgetItem
                Dialogs.exactSize(this, w.w, w.h) { nw, nh ->
                    val (pw, ph) = workspace.contentSizeDp()
                    w.w = nw.coerceIn(MIN_WIDGET_DP, pw); w.h = nh.coerceIn(MIN_WIDGET_DP, ph)
                    w.x = w.x.coerceAtMost(pw - w.w); w.y = w.y.coerceAtMost(ph - w.h)
                    saveAndSync(); updateWidgetSize(w)
                }
            }
            M_RECONFIGURE -> try {
                widgetHost.startAppWidgetConfigureActivityForResult(this, (item as WidgetItem).appWidgetId, 0, REQ_RECONFIGURE, null)
            } catch (e: Exception) { toast("Can't open widget settings") }
            M_REMOVE -> {
                val w = item as WidgetItem
                model.items.remove(w)
                widgetHost.deleteAppWidgetId(w.appWidgetId)
                saveAndSync()
            }
        }
    }

    // ================================================================= folders

    /** [folder] null → create a new folder in the app's cell. */
    private fun moveToFolder(app: AppItem, folder: FolderItem?) {
        if (folder == null) {
            Dialogs.rename(this, "Folder", title = "New folder") { name ->
                val f = FolderItem(model.newId(), name, mutableListOf(app.component))
                f.pageX = app.pageX; f.pageY = app.pageY; f.cellX = app.cellX; f.cellY = app.cellY; f.z = app.z
                model.items.remove(app)
                model.items += f
                saveAndSync()
            }
        } else {
            folder.apps += app.component
            model.items.remove(app)
            saveAndSync()
        }
    }

    private fun ungroup(folder: FolderItem) {
        model.items.remove(folder)
        val apps = folder.apps.map { AppItem(model.newId(), it) }
        model.items += apps
        val (w, h) = workspace.contentSizeDp()
        model.placeItems(apps, w, h, workspace.curX, workspace.curY)
        saveAndSync()
    }

    // ================================================================= widgets

    private fun addWidget(info: AppWidgetProviderInfo) {
        val id = widgetHost.allocateAppWidgetId()
        pendingWidgetId = id
        pendingWidgetInfo = info
        if (widgetManager.bindAppWidgetIdIfAllowed(id, info.profile, info.provider, null)) {
            configureOrAdd()
        } else {
            val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile)
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(intent, REQ_BIND)
            } catch (e: ActivityNotFoundException) {
                abortPendingWidget(); toast("Widget binding not supported")
            }
        }
    }

    private fun configureOrAdd() {
        val info = pendingWidgetInfo ?: return
        val optional = Build.VERSION.SDK_INT >= 31 &&
                (info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL) != 0
        if (info.configure != null && !optional) {
            try {
                widgetHost.startAppWidgetConfigureActivityForResult(this, pendingWidgetId, 0, REQ_CONFIGURE, null)
                return
            } catch (e: Exception) { /* fall through and add unconfigured */ }
        }
        completeAddWidget()
    }

    private fun completeAddWidget() {
        val info = pendingWidgetInfo ?: widgetManager.getAppWidgetInfo(pendingWidgetId)
        val id = pendingWidgetId
        pendingWidgetId = -1; pendingWidgetInfo = null
        if (info == null || id == -1) return
        val d = resources.displayMetrics.density
        val (pw, ph) = workspace.contentSizeDp()
        val item = WidgetItem(model.newId(), id, info.provider.flattenToString())
        item.w = (info.minWidth / d).coerceAtLeast(110f).coerceAtMost(pw)
        item.h = (info.minHeight / d).coerceAtLeast(MIN_WIDGET_DP).coerceAtMost(ph)
        val place = pendingPlacement
        pendingPlacement = null
        if (place != null) {
            // Dropped from the widget picker: use the drop position and size.
            item.w = place.w.coerceIn(MIN_WIDGET_DP, pw); item.h = place.h.coerceIn(MIN_WIDGET_DP, ph)
            item.x = place.x.coerceIn(0f, pw - item.w); item.y = place.y.coerceIn(0f, ph - item.h)
            item.pageX = place.pageX; item.pageY = place.pageY
        } else {
            item.x = (pw - item.w) / 2; item.y = (ph - item.h) / 2
            item.pageX = workspace.curX; item.pageY = workspace.curY
        }
        model.bringToFront(item)
        model.items += item
        saveAndSync()
    }

    private fun abortPendingWidget() {
        if (pendingWidgetId != -1) widgetHost.deleteAppWidgetId(pendingWidgetId)
        pendingWidgetId = -1; pendingWidgetInfo = null; pendingPlacement = null
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_BIND -> if (resultCode == RESULT_OK) configureOrAdd() else abortPendingWidget()
            REQ_CONFIGURE -> if (resultCode == RESULT_OK) completeAddWidget() else abortPendingWidget()
            REQ_ICON_IMAGE -> {
                val uri = data?.data
                if (resultCode == RESULT_OK && uri != null) onIconImagePicked(uri) else pendingIconComponent = null
            }
        }
    }

    private fun cleanupOrphanWidgets() {
        val known = model.widgets().mapTo(HashSet()) { it.appWidgetId }
        for (id in widgetHost.appWidgetIds) {
            if (id !in known && id != pendingWidgetId) widgetHost.deleteAppWidgetId(id)
        }
    }

    private fun removeDeadWidgets() {
        val dead = model.widgets().filter { widgetManager.getAppWidgetInfo(it.appWidgetId) == null }
        for (w in dead) { model.items.remove(w); widgetHost.deleteAppWidgetId(w.appWidgetId) }
    }

    // ---------------------------------------------------------------- free resize

    private fun startResize(item: WidgetItem) {
        finishResize()
        val v = workspace.itemViews[item.id] ?: return
        val page = v.parent as? PageView ?: return
        workspace.snapTo(item.pageX, item.pageY)
        val d = resources.displayMetrics.density
        val initial = Rect(v.left - page.paddingLeft, v.top - page.paddingTop,
            v.right - page.paddingLeft, v.bottom - page.paddingTop)
        val frame = ResizeFrame(this, initial,
            onChange = { r ->
                item.x = r.left / d; item.y = r.top / d
                item.w = r.width() / d; item.h = r.height() / d
                page.requestLayout()
            },
            onDone = { finishResize() })
        resizeFrame = frame
        resizeItem = item
        workspace.locked = true
        page.addView(frame)
    }

    private fun finishResize() {
        val frame = resizeFrame ?: return
        (frame.parent as? PageView)?.removeView(frame)
        resizeFrame = null
        workspace.locked = false
        resizeItem?.let { updateWidgetSize(it) }
        resizeItem = null
        save()
    }

    // ================================================================= misc actions

    private fun applySettings() {
        val (w, h) = workspace.contentSizeDp()
        model.normalize(w, h)
        root.requestApplyInsets()
        applyColorMode()
        saveAndSync()
    }

    /** Day/night: launcher colours, dialogs/menus and status/navigation bar icon colour. */
    private fun applyColorMode() {
        Palette.night = Palette.resolve(this, model.config.colorMode)
        val lightBars = !Palette.night
        if (Build.VERSION.SDK_INT >= 30) {
            val flags = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (lightBars) flags else 0, flags)
        } else {
            @Suppress("DEPRECATION")
            var v = window.decorView.systemUiVisibility
            @Suppress("DEPRECATION")
            v = if (lightBars) v or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                else v and (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR).inv()
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = v
        }
        indicator.invalidate()
    }

    /** uiMode is in configChanges, so a system day/night switch lands here instead of recreating. */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (model.config.colorMode == "system") {
            val was = Palette.night
            applyColorMode()
            if (was != Palette.night) { openPanel?.cancel(); workspace.sync() }
        }
    }

    /** Page that Home returns to (and that Sheaf opens on). */
    private fun setDefaultPage(px: Int, py: Int) {
        model.config.homeX = px
        model.config.homeY = py
        save()
        onPageChanged(workspace.curX, workspace.curY)
        toast("Default page set (${px + 1}, ${py + 1})")
    }

    // ================================================================= panels & external drags

    fun pageContentSizeDp() = workspace.contentSizeDp()

    fun showPanel(panel: DragSourcePanel) {
        openPanel?.cancel()
        val prev = panel.onClosed
        panel.onClosed = { prev(); if (openPanel === panel) openPanel = null }
        openPanel = panel
        root.addView(panel, root.indexOfChild(dragLayer), FrameLayout.LayoutParams(-1, -1))
    }

    /** Long-press-drag of an app out of an open folder panel. */
    fun startFolderAppDrag(folder: FolderItem, component: String, rawX: Float, rawY: Float): Boolean {
        if (isLayoutLocked()) { toast("Layout is locked"); return false }
        val page = workspace.currentPage ?: return false
        val item = AppItem(model.newId(), component)
        val v = createItemView(item)
        v.tag = item
        bindItemView(item, v)
        dragSourceFolder = folder
        val ok = workspace.beginExternalDrag(item, v, page.cellW.toInt(), page.cellH.toInt(), rawX, rawY)
        if (!ok) dragSourceFolder = null
        return ok
    }

    /** Long-press-drag of a widget preview out of the widget picker. */
    fun startWidgetDrag(info: AppWidgetProviderInfo, preview: View, rawX: Float, rawY: Float): Boolean {
        val (w, h) = Panels.defaultWidgetSizeDp(this, info)
        val d = resources.displayMetrics.density
        val item = WidgetItem(-1, -1, info.provider.flattenToString()).apply { this.w = w; this.h = h }
        preview.tag = item
        dragWidgetInfo = info
        val ok = workspace.beginExternalDrag(item, preview, (w * d).toInt(), (h * d).toInt(), rawX, rawY)
        if (!ok) dragWidgetInfo = null
        return ok
    }

    fun externalDragMove(rawX: Float, rawY: Float) = workspace.externalDragMove(rawX, rawY)
    fun externalDragEnd(cancelled: Boolean) = workspace.externalDragEnd(cancelled)

    override fun onExternalDrop(item: HomeItem, intoFolder: FolderItem?, cancelled: Boolean) {
        val source = dragSourceFolder
        val widgetInfo = dragWidgetInfo
        dragSourceFolder = null; dragWidgetInfo = null

        if (item is AppItem && source != null) {
            if (cancelled || intoFolder === source) return // stays in its folder
            source.apps.remove(item.component)
            if (intoFolder != null) {
                intoFolder.apps += item.component
            } else {
                model.bringToFront(item)
                model.items += item
            }
            if (source.apps.isEmpty()) model.items.remove(source)
            saveAndSync()
        } else if (item is WidgetItem && widgetInfo != null) {
            if (cancelled) return
            pendingPlacement = item
            addWidget(widgetInfo)
        }
    }

    // ================================================================= app menu & shortcuts

    /**
     * Long-press menu for an app: its shortcuts first, then actions.
     * [homeItem] is set for icons on the home screen (adds z-order / folder / gestures).
     */
    fun showAppMenu(component: String, anchor: View, homeItem: AppItem?, onAction: () -> Unit = {}) {
        val pkg = ComponentName.unflattenFromString(component)?.packageName ?: return
        val pm = PopupMenu(Palette.menuContext(this), anchor)
        val m = pm.menu
        val list = shortcuts.forPackage(pkg).take(MAX_MENU_SHORTCUTS)
        list.forEachIndexed { i, sc ->
            m.add(1, M_SHORTCUT_BASE + i, i, shortcuts.label(sc)).icon = shortcuts.menuIcon(sc)
        }
        if (Build.VERSION.SDK_INT >= 29 && list.isNotEmpty()) pm.setForceShowIcon(true)
        var o = 100
        val unlocked = !isLayoutLocked()
        if (homeItem != null) {
            if (unlocked) {
                m.add(2, M_FRONT, o++, "Bring to front"); m.add(2, M_BACK, o++, "Send to back")
                m.add(2, M_FOLDER, o++, "Move to folder…")
            }
            m.add(2, M_GESTURES, o++, "Swipe gestures…")
        }
        m.add(2, M_EDIT, o++, "Edit icon & name…")
        m.add(2, M_INFO, o++, "App info")
        if (unlocked) m.add(2, M_HIDE, o++, "Hide app")
        m.add(2, M_UNINSTALL, o, "Uninstall")
        pm.setOnMenuItemClickListener { mi ->
            val id = mi.itemId
            if (id >= M_SHORTCUT_BASE) {
                val sc = list[id - M_SHORTCUT_BASE]
                startShortcut(sc.`package`, sc.id, anchor)
            } else when (id) {
                M_INFO -> openAppInfo(component)
                M_UNINSTALL -> uninstall(component)
                M_EDIT -> Dialogs.editApp(this, component)
                M_HIDE -> {
                    setHidden(model.hidden + component)
                    toast("${labelFor(component)} hidden · unhide in Home screen settings")
                }
                M_GESTURES -> Dialogs.gestures(this, component)
                else -> homeItem?.let { onItemMenu(it, anchor, id) }
            }
            onAction()
            true
        }
        pm.show()
    }

    fun startShortcut(pkg: String, id: String, from: View?) {
        val bounds = from?.let { v ->
            val l = IntArray(2); v.getLocationOnScreen(l); Rect(l[0], l[1], l[0] + v.width, l[1] + v.height)
        }
        val opts = from?.let { ActivityOptions.makeScaleUpAnimation(it, 0, 0, it.width, it.height).toBundle() }
        if (!shortcuts.start(pkg, id, bounds, opts)) toast("Can't open shortcut")
    }

    // ================================================================= icon gestures

    override fun isLayoutLocked() = model.config.layoutLocked

    override fun hasIconGesture(item: AppItem, dir: String) = model.gesture(item.component, dir) != null

    override fun onIconGesture(item: AppItem, dir: String, view: View) {
        model.gesture(item.component, dir)?.let { runGesture(it, view) }
    }

    fun gestureFor(component: String, dir: String) = model.gesture(component, dir)

    fun setGesture(component: String, dir: String, spec: String?) {
        model.setGesture(component, dir, spec)
        save()
    }

    /** Executes "app:…", "shortcut:pkg|id" or "sys:…". */
    fun runGesture(spec: String, from: View?) {
        val kind = spec.substringBefore(':')
        val arg = spec.substringAfter(':')
        when (kind) {
            "app" -> if (from != null) launch(arg, from) else launch(arg, root)
            "shortcut" -> startShortcut(arg.substringBefore('|'), arg.substringAfter('|'), from)
            "sys" -> HomeActions.perform(this, HomeAction.of(arg))
        }
    }

    /** Human-readable description of a gesture spec. */
    fun describeGesture(spec: String?): String {
        if (spec == null) return "Nothing"
        val arg = spec.substringAfter(':')
        return when (spec.substringBefore(':')) {
            "app" -> "Open " + labelFor(arg)
            "shortcut" -> {
                val pkg = arg.substringBefore('|')
                val sc = shortcuts.find(pkg, arg.substringAfter('|'))
                val app = apps.values.firstOrNull { it.packageName == pkg }?.label ?: pkg
                if (sc != null) "$app: ${shortcuts.label(sc)}" else "$app shortcut (unavailable)"
            }
            "sys" -> HomeAction.of(arg).label
            else -> "Nothing"
        }
    }

    // ================================================================= hidden apps

    val hiddenApps: Set<String> get() = model.hidden

    /**
     * Applies a new hidden set: newly hidden apps leave the home screen and folders
     * (empty folders are removed); unhidden apps go to free cells starting at the current page.
     */
    fun setHidden(newHidden: Set<String>) {
        val added = newHidden - model.hidden
        val removed = model.hidden - newHidden
        model.hidden.clear()
        model.hidden += newHidden

        if (added.isNotEmpty()) {
            model.items.removeAll { it is AppItem && it.component in added }
            for (f in model.folders()) {
                f.apps.removeAll { it in added }
                if (f.apps.isEmpty()) model.items.remove(f)
            }
        }
        val back = removed.filter { it in apps }.map { AppItem(model.newId(), it) }
        if (back.isNotEmpty()) {
            model.items += back
            val (w, h) = workspace.contentSizeDp()
            model.placeItems(back, w, h, workspace.curX, workspace.curY)
        }
        saveAndSync()
    }

    private fun openAppInfo(component: String) {
        val pkg = ComponentName.unflattenFromString(component)?.packageName ?: return
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun uninstall(component: String) {
        val pkg = ComponentName.unflattenFromString(component)?.packageName ?: return
        try {
            @Suppress("DEPRECATION")
            startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")))
        } catch (e: Exception) { toast("Can't uninstall") }
    }

    fun save() {
        pruneFolderIcons()
        store.save(model)
    }

    // ---------------------------------------------------------------- folder icon & name

    fun folderIconKey(folder: FolderItem) = "folder:${folder.id}"

    /** Custom icon if set, otherwise the folder-style 2×2 preview of its apps. */
    fun bindFolderIcon(folder: FolderItem, view: AppIconView) {
        val custom = customIcon(folderIconKey(folder))
        if (custom != null) {
            view.folderIcons = null
            view.icon = custom
        } else {
            view.folderIcons = folder.apps.mapNotNull { iconFor(it) }
        }
    }

    fun renameFolder(folder: FolderItem, name: String) {
        folder.title = name.ifBlank { "Folder" }
        saveAndSync()
    }

    /** Re-opens the icon & name editor for an app component or a folder key. */
    fun reopenEditor(key: String) {
        if (key.startsWith("folder:")) {
            val id = key.removePrefix("folder:").toLongOrNull()
            model.folders().firstOrNull { it.id == id }?.let { Dialogs.editFolder(this, it) }
        } else {
            Dialogs.editApp(this, key)
        }
    }

    /** Drops icon overrides of folders that no longer exist (and their image files). */
    private fun pruneFolderIcons() {
        val live = model.folders().mapTo(HashSet()) { folderIconKey(it) }
        val dead = model.customIcons.keys.filter { it.startsWith("folder:") && it !in live }
        for (k in dead) {
            val spec = model.customIcons.remove(k) ?: continue
            if (spec.startsWith("file:")) {
                customIconCache.remove(spec)
                File(customIconDir, spec.removePrefix("file:")).delete()
            }
        }
    }

    fun saveAndSync() {
        save()
        workspace.sync()
    }

    fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val WIDGET_HOST_ID = 0x0A11
        private const val REQ_BIND = 11
        private const val REQ_CONFIGURE = 12
        private const val REQ_RECONFIGURE = 13
        private const val STATE_PENDING_WIDGET = "pending_widget"
        private const val STATE_PLACEMENT = "pending_placement"
        private const val MAX_MENU_SHORTCUTS = 5
        private const val M_GESTURES = 13
        private const val M_HIDE = 14
        private const val M_EDIT = 15
        private const val REQ_ICON_IMAGE = 14
        private const val STATE_ICON_COMPONENT = "pending_icon_component"
        private const val ICON_PX = 256
        private const val CROP_MAX_PX = 2048
        private const val M_SHORTCUT_BASE = 1000
        const val MIN_WIDGET_DP = 40f

        private const val M_FRONT = 1; private const val M_BACK = 2; private const val M_FOLDER = 3
        private const val M_INFO = 4; private const val M_UNINSTALL = 5; private const val M_OPEN = 6
        private const val M_RENAME = 7; private const val M_UNGROUP = 8; private const val M_RESIZE = 9
        private const val M_SIZE = 10; private const val M_RECONFIGURE = 11; private const val M_REMOVE = 12
    }
}
