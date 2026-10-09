package com.rideflux.protocol.familyscooter.m365

import kotlin.math.abs
import com.rideflux.domain.telemetry.ScooterTelemetry
import org.junit.Assert.*
import org.junit.Test

class M365RegisterMapTest {
    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun resource(name: String) = checkNotNull(javaClass.getResource("/m365/$name")).readText()
    private fun readings(register: Int, hex: String) =
        (M365RegisterMap.decode(register, hex(hex), true) as M365RegisterMap.Result.Decoded).readings

    @Test fun inventoryPinsEverySourceRowAndBlocksAllTwelveWrites() {
        assertTrue(M365RegisterMap.capabilities().isEmpty())
        assertEquals(M365RegisterMap.Result.Unsupported, M365RegisterMap.decode(0xB5, hex("0000")))
        val expected = mutableMapOf<Int, M365RegisterMap.Capability>()
        var writable = false
        resource("register-source-rows.md").lineSequence().forEach { line ->
            if (line == "WRITE") writable = true
            if (line.startsWith("|")) {
                val cells = line.split('|')
                val codes = Regex("`(?:0x)?([0-9A-F]{2})`").findAll(cells[1])
                    .map { it.groupValues[1].toInt(16) }.toList()
                val registers = if (cells[1].contains('–')) (codes.first()..codes.last()).toList() else codes
                val sizeText = cells[2].replace("*", "").trim().substringBefore(' ')
                val size = if (sizeText.startsWith("0x")) sizeText.drop(2).toInt(16) else sizeText.toInt()
                registers.forEach { expected[it] = M365RegisterMap.Capability(size, !writable,
                    if (writable) M365RegisterMap.WriteState.NotYetEnabled else M365RegisterMap.WriteState.Unsupported) }
            }
        }
        assertEquals(expected, M365RegisterMap.capabilities(true))
        assertEquals(37, expected.values.count { it.readable })
        assertEquals(12, expected.values.count { !it.readable })
        expected.forEach { (register, cap) ->
            val decoded = M365RegisterMap.decode(register, ByteArray(cap.sizeBytes), true)
            if (!cap.readable) assertEquals(M365RegisterMap.Result.Unsupported, decoded)
            else {
                assertNotEquals(M365RegisterMap.Result.Unsupported, decoded)
                assertNotEquals(M365RegisterMap.Result.Malformed, decoded)
                for (size in listOf(0, cap.sizeBytes - 1, cap.sizeBytes + 1)) {
                    assertEquals(M365RegisterMap.Result.Malformed, M365RegisterMap.decode(register, ByteArray(size), true))
                }
            }
        }
        assertEquals(M365RegisterMap.Result.Unsupported, M365RegisterMap.decode(0xFF, byteArrayOf(), true))
    }

    @Test fun replaysMinimalLogcatPayloadsAgainstMatchingCsvObservations() {
        val rows = resource("hardware-b0.csv").lineSequence().filter { it.isNotBlank() && !it.startsWith('#') }.drop(1).toList()
        assertEquals(3, rows.size)
        rows.forEach { row ->
            val cells = row.split(',')
            val payload = hex(cells[0])
            val value = readings(0xB0, cells[0])
            val legacy = checkNotNull(M365Codec.decodeB0(payload))
            assertEquals(abs(cells[1].toFloat()), value.speedMagnitudeKmh!!, 0.00001f)
            assertEquals(cells[2].toInt(), value.batteryPercent)
            assertEquals(cells[3].toFloat(), value.frameTemperatureC!!, 0f)
            assertEquals((cells[4].toDouble() * 1000).toLong(), value.totalDistanceMetres)
            assertEquals(legacy.speedRaw, value.speedRaw)
            assertEquals(legacy.speedKmh, value.speedMagnitudeKmh)
            assertEquals(value.frameTemperatureC, legacy.toTelemetry(123).frameTemperatureC)
            assertEquals(legacy.tripDistanceMetres / 10, value.tripDistanceRaw)
            assertNull(value.batteryCurrentA)
            assertNull(value.powerW)
            payload[8] = 101
            assertEquals(M365RegisterMap.Result.Malformed, M365RegisterMap.decode(0xB0, payload, true))
        }
    }

    @Test fun individualReadsPreserveUnknownUnitsAndExistingReversePolicy() {
        assertEquals(51, readings(0xB4, "3300").batteryPercent)
        assertEquals(M365RegisterMap.Result.Malformed, M365RegisterMap.decode(0xB4, hex("6500"), true))
        listOf("18fc" to 1f, "ffff" to 0.001f, "0080" to 32.768f, "0000" to 0f).forEach { (wire, speed) ->
            assertEquals(speed, readings(0xB5, wire).speedMagnitudeKmh!!, 0f)
        }
        assertEquals(4294967295L, readings(0xB7, "ffffffff").totalDistanceMetres)
        assertEquals(-10f, readings(0xBB, "9cff").frameTemperatureC!!, 0f)
        assertEquals(31f, readings(0xBB, "3601").frameTemperatureC!!, 0f)
        assertEquals(36f, readings(0x48, "100e").batteryVoltageV!!, 0f)
        val current = readings(0x50, "ffff")
        assertEquals(65535, current.batteryCurrentRaw)
        assertNull(current.batteryCurrentA)
        assertNull(current.powerW)
        assertEquals(100, readings(0xB9, "6400").tripDistanceRaw)
        assertEquals(M365RegisterMap.Result.Raw(0x26, hex("ffff").toList()), M365RegisterMap.decode(0x26, hex("ffff"), true))
        assertNull(M365RegisterMap.Readings(batteryVoltageV = 36f).powerW)
        assertNull(M365RegisterMap.Readings(batteryCurrentA = 2f).powerW)
        assertEquals(72f, M365RegisterMap.Readings(batteryVoltageV = 36f, batteryCurrentA = 2f).powerW!!, 0f)
    }

    @Test fun frameTemperatureAllowsBelowZeroButRejectsNonFiniteValues() {
        assertEquals(-10f, ScooterTelemetry(0, frameTemperatureC = -10f).frameTemperatureC!!, 0f)
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { ScooterTelemetry(0, frameTemperatureC = value) }
        }
    }
}
