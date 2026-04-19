/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.rideflux.app.ui.dashboard.DashboardRoute
import com.rideflux.app.ui.dashboard.DashboardViewModel
import com.rideflux.app.ui.hud.HudRoute
import com.rideflux.app.ui.scanner.ScannerRoute
import com.rideflux.domain.wheel.WheelFamily
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

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
) {
    NavHost(
        navController = navController,
        startDestination = Routes.SCANNER,
    ) {
        composable(Routes.SCANNER) {
            ScannerRoute(
                onDeviceSelected = { address, family ->
                    navController.navigate(Routes.dashboard(address, family))
                },
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
            val address = backStackEntry.arguments?.getString(DashboardViewModel.ARG_ADDRESS).orEmpty()
            val familyName = backStackEntry.arguments?.getString(DashboardViewModel.ARG_FAMILY)
            val family = familyName?.let { runCatching { WheelFamily.valueOf(it) }.getOrNull() }
            DashboardRoute(
                onNavigateUp = { navController.popBackStack() },
                onNavigateToHud = {
                    navController.navigate(Routes.hud(address, family))
                },
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
        ) {
            HudRoute(onNavigateUp = { navController.popBackStack() })
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
    fun dashboard(address: String, family: WheelFamily?): String {
        val encodedAddress = URLEncoder.encode(address, StandardCharsets.UTF_8.name())
        return if (family != null) {
            "dashboard/$encodedAddress?${DashboardViewModel.ARG_FAMILY}=${family.name}"
        } else {
            "dashboard/$encodedAddress"
        }
    }

    /** Build the concrete HUD route for the given wheel. */
    fun hud(address: String, family: WheelFamily?): String {
        val encodedAddress = URLEncoder.encode(address, StandardCharsets.UTF_8.name())
        return if (family != null) {
            "hud/$encodedAddress?${DashboardViewModel.ARG_FAMILY}=${family.name}"
        } else {
            "hud/$encodedAddress"
        }
    }
}
