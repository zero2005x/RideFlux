package com.rideflux.domain.connection

import com.rideflux.domain.command.CommandOutcome
import com.rideflux.domain.device.ScooterDevice
import com.rideflux.domain.telemetry.ScooterTelemetry
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class MiRegistrationState { NOT_REQUIRED, CONSENT_REQUIRED, AUTHENTICATING, WAITING_FOR_POWER_BUTTON }
private val noMiRegistration = MutableStateFlow(MiRegistrationState.NOT_REQUIRED).asStateFlow()

/** Domain-owned view of the retail pairing sequence. */
enum class ScooterHandshakeState {
    UNBONDED, REQUESTING_BLE_RANDOM, WAITING_FOR_USER_CONFIRMATION,
    AWAITING_PAIRING_ACCEPTANCE, PAIRING_COMPLETE, READY_FOR_TELEMETRY,
}

interface ScooterConnection {
    val device: ScooterDevice
    val state: StateFlow<ConnectionState>
    val telemetry: StateFlow<ScooterTelemetry?>
    val handshakeState: StateFlow<ScooterHandshakeState>
    /** True only when a verified lock-write profile is installed. */
    val lockSupported: Boolean get() = false
    val registrationState: StateFlow<MiRegistrationState> get() = noMiRegistration
    val confirmationTimeoutMillis: Long get() = 20_000L
    /** Only explicit stationary/button consent permits Xiaomi registration; speed is unavailable. */
    suspend fun registerAfterUserConfirmation(confirmed: Boolean = false): Boolean = false

    suspend fun start()
    suspend fun close()
    suspend fun lock(): CommandOutcome
    suspend fun unlock(): CommandOutcome
    suspend fun readRegister(register: Int, readLength: Int = 2): ByteArray?
}
