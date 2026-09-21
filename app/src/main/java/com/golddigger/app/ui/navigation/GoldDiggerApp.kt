package com.golddigger.app.ui.navigation

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.golddigger.app.ui.dashboard.DashboardScreen
import com.golddigger.app.ui.formation.FormationScreen
import com.golddigger.app.ui.groups.GroupDetailScreen
import com.golddigger.app.ui.groups.GroupsScreen
import com.golddigger.app.ui.holding.AddEditHoldingScreen
import com.golddigger.app.ui.holding.HoldingDetailScreen
import com.golddigger.app.ui.importphoto.ImportPortfolioScreen
import com.golddigger.app.ui.settings.SettingsScreen
import kotlinx.coroutines.launch

private enum class TopLevel(val label: String, val icon: ImageVector) {
    Dashboard("Portfolio", Icons.AutoMirrored.Filled.TrendingUp),
    Formation("Formation", Icons.Filled.SportsSoccer),
    Groups("Groups", Icons.Filled.Category),
    Settings("Settings", Icons.Filled.Settings),
}

@Composable
fun GoldDiggerApp() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination

    // Shared across the whole app (not just while Routes.HOME is composed) so
    // the selected tab survives pushing/popping a detail screen on top of it.
    val pagerState = rememberPagerState { TopLevel.entries.size }
    val scope = rememberCoroutineScope()

    val showBottomBar = currentRoute?.hierarchy?.any { it.route == Routes.HOME } == true

    Scaffold(
        // Each screen owns its own TopAppBar (and its status-bar inset); this
        // outer Scaffold only owns the bottom nav, so it must not add the top
        // system-bar inset on top of the screens' — that double-counts and
        // leaves a gap under the status bar.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    TopLevel.entries.forEachIndexed { index, top ->
                        NavigationBarItem(
                            selected = pagerState.currentPage == index,
                            onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                            icon = { Icon(top.icon, contentDescription = top.label) },
                            label = { Text(top.label) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Routes.HOME) {
                HomePager(pagerState = pagerState, navController = navController)
            }
            composable(Routes.IMPORT_PHOTO) {
                ImportPortfolioScreen(
                    onBack = { navController.popBackStack() },
                    onImported = { navController.popBackStack() },
                )
            }

            composable(
                route = Routes.ADD_HOLDING_WITH_TICKER,
                arguments = listOf(
                    navArgument(Routes.ARG_TICKER) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) {
                AddEditHoldingScreen(onDone = { navController.popBackStack() })
            }

            composable(
                route = Routes.EDIT_HOLDING,
                arguments = listOf(navArgument(Routes.ARG_HOLDING_ID) { type = NavType.LongType }),
            ) {
                AddEditHoldingScreen(onDone = { navController.popBackStack() })
            }

            composable(
                route = Routes.HOLDING_DETAIL,
                arguments = listOf(navArgument(Routes.ARG_HOLDING_ID) { type = NavType.LongType }),
            ) {
                val id = it.arguments?.getLong(Routes.ARG_HOLDING_ID) ?: 0L
                HoldingDetailScreen(
                    onBack = { navController.popBackStack() },
                    onEdit = { navController.navigate(Routes.editHolding(id)) },
                    onDeleted = { navController.popBackStack(Routes.HOME, inclusive = false) },
                )
            }

            composable(
                route = Routes.GROUP_DETAIL,
                arguments = listOf(navArgument(Routes.ARG_GROUP_ID) { type = NavType.LongType }),
            ) {
                GroupDetailScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}

/**
 * The four top-level tabs as swipeable pages, sharing one [pagerState] with the
 * bottom nav so tapping an icon and dragging the page agree on the same
 * position. All pages stay composed at once ([TopLevel.entries.size] is only
 * 4) so each screen's scroll position and in-flight state survive swiping away
 * and back, matching how the previous bottom-nav-only navigation preserved
 * per-tab state via `saveState`/`restoreState`.
 */
@Composable
private fun HomePager(
    pagerState: androidx.compose.foundation.pager.PagerState,
    navController: NavHostController,
) {
    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
        beyondViewportPageCount = TopLevel.entries.size - 1,
    ) { page ->
        when (TopLevel.entries[page]) {
            TopLevel.Dashboard -> DashboardScreen(
                onHoldingClick = { navController.navigate(Routes.holdingDetail(it)) },
                onAddHolding = { navController.navigate(Routes.ADD_HOLDING) },
                onImportPhoto = { navController.navigate(Routes.IMPORT_PHOTO) },
            )
            TopLevel.Formation -> FormationScreen(
                onHoldingClick = { navController.navigate(Routes.holdingDetail(it)) },
            )
            TopLevel.Groups -> GroupsScreen(
                onGroupClick = { navController.navigate(Routes.groupDetail(it)) },
            )
            TopLevel.Settings -> SettingsScreen()
        }
    }
}
