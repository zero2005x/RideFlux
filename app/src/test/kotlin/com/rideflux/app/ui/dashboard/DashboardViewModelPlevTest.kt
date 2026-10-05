package com.rideflux.app.ui.dashboard

import com.rideflux.domain.command.CommandOutcome
import com.rideflux.domain.connection.ConnectionState
import com.rideflux.domain.connection.MiRegistrationState
import com.rideflux.domain.connection.ScooterConnection
import com.rideflux.domain.connection.ScooterHandshakeState
import com.rideflux.domain.device.PlevCategory
import com.rideflux.domain.device.ScooterDevice
import com.rideflux.domain.telemetry.ScooterTelemetry
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class DashboardViewModelPlevTest {
    private val scooter = object : ScooterConnection {
        override val device = ScooterDevice("AA", "ES2")
        override val state = MutableStateFlow<ConnectionState>(ConnectionState.Ready)
        override val telemetry = MutableStateFlow<ScooterTelemetry?>(null)
        override val handshakeState = MutableStateFlow(ScooterHandshakeState.UNBONDED)
        override val lockSupported = true // synthetic verified-control profile
        override suspend fun start() = Unit
        override suspend fun close() = Unit
        override suspend fun lock(): CommandOutcome = CommandOutcome.Success
        override suspend fun unlock(): CommandOutcome = CommandOutcome.Success
        override suspend fun readRegister(register: Int, readLength: Int): ByteArray? = null
    }

    @Test fun `scooter telemetry hides missing wheel metrics and gates lock on motion`() {
        val stationary = scooterDashboardState(scooter, ConnectionState.Ready,
            ScooterTelemetry(1, speedKmh = 0f, batteryPercent = 72f, tripDistanceMetres = 600),
            ScooterHandshakeState.READY_FOR_TELEMETRY, null)
        assertEquals(PlevCategory.SCOOTER, stationary.deviceCategory)
        assertEquals("ES2", stationary.deviceModel)
        assertEquals(72f, stationary.batteryPercent)
        assertNull(stationary.pwmPercent)
        assertNull(stationary.phaseCurrentA)
        assertNull(stationary.mosTemperatureC)
        assertTrue(isScooterLockActionEnabled(stationary))
        assertFalse(isScooterLockActionEnabled(stationary.copy(speedKmh = 15f)))
        assertFalse(isScooterLockActionEnabled(stationary.copy(speedKmh = null)))
    }

    @Test fun `confirmation state exposes twenty second countdown only while waiting`() {
        val waiting = scooterDashboardState(scooter, ConnectionState.ScooterHandshaking,
            null, ScooterHandshakeState.WAITING_FOR_USER_CONFIRMATION, 20)
        assertEquals(20, waiting.handshakeRemainingSeconds)
        assertEquals(ScooterHandshakeState.WAITING_FOR_USER_CONFIRMATION, waiting.handshakeState)
        assertNull(scooterDashboardState(scooter, ConnectionState.Ready, null,
            ScooterHandshakeState.READY_FOR_TELEMETRY, 10).handshakeRemainingSeconds)
    }

    @Test fun `mi registration state exposes thirty second countdown while waiting for button`() {
        val consentRequired = scooterDashboardState(scooter, ConnectionState.ScooterHandshaking,
            null, ScooterHandshakeState.UNBONDED, null,
            miRegistration = MiRegistrationState.CONSENT_REQUIRED)
        assertEquals(MiRegistrationState.CONSENT_REQUIRED, consentRequired.miRegistrationState)
        assertNull(consentRequired.handshakeRemainingSeconds)

        val waiting = scooterDashboardState(scooter, ConnectionState.ScooterHandshaking,
            null, ScooterHandshakeState.UNBONDED, 30,
            miRegistration = MiRegistrationState.WAITING_FOR_POWER_BUTTON)
        assertEquals(30, waiting.handshakeRemainingSeconds)
        assertEquals(MiRegistrationState.WAITING_FOR_POWER_BUTTON, waiting.miRegistrationState)

        val authenticating = scooterDashboardState(scooter, ConnectionState.ScooterHandshaking,
            null, ScooterHandshakeState.UNBONDED, null,
            miRegistration = MiRegistrationState.AUTHENTICATING)
        assertEquals(MiRegistrationState.AUTHENTICATING, authenticating.miRegistrationState)
        assertNull(authenticating.handshakeRemainingSeconds)
    }
}
