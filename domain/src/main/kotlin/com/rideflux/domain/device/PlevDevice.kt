package com.rideflux.domain.device

import com.rideflux.domain.telemetry.ScooterTelemetry
import com.rideflux.domain.telemetry.SmartBmsTelemetry
import com.rideflux.domain.telemetry.WheelTelemetry

/** Device categories are peers; a BMS is never impersonated as a wheel. */
sealed interface PlevDevice {
    val address: String
    val model: String
}

enum class PlevCategory { WHEEL, SCOOTER, BMS }

data class WheelDevice(
    override val address: String,
    override val model: String,
    val telemetry: WheelTelemetry = WheelTelemetry.EMPTY,
) : PlevDevice

data class BmsDevice(
    override val address: String,
    override val model: String,
    val telemetry: SmartBmsTelemetry? = null,
) : PlevDevice

data class ScooterDevice(
    override val address: String,
    override val model: String,
    val telemetry: ScooterTelemetry? = null,
) : PlevDevice
