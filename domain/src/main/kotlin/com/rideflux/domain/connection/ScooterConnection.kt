package com.rideflux.domain.connection

import com.rideflux.domain.command.CommandOutcome
import com.rideflux.domain.device.ScooterDevice
import com.rideflux.domain.telemetry.ScooterTelemetry
import kotlinx.coroutines.flow.StateFlow

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

    suspend fun start()
    suspend fun close()
    suspend fun lock(): CommandOutcome
    suspend fun unlock(): CommandOutcome
    suspend fun readRegister(register: Int, readLength: Int = 2): ByteArray?
}
