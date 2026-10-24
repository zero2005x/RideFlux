/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.ui.scanner

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import com.rideflux.domain.repository.DiscoveredWheel
import com.rideflux.domain.wheel.WheelFamily
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * S-01 UI regression through the real composables.
 *
 * These cases click the actual scanner UI (device card, "Change brand" icon,
 * brand dialog) and assert on the callback the route installs. A route that
 * forwards `family = null` for a brand choice fails here even though the
 * resolver, codec and repository are untouched — which is exactly the defect
 * the earlier resolver-only tests could not see.
 *
 * Runs on the JVM through Robolectric; no device, no GATT, no vehicle.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScannerBrandDialogComposeTest {

    @get:Rule
    val compose = createComposeRule()

    private val address = "AA:BB:CC:DD:EE:FF"

    private fun unnamedDevice() =
        DiscoveredWheel(address = address, displayName = null, rssi = -60, family = null)

    private fun recognisedDevice() = DiscoveredWheel(
        address = address,
        displayName = "KS-18XL",
        rssi = -52,
        family = WheelFamily.K,
    )

    // -------------------------------------------------- dialog → route event

    @Test
    fun `the brand dialog emits an explicit VESC selection`() {
        var selected: ScannerSelection? = null
        compose.setContent {
            ScannerScreen(
                uiState = ScannerUiState(devices = listOf(unnamedDevice())),
                onStartScan = {},
                onStopScan = {},
                onDeviceSelected = { selected = it },
            )
        }

        compose.onNodeWithContentDescription("Change brand")
            .performSemanticsAction(SemanticsActions.OnClick)
        // The dialog has one button per family, so the last entry can sit
        // outside a small test window. Invoke the button's semantics action:
        // this asserts the click wiring, not the dialog's layout height.
        compose.onNodeWithText("VESC (read-only telemetry)")
            .performSemanticsAction(SemanticsActions.OnClick)

        val event = requireNotNull(selected) { "the brand button did not emit a selection" }
        assertTrue("expected a Brand event, got $event", event is ScannerSelection.Brand)
        event as ScannerSelection.Brand
        assertEquals(WheelFamily.VESC, event.family)
        assertEquals(address, event.device.address)
    }

    @Test
    fun `a plain tap on a recognised wheel emits a family-free scan selection`() {
        var selected: ScannerSelection? = null
        compose.setContent {
            ScannerScreen(
                uiState = ScannerUiState(devices = listOf(recognisedDevice())),
                onStartScan = {},
                onStopScan = {},
                onDeviceSelected = { selected = it },
            )
        }

        compose.onNodeWithText("KS-18XL").performClick()

        val event = requireNotNull(selected) { "the card tap did not emit a selection" }
        assertTrue("expected a Scanned event, got $event", event is ScannerSelection.Scanned)
        assertEquals(address, event.device.address)
    }

    // ------------------------------------------------ route → navigation args

    private fun routeViewModel(): ScannerViewModel {
        val viewModel = mockk<ScannerViewModel>(relaxed = true)
        every { viewModel.uiState } returns MutableStateFlow(
            ScannerUiState(devices = listOf(unnamedDevice())),
        )
        return viewModel
    }

    @Test
    fun `the scanner route forwards a brand choice as a manual family`() {
        var navigated: WheelNavigationSelection? = null
        compose.setContent {
            ScannerRoute(
                onDeviceSelected = { address, family, category ->
                    navigated = WheelNavigationSelection(address, family, category)
                },
                onOpenSettings = {},
                onOpenTripHistory = {},
                viewModel = routeViewModel(),
            )
        }

        compose.onNodeWithContentDescription("Change brand")
            .performSemanticsAction(SemanticsActions.OnClick)
        // The dialog has one button per family, so the last entry can sit
        // outside a small test window. Invoke the button's semantics action:
        // this asserts the click wiring, not the dialog's layout height.
        compose.onNodeWithText("VESC (read-only telemetry)")
            .performSemanticsAction(SemanticsActions.OnClick)

        val target = requireNotNull(navigated) { "the scanner route did not navigate" }
        assertEquals(
            "the brand choice was dropped before navigation (the S-01 defect)",
            WheelFamily.VESC,
            target.family,
        )
        assertEquals(address, target.address)
    }

    @Test
    fun `the scanner route keeps a plain tap family-free`() {
        var navigated: WheelNavigationSelection? = null
        val viewModel = mockk<ScannerViewModel>(relaxed = true)
        every { viewModel.uiState } returns MutableStateFlow(
            ScannerUiState(devices = listOf(recognisedDevice())),
        )
        compose.setContent {
            ScannerRoute(
                onDeviceSelected = { address, family, category ->
                    navigated = WheelNavigationSelection(address, family, category)
                },
                onOpenSettings = {},
                onOpenTripHistory = {},
                viewModel = viewModel,
            )
        }

        compose.onNodeWithText("KS-18XL").performClick()

        val target = requireNotNull(navigated) { "the scanner route did not navigate" }
        assertNull("a plain tap must not become a manual family", target.family)
        assertEquals(address, target.address)
    }
}
