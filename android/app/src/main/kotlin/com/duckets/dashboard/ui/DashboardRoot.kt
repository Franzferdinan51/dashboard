package com.duckets.dashboard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.duckets.dashboard.theme.Muse
import com.duckets.dashboard.ui.screens.DarkbloomScreen
import com.duckets.dashboard.ui.screens.OverviewScreen
import com.duckets.dashboard.ui.screens.SettingsScreen
import com.duckets.dashboard.ui.screens.SystemScreen
import com.duckets.dashboard.vm.DashboardViewModel
import kotlinx.coroutines.launch

enum class Tab(val label: String, val icon: ImageVector) {
    Overview("Overview", Icons.Filled.Dashboard),
    System("System", Icons.Filled.Memory),
    Darkbloom("Darkbloom", Icons.Filled.MonetizationOn),
    Settings("Settings", Icons.Filled.Settings),
}

/**
 * Adaptive shell:
 *  - Compact width (phones): top header + bottom navigation bar, 1 column.
 *  - Medium/Expanded (tablets, foldables, landscape): navigation rail, 2 columns.
 */
@Composable
fun DashboardRoot(windowSizeClass: WindowSizeClass) {
    val vm: DashboardViewModel = viewModel()
    val state by vm.state.collectAsState()
    var tab by remember { mutableStateOf(Tab.Overview) }
    val scope = rememberCoroutineScope()

    val expanded = windowSizeClass.widthSizeClass >= WindowWidthSizeClass.Medium
    val columns = if (windowSizeClass.widthSizeClass >= WindowWidthSizeClass.Expanded) 2 else 1

    Box(Modifier.fillMaxSize().background(Muse.Backdrop)) {
        Scaffold(
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            topBar = { Header(state.connected, state.everConnected) { scope.launch { vm.refreshOnce() } } },
            bottomBar = {
                if (!expanded) {
                    NavigationBar(containerColor = Muse.Night.copy(alpha = 0.92f)) {
                        Tab.entries.forEach { t ->
                            NavigationBarItem(
                                selected = tab == t,
                                onClick = { tab = t },
                                icon = { Icon(t.icon, t.label) },
                                label = { Text(t.label) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Muse.Mist,
                                    selectedTextColor = Muse.Mist,
                                    indicatorColor = Muse.Blue,
                                    unselectedIconColor = Muse.TextDim,
                                    unselectedTextColor = Muse.TextDim,
                                ),
                            )
                        }
                    }
                }
            },
        ) { padding ->
            Row(Modifier.fillMaxSize().padding(padding)) {
                if (expanded) {
                    NavigationRail(
                        containerColor = androidx.compose.ui.graphics.Color.Transparent,
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        Tab.entries.forEach { t ->
                            NavigationRailItem(
                                selected = tab == t,
                                onClick = { tab = t },
                                icon = { Icon(t.icon, t.label) },
                                label = { Text(t.label) },
                                colors = NavigationRailItemDefaults.colors(
                                    selectedIconColor = Muse.Mist,
                                    selectedTextColor = Muse.Mist,
                                    indicatorColor = Muse.Blue,
                                    unselectedIconColor = Muse.TextDim,
                                    unselectedTextColor = Muse.TextDim,
                                ),
                            )
                        }
                    }
                }
                Box(Modifier.weight(1f).fillMaxSize()) {
                    when (tab) {
                        Tab.Overview -> OverviewScreen(state, columns)
                        Tab.System -> SystemScreen(state)
                        Tab.Darkbloom -> DarkbloomScreen(state)
                        Tab.Settings -> SettingsScreen(state, vm)
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(connected: Boolean, everConnected: Boolean, onRefresh: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "MAC STUDIO",
                    style = MaterialTheme.typography.labelMedium,
                    color = Muse.BlueSoft,
                )
                Text(
                    "Dashboard",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Muse.Mist,
                )
            }
            Box(
                Modifier
                    .size(9.dp)
                    .background(
                        when {
                            connected -> Muse.Good
                            everConnected -> Muse.Bad
                            else -> Muse.Warn
                        },
                        androidx.compose.foundation.shape.CircleShape,
                    )
            )
            Spacer(Modifier.size(8.dp))
            Text(
                when {
                    connected -> "Live"
                    everConnected -> "Offline"
                    else -> "Connecting"
                },
                style = MaterialTheme.typography.labelLarge,
                color = Muse.TextDim,
            )
            IconButton(onClick = onRefresh) {
                Icon(Icons.Filled.Refresh, "Refresh now", tint = Muse.BlueSoft)
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}
