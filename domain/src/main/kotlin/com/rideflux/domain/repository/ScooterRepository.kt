package com.rideflux.domain.repository

import com.rideflux.domain.connection.ScooterConnection
import com.rideflux.domain.device.ScooterDevice
import kotlinx.coroutines.flow.Flow

/** Discovery and reference-counted connection ownership for scooters. */
interface ScooterRepository {
    fun scan(): Flow<List<ScooterDevice>>
    suspend fun connect(address: String): ScooterConnection
    /** Whether this process has identified the address as a scooter advertisement. */
    fun isDiscovered(address: String): Boolean = false
    /** Close only this address's session before removing its stored credential. */
    suspend fun removePairingKey(address: String) {
        throw UnsupportedOperationException("Pairing-key removal is unavailable")
    }
    /** Borrowed connections; observers must not close these handles. */
    fun activeConnections(): Flow<Map<String, ScooterConnection>>
}
