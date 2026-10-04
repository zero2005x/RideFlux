package com.rideflux.hud.source

import com.rideflux.domain.connection.ConnectionState
import com.rideflux.domain.repository.PlevConnectionHandle
import com.rideflux.domain.repository.PlevRepository
import com.rideflux.domain.telemetry.WheelTelemetry
import com.rideflux.hud.SignalQuality
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/** Direct PLEV source; the repository owns discovery and supplies a scooter handle. */
class PlevTelemetrySource(
    private val repository: PlevRepository,
    private val address: String,
) : HudTelemetrySource {
    override fun frames(): Flow<HudTelemetryFrame> = flow {
        val handle = repository.connect(address)
        try {
            val scooter = (handle as? PlevConnectionHandle.Scooter)?.connection
                ?: error("No verified scooter connection for $address")
            combine(scooter.state, scooter.telemetry) { state, telemetry ->
                HudTelemetryFrame(
                    state = state,
                    telemetry = WheelTelemetry.EMPTY,
                    scooterTelemetry = telemetry,
                    signal = when (state) {
                        ConnectionState.Ready -> SignalQuality.GOOD
                        ConnectionState.Connecting, ConnectionState.ScooterHandshaking,
                        is ConnectionState.Handshaking -> SignalQuality.WEAK
                        ConnectionState.Disconnected, is ConnectionState.Failed -> SignalQuality.NONE
                    },
                    staleHint = false,
                )
            }.collect { emit(it) }
        } finally {
            withContext(NonCancellable) { handle.close() }
        }
    }
}
