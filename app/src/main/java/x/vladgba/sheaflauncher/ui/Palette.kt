package x.vladgba.sheaflauncher.ui

import android.content.Context
import android.content.res.Configuration
import android.view.ContextThemeWrapper

/**
 * Day / night colours for everything Sheaf draws itself.
 * [night] is set by the activity from the "Color mode" setting
 * (system / day / night) and read whenever views bind or draw.
 */
object Palette {
    var night = true

    /** Resolves a color-mode setting ("system" | "day" | "night") to night = true/false. */
    fun resolve(ctx: Context, mode: String): Boolean = when (mode) {
        "day" -> false
        "night" -> true
        else -> (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
    }

    // Home screen (over the wallpaper)
    val label get() = if (night) 0xFFFFFFFF.toInt() else 0xFF1F1F1F.toInt()
    val labelShadow get() = if (night) 0xB0000000.toInt() else 0x99FFFFFF.toInt()
    val folderBg get() = if (night) 0x66FFFFFF else 0x40000000
    val dotOn get() = if (night) 0xFFFFFFFF.toInt() else 0xFF1F1F1F.toInt()
    val dotOff get() = if (night) 0x66FFFFFF else 0x55000000

    // Panels (folder, widget picker)
    val scrim get() = if (night) 0x77000000 else 0x44000000
    val card get() = if (night) 0xF0202124.toInt() else 0xF5F7F7F7.toInt()
    val cardText get() = if (night) 0xFFFFFFFF.toInt() else 0xFF1F1F1F.toInt()
    val cardTextDim get() = if (night) 0xB3FFFFFF.toInt() else 0x99000000.toInt()
    val previewBg get() = if (night) 0x22FFFFFF else 0x14000000

    // System-styled UI (dialogs, popup menus)
    val dialogTheme get() = if (night) android.R.style.Theme_DeviceDefault_Dialog_Alert
                            else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
    private val menuTheme get() = if (night) android.R.style.Theme_DeviceDefault
                                  else android.R.style.Theme_DeviceDefault_Light

    /** Context for PopupMenus so they follow the chosen mode. */
    fun menuContext(ctx: Context): Context = ContextThemeWrapper(ctx, menuTheme)
}
