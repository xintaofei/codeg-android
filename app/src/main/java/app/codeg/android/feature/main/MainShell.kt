package app.codeg.android.feature.main

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavGraph.Companion.findStartDestination
import kotlin.math.roundToInt
import app.codeg.android.R
import app.codeg.android.app.AppViewModel
import app.codeg.android.core.datastore.ServerProfile
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.feature.activity.ActivityScreen
import app.codeg.android.feature.projects.ProjectDetailScreen
import app.codeg.android.feature.search.SearchScreen
import app.codeg.android.feature.projects.ProjectListScreen
import app.codeg.android.feature.server.ServerEditorScreen
import app.codeg.android.feature.settings.SettingsScreen
import app.codeg.android.feature.server.ServerListScreen
import app.codeg.android.feature.sessiondetail.SessionDetailScreen
import app.codeg.android.feature.sessions.SessionListScreen

/**
 * Bottom-navigation destinations. Each carries an outlined icon (unselected) and
 * a filled icon (selected) — the Material 3 / GitHub / ChatGPT convention where
 * the active tab fills in.
 */
enum class HomeTab(
    val route: String,
    @StringRes val label: Int,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
) {
    CHATS("chats", R.string.tab_chats, Icons.Outlined.Forum, Icons.Rounded.Forum),
    FOLDERS("folders", R.string.tab_folders, Icons.Outlined.FolderOpen, Icons.Rounded.FolderOpen),
    ACTIVITY("activity", R.string.tab_activity, Icons.Outlined.Bolt, Icons.Rounded.Bolt),
    SEARCH("search", R.string.tab_search, Icons.Outlined.Search, Icons.Rounded.Search),
    SETTINGS("settings", R.string.tab_settings, Icons.Outlined.Settings, Icons.Rounded.Settings),
}

private const val ROUTE_SERVERS = "servers"
private const val ROUTE_EDITOR = "editor"
private const val ROUTE_NEW_TASK = "conversation_new"

/**
 * Whether the bottom bar — and, by extension, the New Task FAB — is currently
 * shown. Driven by scroll direction in [MainShell] and read by the Chats screen
 * so the FAB collapses to just "+" in lockstep with the bar sliding away.
 */
val LocalBarsVisible = compositionLocalOf { true }

/**
 * The main app shell once a server exists: a Material 3 bottom navigation bar
 * over a single [NavHost]. Tab destinations show the bar; full-screen
 * destinations (session detail, server management) hide it. Mirrors the iOS
 * compact-size `TabView`, with session detail / server management pushed on top.
 */
@Composable
fun MainShell(appViewModel: AppViewModel, servers: List<ServerProfile>) {
    val nav = rememberNavController()
    val backStackEntry by nav.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = HomeTab.entries.any { it.route == currentRoute }
    val tabRoutes = remember { HomeTab.entries.map { it.route }.toSet() }
    val selectedProfile by appViewModel.selectedProfile.collectAsStateWithLifecycle()
    // Wide screens (tablets / landscape) use a side NavigationRail instead of the bottom bar.
    val wide = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 720

    // Scroll-driven bottom bar: scrolling up (reading further down the list) slides
    // the bar off the bottom edge and collapses the New Task FAB to just "+";
    // scrolling back down slides it in and restores the label. The bar lives in this
    // shared Scaffold while the scroll happens in each tab's LazyColumn, so a
    // NestedScrollConnection on the content picks up every tab's scroll for free.
    var barsVisible by remember { mutableStateOf(true) }
    val density = LocalDensity.current
    // 0 = fully shown, 1 = fully hidden. Collapsing the bar's *reported* height (not
    // just translating it) is what lets the Scaffold hand the freed strip back to the
    // content, so every tab's list reclaims the space instead of leaving it blank.
    val barProgress by animateFloatAsState(
        targetValue = if (barsVisible) 0f else 1f,
        animationSpec = tween(durationMillis = 280),
        label = "bottomBarCollapse",
    )
    // Accumulate the scroll the list *actually consumed* and flip fully open/closed
    // once it passes a small threshold, resetting the accumulator on each direction
    // change so a deliberate nudge toggles but jitter doesn't. Keying off consumed
    // (not the raw gesture) means a short, non-scrolling list — or over-dragging past
    // the top/bottom edge — never toggles the bar. Keyed to currentRoute so it resets
    // per tab.
    val barConnection = remember(currentRoute, density) {
        val threshold = with(density) { 6.dp.toPx() }
        object : NestedScrollConnection {
            var acc = 0f
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                val dy = consumed.y
                if (dy == 0f) return Offset.Zero // list didn't move → leave the bar alone
                if ((dy < 0f && acc > 0f) || (dy > 0f && acc < 0f)) acc = 0f
                acc += dy
                if (acc < -threshold && barsVisible) {
                    barsVisible = false; acc = 0f
                } else if (acc > threshold && !barsVisible) {
                    barsVisible = true; acc = 0f
                }
                return Offset.Zero // observe only; never consume scroll
            }
        }
    }
    // Any route change — switching tabs or pushing a full-screen detail — reveals the bar.
    LaunchedEffect(currentRoute) { barsVisible = true }

    val onTab: (HomeTab) -> Unit = { tab ->
        nav.navigate(tab.route) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    // Handle incoming codeg:// deep links.
    val pendingLink by app.codeg.android.core.common.DeepLinkBus.pending.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(pendingLink) {
        val route = pendingLink ?: return@LaunchedEffect
        fun toTab(it: HomeTab) = nav.navigate(it.route) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true; restoreState = true
        }
        when (route) {
            is app.codeg.android.core.common.DeepLinkRoute.OpenTab -> when (route.tab) {
                "chats" -> toTab(HomeTab.CHATS)
                "projects", "folders" -> toTab(HomeTab.FOLDERS)
                "activity" -> toTab(HomeTab.ACTIVITY)
                "search" -> toTab(HomeTab.SEARCH)
                "settings" -> toTab(HomeTab.SETTINGS)
                else -> {}
            }
            is app.codeg.android.core.common.DeepLinkRoute.OpenConversation -> nav.navigate("conversation/${route.id}")
            is app.codeg.android.core.common.DeepLinkRoute.OpenProject -> nav.navigate("project/${route.id}")
            is app.codeg.android.core.common.DeepLinkRoute.OpenSettings -> toTab(HomeTab.SETTINGS)
        }
        app.codeg.android.core.common.DeepLinkBus.consume(route)
    }

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBottomBar && !wide) {
                CodegBottomBar(
                    currentRoute,
                    onTab,
                    // Report a shrinking height as the bar hides so the Scaffold gives the
                    // freed strip back to the content (the whole point — reclaim the space);
                    // the bar is drawn at full height and slid down within, clipped away.
                    modifier = Modifier
                        .clipToBounds()
                        .layout { measurable, constraints ->
                            val placeable = measurable.measure(constraints)
                            val full = placeable.height
                            val reported = (full * (1f - barProgress)).roundToInt().coerceIn(0, full)
                            layout(placeable.width, reported) {
                                placeable.placeRelative(0, (full * barProgress).roundToInt())
                            }
                        },
                )
            }
        },
    ) { padding ->
        // Only intercept scroll when the bottom bar is actually shown (a tab route on a
        // narrow screen); the side rail and full-screen routes don't hide-on-scroll.
        val contentModifier =
            if (showBottomBar && !wide) Modifier.nestedScroll(barConnection) else Modifier
        androidx.compose.foundation.layout.Row(
            Modifier.padding(padding).fillMaxSize().then(contentModifier),
        ) {
            if (showBottomBar && wide) CodegNavRail(currentRoute, onTab)
            NavHost(
                navController = nav,
                startDestination = HomeTab.CHATS.route,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                // Material motion: lateral tab switches fade through; pushing a
                // detail/editor slides in along the X axis (and back reverses it).
                enterTransition = {
                    val lateral = initialState.destination.route in tabRoutes &&
                        targetState.destination.route in tabRoutes
                    if (lateral) fadeIn(tween(190))
                    else slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(300)) +
                        fadeIn(tween(300))
                },
                exitTransition = {
                    val lateral = initialState.destination.route in tabRoutes &&
                        targetState.destination.route in tabRoutes
                    if (lateral) fadeOut(tween(190))
                    else slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(300)) +
                        fadeOut(tween(300))
                },
                popEnterTransition = {
                    slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(300)) +
                        fadeIn(tween(300))
                },
                popExitTransition = {
                    slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(300)) +
                        fadeOut(tween(300))
                },
            ) {
            composable(HomeTab.CHATS.route) {
                CompositionLocalProvider(LocalBarsVisible provides barsVisible) {
                    SessionListScreen(
                        servers = servers,
                        selectedId = selectedProfile?.id,
                        onSelectServer = appViewModel::selectServer,
                        onManageServers = { nav.navigate(ROUTE_SERVERS) },
                        onOpenConversation = { id -> nav.navigate("conversation/$id") },
                        onNewTask = { nav.navigate(ROUTE_NEW_TASK) },
                    )
                }
            }
            composable(HomeTab.FOLDERS.route) {
                ProjectListScreen(onOpenFolder = { id -> nav.navigate("project/$id") })
            }
            composable(HomeTab.ACTIVITY.route) {
                ActivityScreen(onOpenConversation = { id -> nav.navigate("conversation/$id") })
            }
            composable(HomeTab.SEARCH.route) {
                SearchScreen(onOpenConversation = { id -> nav.navigate("conversation/$id") })
            }
            composable(HomeTab.SETTINGS.route) {
                SettingsScreen()
            }

            composable(
                route = "conversation/{id}",
                arguments = listOf(navArgument("id") { type = NavType.IntType }),
            ) {
                SessionDetailScreen(
                    onBack = { nav.popBackStack() },
                    onOpenSession = { folderId -> nav.navigate("$ROUTE_NEW_TASK?folderId=$folderId") },
                )
            }
            composable(
                route = "$ROUTE_NEW_TASK?folderId={folderId}",
                arguments = listOf(
                    navArgument("folderId") {
                        type = NavType.IntType
                        defaultValue = -1
                    },
                ),
            ) {
                SessionDetailScreen(
                    onBack = { nav.popBackStack() },
                    onOpenSession = { folderId -> nav.navigate("$ROUTE_NEW_TASK?folderId=$folderId") },
                )
            }

            composable(
                route = "project/{folderId}",
                arguments = listOf(navArgument("folderId") { type = NavType.IntType }),
            ) {
                ProjectDetailScreen(onBack = { nav.popBackStack() })
            }

            composable(ROUTE_SERVERS) {
                ServerListScreen(
                    servers = servers,
                    selectedId = selectedProfile?.id,
                    onSelect = appViewModel::selectServer,
                    onDelete = appViewModel::deleteServer,
                    onAdd = { nav.navigate(ROUTE_EDITOR) },
                    onEdit = { id -> nav.navigate("$ROUTE_EDITOR?serverId=$id") },
                    onBack = { nav.popBackStack() },
                )
            }
            composable(
                route = "$ROUTE_EDITOR?serverId={serverId}",
                arguments = listOf(
                    navArgument("serverId") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) {
                ServerEditorScreen(onDone = { nav.popBackStack() })
            }
            }
        }
    }
}

@Composable
private fun CodegNavRail(currentRoute: String?, onSelect: (HomeTab) -> Unit) {
    val colors = CodegTheme.colors
    // The wide-screen analogue of the bottom bar: the same frosted glass material,
    // delineated from the content by a trailing hairline rather than a top one.
    androidx.compose.foundation.layout.Row {
        androidx.compose.material3.NavigationRail(
            containerColor = Color.Transparent,
        ) {
            HomeTab.entries.forEach { tab ->
                val selected = currentRoute == tab.route
                androidx.compose.material3.NavigationRailItem(
                    selected = selected,
                    onClick = { onSelect(tab) },
                    icon = {
                        Icon(
                            if (selected) tab.selectedIcon else tab.icon,
                            contentDescription = stringResource(tab.label),
                        )
                    },
                    label = { Text(stringResource(tab.label)) },
                    alwaysShowLabel = true,
                    colors = androidx.compose.material3.NavigationRailItemDefaults.colors(
                        selectedIconColor = colors.accent,
                        selectedTextColor = colors.accent,
                        unselectedIconColor = colors.textTertiary,
                        unselectedTextColor = colors.textTertiary,
                        indicatorColor = colors.accent.copy(alpha = 0.20f),
                    ),
                )
            }
        }
        androidx.compose.material3.VerticalDivider(
            thickness = androidx.compose.ui.unit.Dp.Hairline,
            color = colors.surfaceStroke,
        )
    }
}

@Composable
private fun CodegBottomBar(
    currentRoute: String?,
    onSelect: (HomeTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CodegTheme.colors
    androidx.compose.foundation.layout.Column(modifier) {
        // A hairline that delineates the bar from the content above it, the way
        // GitHub / Telegram / ChatGPT separate their bottom navigation.
        HorizontalDivider(thickness = androidx.compose.ui.unit.Dp.Hairline, color = colors.surfaceStroke)
        NavigationBar(
            // Fully transparent so the CodegBackground (base + both glows) flows
            // uninterrupted to the bottom edge. A translucent fill is pointless in
            // light mode (elevated #FFFFFF and base #F2F4F6 are both near-white, so it
            // still reads as a white slab); letting the canvas itself show through is
            // what actually makes the tabs continuous with the content above them.
            containerColor = Color.Transparent,
            tonalElevation = 0.dp,
            modifier = Modifier.navigationBarsPadding(),
        ) {
            HomeTab.entries.forEach { tab ->
                val selected = currentRoute == tab.route
                NavigationBarItem(
                    selected = selected,
                    onClick = { onSelect(tab) },
                    icon = {
                        Icon(
                            if (selected) tab.selectedIcon else tab.icon,
                            contentDescription = stringResource(tab.label),
                        )
                    },
                    label = { Text(stringResource(tab.label)) },
                    alwaysShowLabel = true,
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = colors.accent,
                        selectedTextColor = colors.accent,
                        unselectedIconColor = colors.textTertiary,
                        unselectedTextColor = colors.textTertiary,
                        indicatorColor = colors.accent.copy(alpha = 0.20f),
                    ),
                )
            }
        }
    }
}
