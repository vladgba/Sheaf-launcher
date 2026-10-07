package x.vladgba.sheaflauncher

import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Process
import android.util.Log
import kotlin.math.roundToInt

/**
 * App shortcuts (the "New message", "Selfie", … actions apps publish).
 * Only the default home app may read them, so everything degrades to
 * "no shortcuts" when Sheaf isn't the default launcher.
 */
class Shortcuts(private val context: Context) {
    private val la = context.getSystemService(LauncherApps::class.java)

    val available: Boolean
        get() = try { la.hasShortcutHostPermission() } catch (_: Exception) { false }

    fun forPackage(pkg: String): List<ShortcutInfo> = query(pkg, null)

    fun find(pkg: String, id: String): ShortcutInfo? = query(pkg, listOf(id)).firstOrNull()

    private fun query(pkg: String, ids: List<String>?): List<ShortcutInfo> {
        if (!available) return emptyList()
        val q = LauncherApps.ShortcutQuery()
            .setPackage(pkg)
            .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
        if (ids != null) q.setShortcutIds(ids)
        return try {
            (la.getShortcuts(q, Process.myUserHandle()) ?: emptyList())
                .filter { it.isEnabled }
                // Manifest (static) shortcuts first, then dynamic, each by rank — same order as AOSP.
                .sortedWith(compareBy({ if (it.isDeclaredInManifest) 0 else 1 }, { it.rank }))
                .distinctBy { it.id }
        } catch (e: Exception) {
            // SecurityException (not default launcher) or IllegalStateException (user locked)
            Log.w(TAG, "getShortcuts($pkg)", e)
            emptyList()
        }
    }

    fun label(s: ShortcutInfo): String = (s.shortLabel ?: s.longLabel ?: s.id).toString()

    fun icon(s: ShortcutInfo): Drawable? = try {
        la.getShortcutIconDrawable(s, context.resources.displayMetrics.densityDpi)
    } catch (_: Exception) { null }

    /** Small fixed-size icon for menus (raw shortcut icons can be very large). */
    fun menuIcon(s: ShortcutInfo): Drawable? = icon(s)?.let { small(context, it) }

    fun start(pkg: String, id: String, bounds: Rect?, opts: Bundle?): Boolean = try {
        la.startShortcut(pkg, id, bounds, opts, Process.myUserHandle())
        true
    } catch (e: Exception) {
        Log.w(TAG, "startShortcut($pkg/$id)", e)
        false
    }

    companion object {
        private const val TAG = "Sheaf"

        fun small(ctx: Context, d: Drawable, sizeDp: Float = 24f): Drawable {
            val px = (sizeDp * ctx.resources.displayMetrics.density).roundToInt()
            val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
            val copy = d.constantState?.newDrawable(ctx.resources)?.mutate() ?: d
            copy.setBounds(0, 0, px, px)
            copy.draw(Canvas(bmp))
            return BitmapDrawable(ctx.resources, bmp)
        }
    }
}

/** Icon swipe directions, in display order. */
val GESTURE_DIRS = listOf("up", "right", "down", "left")

fun gestureDirLabel(dir: String) = when (dir) {
    "up" -> "Swipe up"; "right" -> "Swipe right"; "down" -> "Swipe down"; else -> "Swipe left"
}
