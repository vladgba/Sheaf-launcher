package x.vladgba.sheaflauncher

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.view.Gravity
import android.view.View
import android.widget.RemoteViews
import android.widget.TextView
import x.vladgba.sheaflauncher.ui.dpi

/**
 * Widget host that creates [SheafWidgetHostView]s and reports provider changes
 * (app updated / installed), so widgets that failed to load can be retried.
 */
class SheafWidgetHost(context: Context, hostId: Int, private val onProvidersChangedCb: () -> Unit) :
    AppWidgetHost(context, hostId) {

    override fun onCreateView(context: Context, appWidgetId: Int, appWidget: AppWidgetProviderInfo?): AppWidgetHostView =
        SheafWidgetHostView(context)

    override fun onProvidersChanged() {
        super.onProvidersChanged()
        onProvidersChangedCb()
    }
}

/**
 * Host view that remembers whether the last update failed to inflate and shows a
 * tappable "retry" view instead of the stock "Can't load widget".
 */
class SheafWidgetHostView(context: Context) : AppWidgetHostView(context) {
    var removePadding = false
    var onRetry: (() -> Unit)? = null
    var failed = false
        private set

    override fun setAppWidget(appWidgetId: Int, info: AppWidgetProviderInfo?) {
        super.setAppWidget(appWidgetId, info)
        // Sheaf has always laid widgets out without host padding. Provider-info
        // refreshes call this too, so keep that policy when Android resets it.
        setPadding(0, 0, 0, 0)
    }

    /**
     * Android subtracts default widget padding when reporting sizes, regardless
     * of this view's actual padding. Opt out by compensating for that subtraction.
     * This does not alter padding/margins inside the provider's RemoteViews.
     */
    fun updateWorkspaceSize(widthDp: Float, heightDp: Float) {
        val padding = if (removePadding) {
            appWidgetInfo?.provider?.let { getDefaultPaddingForWidget(context, it, null) }
        } else null
        val density = resources.displayMetrics.density
        val horizontalDp = padding?.let { (it.left + it.right) / density } ?: 0f
        val verticalDp = padding?.let { (it.top + it.bottom) / density } ?: 0f
        if (Build.VERSION.SDK_INT >= 31) {
            updateAppWidgetSize(Bundle(), listOf(SizeF(widthDp + horizontalDp, heightDp + verticalDp)))
        } else {
            // The legacy framework overload truncates its padding correction to int dp.
            val width = widthDp.toInt() + horizontalDp.toInt()
            val height = heightDp.toInt() + verticalDp.toInt()
            @Suppress("DEPRECATION")
            updateAppWidgetSize(null, width, height, width, height)
        }
    }

    override fun updateAppWidget(remoteViews: RemoteViews?) {
        failed = false
        super.updateAppWidget(remoteViews) // calls getErrorView() if inflation fails
    }

    override fun getErrorView(): View {
        failed = true
        return TextView(context).apply {
            text = "Widget didn't load\nTap to retry"
            gravity = Gravity.CENTER
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0x66000000)
            val p = context.dpi(8f); setPadding(p, p, p, p)
            setOnClickListener { onRetry?.invoke() }
        }
    }
}
