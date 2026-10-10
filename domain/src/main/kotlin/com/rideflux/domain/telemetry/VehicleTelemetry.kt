package com.rideflux.domain.telemetry

@JvmInline value class Millivolt(val value: Int) {
    init { require(value >= 0) }
}

@JvmInline value class Celsius(val value: Float) {
    init { require(value.isFinite()) }
}

/** A null field means that the device has not reported that measurement. */
data class SmartBmsTelemetry(
    val timestampMillis: Long,
    val totalVoltageV: Float? = null,
    val currentA: Float? = null,
    val remainingCapacityAh: Float? = null,
    val stateOfHealthPercent: Float? = null,
    val cycleCount: Int? = null,
    val cellVoltages: List<Millivolt> = emptyList(),
    val temperatures: List<Celsius> = emptyList(),
    val chargeMosEnabled: Boolean? = null,
    val dischargeMosEnabled: Boolean? = null,
) {
    init {
        require(totalVoltageV == null || totalVoltageV.isFinite() && totalVoltageV >= 0f)
        require(currentA == null || currentA.isFinite())
        require(remainingCapacityAh == null || remainingCapacityAh.isFinite() && remainingCapacityAh >= 0f)
        require(stateOfHealthPercent == null || stateOfHealthPercent.isFinite() && stateOfHealthPercent in 0f..100f)
        require(cycleCount == null || cycleCount >= 0)
    }
}

data class ScooterTelemetry(
    val timestampMillis: Long,
    val speedKmh: Float? = null,
    val batteryPercent: Float? = null,
    val totalDistanceMetres: Long? = null,
    val tripDistanceMetres: Long? = null,
    val gear: Int? = null,
    val throttlePercent: Float? = null,
    val brakePercent: Float? = null,
    val cruiseEnabled: Boolean? = null,
    /** ESC frame temperature; unknown for profiles that do not report it. */
    val frameTemperatureC: Float? = null,
) {
    init {
        require(speedKmh == null || speedKmh.isFinite() && speedKmh >= 0f)
        require(batteryPercent == null || batteryPercent.isFinite() && batteryPercent in 0f..100f)
        require(totalDistanceMetres == null || totalDistanceMetres >= 0L)
        require(tripDistanceMetres == null || tripDistanceMetres >= 0L)
        require(throttlePercent == null || throttlePercent.isFinite() && throttlePercent in 0f..100f)
        require(brakePercent == null || brakePercent.isFinite() && brakePercent in 0f..100f)
        require(frameTemperatureC == null || frameTemperatureC.isFinite())
    }
}
