package com.rideflux.protocol.repository

import com.rideflux.domain.connection.ScooterConnection
import com.rideflux.domain.device.PlevDevice
import com.rideflux.domain.device.ScooterDevice
import com.rideflux.domain.device.WheelDevice
import com.rideflux.domain.repository.PlevConnectionHandle
import com.rideflux.domain.repository.PlevRepository
import com.rideflux.domain.repository.ScooterRepository
import com.rideflux.domain.repository.WheelRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import java.util.concurrent.ConcurrentHashMap

/** Combines wheel and scooter discovery while preserving each repository's connection ownership. */
class PlevRepositoryImpl(
    private val wheels: WheelRepository,
    private val scooters: Flow<List<ScooterDevice>> = flowOf(emptyList()),
    private val connectScooter: suspend (String) -> ScooterConnection? = { null },
    private val activeScooters: Flow<Map<String, ScooterConnection>> = flowOf(emptyMap()),
    private val scooterOwnsAddress: (String) -> Boolean = { false },
) : PlevRepository {
    private val knownScooterAddresses = ConcurrentHashMap.newKeySet<String>()

    constructor(wheels: WheelRepository, scooterRepo: ScooterRepository) : this(
        wheels = wheels,
        scooters = scooterRepo.scan(),
        connectScooter = { address -> scooterRepo.connect(address) },
        activeScooters = scooterRepo.activeConnections(),
        scooterOwnsAddress = scooterRepo::isDiscovered,
    )

    override fun scan(): Flow<List<PlevDevice>> = combine(wheels.scan(), scooters) { found, scooters ->
        knownScooterAddresses.addAll(scooters.map(ScooterDevice::address))
        val scooterAddresses = scooters.mapTo(HashSet()) { it.address }
        found.filterNot { it.address in scooterAddresses }
            .map { WheelDevice(it.address, it.displayName ?: it.address) } + scooters
    }

    override suspend fun connect(address: String): PlevConnectionHandle =
        if (address in knownScooterAddresses || scooterOwnsAddress(address)) {
            PlevConnectionHandle.Scooter(requireNotNull(connectScooter(address)) {
                "No scooter connection available for $address"
            })
        } else PlevConnectionHandle.Wheel(wheels.connect(address), address)

    override fun activeConnections(): Flow<Map<String, PlevConnectionHandle>> =
        combine(wheels.activeConnections(), activeScooters) { live, scooters ->
            live.mapValues { (address, connection) ->
                PlevConnectionHandle.Wheel(connection, address)
            } + scooters.mapValues { (_, connection) -> PlevConnectionHandle.Scooter(connection) }
        }
}
