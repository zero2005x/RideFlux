package com.wheellog.next.core.testing

import com.wheellog.next.domain.model.AlertFlag
import com.wheellog.next.domain.model.TelemetryState

/**
 * Shared test fixtures for unit and integration tests.
 */
object TestFixtures {

    /** Standard telemetry sample. */
    val sampleTelemetry = TelemetryState(
        speedKmh = 25.5f,
        batteryPercent = 72,
        voltageV = 78.4f,
        temperatureC = 38.2f,
        totalDistanceKm = 1234.5f,
        tripDistanceKm = 12.3f,
        currentA = 5.2f,
        alertFlags = emptySet(),
    )

    /** Telemetry with high-temp alert. */
    val alertTelemetry = sampleTelemetry.copy(
        temperatureC = 70f,
        alertFlags = setOf(AlertFlag.HIGH_TEMPERATURE),
    )

    // ---------- KingSong frame fixtures ----------

    /** KingSong type 0xA9 frame: voltage=78.40V, speed=25.50km/h, trip=12300m, current=5.20A, temp=38.20°C */
    val kingSongLiveData1Frame: ByteArray = ByteArray(20).apply {
        this[0] = 0xAA.toByte()          // header
        this[1] = 0x55.toByte()          // header
        // voltage = 7840 = 0x1EA0
        this[2] = 0x1E.toByte(); this[3] = 0xA0.toByte()
        // speed = 2550 = 0x09F6
        this[4] = 0x09.toByte(); this[5] = 0xF6.toByte()
        // trip distance = 12300 (meters) = 0x0000300C
        this[6] = 0x00.toByte(); this[7] = 0x00.toByte()
        this[8] = 0x30.toByte(); this[9] = 0x0C.toByte()
        // current = 520 = 0x0208
        this[10] = 0x02.toByte(); this[11] = 0x08.toByte()
        // temperature = 3820 = 0x0EEC
        this[12] = 0x0E.toByte(); this[13] = 0xEC.toByte()
        // padding
        this[16] = 0xA9.toByte()         // frame type
    }

    /** KingSong type 0xB9 frame: totalDistance=1234500 meters = 0x0012D594 */
    val kingSongLiveData2Frame: ByteArray = ByteArray(20).apply {
        this[0] = 0xAA.toByte()
        this[1] = 0x55.toByte()
        // total distance = 1234500 = 0x0012D594
        this[2] = 0x00.toByte(); this[3] = 0x12.toByte()
        this[4] = 0xD5.toByte(); this[5] = 0x94.toByte()
        this[16] = 0xB9.toByte()
    }

    // ---------- Begode frame fixtures ----------

    /** Begode frame: voltage=84.0V, speed=30.0km/h, trip=5000m, current=8.0A, temp raw=31100 (38°C), totalDist=50000m */
    val begodeFrame: ByteArray = ByteArray(20).apply {
        this[0] = 0x55.toByte()
        // voltage = 8400 = 0x20D0
        this[2] = 0x20.toByte(); this[3] = 0xD0.toByte()
        // speed = 3000 = 0x0BB8
        this[4] = 0x0B.toByte(); this[5] = 0xB8.toByte()
        // trip distance = 5000 m = 0x00001388
        this[6] = 0x00.toByte(); this[7] = 0x00.toByte()
        this[8] = 0x13.toByte(); this[9] = 0x88.toByte()
        // current = 800 = 0x0320
        this[10] = 0x03.toByte(); this[11] = 0x20.toByte()
        // temperature raw = 31100 (311.00 K → 38°C) = 0x7984? No.
        // raw temp = (38+273)*100 = 31100 = 0x7984
        this[12] = 0x79.toByte(); this[13] = 0x84.toByte()
        // total distance = 50000 m = 0x0000C350
        this[14] = 0x00.toByte(); this[15] = 0x00.toByte()
        this[16] = 0xC3.toByte(); this[17] = 0x50.toByte()
    }

    // ---------- Inmotion frame fixtures ----------

    /** Inmotion frame: voltage=84.0V, speed=20.0km/h, trip=3000m, current=4.0A, temp=35.0°C, battery=65% */
    val inmotionFrame: ByteArray = ByteArray(20).apply {
        this[0] = 0xDC.toByte()
        this[1] = 0x5A.toByte()
        this[2] = 14 // length of payload
        // voltage = 8400 = 0x20D0
        this[4] = 0x20.toByte(); this[5] = 0xD0.toByte()
        // speed = 2000 = 0x07D0
        this[6] = 0x07.toByte(); this[7] = 0xD0.toByte()
        // trip = 3000 m = 0x00000BB8
        this[8] = 0x00.toByte(); this[9] = 0x00.toByte()
        this[10] = 0x0B.toByte(); this[11] = 0xB8.toByte()
        // current = 400 = 0x0190
        this[12] = 0x01.toByte(); this[13] = 0x90.toByte()
        // temperature = 3500 = 0x0DAC
        this[14] = 0x0D.toByte(); this[15] = 0xAC.toByte()
        // battery = 65 = 0x0041
        this[16] = 0x00.toByte(); this[17] = 0x41.toByte()
    }
}
