package com.rideflux.domain.repository

import com.rideflux.domain.connection.ConnectionState
import com.rideflux.domain.connection.ScooterConnection
import com.rideflux.domain.connection.WheelConnection
import com.rideflux.domain.device.PlevCategory
import com.rideflux.domain.device.PlevDevice
import com.rideflux.domain.device.ScooterDevice
import com.rideflux.domain.wheel.WheelFamily
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.StateFlow

/** A connection handle owns one repository reference and must be closed once. */
sealed interface PlevConnectionHandle {
    val address: String
    val state: StateFlow<ConnectionState>
    suspend fun close()

    data class Wheel(val connection: WheelConnection, override val address: String) : PlevConnectionHandle {
        override val state: StateFlow<ConnectionState> get() = connection.state
        override suspend fun close() = connection.close()
    }

    data class Scooter(val connection: ScooterConnection) : PlevConnectionHandle {
        override val address: String get() = connection.device.address
        override val state: StateFlow<ConnectionState> get() = connection.state
        override suspend fun close() = connection.close()
    }
}

/** Unified discovery and connection boundary for wheel, scooter, and future BMS devices. */
interface PlevRepository {
    fun scan(): Flow<List<PlevDevice>>

    /**
     * Wheel discovery with its native metadata (RSSI/family). Consumers that
     * need the scanner row must collect this rather than re-scanning the
     * wheel repository separately, so one discovery stream feeds both the UI
     * and the route-evidence registry.
     */
    fun scanWheels(): Flow<List<DiscoveredWheel>> = flowOf(emptyList())

    /** Scooter discovery on the same classification authority. */
    fun scanScooters(): Flow<List<ScooterDevice>> = flowOf(emptyList())

    /**
     * Address-only connection used by discovery-driven callers. A saved
     * vehicle must call [connect] with its persisted category so a cold
     * process does not depend on this process's scan cache.
     */
    suspend fun connect(address: String): PlevConnectionHandle

    /**
     * Category-aware connection. An explicit [category] from a persisted
     * profile is stronger than the process-local discovery cache; a conflict
     * with discovered evidence must fail closed instead of borrowing the
     * other transport. The default keeps existing discovery-only callers
     * source-compatible.
     */
    suspend fun connect(
        address: String,
        category: PlevCategory?,
        expectedFamily: WheelFamily? = null,
        modelHint: String? = null,
    ): PlevConnectionHandle = connect(address)
    /** Borrowed live handles; observers must not close them. */
    fun activeConnections(): Flow<Map<String, PlevConnectionHandle>>
}
