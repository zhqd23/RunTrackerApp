package com.run.tracker.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.run.tracker.R
import com.run.tracker.map.createMapView
import com.run.tracker.ui.detail.RunDetailScreen
import com.run.tracker.ui.history.HistoryScreen
import com.run.tracker.ui.stats.StatsScreen
import com.run.tracker.ui.tracking.TrackingScreen

/** 底部导航三个 Tab */
enum class Screen(@StringRes val labelRes: Int, val icon: ImageVector) {
    Tracking(R.string.nav_tracking, Icons.AutoMirrored.Filled.DirectionsRun),
    History(R.string.nav_history, Icons.AutoMirrored.Filled.List),
    Stats(R.string.nav_stats, Icons.Filled.BarChart)
}

/**
 * 应用导航：底部 Tab（运动 / 记录 / 统计）+ 详情页。
 */
@Composable
fun RunNavGraph() {
    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = "main"
    ) {
        composable("main") {
            MainScaffold(
                onOpenDetail = { runId -> navController.navigate("runDetail/$runId") }
            )
        }
        composable(
            route = "runDetail/{runId}",
            arguments = listOf(navArgument("runId") { type = NavType.LongType })
        ) { entry ->
            val runId = entry.arguments?.getLong("runId") ?: 0L
            RunDetailScreen(
                runId = runId,
                onBack = { navController.popBackStack() }
            )
        }
    }
}

@Composable
private fun MainScaffold(onOpenDetail: (Long) -> Unit) {
    val screens = Screen.entries
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // 共享地图实例：跨 Tab 复用，避免切页反复重建 MapView 导致卡顿/崩溃
    val mapView = remember { createMapView(context) }
    val currentTab by rememberUpdatedState(selectedTab)

    // 跟随 Activity 前台状态 resume/pause；MainScaffold 销毁时先 onPause 再 onDetach
    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> if (currentTab == 0) mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onDetach()
        }
    }

    // 跟随 Tab 切换 resume/pause 地图
    LaunchedEffect(selectedTab) {
        if (selectedTab == 0) mapView.onResume() else mapView.onPause()
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                screens.forEachIndexed { index, screen ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = {
                            Icon(
                                imageVector = screen.icon,
                                contentDescription = stringResource(screen.labelRes)
                            )
                        },
                        label = { Text(stringResource(screen.labelRes)) }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                0 -> TrackingScreen(mapView = mapView)
                1 -> HistoryScreen(onOpenDetail = onOpenDetail)
                2 -> StatsScreen()
            }
        }
    }
}
