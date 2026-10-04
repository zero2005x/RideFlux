package com.rideflux.domain.repository

import com.rideflux.domain.connection.ConnectionState
import com.rideflux.domain.connection.ScooterConnection
import com.rideflux.domain.connection.WheelConnection
import com.rideflux.domain.device.PlevDevice
import kotlinx.coroutines.flow.Flow
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
    suspend fun connect(address: String): PlevConnectionHandle
    /** Borrowed live handles; observers must not close them. */
    fun activeConnections(): Flow<Map<String, PlevConnectionHandle>>
}
