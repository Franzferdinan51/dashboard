package com.duckets.dashboard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import com.duckets.dashboard.theme.DashboardTheme
import com.duckets.dashboard.ui.DashboardRoot
import com.duckets.dashboard.widget.WidgetRefreshWorker

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep the home-screen widget honest: opening the app schedules
        // its periodic refresh (no-op if already running) and kicks a
        // fresh fetch for any placed widgets.
        WidgetRefreshWorker.enqueuePeriodic(this)
        WidgetRefreshWorker.enqueueOneTime(this)
        enableEdgeToEdge()
        setContent {
            DashboardTheme {
                val windowSizeClass = calculateWindowSizeClass(this)
                DashboardRoot(windowSizeClass)
            }
        }
    }
}
