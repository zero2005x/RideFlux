package com.wheellog.next.domain.usecase

import com.wheellog.next.domain.model.TelemetryState
import com.wheellog.next.domain.repository.EucRepository
import kotlinx.coroutines.flow.Flow

/** Observe real-time EUC telemetry data. */
class ObserveTelemetryUseCase(
    private val repository: EucRepository,
) {
    operator fun invoke(): Flow<TelemetryState> = repository.observeTelemetry()
}
