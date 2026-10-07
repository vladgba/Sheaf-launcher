package x.vladgba.sheaflauncher.model

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max

/**
 * Everything that lives on a home screen page.
 *
 * Pages form a 2D matrix: [pageX] is the column (left/right swipe),
 * [pageY] is the row (up/down swipe). [z] is the stacking order inside a page
 * — higher z is drawn on top and receives touches first.
 */
sealed class HomeItem(val id: Long) {
    var pageX = 0
    var pageY = 0
    var z = 0
}

/** Apps and folders snap to the icon grid. Several may share a cell (overlap). */
sealed class GridItem(id: Long) : HomeItem(id) {
    var cellX = 0
    var cellY = 0

    /** Transient: true while waiting for [WorkspaceModel.placeItems]. */
    var needsPlacement = false
}

class AppItem(id: Long, val component: String) : GridItem(id)

class FolderItem(id: Long, var title: String, val apps: MutableList<String>) : GridItem(id)

/** Widgets ignore the grid: free position and size, stored in dp relative to the page content area. */
class WidgetItem(id: Long, val appWidgetId: Int, val provider: String) : HomeItem(id) {
    var x = 0f
    var y = 0f
    var w = 0f
    var h = 0f
}

class LauncherConfig {
    var columns = 5        // icon grid, horizontal cells
    var rows = 6           // icon grid, vertical cells
    var pagesX = 3         // page matrix width  (left/right swipe)
    var pagesY = 2         // page matrix height (up/down swipe)
    var showLabels = true
    var iconScale = 100    // percent
    var homeX = 0          // default page: where Home returns to
    var folderColumns = 4  // folder popup grid
    var folderRows = 4     // rows visible before the folder scrolls
    var colorMode = "system" // system | day | night
    var layoutLocked = false // no moving, resizing, adding or removing items
    var homeAction = "none" // Home pressed on the default page: none | lock | notifications | quick_settings
    var homeY = 0

    fun toJson(): JSONObject = JSONObject()
        .put("columns", columns).put("rows", rows)
        .put("pagesX", pagesX).put("pagesY", pagesY)
        .put("showLabels", showLabels).put("iconScale", iconScale)
        .put("homeX", homeX).put("homeY", homeY)
        .put("folderColumns", folderColumns).put("folderRows", folderRows)
        .put("homeAction", homeAction).put("layoutLocked", layoutLocked).put("colorMode", colorMode)

    fun readJson(o: JSONObject) {
        columns = o.optInt("columns", columns).coerceIn(MIN_GRID, MAX_GRID)
        rows = o.optInt("rows", rows).coerceIn(MIN_GRID, MAX_GRID)
        pagesX = o.optInt("pagesX", pagesX).coerceIn(1, MAX_PAGES)
        pagesY = o.optInt("pagesY", pagesY).coerceIn(1, MAX_PAGES)
        showLabels = o.optBoolean("showLabels", showLabels)
        iconScale = o.optInt("iconScale", iconScale).coerceIn(50, 150)
        folderColumns = o.optInt("folderColumns", folderColumns).coerceIn(MIN_FOLDER_GRID, MAX_FOLDER_GRID)
        folderRows = o.optInt("folderRows", folderRows).coerceIn(MIN_FOLDER_GRID, MAX_FOLDER_GRID)
        homeAction = o.optString("homeAction", homeAction)
        layoutLocked = o.optBoolean("layoutLocked", layoutLocked)
        colorMode = o.optString("colorMode", colorMode)
        homeX = o.optInt("homeX", homeX).coerceIn(0, pagesX - 1)
        homeY = o.optInt("homeY", homeY).coerceIn(0, pagesY - 1)
    }

    companion object {
        const val MIN_GRID = 2
        const val MAX_GRID = 14
        const val MAX_PAGES = 12
        const val MIN_FOLDER_GRID = 1
        const val MAX_FOLDER_GRID = 8
    }
}

class WorkspaceModel {
    val config = LauncherConfig()
    val items = mutableListOf<HomeItem>()
    private var nextId = 1L

    fun newId() = nextId++

    /**
     * Swipe actions per app, keyed by component then direction (up/right/down/left).
     * Keyed by component (not item) so they survive moving the app in/out of folders.
     * Values: "app:<component>", "shortcut:<package>|<shortcut id>", "sys:<HomeAction key>".
     */
    val gestures = HashMap<String, HashMap<String, String>>()

    /** Components hidden from the home screen (still launchable via swipe gestures). */
    val hidden = HashSet<String>()

    /** Per-app label override (component → text). */
    val customLabels = HashMap<String, String>()
    /** Per-app icon override: "file:<name in files/custom_icons>" or "app:<component whose icon to use>". */
    val customIcons = HashMap<String, String>()

    fun gesture(component: String, dir: String): String? = gestures[component]?.get(dir)

    fun setGesture(component: String, dir: String, spec: String?) {
        if (spec == null) {
            gestures[component]?.remove(dir)
            if (gestures[component]?.isEmpty() == true) gestures.remove(component)
        } else {
            gestures.getOrPut(component) { HashMap() }[dir] = spec
        }
    }

    fun itemsOn(px: Int, py: Int) = items.filter { it.pageX == px && it.pageY == py }

    fun topZ(px: Int, py: Int) = itemsOn(px, py).maxOfOrNull { it.z } ?: 0
    fun bottomZ(px: Int, py: Int) = itemsOn(px, py).minOfOrNull { it.z } ?: 0

    fun bringToFront(item: HomeItem) {
        item.z = topZ(item.pageX, item.pageY) + 1
    }

    fun sendToBack(item: HomeItem) {
        item.z = bottomZ(item.pageX, item.pageY) - 1
    }

    fun widgets() = items.filterIsInstance<WidgetItem>()
    fun folders() = items.filterIsInstance<FolderItem>()

    fun folderAt(px: Int, py: Int, cx: Int, cy: Int): FolderItem? = items
        .filterIsInstance<FolderItem>()
        .filter { it.pageX == px && it.pageY == py && it.cellX == cx && it.cellY == cy }
        .maxByOrNull { it.z }

    /**
     * Makes the model match the installed apps: removes uninstalled apps
     * (also from folders), adds new ones to free cells. Every installed app
     * ends up on the home screen, either directly or inside a folder.
     *
     * @param installed components sorted in the order new apps should be placed
     * @return true if anything changed
     */
    fun syncApps(installed: List<String>, pageWdp: Float, pageHdp: Float): Boolean {
        val installedSet = installed.toHashSet()
        var changed = false

        // Drop uninstalled apps and duplicates.
        val seen = HashSet<String>()
        val it = items.iterator()
        while (it.hasNext()) {
            val item = it.next()
            if (item is AppItem && (item.component !in installedSet || !seen.add(item.component))) {
                it.remove(); changed = true
            }
        }
        for (f in folders()) {
            val before = f.apps.size
            f.apps.removeAll { c -> c !in installedSet || !seen.add(c) }
            if (f.apps.size != before) changed = true
            if (f.apps.isEmpty()) { items.remove(f); changed = true }
        }

        // Add apps that are not on the home screen yet.
        for (c in installed) {
            if (c !in seen) {
                items += AppItem(newId(), c).apply { needsPlacement = true }
                seen += c
                changed = true
            }
        }

        if (normalize(pageWdp, pageHdp)) changed = true
        return changed
    }

    /**
     * Repairs items left outside the grid / page matrix (e.g. after the user
     * shrinks the grid) and places everything flagged [GridItem.needsPlacement].
     */
    fun normalize(pageWdp: Float, pageHdp: Float): Boolean {
        val c = config
        var changed = false
        // Default page must stay inside the page matrix if it shrank.
        val hx = c.homeX.coerceIn(0, c.pagesX - 1)
        val hy = c.homeY.coerceIn(0, c.pagesY - 1)
        if (hx != c.homeX || hy != c.homeY) { c.homeX = hx; c.homeY = hy; changed = true }
        for (item in items) {
            when (item) {
                is GridItem -> if (item.pageX !in 0 until c.pagesX || item.pageY !in 0 until c.pagesY ||
                    item.cellX !in 0 until c.columns || item.cellY !in 0 until c.rows
                ) item.needsPlacement = true

                is WidgetItem -> {
                    val px = item.pageX.coerceIn(0, c.pagesX - 1)
                    val py = item.pageY.coerceIn(0, c.pagesY - 1)
                    if (px != item.pageX || py != item.pageY) {
                        item.pageX = px; item.pageY = py; changed = true
                    }
                }
            }
        }
        val pending = items.filterIsInstance<GridItem>().filter { it.needsPlacement }
        if (pending.isNotEmpty()) {
            placeItems(pending, pageWdp, pageHdp)
            changed = true
        }
        return changed
    }

    /**
     * Puts each item into the first free cell, scanning pages row by row
     * (left→right, then the next row of pages), starting at page
     * ([startX], [startY]) and wrapping around. A cell is busy if an
     * app/folder sits in it or a widget covers its centre. When everything is
     * full a new column of pages is added, so no app is ever left off the home screen.
     */
    fun placeItems(
        toPlace: List<GridItem>, pageWdp: Float, pageHdp: Float,
        startX: Int = 0, startY: Int = 0,
    ) {
        val c = config
        val pendingIds = toPlace.map { it.id }.toHashSet()
        val occupancy = HashMap<Int, BooleanArray>()

        fun grid(px: Int, py: Int): BooleanArray = occupancy.getOrPut(py * 1000 + px) {
            val g = BooleanArray(c.columns * c.rows)
            val cw = if (pageWdp > 0) pageWdp / c.columns else 1f
            val ch = if (pageHdp > 0) pageHdp / c.rows else 1f
            for (item in itemsOn(px, py)) {
                when (item) {
                    is GridItem -> if (item.id !in pendingIds &&
                        item.cellX in 0 until c.columns && item.cellY in 0 until c.rows
                    ) g[item.cellY * c.columns + item.cellX] = true

                    is WidgetItem -> for (cy in 0 until c.rows) for (cx in 0 until c.columns) {
                        val mx = (cx + 0.5f) * cw
                        val my = (cy + 0.5f) * ch
                        if (mx >= item.x && mx < item.x + item.w && my >= item.y && my < item.y + item.h)
                            g[cy * c.columns + cx] = true
                    }
                }
            }
            g
        }

        for (item in toPlace) {
            var placed = false
            while (!placed) {
                val total = c.pagesX * c.pagesY
                val start = startY.coerceIn(0, c.pagesY - 1) * c.pagesX + startX.coerceIn(0, c.pagesX - 1)
                search@ for (n in 0 until total) {
                    val l = (start + n) % total
                    val px = l % c.pagesX
                    val py = l / c.pagesX
                    val g = grid(px, py)
                    val idx = g.indexOfFirst { !it }
                    if (idx >= 0) {
                        g[idx] = true
                        item.pageX = px; item.pageY = py
                        item.cellX = idx % c.columns; item.cellY = idx / c.columns
                        item.z = topZ(px, py) + 1
                        item.needsPlacement = false
                        placed = true
                        break@search
                    }
                }
                if (!placed) c.pagesX++ // out of room: grow the page matrix
            }
        }
    }

    // ---------------------------------------------------------------- JSON

    fun toJson(): JSONObject {
        val arr = JSONArray()
        for (item in items) {
            val o = JSONObject()
                .put("id", item.id).put("px", item.pageX).put("py", item.pageY).put("z", item.z)
            when (item) {
                is AppItem -> o.put("type", "app").put("component", item.component)
                    .put("cx", item.cellX).put("cy", item.cellY)

                is FolderItem -> o.put("type", "folder").put("title", item.title)
                    .put("apps", JSONArray(item.apps))
                    .put("cx", item.cellX).put("cy", item.cellY)

                is WidgetItem -> o.put("type", "widget").put("widgetId", item.appWidgetId)
                    .put("provider", item.provider)
                    .put("x", item.x.toDouble()).put("y", item.y.toDouble())
                    .put("w", item.w.toDouble()).put("h", item.h.toDouble())
            }
            arr.put(o)
        }
        return JSONObject().put("version", 1).put("nextId", nextId)
            .put("config", config.toJson()).put("items", arr)
            .put("hidden", JSONArray(hidden.sorted()))
            .put("customLabels", JSONObject(customLabels as Map<*, *>))
            .put("customIcons", JSONObject(customIcons as Map<*, *>))
            .put("gestures", JSONObject().apply {
                for ((c, dirs) in gestures) put(c, JSONObject(dirs as Map<*, *>))
            })
    }

    companion object {
        fun fromJson(root: JSONObject): WorkspaceModel {
            val m = WorkspaceModel()
            root.optJSONObject("config")?.let { m.config.readJson(it) }
            root.optJSONArray("hidden")?.let { h -> for (i in 0 until h.length()) m.hidden += h.getString(i) }
            root.optJSONObject("customLabels")?.let { o -> for (k in o.keys()) m.customLabels[k] = o.getString(k) }
            root.optJSONObject("customIcons")?.let { o -> for (k in o.keys()) m.customIcons[k] = o.getString(k) }
            root.optJSONObject("gestures")?.let { g ->
                for (c in g.keys()) {
                    val dirs = g.optJSONObject(c) ?: continue
                    val map = HashMap<String, String>()
                    for (d in dirs.keys()) map[d] = dirs.getString(d)
                    if (map.isNotEmpty()) m.gestures[c] = map
                }
            }
            val arr = root.optJSONArray("items") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.getLong("id")
                val item: HomeItem = when (o.optString("type")) {
                    "app" -> AppItem(id, o.getString("component")).apply {
                        cellX = o.optInt("cx"); cellY = o.optInt("cy")
                    }

                    "folder" -> {
                        val a = o.optJSONArray("apps") ?: JSONArray()
                        FolderItem(id, o.optString("title", "Folder"),
                            MutableList(a.length()) { a.getString(it) }).apply {
                            cellX = o.optInt("cx"); cellY = o.optInt("cy")
                        }
                    }

                    "widget" -> WidgetItem(id, o.getInt("widgetId"), o.optString("provider")).apply {
                        x = o.optDouble("x", 0.0).toFloat(); y = o.optDouble("y", 0.0).toFloat()
                        w = o.optDouble("w", 100.0).toFloat(); h = o.optDouble("h", 100.0).toFloat()
                    }

                    else -> continue
                }
                item.pageX = o.optInt("px"); item.pageY = o.optInt("py"); item.z = o.optInt("z")
                m.items += item
            }
            m.nextId = max(root.optLong("nextId", 1L), (m.items.maxOfOrNull { it.id } ?: 0L) + 1)
            return m
        }
    }
}
