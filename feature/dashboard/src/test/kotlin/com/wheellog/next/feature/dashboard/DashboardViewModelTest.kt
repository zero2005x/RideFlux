package com.wheellog.next.feature.dashboard

import app.cash.turbine.test
import com.wheellog.next.core.testing.FakeEucRepository
import com.wheellog.next.core.testing.FakePreferencesRepository
import com.wheellog.next.core.testing.FakeTripRepository
import com.wheellog.next.core.testing.TestFixtures
import com.wheellog.next.domain.model.AlertFlag
import com.wheellog.next.domain.model.ConnectionState
import com.wheellog.next.domain.usecase.ObserveTelemetryUseCase
import com.wheellog.next.domain.usecase.TripRecorder
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
class DashboardViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeEucRepository: FakeEucRepository
    private lateinit var fakePreferencesRepository: FakePreferencesRepository
    private lateinit var fakeTripRepository: FakeTripRepository
    private lateinit var viewModel: DashboardViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeEucRepository = FakeEucRepository()
        fakePreferencesRepository = FakePreferencesRepository()
        fakeTripRepository = FakeTripRepository()
        viewModel = DashboardViewModel(
            observeTelemetry = ObserveTelemetryUseCase(fakeEucRepository),
            eucRepository = fakeEucRepository,
            preferencesRepository = fakePreferencesRepository,
            tripRecorder = TripRecorder(fakeTripRepository),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state has IDLE connection`() {
        assertEquals(ConnectionState.IDLE, viewModel.state.value.connectionState)
    }

    @Test
    fun `telemetry updates propagate to state`() = runTest {
        val telemetry = TestFixtures.sampleTelemetry
        fakeEucRepository.connectionStateFlow.value = ConnectionState.CONNECTED
        fakeEucRepository.telemetryFlow.value = telemetry
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(ConnectionState.CONNECTED, state.connectionState)
        assertEquals(telemetry.speedKmh, state.speedKmh, 0.01f)
        assertEquals(telemetry.batteryPercent, state.batteryPercent)
        assertEquals(telemetry.voltageV, state.voltageV, 0.01f)
    }

    @Test
    fun `new alert triggers ShowAlert effect`() = runTest {
        viewModel.effect.test {
            fakeEucRepository.telemetryFlow.value = TestFixtures.alertTelemetry
            testDispatcher.scheduler.advanceUntilIdle()

            val effect = awaitItem()
            assertTrue(effect is DashboardEffect.ShowAlert)
            assertEquals(AlertFlag.HIGH_TEMPERATURE, (effect as DashboardEffect.ShowAlert).flag)
        }
    }

    @Test
    fun `NavigateToScan intent emits NavigateToScan effect`() = runTest {
        viewModel.effect.test {
            viewModel.onIntent(DashboardIntent.NavigateToScan)
            testDispatcher.scheduler.advanceUntilIdle()

            val effect = awaitItem()
            assertTrue(effect is DashboardEffect.NavigateToScan)
        }
    }

    @Test
    fun `NavigateToSettings intent emits NavigateToSettings effect`() = runTest {
        viewModel.effect.test {
            viewModel.onIntent(DashboardIntent.NavigateToSettings)
            testDispatcher.scheduler.advanceUntilIdle()

            val effect = awaitItem()
            assertTrue(effect is DashboardEffect.NavigateToSettings)
        }
    }

    @Test
    fun `metric unit preference updates state`() = runTest {
        fakePreferencesRepository.setUseMetricUnits(false)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(false, viewModel.state.value.useMetricUnits)
    }
}
