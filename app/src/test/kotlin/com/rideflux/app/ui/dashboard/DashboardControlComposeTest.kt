/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.ui.dashboard

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.rideflux.domain.connection.ConnectionState
import com.rideflux.domain.wheel.WheelControlContract
import com.rideflux.domain.wheel.WheelIdentityEvidence
import com.rideflux.domain.wheel.WheelSupport
import com.rideflux.domain.wheel.WheelTelemetryCoverage
import com.rideflux.protocol.familyi2.InmotionI2WheelCodec
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Real Compose entry-point regressions for capability-driven controls.
 *
 * These render [DashboardScreen] itself (not an extracted helper), so a
 * production wiring mistake — e.g. passing `enabled = Ready` to every control —
 * fails here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DashboardControlComposeTest {

    @get:Rule
    val compose = createComposeRule()

    private fun render(
        state: DashboardUiState,
        onHeadlight: (Boolean) -> Unit = {},
        onBeep: () -> Unit = {},
    ) {
        compose.setContent {
            DashboardScreen(
                uiState = state,
                history = emptyList(),
                events = emptyList(),
                activeAlert = null,
                onNavigateUp = {},
                onSetHeadlight = onHeadlight,
                onBeep = onBeep,
            )
        }
    }

    @Test
    fun `a read-only wheel renders every control disabled`() {
        render(
            DashboardUiState(
                connectionState = ConnectionState.Ready,
                capabilities = InmotionI2WheelCodec.READ_ONLY_CAPABILITIES,
                support = WheelSupport.FAMILY_ONLY,
                speedKmh = 0f,
            ),
        )

        compose.onNodeWithTag("dashboard_control_headlight").assertIsNotEnabled()
        compose.onNodeWithTag("dashboard_control_beep").assertIsNotEnabled()
        compose.onNodeWithTag("dashboard_control_ride_mode_0").assertIsNotEnabled()
        compose.onNodeWithTag("dashboard_control_speed_limit").assertIsNotEnabled()
        compose.onNodeWithTag("dashboard_control_calibrate").assertIsNotEnabled()
        compose.onNodeWithTag("dashboard_control_power_off").assertIsNotEnabled()
        compose.onNodeWithText("Model not recognised. Controls are disabled; only core telemetry is shown.")
            .assertExists()
    }

    @Test
    fun `a supported wheel enables the advertised controls`() {
        render(
            DashboardUiState(
                connectionState = ConnectionState.Ready,
                capabilities = InmotionI2WheelCodec.DEFAULT_CAPABILITIES,
                support = WheelSupport(
                    identityEvidence = WheelIdentityEvidence.MODEL_AND_FIRMWARE,
                    telemetryCoverage = WheelTelemetryCoverage.MODEL_LAYOUT,
                    controlContract = WheelControlContract.FAMILY_CLOSED,
                ),
                speedKmh = 0f,
            ),
        )

        compose.onNodeWithTag("dashboard_control_headlight").assertIsEnabled()
        compose.onNodeWithTag("dashboard_control_beep").assertIsEnabled()
        compose.onNodeWithTag("dashboard_control_ride_mode_0").assertIsEnabled()
        compose.onNodeWithTag("dashboard_control_speed_limit").assertIsEnabled()
        compose.onNodeWithTag("dashboard_control_calibrate").assertIsEnabled()
        compose.onNodeWithTag("dashboard_control_power_off").assertIsEnabled()
    }

    @Test
    fun `a pending headlight disables the switch and a failure is rendered`() {
        render(
            DashboardUiState(
                connectionState = ConnectionState.Ready,
                capabilities = InmotionI2WheelCodec.DEFAULT_CAPABILITIES,
                support = WheelSupport(
                    identityEvidence = WheelIdentityEvidence.MODEL_AND_FIRMWARE,
                    telemetryCoverage = WheelTelemetryCoverage.MODEL_LAYOUT,
                    controlContract = WheelControlContract.FAMILY_CLOSED,
                ),
                speedKmh = 0f,
                headlightPending = true,
                commandFailure = CommandFailure(
                    "set headlight",
                    CommandFailureReason.TRANSPORT_ERROR,
                ),
            ),
        )

        compose.onNodeWithTag("dashboard_control_headlight").assertIsNotEnabled()
        compose.onNodeWithText("Command did not reach the wheel.").assertExists()
    }
}
