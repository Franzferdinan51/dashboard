package com.duckets.dashboard.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context

/**
 * The widget itself holds no state — every update path funnels into
 * [WidgetRefreshWorker], which fetches and rewrites the RemoteViews.
 * The provider only arranges for that to happen: periodic WorkManager
 * refresh while at least one widget is placed, plus a one-time kick
 * on the system's update ticks.
 */
class DashboardWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        WidgetRefreshWorker.enqueueOneTime(context)
    }

    override fun onEnabled(context: Context) {
        WidgetRefreshWorker.enqueuePeriodic(context)
        WidgetRefreshWorker.enqueueOneTime(context)
    }

    override fun onDisabled(context: Context) {
        // Last widget removed: stop paying for refreshes nobody sees.
        WidgetRefreshWorker.cancelPeriodic(context)
    }
}
