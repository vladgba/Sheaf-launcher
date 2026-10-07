package x.vladgba.sheaflauncher

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
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
    var onRetry: (() -> Unit)? = null
    var failed = false
        private set

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
