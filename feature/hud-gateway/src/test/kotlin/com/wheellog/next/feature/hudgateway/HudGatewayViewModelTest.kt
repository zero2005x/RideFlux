package com.wheellog.next.feature.hudgateway

import app.cash.turbine.test
import com.wheellog.next.core.testing.FakeHudGatewayRepository
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
class HudGatewayViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeHudGateway: FakeHudGatewayRepository
    private lateinit var viewModel: HudGatewayViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeHudGateway = FakeHudGatewayRepository()
        viewModel = HudGatewayViewModel(
            hudGateway = fakeHudGateway,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is not advertising`() {
        assertEquals(false, viewModel.state.value.isAdvertising)
    }

    @Test
    fun `ToggleAdvertising starts advertising`() = runTest {
        viewModel.onIntent(HudGatewayIntent.ToggleAdvertising)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(true, viewModel.state.value.isAdvertising)
        assertEquals(1, fakeHudGateway.startAdvertisingCount)
    }

    @Test
    fun `ToggleAdvertising twice stops advertising`() = runTest {
        viewModel.onIntent(HudGatewayIntent.ToggleAdvertising)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onIntent(HudGatewayIntent.ToggleAdvertising)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(false, viewModel.state.value.isAdvertising)
        assertEquals(1, fakeHudGateway.stopAdvertisingCount)
    }
}
