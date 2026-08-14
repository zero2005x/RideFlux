/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import android.net.Uri
import android.util.Log
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.hilt.navigation.compose.hiltViewModel
import com.rideflux.app.ui.dashboard.DashboardRoute
import com.rideflux.app.ui.dashboard.DashboardViewModel
import com.rideflux.app.ui.hud.HudRoute
import com.rideflux.app.ui.scanner.ScannerRoute
import com.rideflux.app.ui.settings.SettingsRoute
import com.rideflux.app.ui.trips.TripDetailRoute
import com.rideflux.app.ui.trips.TripDetailViewModel
import com.rideflux.app.ui.trips.TripHistoryRoute
import com.rideflux.domain.wheel.WheelFamily

private const val TAG = "RideFluxNavHost"

/**
 * Top-level navigation graph for RideFlux.
 *
 *  * [Routes.SCANNER] hosts the BLE device-scan screen. A tap on any
 *    discovered wheel navigates to the dashboard with the selected
 *    `address` (and optional `family`).
 *  * [Routes.DASHBOARD] hosts the live-telemetry screen. Its
 *    [DashboardViewModel] picks the nav arguments up via
 *    `SavedStateHandle` using the
 *    [DashboardViewModel.ARG_ADDRESS] / [DashboardViewModel.ARG_FAMILY]
 *    keys.
 */
@Composable
fun RideFluxNavHost(
    navController: NavHostController = rememberNavController(),
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = Routes.SCANNER,
        modifier = modifier,
    ) {
        composable(Routes.SCANNER) {
            ScannerRoute(
                onDeviceSelected = { address, family ->
                    // Coalesce rapid double-taps so a device cannot be
                    // pushed twice onto the back stack (which would also
                    // confuse the pattern-only VM lookup below).
                    navController.navigate(Routes.dashboard(address, family)) {
                        launchSingleTop = true
                    }
                },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) { launchSingleTop = true } },
                onOpenTripHistory = { navController.navigate(Routes.TRIP_HISTORY) { launchSingleTop = true } },
            )
        }
        composable(
            route = Routes.DASHBOARD_PATTERN,
            arguments = listOf(
                navArgument(DashboardViewModel.ARG_ADDRESS) {
                    type = NavType.StringType
                    nullable = false
                },
                navArgument(DashboardViewModel.ARG_FAMILY) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { backStackEntry ->
            val address = backStackEntry.arguments
                ?.getString(DashboardViewModel.ARG_ADDRESS)
            if (address == null) {
                LaunchedEffect(backStackEntry) { navController.popBackStack() }
                return@composable
            }
            val familyName = backStackEntry.arguments?.getString(DashboardViewModel.ARG_FAMILY)
            val family = familyName?.let {
                runCatching { WheelFamily.valueOf(it) }.getOrElse { e ->
                    Log.w(TAG, "Unrecognised wheel family '$it'; treating as unknown", e)
                    null
                }
            }
            DashboardRoute(
                onNavigateUp = { navController.popBackStack() },
                onNavigateToHud = {
                    navController.navigate(Routes.hud(address, family)) {
                        launchSingleTop = true
                    }
                },
                onOpenTrip = { tripId -> navController.navigate(Routes.trip(tripId)) },
            )
        }
        composable(
            route = Routes.HUD_PATTERN,
            arguments = listOf(
                navArgument(DashboardViewModel.ARG_ADDRESS) {
                    type = NavType.StringType
                    nullable = false
                },
                navArgument(DashboardViewModel.ARG_FAMILY) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { hudEntry ->
            // The HUD must show the exact same telemetry as the
            // dashboard, so share the dashboard's DashboardViewModel
            // instance rather than minting a new one scoped to the
            // HUD entry (which would open a second BLE connection).
            //
            // Guard the lookup by the HUD's own address argument: the
            // pattern-only getBackStackEntry would otherwise hand back
            // a dashboard entry for a DIFFERENT wheel (e.g. wheel A
            // selected, back to scanner, then wheel B) and silently
            // show the wrong telemetry.
            val hudAddress = hudEntry.arguments
                ?.getString(DashboardViewModel.ARG_ADDRESS)
            if (hudAddress == null) {
                LaunchedEffect(hudEntry) { navController.popBackStack() }
                return@composable
            }
            val dashboardEntry = navController.previousBackStackEntry?.takeIf { entry ->
                entry.destination.route == Routes.DASHBOARD_PATTERN &&
                    entry.arguments?.getString(DashboardViewModel.ARG_ADDRESS) == hudAddress
            }
            val viewModel = if (dashboardEntry != null) {
                hiltViewModel<DashboardViewModel>(viewModelStoreOwner = dashboardEntry)
            } else {
                // Fallback: no matching dashboard entry on the stack.
                // Scope a fresh VM to the HUD entry explicitly rather
                // than silently sharing a mismatched one.
                hiltViewModel<DashboardViewModel>()
            }
            HudRoute(
                onNavigateUp = { navController.popBackStack() },
                viewModel = viewModel,
            )
        }
        composable(Routes.SETTINGS) {
            SettingsRoute(
                onNavigateUp = { navController.popBackStack() },
                onOpenTripHistory = { navController.navigate(Routes.TRIP_HISTORY) },
            )
        }
        composable(Routes.TRIP_HISTORY) {
            TripHistoryRoute(
                onNavigateUp = { navController.popBackStack() },
                onOpenTrip = { navController.navigate(Routes.trip(it)) },
            )
        }
        composable(
            route = Routes.TRIP_PATTERN,
            arguments = listOf(navArgument(TripDetailViewModel.ARG_TRIP_ID) { type = NavType.LongType }),
        ) {
            TripDetailRoute(
                onNavigateUp = { navController.popBackStack() },
                onDeleted = { navController.popBackStack() },
            )
        }
    }
}

/**
 * Centralised route constants + builders. Keeping the pattern and
 * the call-site builder in one place avoids drift between the
 * [NavHost] declaration and the callers that trigger navigation.
 */
object Routes {
    const val SCANNER: String = "scanner"
    const val SETTINGS: String = "settings"
    const val TRIP_HISTORY: String = "trip-history"
    const val TRIP_PATTERN: String = "trip/{${TripDetailViewModel.ARG_TRIP_ID}}"

    /** Navigation argument pattern used in [NavHost]. */
    const val DASHBOARD_PATTERN: String =
        "dashboard/{${DashboardViewModel.ARG_ADDRESS}}?${DashboardViewModel.ARG_FAMILY}={${DashboardViewModel.ARG_FAMILY}}"

    /** Navigation argument pattern for the AR HUD surface. */
    const val HUD_PATTERN: String =
        "hud/{${DashboardViewModel.ARG_ADDRESS}}?${DashboardViewModel.ARG_FAMILY}={${DashboardViewModel.ARG_FAMILY}}"

    /**
     * Build the concrete dashboard route. MAC addresses are
     * URL-encoded defensively even though `:` is technically
     * allowed in path segments — this keeps future, more exotic
     * identifiers (e.g. UUIDs) safe.
     */
    fun dashboard(address: String, family: WheelFamily?): String =
        buildRoute("dashboard", address, family)

    /** Build the concrete HUD route for the given wheel. */
    fun hud(address: String, family: WheelFamily?): String =
        buildRoute("hud", address, family)

    fun trip(tripId: Long): String = "trip/$tripId"

    /** Shared encode-and-build logic so dashboard/hud never drift. */
    private fun buildRoute(destination: String, address: String, family: WheelFamily?): String {
        val encodedAddress = Uri.encode(address)
        return if (family != null) {
            "$destination/$encodedAddress?${DashboardViewModel.ARG_FAMILY}=${Uri.encode(family.name)}"
        } else {
            "$destination/$encodedAddress"
        }
    }
}
