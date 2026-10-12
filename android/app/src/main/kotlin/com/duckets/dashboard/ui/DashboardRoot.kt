package com.duckets.dashboard.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.duckets.dashboard.theme.Muse
import com.duckets.dashboard.ui.components.PulseDot
import com.duckets.dashboard.ui.screens.DarkbloomScreen
import com.duckets.dashboard.ui.screens.EarningsScreen
import com.duckets.dashboard.ui.screens.MiningScreen
import com.duckets.dashboard.ui.screens.OverviewScreen
import com.duckets.dashboard.ui.screens.SettingsScreen
import com.duckets.dashboard.ui.screens.SystemScreen
import com.duckets.dashboard.ui.screens.WindowsScreen
import com.duckets.dashboard.vm.DashboardViewModel
import kotlinx.coroutines.delay

enum class Tab(val label: String, val icon: ImageVector) {
    Overview("Overview", Icons.Filled.Dashboard),
    System("System", Icons.Filled.Memory),
    Darkbloom("Darkbloom", Icons.Filled.MonetizationOn),
    Mining("Mining", Icons.Filled.Bolt),
    Earnings("Earnings", Icons.Filled.AccountBalanceWallet),
    Windows("Windows", Icons.Filled.Computer),
    Settings("Settings", Icons.Filled.Settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardRoot(windowSizeClass: WindowSizeClass) {
    val vm: DashboardViewModel = viewModel()
    val state by vm.state.collectAsState()
    var tabName by rememberSaveable { mutableStateOf(Tab.Overview.name) }
    val tab = runCatching { Tab.valueOf(tabName) }.getOrDefault(Tab.Overview)
    // Portrait phone nav: a slim bar (current section + Menu) and a
    // hamburger sheet with every destination — seven items in a bottom
    // bar was too crowded to read or tap.
    var menuOpen by remember { mutableStateOf(false) }

    // Poll only while the app is visible: no battery/network cost in the
    // background, and pull-to-refresh covers any gap on return.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> vm.startPolling()
                Lifecycle.Event.ON_STOP -> vm.stopPolling()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // List scroll positions survive tab switches (AnimatedContent disposes
    // off-screen tabs, so the states live here, above it).
    val overviewListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val systemListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val darkbloomListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val miningListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val earningsListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val windowsListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val settingsListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }

    // Adaptive: phones (Compact) get bottom nav + 1 column; tablets, foldables
    // and landscape phones (Medium/Expanded) get a rail + 2 columns.
    val expanded = windowSizeClass.widthSizeClass >= WindowWidthSizeClass.Medium
    val columns = if (expanded) 2 else 1
    val hPad = if (expanded) 24 else 16

    Box(Modifier.fillMaxSize().background(Muse.Backdrop)) {
        Scaffold(
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            topBar = {
                Column {
                    Header(
                        machineName = state.overview?.system?.computerName ?: "Mac Studio",
                        connected = state.connected,
                        everConnected = state.everConnected,
                        lastError = state.lastError,
                        isRefreshing = state.isRefreshing,
                        lastUpdatedMillis = state.lastUpdatedMillis,
                        latencyMs = state.latencyMs,
                        onRefresh = { vm.refreshNow() },
                    )
                    if (!state.connected && state.overview != null) {
                        StaleBanner(
                            message = state.lastError ?: "Connection lost — showing the last sample.",
                            onRetry = { vm.refreshNow() },
                        )
                    }
                }
            },
            bottomBar = {
                if (!expanded) {
                    NavigationBar(containerColor = Muse.Night.copy(alpha = 0.96f)) {
                        val itemColors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Muse.Mist,
                            selectedTextColor = Muse.Mist,
                            indicatorColor = Muse.Blue,
                            unselectedIconColor = Muse.TextDim,
                            unselectedTextColor = Muse.TextDim,
                        )
                        // Current destination — tapping it opens the menu too.
                        NavigationBarItem(
                            selected = true,
                            onClick = { menuOpen = true },
                            icon = { Icon(tab.icon, tab.label) },
                            label = { Text(tab.label) },
                            colors = itemColors,
                        )
                        NavigationBarItem(
                            selected = false,
                            onClick = { menuOpen = true },
                            icon = { Icon(Icons.Filled.Menu, "Menu") },
                            label = { Text("Menu") },
                            colors = itemColors,
                        )
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
                        // Seven destinations overflow short landscape
                        // screens (720dp phones): the rail scrolls.
                        Column(
                            Modifier.verticalScroll(rememberScrollState()),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Tab.entries.forEach { t ->
                                NavigationRailItem(
                                    selected = tab == t,
                                    onClick = { tabName = t.name },
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
                }
                Box(Modifier.weight(1f).fillMaxSize()) {
                    AnimatedContent(
                        targetState = tab,
                        transitionSpec = {
                            (fadeIn(tween(220)) + slideInHorizontally(tween(220)) { it / 6 }) togetherWith
                                (fadeOut(tween(160)) + slideOutHorizontally(tween(160)) { -it / 6 })
                        },
                        label = "tab",
                    ) { current ->
                        when (current) {
                            Tab.Overview -> OverviewScreen(state, columns, onRefresh = { vm.refreshNow() }, listState = overviewListState)
                            Tab.System -> SystemScreen(state, columns, onRefresh = { vm.refreshNow() }, listState = systemListState)
                            Tab.Darkbloom -> DarkbloomScreen(state, columns, onRefresh = { vm.refreshNow() }, listState = darkbloomListState)
                            Tab.Mining -> MiningScreen(state, columns, onRefresh = { vm.refreshNow() }, listState = miningListState)
                            Tab.Earnings -> EarningsScreen(state, columns, onRefresh = { vm.refreshNow() }, listState = earningsListState)
                            Tab.Windows -> WindowsScreen(state, columns, onRefresh = { vm.refreshNow() }, listState = windowsListState)
                            Tab.Settings -> SettingsScreen(state, vm, columns, listState = settingsListState)
                        }
                    }
                }
            }
        }
        if (!expanded && menuOpen) {
            TabMenuSheet(
                current = tab,
                onPick = { picked ->
                    tabName = picked.name
                    menuOpen = false
                },
                onDismiss = { menuOpen = false },
            )
        }
    }
}

/**
 * Hamburger destination menu for portrait phones: every tab as a big,
 * easy-to-hit row in a bottom sheet, the current one highlighted.
 * Borrowed from the Muse Companion pattern — one clean home surface,
 * destinations on demand instead of a crowded permanent bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TabMenuSheet(current: Tab, onPick: (Tab) -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Muse.Night,
        contentColor = Muse.Mist,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                "GO TO",
                style = MaterialTheme.typography.labelMedium,
                color = Muse.TextFaint,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
            Tab.entries.forEach { t ->
                val selected = t == current
                Row(
                    Modifier.fillMaxWidth()
                        .background(
                            if (selected) Muse.Blue.copy(alpha = 0.20f) else androidx.compose.ui.graphics.Color.Transparent,
                            Muse.ChipShape,
                        )
                        .clickable { onPick(t) }
                        .padding(horizontal = 14.dp, vertical = 15.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        t.icon, t.label,
                        tint = if (selected) Muse.Mist else Muse.TextDim,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(14.dp))
                    Text(
                        t.label,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (selected) Muse.Mist else Muse.TextDim,
                    )
                    if (selected) {
                        Spacer(Modifier.weight(1f))
                        Box(
                            Modifier.size(8.dp)
                                .background(Muse.Good, androidx.compose.foundation.shape.CircleShape),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}

@Composable
private fun Header(
    machineName: String,
    connected: Boolean,
    everConnected: Boolean,
    lastError: String?,
    isRefreshing: Boolean,
    lastUpdatedMillis: Long,
    latencyMs: Long?,
    onRefresh: () -> Unit,
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) { now = System.currentTimeMillis(); delay(1000) }
    }
    val spin = rememberInfiniteTransition(label = "spin")
    val angle by spin.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Restart),
        label = "spinAngle",
    )
    Column(
        Modifier.fillMaxWidth().statusBarsPadding()
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    machineName.uppercase(java.util.Locale.US),
                    style = MaterialTheme.typography.labelMedium,
                    color = Muse.BlueSoft, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text("Dashboard", style = MaterialTheme.typography.headlineMedium, color = Muse.Mist)
                Text(
                    buildString {
                        append(fmtFreshness(lastUpdatedMillis, now))
                        if (connected && latencyMs != null) append(" · ${latencyMs} ms")
                    },
                    style = MaterialTheme.typography.bodySmall, color = Muse.TextFaint,
                )
            }
            when {
                connected -> PulseDot(Muse.Good, sizeDp = 9)
                everConnected -> PulseDot(Muse.Bad, sizeDp = 9)
                lastError != null -> PulseDot(Muse.Bad, sizeDp = 9)
                else -> PulseDot(Muse.Warn, sizeDp = 9)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                when {
                    connected -> "Live"
                    everConnected -> "Offline"
                    // A failed first connection must not sit on "Connecting"
                    // forever — say so plainly.
                    lastError != null -> "Can't connect"
                    else -> "Connecting"
                },
                style = MaterialTheme.typography.labelLarge, color = Muse.TextDim,
            )
            IconButton(onClick = onRefresh) {
                Icon(
                    Icons.Filled.Refresh, "Refresh now", tint = Muse.BlueSoft,
                    modifier = if (isRefreshing) Modifier.rotate(angle) else Modifier,
                )
            }
        }
    }
}

@Composable
private fun StaleBanner(message: String, onRetry: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            .background(Muse.Warn.copy(alpha = 0.14f), Muse.ChipShape)
            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.WarningAmber, null, tint = Muse.Warn, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(message, style = MaterialTheme.typography.bodySmall, color = Muse.Mist,
            modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        TextButton(onClick = onRetry) { Text("Retry", color = Muse.Warn) }
    }
    Spacer(Modifier.height(2.dp))
}
