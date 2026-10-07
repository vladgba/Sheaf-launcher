package x.vladgba.sheaflauncher

import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.graphics.drawable.Drawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import x.vladgba.sheaflauncher.model.FolderItem
import x.vladgba.sheaflauncher.model.LauncherConfig
import x.vladgba.sheaflauncher.ui.AppIconView
import x.vladgba.sheaflauncher.ui.Palette
import x.vladgba.sheaflauncher.ui.dp
import x.vladgba.sheaflauncher.ui.dpi
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** All dialogs, built in code (the project has no layout XML or support libraries). */
object Dialogs {

    private fun builder(ctx: Context) = AlertDialog.Builder(ctx, Palette.dialogTheme)

    private fun textColor(ctx: Context): Int {
        val tv = TypedValue()
        ctx.theme.resolveAttribute(android.R.attr.textColorPrimary, tv, true)
        return if (tv.resourceId != 0) ctx.getColor(tv.resourceId) else tv.data
    }

    // ------------------------------------------------------------ settings

    fun settings(a: LauncherActivity, cfg: LauncherConfig, onApply: () -> Unit) {
        val b = builder(a)
        val ctx = b.context
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(a.dpi(24f), a.dpi(12f), a.dpi(24f), 0)
        }

        // Horizontal sliders: label + current value on one line, SeekBar below.
        class Slider(val bar: SeekBar, val min: Int) { val value get() = bar.progress + min }

        fun picker(label: String, min: Int, max: Int, value: Int): Slider {
            val head = LinearLayout(ctx).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, a.dpi(10f), 0, 0)
            }
            head.addView(TextView(ctx).apply { text = label; textSize = 15f },
                LinearLayout.LayoutParams(0, -2, 1f))
            val valueText = TextView(ctx).apply { textSize = 15f; text = value.coerceIn(min, max).toString() }
            head.addView(valueText)
            val bar = SeekBar(ctx).apply {
                this.max = max - min
                progress = value.coerceIn(min, max) - min
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                        valueText.text = (p + min).toString()
                    }
                    override fun onStartTrackingTouch(sb: SeekBar) {}
                    override fun onStopTrackingTouch(sb: SeekBar) {}
                })
            }
            col.addView(head)
            col.addView(bar, LinearLayout.LayoutParams(-1, -2))
            return Slider(bar, min)
        }

        val cols = picker("Grid columns", LauncherConfig.MIN_GRID, LauncherConfig.MAX_GRID, cfg.columns)
        val rows = picker("Grid rows", LauncherConfig.MIN_GRID, LauncherConfig.MAX_GRID, cfg.rows)
        val px = picker("Pages horizontally (← →)", 1, LauncherConfig.MAX_PAGES, cfg.pagesX)
        val py = picker("Pages vertically (↑ ↓)", 1, LauncherConfig.MAX_PAGES, cfg.pagesY)
        val scale = picker("Icon size %", 50, 150, cfg.iconScale)
        val fcols = picker("Folder columns", LauncherConfig.MIN_FOLDER_GRID, LauncherConfig.MAX_FOLDER_GRID, cfg.folderColumns)
        val frows = picker("Folder rows (then scroll)", LauncherConfig.MIN_FOLDER_GRID, LauncherConfig.MAX_FOLDER_GRID, cfg.folderRows)
        val labels = CheckBox(ctx).apply { text = "Show app labels"; isChecked = cfg.showLabels }
        col.addView(labels)

        // Color mode
        col.addView(TextView(ctx).apply {
            text = "Color mode"; textSize = 16f
            setPadding(0, a.dpi(16f), 0, a.dpi(4f))
        })
        val modes = listOf("system" to "Follow system", "day" to "Day", "night" to "Night")
        val modeGroup = RadioGroup(ctx).apply { orientation = RadioGroup.HORIZONTAL }
        for ((key, name) in modes) modeGroup.addView(RadioButton(ctx).apply {
            id = View.generateViewId(); text = name; tag = key
            isChecked = key == cfg.colorMode
            setPadding(0, 0, a.dpi(12f), 0)
        })
        col.addView(modeGroup)

        // Home button on the default page
        col.addView(TextView(ctx).apply {
            text = "Home button on default page"; textSize = 16f
            setPadding(0, a.dpi(16f), 0, a.dpi(4f))
        })
        val actions = HomeAction.entries
        val group = RadioGroup(ctx)
        actions.forEachIndexed { i, act ->
            group.addView(RadioButton(ctx).apply {
                id = View.generateViewId(); text = act.label; tag = act
                isChecked = act == HomeAction.of(cfg.homeAction)
            })
        }
        col.addView(group)
        val status = TextView(ctx).apply {
            textSize = 13f
            alpha = 0.75f
            text = "Accessibility service: " + (if (HomeActions.accessibilityOn()) "on" else "off") +
                    "   ·   Device admin: " + (if (HomeActions.adminOn(a)) "on" else "off")
        }
        col.addView(status)
        col.addView(Button(ctx, null, android.R.attr.borderlessButtonStyle).apply {
            text = "Hidden apps (${a.hiddenApps.size})…"
            setOnClickListener { hiddenApps(a) { text = "Hidden apps (${a.hiddenApps.size})…" } }
        })
        val links = LinearLayout(ctx)
        links.addView(Button(ctx, null, android.R.attr.borderlessButtonStyle).apply {
            text = "Accessibility…"; setOnClickListener { HomeActions.openAccessibilitySettings(a) }
        })
        links.addView(Button(ctx, null, android.R.attr.borderlessButtonStyle).apply {
            if (HomeActions.adminOn(a)) {
                text = "Remove device admin"
                setOnClickListener { HomeActions.removeAdmin(a); text = "Device admin removed"; isEnabled = false }
            } else {
                text = "Device admin…"; setOnClickListener { HomeActions.requestAdmin(a) }
            }
        })
        col.addView(links)

        b.setTitle("Home screen")
            .setView(ScrollView(ctx).apply { addView(col) })
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                cfg.columns = cols.value; cfg.rows = rows.value
                cfg.pagesX = px.value; cfg.pagesY = py.value
                cfg.iconScale = scale.value; cfg.showLabels = labels.isChecked
                cfg.folderColumns = fcols.value; cfg.folderRows = frows.value
                val picked = (0 until group.childCount).map { group.getChildAt(it) as RadioButton }
                    .firstOrNull { it.isChecked }?.tag as? HomeAction
                cfg.homeAction = (picked ?: HomeAction.NONE).key
                cfg.colorMode = (0 until modeGroup.childCount).map { modeGroup.getChildAt(it) as RadioButton }
                    .firstOrNull { it.isChecked }?.tag as? String ?: "system"
                onApply()
            }
            .show()
    }

    // ------------------------------------------------------------ widgets

    fun exactSize(a: LauncherActivity, w: Float, h: Float, onSet: (Float, Float) -> Unit) {
        val b = builder(a)
        val ctx = b.context
        fun field(hint: String, v: Float) = EditText(ctx).apply {
            this.hint = hint
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(v.roundToInt().toString())
            setSelectAllOnFocus(true)
        }
        val ew = field("Width (dp)", w)
        val eh = field("Height (dp)", h)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(a.dpi(24f), a.dpi(8f), a.dpi(24f), 0)
            addView(TextView(ctx).apply { text = "Width (dp)" }); addView(ew)
            addView(TextView(ctx).apply { text = "Height (dp)" }); addView(eh)
        }
        b.setTitle("Widget size").setView(col)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val nw = ew.text.toString().toFloatOrNull() ?: w
                val nh = eh.text.toString().toFloatOrNull() ?: h
                onSet(nw, nh)
            }
            .show()
    }

    // ------------------------------------------------------------ folders

    fun rename(a: LauncherActivity, current: String, title: String = "Rename folder", onSet: (String) -> Unit) {
        val b = builder(a)
        val e = EditText(b.context).apply { setText(current); setSelectAllOnFocus(true); setSingleLine() }
        val wrap = LinearLayout(b.context).apply {
            setPadding(a.dpi(24f), a.dpi(8f), a.dpi(24f), 0)
            addView(e, LinearLayout.LayoutParams(-1, -2))
        }
        b.setTitle(title).setView(wrap)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                onSet(e.text.toString().trim().ifEmpty { current })
            }
            .show()
        e.requestFocus()
    }

    /** Pick an existing folder, or null for "New folder". */
    fun chooseFolder(a: LauncherActivity, folders: List<FolderItem>, onPick: (FolderItem?) -> Unit) {
        val names = listOf("➕  New folder") + folders.map { "${it.title}  (page ${it.pageX + 1},${it.pageY + 1})" }
        builder(a).setTitle("Move to folder")
            .setItems(names.toTypedArray()) { _, i -> onPick(if (i == 0) null else folders[i - 1]) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------ custom icon & name

    /** Change an app's name and icon. Empty name = the app's own name. */
    fun editApp(a: LauncherActivity, component: String) = editItem(
        a, key = component,
        nameNow = a.customLabelOf(component) ?: "",
        hint = a.originalLabel(component),
        note = "Leave the name empty to use the app's own name.",
        resetLabel = "Reset icon",
        onName = { a.setCustomLabel(component, it) },
        preview = { v -> v.icon = a.iconFor(component) },
    )

    /** Same editor for folders; "reset" brings back the folder-style (app previews) icon. */
    fun editFolder(a: LauncherActivity, folder: FolderItem) = editItem(
        a, key = a.folderIconKey(folder),
        nameNow = folder.title,
        hint = "Folder",
        note = "",
        resetLabel = "Folder-style icon (default)",
        onName = { a.renameFolder(folder, it) },
        preview = { v -> a.bindFolderIcon(folder, v) },
    )

    /**
     * Shared icon & name editor. [key] identifies the icon override
     * (app component or folder key). Icon: picture (with crop), another app's icon, or reset.
     */
    private fun editItem(
        a: LauncherActivity, key: String, nameNow: String, hint: String, note: String,
        resetLabel: String, onName: (String) -> Unit, preview: (AppIconView) -> Unit,
    ) {
        val b = builder(a)
        val ctx = b.context
        val icon = AppIconView(ctx).apply { showLabel = false; isClickable = false; preview(this) }
        val name = EditText(ctx).apply {
            setSingleLine()
            this.hint = hint
            setText(nameNow)
            setSelectAllOnFocus(true)
        }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(a.dpi(24f), a.dpi(12f), a.dpi(24f), 0)
        }
        val top = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(icon, LinearLayout.LayoutParams(a.dpi(64f), a.dpi(64f)))
        top.addView(name, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = a.dpi(12f) })
        col.addView(top)
        if (note.isNotEmpty()) col.addView(TextView(ctx).apply {
            text = note; textSize = 12f; alpha = 0.7f
            setPadding(0, a.dpi(4f), 0, a.dpi(8f))
        })

        val dialog = b.setTitle("Edit icon & name")
            .setView(ScrollView(ctx).apply { addView(col) })
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Save") { _, _ -> onName(name.text.toString().trim()) }
            .create()

        /** Icon actions keep whatever name was typed so far. */
        fun iconAction(label: String, enabled: Boolean = true, act: () -> Unit) {
            col.addView(Button(ctx, null, android.R.attr.borderlessButtonStyle).apply {
                text = label
                isEnabled = enabled
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setOnClickListener {
                    onName(name.text.toString().trim())
                    dialog.dismiss()
                    act()
                }
            })
        }
        iconAction("Choose image…") { a.pickIconImage(key) }
        iconAction("Use another app's icon…") {
            pickApp(a, "Use icon of", onCancel = { a.reopenEditor(key) }) { c ->
                a.setCustomIcon(key, if (c == key) null else "app:$c")
                a.reopenEditor(key)
            }
        }
        iconAction(resetLabel, enabled = a.hasCustomIcon(key)) {
            a.setCustomIcon(key, null)
            a.reopenEditor(key)
        }
        dialog.show()
    }

    // ------------------------------------------------------------ hidden apps

    /** Checklist of all installed apps; checked = hidden from the home screen. */
    fun hiddenApps(a: LauncherActivity, onDone: () -> Unit = {}) {
        val list = a.apps.values.sortedBy { it.label.lowercase() }
        if (list.isEmpty()) { a.toast("Apps are still loading"); return }
        val checked = BooleanArray(list.size) { list[it].component in a.hiddenApps }
        builder(a).setTitle("Hidden apps")
            .setMultiChoiceItems(list.map { it.label }.toTypedArray(), checked) { _, i, on -> checked[i] = on }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                a.setHidden(list.indices.filter { checked[it] }.map { list[it].component }.toSet() +
                        // keep hidden entries for apps not currently installed
                        a.hiddenApps.filter { c -> list.none { it.component == c } })
                onDone()
            }
            .show()
    }

    // ------------------------------------------------------------ icon gestures

    /** Per-app swipe actions: one row per direction; tap a row to change it. */
    fun gestures(a: LauncherActivity, component: String) {
        val rows = GESTURE_DIRS.map { d -> "${gestureDirLabel(d)}:  ${a.describeGesture(a.gestureFor(component, d))}" }
        builder(a).setTitle("Swipe gestures · " + a.labelFor(component))
            .setItems(rows.toTypedArray()) { _, i -> chooseGesture(a, component, GESTURE_DIRS[i]) }
            .setPositiveButton("Done", null)
            .show()
    }

    private fun chooseGesture(a: LauncherActivity, component: String, dir: String) {
        val pkg = component.substringBefore('/')
        val ownShortcuts = a.shortcuts.forPackage(pkg)
        val options = mutableListOf<Pair<String, () -> Unit>>()
        val back = { gestures(a, component) }
        fun set(spec: String?) { a.setGesture(component, dir, spec); back() }

        options += "Nothing" to { set(null) }
        if (ownShortcuts.isNotEmpty()) options += "${a.labelFor(component)} shortcut…" to {
            pickShortcut(a, pkg, onCancel = back) { id -> set("shortcut:$pkg|$id") }
        }
        options += "Open app…" to { pickApp(a, "Open app", onCancel = back) { c -> set("app:$c") } }
        if (a.shortcuts.available) options += "Other app's shortcut…" to {
            pickApp(a, "Shortcut from", onCancel = back) { c ->
                val p = c.substringBefore('/')
                pickShortcut(a, p, onCancel = back) { id -> set("shortcut:$p|$id") }
            }
        }
        for (act in HomeAction.entries) if (act != HomeAction.NONE) options += act.label to { set("sys:${act.key}") }

        builder(a).setTitle(gestureDirLabel(dir))
            .setItems(options.map { it.first }.toTypedArray()) { _, i -> options[i].second() }
            .setNegativeButton(android.R.string.cancel) { _, _ -> back() }
            .show()
    }

    /** Icon + text rows for list dialogs. */
    private fun iconAdapter(ctx: Context, items: List<Pair<Drawable?, String>>) = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(p: Int) = items[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
            val row = (convert as? LinearLayout) ?: LinearLayout(ctx).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ctx.dpi(20f), ctx.dpi(8f), ctx.dpi(20f), ctx.dpi(8f))
                addView(ImageView(ctx), LinearLayout.LayoutParams(ctx.dpi(36f), ctx.dpi(36f)))
                addView(TextView(ctx).apply { setPadding(ctx.dpi(16f), 0, 0, 0); textSize = 15f },
                    LinearLayout.LayoutParams(0, -2, 1f))
            }
            (row.getChildAt(0) as ImageView).setImageDrawable(items[p].first)
            (row.getChildAt(1) as TextView).text = items[p].second
            return row
        }
    }

    fun pickApp(a: LauncherActivity, title: String, onCancel: () -> Unit = {}, onPick: (String) -> Unit) {
        val list = a.apps.values.sortedBy { it.label.lowercase() }
        val b = builder(a)
        b.setTitle(title)
            .setAdapter(iconAdapter(b.context, list.map { it.icon to it.label })) { _, i -> onPick(list[i].component) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> onCancel() }
            .show()
    }

    fun pickShortcut(a: LauncherActivity, pkg: String, onCancel: () -> Unit = {}, onPick: (String) -> Unit) {
        val list = a.shortcuts.forPackage(pkg)
        if (list.isEmpty()) { a.toast("This app has no shortcuts"); onCancel(); return }
        val b = builder(a)
        b.setTitle("Shortcut")
            .setAdapter(iconAdapter(b.context, list.map { a.shortcuts.icon(it) to a.shortcuts.label(it) })) { _, i ->
                onPick(list[i].id)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> onCancel() }
            .show()
    }
}
