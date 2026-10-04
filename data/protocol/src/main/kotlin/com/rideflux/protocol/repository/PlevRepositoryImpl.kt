package com.rideflux.protocol.repository

import com.rideflux.domain.connection.ScooterConnection
import com.rideflux.domain.device.PlevDevice
import com.rideflux.domain.device.ScooterDevice
import com.rideflux.domain.device.WheelDevice
import com.rideflux.domain.repository.PlevConnectionHandle
import com.rideflux.domain.repository.PlevRepository
import com.rideflux.domain.repository.WheelRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf

/**
 * Bridges the existing wheel repository to PLEV discovery. A verified scooter provider can
 * be added without changing callers; no unverified FE95 characteristic is opened by default.
 */
class PlevRepositoryImpl(
    private val wheels: WheelRepository,
    private val scooters: Flow<List<ScooterDevice>> = flowOf(emptyList()),
    private val connectScooter: suspend (String) -> ScooterConnection? = { null },
    private val activeScooters: Flow<Map<String, ScooterConnection>> = flowOf(emptyMap()),
) : PlevRepository {
    override fun scan(): Flow<List<PlevDevice>> = combine(wheels.scan(), scooters) { found, scooters ->
        found.map { WheelDevice(it.address, it.displayName ?: it.address) } + scooters
    }

    override suspend fun connect(address: String): PlevConnectionHandle =
        connectScooter(address)?.let(PlevConnectionHandle::Scooter)
            ?: PlevConnectionHandle.Wheel(wheels.connect(address), address)

    override fun activeConnections(): Flow<Map<String, PlevConnectionHandle>> =
        combine(wheels.activeConnections(), activeScooters) { live, scooters ->
            live.mapValues { (address, connection) ->
                PlevConnectionHandle.Wheel(connection, address)
            } + scooters.mapValues { (_, connection) -> PlevConnectionHandle.Scooter(connection) }
        }
}
