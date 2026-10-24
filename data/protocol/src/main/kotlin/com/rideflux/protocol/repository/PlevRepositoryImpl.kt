package com.rideflux.protocol.repository

import com.rideflux.domain.connection.ScooterConnection
import com.rideflux.domain.device.PlevCategory
import com.rideflux.domain.device.PlevDevice
import com.rideflux.domain.device.ScooterDevice
import com.rideflux.domain.device.WheelDevice
import com.rideflux.domain.repository.PlevConnectionHandle
import com.rideflux.domain.repository.DiscoveredWheel
import com.rideflux.domain.repository.PlevRepository
import com.rideflux.domain.repository.PlevRouteConflictException
import com.rideflux.domain.repository.ScooterRepository
import com.rideflux.domain.repository.WheelRepository
import com.rideflux.domain.wheel.WheelFamily
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach

/** Combines wheel and scooter discovery while preserving each repository's connection ownership. */
class PlevRepositoryImpl(
    private val wheels: WheelRepository,
    private val scooters: Flow<List<ScooterDevice>> = flowOf(emptyList()),
    private val connectScooter: suspend (String) -> ScooterConnection? = { null },
    private val connectScooterWithModel: suspend (String, String?) -> ScooterConnection? =
        { address, _ -> connectScooter(address) },
    private val activeScooters: Flow<Map<String, ScooterConnection>> = flowOf(emptyMap()),
    private val scooterOwnsAddress: (String) -> Boolean = { false },
) : PlevRepository {
    constructor(wheels: WheelRepository, scooterRepo: ScooterRepository) : this(
        wheels = wheels,
        scooters = scooterRepo.scan(),
        connectScooter = { address -> scooterRepo.connect(address) },
        connectScooterWithModel = { address, modelHint -> scooterRepo.connectWithModel(address, modelHint) },
        activeScooters = scooterRepo.activeConnections(),
        scooterOwnsAddress = scooterRepo::isDiscovered,
    )

    override fun scanWheels(): Flow<List<DiscoveredWheel>> = wheels.scan().onEach { found ->
        PlevDiscoveryRegistry.recordWheels(wheels, found)
    }

    override fun scanScooters(): Flow<List<ScooterDevice>> = scooters.onEach { found ->
        PlevDiscoveryRegistry.recordScooters(wheels, found.mapTo(HashSet()) { it.address })
    }

    override fun scan(): Flow<List<PlevDevice>> =
        combine(scanWheels(), scanScooters()) { found, scooters ->
            val scooterAddresses = scooters.mapTo(HashSet()) { it.address }
            found.filterNot { it.address in scooterAddresses }
                .map { WheelDevice(it.address, it.displayName ?: it.address) } + scooters
        }

    override suspend fun connect(address: String): PlevConnectionHandle =
        if (PlevDiscoveryRegistry.isScooter(wheels, address) || scooterOwnsAddress(address)) {
            scooterHandle(address, null)
        } else PlevConnectionHandle.Wheel(wheels.connect(address), address)

    override suspend fun connect(
        address: String,
        category: PlevCategory?,
        expectedFamily: WheelFamily?,
        modelHint: String?,
    ): PlevConnectionHandle =
        when (category) {
            PlevCategory.SCOOTER -> {
                if (PlevDiscoveryRegistry.isAffirmativeWheel(wheels, address)) {
                    throw PlevRouteConflictException(address, PlevCategory.SCOOTER, "wheel discovery")
                }
                scooterHandle(address, modelHint)
            }
            PlevCategory.WHEEL -> {
                if (PlevDiscoveryRegistry.isScooter(wheels, address) || scooterOwnsAddress(address)) {
                    throw PlevRouteConflictException(address, PlevCategory.WHEEL, "scooter discovery")
                }
                PlevConnectionHandle.Wheel(wheels.connect(address, expectedFamily), address)
            }
            PlevCategory.BMS -> throw PlevRouteConflictException(
                address,
                PlevCategory.BMS,
                "no BMS connection boundary is wired",
            )
            null -> connect(address)
        }

    private suspend fun scooterHandle(
        address: String,
        modelHint: String?,
    ): PlevConnectionHandle.Scooter {
        val connection = if (modelHint.isNullOrBlank()) {
            connectScooter(address)
        } else {
            connectScooterWithModel(address, modelHint)
        }
        return PlevConnectionHandle.Scooter(requireNotNull(connection) {
            "No scooter connection available for $address"
        })
    }

    override fun activeConnections(): Flow<Map<String, PlevConnectionHandle>> =
        combine(wheels.activeConnections(), activeScooters) { live, scooters ->
            live.mapValues { (address, connection) ->
                PlevConnectionHandle.Wheel(connection, address)
            } + scooters.mapValues { (_, connection) -> PlevConnectionHandle.Scooter(connection) }
        }
}
