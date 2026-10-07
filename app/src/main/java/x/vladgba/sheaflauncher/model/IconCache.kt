package x.vladgba.sheaflauncher.model

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.util.AtomicFile
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

/**
 * Keeps app icons + labels so the home screen is never drawn with empty icons.
 *
 *  - Memory: the last loaded app list survives activity recreation
 *    (theme change, process kept alive), so the icons are there immediately.
 *  - Disk: icons are stored as PNGs in files/icon_cache with an index
 *    (label + package update time). After a cold start / reboot they are
 *    shown immediately, and the live icons from PackageManager replace them
 *    once the background reload finishes.
 *
 * Only icons whose package changed (or that are missing) are re-encoded.
 */
class IconCache(context: Context) {

    private val res = context.resources
    private val dir = File(context.filesDir, "icon_cache").apply { mkdirs() }
    private val index = AtomicFile(File(dir, "index.json"))
    /** Bitmap edge: big enough for the 150% icon size setting on large grids. */
    private val sizePx = (96 * res.displayMetrics.density).roundToInt().coerceIn(96, 384)

    /** Instant: last list loaded in this process, or empty. */
    fun memory(): Map<String, AppInfo> = memoryCache

    /** Reads the disk cache. Call on the main thread before the first bind (fast: small PNGs). */
    fun loadDisk(): Map<String, AppInfo> {
        val idx = readIndex() ?: return emptyMap()
        val out = HashMap<String, AppInfo>()
        for (component in idx.keys()) {
            val e = idx.optJSONObject(component) ?: continue
            val f = File(dir, e.optString("file"))
            val bmp = try { BitmapFactory.decodeFile(f.path) } catch (_: Exception) { null } ?: continue
            out[component] = AppInfo(component, e.optString("label"), BitmapDrawable(res, bmp), e.optLong("updated"))
        }
        return out
    }

    /** Remembers the fresh list and writes changed icons to disk. Call off the main thread. */
    fun store(apps: List<AppInfo>) {
        memoryCache = apps.associateBy { it.component }
        val old = readIndex() ?: JSONObject()
        val idx = JSONObject()
        val keep = HashSet<String>()
        for (a in apps) {
            val name = fileName(a.component)
            keep += name
            val prev = old.optJSONObject(a.component)
            val file = File(dir, name)
            val fresh = prev != null && prev.optLong("updated") == a.updated && a.updated != 0L && file.exists()
            if (!fresh && !writePng(a, file)) continue
            idx.put(a.component, JSONObject()
                .put("label", a.label).put("file", name).put("updated", a.updated))
        }
        // Drop icons of uninstalled apps.
        dir.listFiles()?.forEach { if (it.name.endsWith(".png") && it.name !in keep) it.delete() }
        val out = try { index.startWrite() } catch (e: Exception) { return }
        try {
            out.write(idx.toString().toByteArray(Charsets.UTF_8))
            index.finishWrite(out)
        } catch (e: Exception) {
            index.failWrite(out)
        }
    }

    private fun writePng(a: AppInfo, file: File): Boolean = try {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val d = a.icon.constantState?.newDrawable(res)?.mutate() ?: a.icon
        d.setBounds(0, 0, sizePx, sizePx)
        d.draw(Canvas(bmp))
        val tmp = File(dir, file.name + ".tmp")
        FileOutputStream(tmp).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        tmp.renameTo(file)
    } catch (e: Exception) {
        Log.w("Sheaf", "icon cache write failed for ${a.component}", e)
        false
    }

    private fun readIndex(): JSONObject? = try {
        JSONObject(String(index.readFully(), Charsets.UTF_8))
    } catch (_: Exception) { null }

    private fun fileName(component: String) =
        component.replace(Regex("[^A-Za-z0-9._-]"), "_").take(180) + "_" +
            Integer.toHexString(component.hashCode()) + ".png"

    private companion object {
        @Volatile var memoryCache: Map<String, AppInfo> = emptyMap()
    }
}
