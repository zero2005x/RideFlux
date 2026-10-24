/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.ui.scanner

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performClick
import com.rideflux.domain.device.PlevCategory
import com.rideflux.domain.garage.VehicleCalibration
import com.rideflux.domain.garage.vehicleProfile
import com.rideflux.domain.settings.AlertThresholds
import com.rideflux.domain.wheel.WheelFamily
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Saved-vehicle UI enters the *persisted* profile path, not a scan result.
 * The click must forward the profile's family and category so a scooter is
 * not re-routed as a wheel after a restart.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SavedVehicleComposeTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a saved wheel profile navigates with its persisted family`() {
        var selected: com.rideflux.domain.garage.VehicleProfile? = null
        val profile = vehicleProfile(
            address = "AA:BB:CC:DD:EE:FF",
            category = PlevCategory.WHEEL,
            family = WheelFamily.I2,
            displayName = "Tourer",
        )
        compose.setContent {
            ScannerScreen(
                uiState = ScannerUiState(savedVehicles = listOf(profile)),
                onStartScan = {},
                onStopScan = {},
                onDeviceSelected = {},
                onSavedVehicleSelected = { selected = it },
            )
        }

        compose.onNodeWithText("Tourer").performClick()

        assertEquals(profile, selected)
        assertEquals(WheelFamily.I2, selected?.family)
        assertEquals(PlevCategory.WHEEL, selected?.category)
    }

    @Test
    fun `the production Save emits name family alerts and display calibration once`() {
        val profile = vehicleProfile(
            address = "AA:BB:CC:DD:EE:31",
            category = PlevCategory.WHEEL,
            family = WheelFamily.G,
            displayName = "Original",
        )
        var savedAddress: String? = null
        var savedName: String? = null
        var savedFamily: WheelFamily? = null
        var savedThresholds: AlertThresholds? = null
        var savedCalibration: VehicleCalibration? = null
        compose.setContent {
            ScannerScreen(
                uiState = ScannerUiState(savedVehicles = listOf(profile)),
                onStartScan = {},
                onStopScan = {},
                onDeviceSelected = {},
                onSaveSavedVehicle = { address, name, family, thresholds, calibration ->
                    savedAddress = address
                    savedName = name
                    savedFamily = family
                    savedThresholds = thresholds
                    savedCalibration = calibration
                },
            )
        }

        compose.onNodeWithContentDescription("Edit vehicle").performClick()
        compose.onNodeWithText("Use per-vehicle alerts").performClick()
        compose.onNodeWithTag("vehicle_speed").performTextClearance()
        compose.onNodeWithTag("vehicle_speed").performTextInput("33")
        compose.onNodeWithTag("vehicle_pitch").performTextClearance()
        compose.onNodeWithTag("vehicle_pitch").performTextInput("1.5")
        compose.onNodeWithTag("vehicle_roll").performTextClearance()
        compose.onNodeWithTag("vehicle_roll").performTextInput("-2")
        compose.onNodeWithText("Save").performClick()

        assertEquals(profile.address, savedAddress)
        assertEquals("Original", savedName)
        assertEquals(WheelFamily.G, savedFamily)
        assertEquals(33f, savedThresholds!!.speedLimitKmh)
        assertEquals(1.5f, savedCalibration!!.pitchOffsetDegrees)
        assertEquals(-2f, savedCalibration!!.rollOffsetDegrees)
    }

    @Test
    fun `a saved scooter profile keeps its category after restart`() {
        var selected: com.rideflux.domain.garage.VehicleProfile? = null
        val profile = vehicleProfile(
            address = "02:00:00:00:00:03",
            category = PlevCategory.SCOOTER,
            family = null,
            displayName = "Scooter",
        )
        compose.setContent {
            ScannerScreen(
                uiState = ScannerUiState(savedVehicles = listOf(profile)),
                onStartScan = {},
                onStopScan = {},
                onDeviceSelected = {},
                onSavedVehicleSelected = { selected = it },
            )
        }

        compose.onNodeWithText("Scooter").performClick()

        assertEquals(PlevCategory.SCOOTER, selected?.category)
        assertEquals(null, selected?.family)
    }
}
