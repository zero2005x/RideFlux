package com.wheellog.next.feature.settings

import app.cash.turbine.test
import com.wheellog.next.core.testing.FakePreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakePreferencesRepository: FakePreferencesRepository
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakePreferencesRepository = FakePreferencesRepository()
        viewModel = SettingsViewModel(
            preferencesRepository = fakePreferencesRepository,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state has default preferences`() {
        assertEquals(true, viewModel.state.value.useMetricUnits)
        assertEquals(30f, viewModel.state.value.overspeedThresholdKmh, 0.01f)
    }

    @Test
    fun `SetMetricUnits intent updates state`() = runTest {
        viewModel.onIntent(SettingsIntent.SetMetricUnits(false))
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(false, viewModel.state.value.useMetricUnits)
    }

    @Test
    fun `SetOverspeedThreshold intent updates state`() = runTest {
        viewModel.onIntent(SettingsIntent.SetOverspeedThreshold(45f))
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(45f, viewModel.state.value.overspeedThresholdKmh, 0.01f)
    }

    @Test
    fun `preference changes propagate to state`() = runTest {
        fakePreferencesRepository.setUseMetricUnits(false)
        fakePreferencesRepository.setOverspeedThresholdKmh(50f)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(false, viewModel.state.value.useMetricUnits)
        assertEquals(50f, viewModel.state.value.overspeedThresholdKmh, 0.01f)
    }
}
