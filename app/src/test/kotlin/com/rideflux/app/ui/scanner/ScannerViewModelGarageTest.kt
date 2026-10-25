/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.ui.scanner

import com.rideflux.domain.device.PlevCategory
import com.rideflux.domain.device.ScooterDevice
import com.rideflux.domain.garage.InMemoryVehicleProfileStore
import com.rideflux.domain.garage.VehicleCalibration
import com.rideflux.domain.settings.AlertThresholds
import com.rideflux.domain.repository.DiscoveredWheel
import com.rideflux.domain.repository.ScooterRepository
import com.rideflux.domain.repository.WheelRepository
import com.rideflux.domain.wheel.WheelFamily
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScannerViewModelGarageTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Before
    fun setUp() = Unit

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `brand selection is persisted and removal does not touch another vehicle`() = runBlocking {
        val store = InMemoryVehicleProfileStore()
        val viewModel = ScannerViewModel(NoWheels, NoScooters, store)
        val device = DiscoveredWheel("AA:BB:CC:DD:EE:01", "Mystery", -50, null)
        val other = DiscoveredWheel("AA:BB:CC:DD:EE:02", "Other", -55, WheelFamily.G)

        viewModel.recordSelection(ScannerSelection.Brand(device, WheelFamily.I2))
        viewModel.recordSelection(ScannerSelection.Brand(other, WheelFamily.G))
        await("two saved profiles") { store.profiles.value.takeIf { it.size == 2 } }

        val saved = store.profile("AA:BB:CC:DD:EE:01")!!
        assertEquals(WheelFamily.I2, saved.family)
        assertEquals(PlevCategory.WHEEL, saved.category)
        assertEquals("Mystery", saved.displayName)

        viewModel.removeVehicle("AA:BB:CC:DD:EE:01")
        await("one saved profile") { store.profiles.value.takeIf { it.size == 1 } }
        assertNull(store.profile("AA:BB:CC:DD:EE:01"))
        assertTrue("deletion must retire the persisted family hint",
            !store.familyHints.value.containsKey("AA:BB:CC:DD:EE:01"))
        assertEquals(WheelFamily.G, store.profile("AA:BB:CC:DD:EE:02")!!.family)
    }

    @Test
    fun `scooter selection and rename keep the vehicle scope`() = runBlocking {
        val store = InMemoryVehicleProfileStore()
        val viewModel = ScannerViewModel(NoWheels, NoScooters, store)
        viewModel.recordScooterSelection(ScooterDevice("02:00:00:00:00:03", "ES2"))
        await("saved scooter") { store.profile("02:00:00:00:00:03") }

        val scooter = store.profile("02:00:00:00:00:03")!!
        assertEquals(PlevCategory.SCOOTER, scooter.category)
        assertNull(scooter.family)

        viewModel.renameVehicle("02:00:00:00:00:03", "City scooter")
        await("renamed scooter") {
            store.profile("02:00:00:00:00:03")?.takeIf { it.displayName == "City scooter" }
        }
        assertEquals(PlevCategory.SCOOTER, store.profile("02:00:00:00:00:03")!!.category)
    }

    @Test
    fun `production save is one atomic update and survives a recreated store`() = runBlocking {
        val address = "AA:BB:CC:DD:EE:10"
        val store = InMemoryVehicleProfileStore(listOf(
            com.rideflux.domain.garage.vehicleProfile(
                address = address,
                category = PlevCategory.WHEEL,
                family = WheelFamily.G,
                displayName = "Original",
            ),
        ))
        val viewModel = ScannerViewModel(NoWheels, NoScooters, store)
        viewModel.saveVehicle(
            address = address,
            name = "New name",
            family = WheelFamily.V,
            alertThresholds = AlertThresholds(
                speedLimitKmh = 33f,
                temperatureLimitC = 71f,
                lowBatteryPercent = 21f,
                pwmAlertPercent = 88f,
                enabled = true,
            ),
            calibration = VehicleCalibration(pitchOffsetDegrees = 1.5f, rollOffsetDegrees = -2f),
        )
        await("atomic save") {
            store.profile(address)?.takeIf {
                it.displayName == "New name" && it.family == WheelFamily.V
            }
        }
        val saved = store.profile(address)!!
        assertEquals(33f, saved.alertThresholds!!.speedLimitKmh)
        assertEquals(1.5f, saved.calibration.pitchOffsetDegrees)

        // Recreating the repository/ViewModel from the persisted records must
        // keep both fields, not only the one from the second callback.
        val restartedStore = InMemoryVehicleProfileStore(store.profiles.value)
        val restarted = ScannerViewModel(NoWheels, NoScooters, restartedStore)
        assertEquals("New name", restartedStore.profile(address)!!.displayName)
        assertEquals(WheelFamily.V, restartedStore.profile(address)!!.family)
        assertEquals(33f, restartedStore.profile(address)!!.alertThresholds!!.speedLimitKmh)

        restarted.resetVehicleOverrides(address)
        await("reset overrides") {
            restartedStore.profile(address)?.takeIf {
                it.alertThresholds == null && it.calibration == VehicleCalibration()
            }
        }
        assertEquals("New name", restartedStore.profile(address)!!.displayName)
        assertEquals(WheelFamily.V, restartedStore.profile(address)!!.family)
    }

    @Test
    fun `an advertisement name is not persisted as a verified model`() = runBlocking {
        val store = InMemoryVehicleProfileStore()
        val viewModel = ScannerViewModel(NoWheels, NoScooters, store)
        viewModel.recordSelection(
            ScannerSelection.Scanned(DiscoveredWheel("AA:BB:CC:DD:EE:11", "advertised name", -50, null)),
        )
        await("profile") { store.profile("AA:BB:CC:DD:EE:11") }
        assertNull("advertisement displayName must not become model evidence",
            store.profile("AA:BB:CC:DD:EE:11")!!.modelName)
        assertEquals("advertised name", store.profile("AA:BB:CC:DD:EE:11")!!.displayName)
    }

    private suspend fun <T : Any> await(what: String, probe: suspend () -> T?): T {
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            probe()?.let { return it }
            delay(10)
        }
        error("timed out waiting for $what")
    }

    private object NoWheels : WheelRepository {
        override fun scan(): Flow<List<DiscoveredWheel>> = flowOf(emptyList())
        override fun activeConnections(): Flow<Map<String, com.rideflux.domain.connection.WheelConnection>> =
            flowOf(emptyMap())
        override suspend fun connect(
            address: String,
            expectedFamily: WheelFamily?,
        ): com.rideflux.domain.connection.WheelConnection = error("not used")
    }

    private object NoScooters : ScooterRepository {
        override fun scan(): Flow<List<ScooterDevice>> = flowOf(emptyList())
        override fun activeConnections(): Flow<Map<String, com.rideflux.domain.connection.ScooterConnection>> =
            flowOf(emptyMap())
        override suspend fun connect(address: String): com.rideflux.domain.connection.ScooterConnection =
            error("not used")
    }
}
