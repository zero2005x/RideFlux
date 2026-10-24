package com.rideflux.protocol.repository

import com.rideflux.domain.repository.DiscoveredWheel
import com.rideflux.domain.repository.WheelRepository
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide discovery evidence keyed by the underlying wheel repository.
 *
 * Scanner discovery and the typed router must share one classification
 * authority; otherwise a saved scooter can open the scooter provider even
 * after the scanner just saw a wheel at that address. Identity keying keeps
 * tests and independent repository instances isolated while Hilt's singleton
 * repositories share exactly one view in production.
 */
internal object PlevDiscoveryRegistry {
    private data class Evidence(
        /** A row with a non-null family is affirmative wheel evidence. */
        val affirmativeWheels: MutableSet<String> = ConcurrentHashMap.newKeySet(),
        /** A family=null row is an unclassified hint, never conflict proof. */
        val weakWheels: MutableSet<String> = ConcurrentHashMap.newKeySet(),
        val scooters: MutableSet<String> = ConcurrentHashMap.newKeySet(),
    )

    private val bySource = Collections.synchronizedMap(
        IdentityHashMap<WheelRepository, Evidence>(),
    )

    fun recordWheels(source: WheelRepository, wheels: List<DiscoveredWheel>) {
        val evidence = bySource.getOrPut(source) { Evidence() }
        evidence.affirmativeWheels.clear()
        evidence.weakWheels.clear()
        for (wheel in wheels) {
            if (wheel.family != null) evidence.affirmativeWheels.add(wheel.address)
            else evidence.weakWheels.add(wheel.address)
        }
    }

    fun recordScooters(source: WheelRepository, scooterAddresses: Set<String>) {
        val evidence = bySource.getOrPut(source) { Evidence() }
        evidence.scooters.clear()
        evidence.scooters.addAll(scooterAddresses)
        // A positive wheel family is not erased by a simultaneous scooter
        // row. The two records conflict and the typed caller must fail closed.
        evidence.weakWheels.removeAll(scooterAddresses)
    }

    fun isAffirmativeWheel(source: WheelRepository, address: String): Boolean =
        bySource[source]?.affirmativeWheels?.contains(address) == true

    fun isScooter(source: WheelRepository, address: String): Boolean =
        bySource[source]?.scooters?.contains(address) == true
}
