package com.wheellog.next.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import com.wheellog.next.service.BleConnectionService
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.wheellog.next.feature.dashboard.DashboardScreen
import com.wheellog.next.feature.devicescan.DeviceScanScreen
import com.wheellog.next.feature.hudgateway.HudGatewayScreen
import com.wheellog.next.feature.settings.SettingsScreen
import com.wheellog.next.feature.trips.TripDetailScreen
import com.wheellog.next.feature.trips.TripsScreen

/** Top-level navigation routes. */
object Routes {
    const val DASHBOARD = "dashboard"
    const val DEVICE_SCAN = "device_scan"
    const val TRIPS = "trips"
    const val TRIP_DETAIL = "trip_detail/{tripId}"
    const val SETTINGS = "settings"
    const val HUD_GATEWAY = "hud_gateway"

    fun tripDetail(tripId: Long) = "trip_detail/$tripId"
}

/** Bottom navigation tab definition. */
private data class BottomNavItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val bottomNavItems = listOf(
    BottomNavItem(Routes.DASHBOARD, "Dashboard", Icons.Default.Speed),
    BottomNavItem(Routes.TRIPS, "Trips", Icons.AutoMirrored.Filled.ListAlt),
    BottomNavItem(Routes.HUD_GATEWAY, "HUD", Icons.Default.Visibility),
    BottomNavItem(Routes.SETTINGS, "Settings", Icons.Default.Settings),
)

@Composable
fun RideFluxNavHost() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    // Hide bottom bar on detail screens
    val showBottomBar = currentDestination?.route != Routes.DEVICE_SCAN &&
        currentDestination?.route != Routes.TRIP_DETAIL

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                ) {
                    bottomNavItems.forEach { item ->
                        val selected = currentDestination?.hierarchy?.any {
                            it.route == item.route
                        } == true

                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(item.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(item.icon, contentDescription = item.label)
                            },
                            label = { Text(item.label) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.DASHBOARD,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Routes.DASHBOARD) {
                DashboardScreen(
                    onNavigateToScan = { navController.navigate(Routes.DEVICE_SCAN) },
                    onNavigateToSettings = {
                        navController.navigate(Routes.SETTINGS) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
            composable(Routes.DEVICE_SCAN) {
                val context = LocalContext.current
                DeviceScanScreen(
                    onNavigateToDashboard = {
                        BleConnectionService.start(context)
                        navController.navigate(Routes.DASHBOARD) {
                            popUpTo(Routes.DASHBOARD) { inclusive = true }
                        }
                    },
                )
            }
            composable(Routes.TRIPS) {
                TripsScreen(
                    onTripClick = { tripId ->
                        navController.navigate(Routes.tripDetail(tripId))
                    },
                )
            }
            composable(
                route = Routes.TRIP_DETAIL,
                arguments = listOf(navArgument("tripId") { type = NavType.LongType }),
            ) {
                TripDetailScreen(
                    onNavigateBack = { navController.popBackStack() },
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen()
            }
            composable(Routes.HUD_GATEWAY) {
                HudGatewayScreen()
            }
        }
    }
}
