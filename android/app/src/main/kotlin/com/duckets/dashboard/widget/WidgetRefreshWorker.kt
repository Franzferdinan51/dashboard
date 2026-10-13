package com.duckets.dashboard.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.duckets.dashboard.BuildConfig
import com.duckets.dashboard.MainActivity
import com.duckets.dashboard.R
import com.duckets.dashboard.data.DashboardRepository
import com.duckets.dashboard.data.MiningData
import com.duckets.dashboard.data.Overview
import com.duckets.dashboard.theme.Muse
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Fetches the bridge numbers the widget shows and rewrites every placed
 * widget. Driven by WorkManager: a periodic job (15-min platform floor)
 * keeps it fresh, one-time kicks land on widget placement, on the
 * system's own update tick, and whenever the app opens.
 */
class WidgetRefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        WidgetUpdater.refresh(applicationContext)
        // refresh() swallows fetch failures into an "unreachable" render;
        // from WorkManager's side the job itself always ran.
        return Result.success()
    }

    companion object {
        private const val PERIODIC_NAME = "dashboard-widget-periodic"
        private const val ONETIME_NAME = "dashboard-widget-refresh"

        fun enqueuePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<WidgetRefreshWorker>(
                15, TimeUnit.MINUTES,
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, request,
            )
        }

        fun enqueueOneTime(context: Context) {
            val request = OneTimeWorkRequestBuilder<WidgetRefreshWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONETIME_NAME, ExistingWorkPolicy.REPLACE, request,
            )
        }

        fun cancelPeriodic(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_NAME)
        }
    }
}

object WidgetUpdater {

    suspend fun refresh(context: Context) {
        val appContext = context.applicationContext
        // Same prefs file + key the app itself uses, so the widget always
        // follows the bridge address from Settings.
        val prefs = appContext.getSharedPreferences("dashboard", Context.MODE_PRIVATE)
        val url = prefs.getString("bridge_url", BuildConfig.DEFAULT_BRIDGE_URL)
            ?: BuildConfig.DEFAULT_BRIDGE_URL
        val repo = DashboardRepository()
        val overview = runCatching { repo.fetchOverview(url) }.getOrNull()
        val mining = runCatching { repo.fetchMining(url) }.getOrNull()

        val views = buildViews(appContext, overview, mining)
        val manager = AppWidgetManager.getInstance(appContext)
        val ids = manager.getAppWidgetIds(
            ComponentName(appContext, DashboardWidgetProvider::class.java),
        )
        if (ids.isNotEmpty()) manager.updateAppWidget(ids, views)
    }

    private fun buildViews(context: Context, ov: Overview?, mn: MiningData?): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_dashboard)
        val clock = SimpleDateFormat("h:mm a", Locale.US).format(Date())

        // Combined $/day: the same two bridge numbers the app's hero
        // card sums (Darkbloom realized estimate + verified mining).
        val dbDay = ov?.darkbloom?.earnings?.estimates?.perDayUsd
        val mineDay = mn?.batmanVerified?.usdPerDayNet
        views.setTextViewText(
            R.id.widget_combined,
            when {
                dbDay == null && mineDay == null -> "— /day"
                else -> String.format(Locale.US, "$%,.2f /day", (dbDay ?: 0.0) + (mineDay ?: 0.0))
            },
        )

        val db = ov?.darkbloom
        val (dbText, dbColor) = when {
            ov == null -> "Darkbloom · bridge unreachable" to Muse.Bad
            db == null || !db.installed -> "Darkbloom · not installed" to Muse.TextDim
            !db.running -> "Darkbloom · stopped" to Muse.Bad
            db.earning -> "Darkbloom · earning" to Muse.Good
            else -> "Darkbloom · online, not earning" to Muse.Warn
        }
        views.setTextViewText(R.id.widget_darkbloom, dbText)
        views.setTextColor(R.id.widget_darkbloom, dbColor.toArgb())

        val live = mn?.batmanLive
        val (minerText, minerColor) = when {
            live == null -> "Batman-3 · no data" to Muse.TextDim
            live.running -> {
                val hr = live.hashrateThs?.let {
                    String.format(Locale.US, "%.1f TH/s", it)
                } ?: "—"
                "Batman-3 · mining · $hr" to Muse.Good
            }
            else -> "Batman-3 · miner stopped" to Muse.Bad
        }
        views.setTextViewText(R.id.widget_miner, minerText)
        views.setTextColor(R.id.widget_miner, minerColor.toArgb())

        views.setTextViewText(
            R.id.widget_updated,
            if (ov == null) "Couldn't reach the bridge · tried $clock · tap to open"
            else "Updated $clock · tap to open",
        )

        // Whole widget opens the app (which also kicks a fresh refresh).
        val launch = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            context, 0, launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.widget_root, pending)
        return views
    }
}
