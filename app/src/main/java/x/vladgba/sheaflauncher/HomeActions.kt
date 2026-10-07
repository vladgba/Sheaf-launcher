package x.vladgba.sheaflauncher

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * What the Home button does when you're already on the default page.
 * Stored by [key] in the workspace config.
 */
enum class HomeAction(val key: String, val label: String) {
    NONE("none", "Nothing"),
    LOCK("lock", "Lock screen"),
    NOTIFICATIONS("notifications", "Expand notifications"),
    QUICK_SETTINGS("quick_settings", "Expand quick settings");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: NONE
    }
}

/**
 * Accessibility service used only for global actions (lock screen, notification
 * shade, quick settings). It does not read window content and ignores all events.
 */
class SheafAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onDestroy() { instance = null; super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    companion object {
        @SuppressLint("StaticFieldLeak")
        @Volatile var instance: SheafAccessibilityService? = null
            private set
    }
}

/** Device admin fallback for locking (policy: force-lock only). */
class SheafDeviceAdmin : DeviceAdminReceiver()

object HomeActions {
    private const val TAG = "Sheaf"

    private fun admin(ctx: Context) = ComponentName(ctx, SheafDeviceAdmin::class.java)
    private fun dpm(ctx: Context) = ctx.getSystemService(DevicePolicyManager::class.java)

    fun accessibilityOn() = SheafAccessibilityService.instance != null
    fun adminOn(ctx: Context) = dpm(ctx).isAdminActive(admin(ctx))

    /** Runs the action; if nothing can perform it yet, explains how to enable it. */
    fun perform(a: Activity, action: HomeAction) {
        val svc = SheafAccessibilityService.instance
        when (action) {
            HomeAction.NONE -> {}

            HomeAction.LOCK -> when {
                // Preferred: keeps fingerprint/face unlock available.
                svc != null && Build.VERSION.SDK_INT >= 28 ->
                    svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
                adminOn(a) -> dpm(a).lockNow()
                else -> askToEnableLock(a)
            }

            HomeAction.NOTIFICATIONS ->
                if (svc != null) svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
                else if (!expandStatusBar(a, quickSettings = false)) askToEnableAccessibility(a)

            HomeAction.QUICK_SETTINGS ->
                if (svc != null) svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
                else if (!expandStatusBar(a, quickSettings = true)) askToEnableAccessibility(a)
        }
    }

    /**
     * Fallback without accessibility: hidden StatusBarManager API via reflection
     * (needs the normal EXPAND_STATUS_BAR permission). Works on most devices but is
     * not guaranteed by Android, so the accessibility service is preferred.
     */
    @SuppressLint("WrongConstant")
    private fun expandStatusBar(ctx: Context, quickSettings: Boolean): Boolean = try {
        val sbm = ctx.getSystemService("statusbar") ?: throw IllegalStateException("no statusbar")
        val name = if (quickSettings) "expandSettingsPanel" else "expandNotificationsPanel"
        sbm.javaClass.getMethod(name).invoke(sbm)
        true
    } catch (e: Throwable) {
        Log.w(TAG, "status bar expand failed", e)
        false
    }

    // ------------------------------------------------------------ enabling

    private fun askToEnableLock(a: Activity) {
        val options = buildList {
            if (Build.VERSION.SDK_INT >= 28) add("Accessibility service (recommended)")
            add("Device admin")
        }
        AlertDialog.Builder(a, x.vladgba.sheaflauncher.ui.Palette.dialogTheme)
            .setTitle("Allow Sheaf to lock the screen")
            .setItems(options.toTypedArray()) { _, i ->
                if (options[i].startsWith("Accessibility")) openAccessibilitySettings(a) else requestAdmin(a)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun askToEnableAccessibility(a: Activity) {
        AlertDialog.Builder(a, x.vladgba.sheaflauncher.ui.Palette.dialogTheme)
            .setTitle("Enable Sheaf accessibility")
            .setMessage("Opening the notification shade or quick settings needs the Sheaf accessibility service. " +
                    "It only performs these system actions and does not read screen content.")
            .setPositiveButton("Open settings") { _, _ -> openAccessibilitySettings(a) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun openAccessibilitySettings(a: Activity) {
        try {
            a.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: Exception) {
            Log.w(TAG, "no accessibility settings", e)
        }
    }

    fun requestAdmin(a: Activity) {
        val i = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin(a))
            .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Lets Sheaf lock the screen when you press Home on the default page.")
        try { a.startActivity(i) } catch (e: Exception) { Log.w(TAG, "admin request failed", e) }
    }

    /** Must be called before Sheaf can be uninstalled if device admin was enabled. */
    fun removeAdmin(ctx: Context) {
        if (adminOn(ctx)) dpm(ctx).removeActiveAdmin(admin(ctx))
    }
}
