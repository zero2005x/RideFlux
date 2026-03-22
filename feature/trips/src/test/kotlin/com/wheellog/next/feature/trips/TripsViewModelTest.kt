package com.wheellog.next.feature.trips

import app.cash.turbine.test
import com.wheellog.next.core.testing.FakeTripRepository
import com.wheellog.next.domain.model.Trip
import com.wheellog.next.domain.model.WheelType
import com.wheellog.next.domain.usecase.DeleteTripUseCase
import com.wheellog.next.domain.usecase.ExportTripsUseCase
import com.wheellog.next.domain.usecase.ObserveTripsUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TripsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeTripRepository: FakeTripRepository
    private lateinit var viewModel: TripsViewModel

    private val sampleTrip = Trip(
        id = 1,
        startTimeMillis = 1_700_000_000_000L,
        endTimeMillis = 1_700_000_600_000L,
        distanceKm = 5.5f,
        maxSpeedKmh = 32f,
        avgSpeedKmh = 22f,
        deviceAddress = "AA:BB:CC:DD:EE:FF",
        wheelType = WheelType.KING_SONG,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeTripRepository = FakeTripRepository()
        viewModel = TripsViewModel(
            observeTrips = ObserveTripsUseCase(fakeTripRepository),
            deleteTrip = DeleteTripUseCase(fakeTripRepository),
            exportTrips = ExportTripsUseCase(fakeTripRepository),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is loading`() {
        assertTrue(viewModel.state.value.isLoading)
    }

    @Test
    fun `LoadTrips populates state`() = runTest {
        fakeTripRepository.saveTrip(sampleTrip)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.state.value
        assertFalse(state.isLoading)
        assertEquals(1, state.trips.size)
        assertEquals(5.5f, state.trips[0].distanceKm, 0.01f)
    }

    @Test
    fun `DeleteTrip removes trip and emits effect`() = runTest {
        fakeTripRepository.saveTrip(sampleTrip)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.effect.test {
            viewModel.onIntent(TripsIntent.DeleteTrip(1))
            testDispatcher.scheduler.advanceUntilIdle()

            val effect = awaitItem()
            assertTrue(effect is TripsEffect.TripDeleted)
        }

        assertEquals(0, viewModel.state.value.trips.size)
    }

    @Test
    fun `ExportAllTrips emits ShareCsv effect`() = runTest {
        fakeTripRepository.saveTrip(sampleTrip)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.effect.test {
            viewModel.onIntent(TripsIntent.ExportAllTrips)
            testDispatcher.scheduler.advanceUntilIdle()

            val effect = awaitItem()
            assertTrue(effect is TripsEffect.ShareCsv)
            val csv = (effect as TripsEffect.ShareCsv).csvContent
            assertTrue(csv.contains("id,start_time"))
            assertTrue(csv.contains("KING_SONG"))
        }
    }
}
