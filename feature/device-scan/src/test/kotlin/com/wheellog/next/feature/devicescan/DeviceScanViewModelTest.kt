package com.wheellog.next.feature.devicescan

import app.cash.turbine.test
import com.wheellog.next.core.testing.FakeEucRepository
import com.wheellog.next.core.testing.FakePreferencesRepository
import com.wheellog.next.domain.repository.DiscoveredDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceScanViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeEucRepository: FakeEucRepository
    private lateinit var fakePreferencesRepository: FakePreferencesRepository
    private lateinit var viewModel: DeviceScanViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeEucRepository = FakeEucRepository()
        fakePreferencesRepository = FakePreferencesRepository()
        viewModel = DeviceScanViewModel(
            eucRepository = fakeEucRepository,
            preferencesRepository = fakePreferencesRepository,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is not scanning`() {
        assertEquals(false, viewModel.state.value.isScanning)
        assertTrue(viewModel.state.value.devices.isEmpty())
    }

    @Test
    fun `StartScan sets isScanning to true`() = runTest {
        viewModel.onIntent(DeviceScanIntent.StartScan)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(true, viewModel.state.value.isScanning)
    }

    @Test
    fun `StopScan sets isScanning to false`() = runTest {
        viewModel.onIntent(DeviceScanIntent.StartScan)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onIntent(DeviceScanIntent.StopScan)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(false, viewModel.state.value.isScanning)
    }

    @Test
    fun `SelectDevice connects and emits NavigateToDashboard`() = runTest {
        viewModel.effect.test {
            viewModel.onIntent(DeviceScanIntent.SelectDevice("AA:BB:CC:DD:EE:FF"))
            testDispatcher.scheduler.advanceUntilIdle()

            assertTrue(fakeEucRepository.connectCalled)
            assertEquals("AA:BB:CC:DD:EE:FF", fakeEucRepository.lastConnectedAddress)

            val effect = awaitItem()
            assertTrue(effect is DeviceScanEffect.NavigateToDashboard)
        }
    }

    @Test
    fun `SelectDevice saves the address to preferences`() = runTest {
        viewModel.onIntent(DeviceScanIntent.SelectDevice("AA:BB:CC:DD:EE:FF"))
        testDispatcher.scheduler.advanceUntilIdle()

        fakePreferencesRepository.lastDeviceAddress().test {
            assertEquals("AA:BB:CC:DD:EE:FF", awaitItem())
        }
    }
}
