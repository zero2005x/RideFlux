package com.wheellog.next.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class HudBleClientTest {

    // ═══════════════════════════════════════════════════════════════════
    //  decodePayload — 14-byte payload (with useMetric)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `decodePayload parses 14-byte metric payload`() {
        val bytes = buildPayload(speed = 36.0f, battery = 85, temp = 42.5f, alert = 0x05, useMetric = 1)
        val data = HudBleClient.decodePayload(bytes)

        assertEquals(36.0f, data.speedKmh, 0.001f)
        assertEquals(85, data.batteryPercent)
        assertEquals(42.5f, data.temperatureC, 0.001f)
        assertEquals(0x05, data.alertMask)
        assertTrue(data.useMetric)
    }

    @Test
    fun `decodePayload parses 14-byte imperial payload`() {
        val bytes = buildPayload(speed = 72.0f, battery = 50, temp = 30.0f, alert = 0, useMetric = 0)
        val data = HudBleClient.decodePayload(bytes)

        assertEquals(72.0f, data.speedKmh, 0.001f)
        assertEquals(50, data.batteryPercent)
        assertFalse(data.useMetric)
    }

    @Test
    fun `decodePayload parses negative speed`() {
        val bytes = buildPayload(speed = -15.5f, battery = 60, temp = 25.0f, alert = 0, useMetric = 1)
        val data = HudBleClient.decodePayload(bytes)
        assertEquals(-15.5f, data.speedKmh, 0.001f)
    }

    @Test
    fun `decodePayload parses very large speed`() {
        val bytes = buildPayload(speed = 999.99f)
        val data = HudBleClient.decodePayload(bytes)
        assertEquals(999.99f, data.speedKmh, 0.01f)
    }

    @Test
    fun `decodePayload parses zero speed`() {
        val bytes = buildPayload(speed = 0f)
        val data = HudBleClient.decodePayload(bytes)
        assertEquals(0f, data.speedKmh, 0f)
    }

    @Test
    fun `decodePayload parses alert mask bits`() {
        val bytes = buildPayload(alert = 0x0F) // all 4 alerts
        val data = HudBleClient.decodePayload(bytes)

        assertTrue(data.hasOverspeed)
        assertTrue(data.hasLowBattery)
        assertTrue(data.hasHighTemperature)
        assertTrue(data.hasTiltBack)
        assertTrue(data.hasAnyAlert)
    }

    @Test
    fun `decodePayload parses individual alert OVERSPEED`() {
        val data = HudBleClient.decodePayload(buildPayload(alert = 0x01))
        assertTrue(data.hasOverspeed)
        assertFalse(data.hasLowBattery)
        assertFalse(data.hasHighTemperature)
        assertFalse(data.hasTiltBack)
    }

    @Test
    fun `decodePayload parses individual alert LOW_BATTERY`() {
        val data = HudBleClient.decodePayload(buildPayload(alert = 0x02))
        assertFalse(data.hasOverspeed)
        assertTrue(data.hasLowBattery)
    }

    @Test
    fun `decodePayload parses individual alert HIGH_TEMPERATURE`() {
        val data = HudBleClient.decodePayload(buildPayload(alert = 0x04))
        assertTrue(data.hasHighTemperature)
        assertFalse(data.hasTiltBack)
    }

    @Test
    fun `decodePayload parses individual alert TILT_BACK`() {
        val data = HudBleClient.decodePayload(buildPayload(alert = 0x08))
        assertTrue(data.hasTiltBack)
        assertFalse(data.hasOverspeed)
    }

    @Test
    fun `decodePayload parses no alerts as zero mask`() {
        val data = HudBleClient.decodePayload(buildPayload(alert = 0))
        assertFalse(data.hasAnyAlert)
    }

    @Test
    fun `decodePayload parses battery 0 and 255`() {
        val low = HudBleClient.decodePayload(buildPayload(battery = 0))
        assertEquals(0, low.batteryPercent)

        val high = HudBleClient.decodePayload(buildPayload(battery = 255))
        assertEquals(255, high.batteryPercent)
    }

    @Test
    fun `decodePayload parses battery mid-range`() {
        val data = HudBleClient.decodePayload(buildPayload(battery = 128))
        assertEquals(128, data.batteryPercent)
    }

    @Test
    fun `decodePayload parses negative temperature`() {
        val data = HudBleClient.decodePayload(buildPayload(temp = -20.0f))
        assertEquals(-20.0f, data.temperatureC, 0.001f)
    }

    @Test
    fun `decodePayload parses zero temperature`() {
        val data = HudBleClient.decodePayload(buildPayload(temp = 0f))
        assertEquals(0f, data.temperatureC, 0f)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  decodePayload — 13-byte legacy payload (backward compatibility)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `decodePayload with 13-byte legacy payload defaults useMetric to true`() {
        val bytes = ByteBuffer.allocate(13)
            .putFloat(25.0f)
            .put(60.toByte())
            .putFloat(35.0f)
            .putInt(0x02)
            .array()

        val data = HudBleClient.decodePayload(bytes)
        assertEquals(25.0f, data.speedKmh, 0.001f)
        assertEquals(60, data.batteryPercent)
        assertEquals(35.0f, data.temperatureC, 0.001f)
        assertEquals(0x02, data.alertMask)
        assertTrue(data.useMetric) // backward-compatible default
    }

    @Test
    fun `decodePayload with 13-byte payload preserves all other fields`() {
        val bytes = ByteBuffer.allocate(13)
            .putFloat(88.0f)
            .put(99.toByte())
            .putFloat(41.0f)
            .putInt(0x0F)
            .array()

        val data = HudBleClient.decodePayload(bytes)
        assertEquals(88.0f, data.speedKmh, 0.001f)
        assertEquals(99, data.batteryPercent)
        assertEquals(41.0f, data.temperatureC, 0.001f)
        assertEquals(0x0F, data.alertMask)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  decodePayload — boundary sizes
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `decodePayload with exactly 12 bytes returns defaults`() {
        val data = HudBleClient.decodePayload(ByteArray(12))
        assertEquals(0f, data.speedKmh, 0f)
        assertEquals(0, data.batteryPercent)
        assertTrue(data.useMetric)
    }

    @Test
    fun `decodePayload with too-short payload returns defaults`() {
        val data = HudBleClient.decodePayload(ByteArray(5))
        assertEquals(0f, data.speedKmh, 0f)
        assertEquals(0, data.batteryPercent)
        assertEquals(0f, data.temperatureC, 0f)
        assertEquals(0, data.alertMask)
        assertTrue(data.useMetric)
    }

    @Test
    fun `decodePayload with 1-byte payload returns defaults`() {
        val data = HudBleClient.decodePayload(ByteArray(1))
        assertEquals(0f, data.speedKmh, 0f)
    }

    @Test
    fun `decodePayload with empty payload returns defaults`() {
        val data = HudBleClient.decodePayload(ByteArray(0))
        assertEquals(0f, data.speedKmh, 0f)
        assertTrue(data.useMetric)
    }

    @Test
    fun `decodePayload with oversized payload ignores extra bytes`() {
        val bytes = ByteBuffer.allocate(20)
            .putFloat(55.5f)
            .put(77.toByte())
            .putFloat(33.0f)
            .putInt(0x03)
            .put(0.toByte())
            // 6 extra bytes
            .putFloat(99.9f)
            .putShort(0x7F)
            .array()

        val data = HudBleClient.decodePayload(bytes)
        assertEquals(55.5f, data.speedKmh, 0.001f)
        assertEquals(77, data.batteryPercent)
        assertEquals(33.0f, data.temperatureC, 0.001f)
        assertEquals(0x03, data.alertMask)
        assertFalse(data.useMetric)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Encode → Decode round-trip
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `encode-decode round-trip preserves all fields`() {
        val bytes = buildPayload(
            speed = 108.0f,
            battery = 73,
            temp = 38.5f,
            alert = 0x09, // OVERSPEED | TILT_BACK
            useMetric = 0,
        )
        val data = HudBleClient.decodePayload(bytes)

        assertEquals(108.0f, data.speedKmh, 0.001f)
        assertEquals(73, data.batteryPercent)
        assertEquals(38.5f, data.temperatureC, 0.001f)
        assertEquals(0x09, data.alertMask)
        assertFalse(data.useMetric)
    }

    @Test
    fun `encode-decode round-trip with zero values`() {
        val bytes = buildPayload()
        val data = HudBleClient.decodePayload(bytes)

        assertEquals(0f, data.speedKmh, 0f)
        assertEquals(0, data.batteryPercent)
        assertEquals(0f, data.temperatureC, 0f)
        assertEquals(0, data.alertMask)
        assertTrue(data.useMetric)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  parseInstanceId — pairing isolation
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `parseInstanceId with null returns null`() {
        assertNull(HudBleClient.parseInstanceId(null))
    }

    @Test
    fun `parseInstanceId with empty array returns null`() {
        assertNull(HudBleClient.parseInstanceId(byteArrayOf()))
    }

    @Test
    fun `parseInstanceId with 1-byte array returns null`() {
        assertNull(HudBleClient.parseInstanceId(byteArrayOf(0x01)))
    }

    @Test
    fun `parseInstanceId with 2-byte array returns null`() {
        assertNull(HudBleClient.parseInstanceId(byteArrayOf(0x01, 0x02)))
    }

    @Test
    fun `parseInstanceId with 3-byte array returns null`() {
        assertNull(HudBleClient.parseInstanceId(byteArrayOf(0x01, 0x02, 0x03)))
    }

    @Test
    fun `parseInstanceId with exactly 4 bytes returns correct hex`() {
        val result = HudBleClient.parseInstanceId(
            byteArrayOf(0xAB.toByte(), 0xCD.toByte(), 0x12, 0x34),
        )
        assertEquals("abcd1234", result)
    }

    @Test
    fun `parseInstanceId with more than 4 bytes only uses first 4`() {
        val result = HudBleClient.parseInstanceId(
            byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte(), 0xFF.toByte(), 0x00),
        )
        assertEquals("deadbeef", result)
    }

    @Test
    fun `parseInstanceId formats bytes as lowercase hex`() {
        val result = HudBleClient.parseInstanceId(
            byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte()),
        )
        assertEquals("aabbccdd", result)
    }

    @Test
    fun `parseInstanceId handles all-zero bytes`() {
        val result = HudBleClient.parseInstanceId(byteArrayOf(0x00, 0x00, 0x00, 0x00))
        assertEquals("00000000", result)
    }

    @Test
    fun `parseInstanceId handles all-0xFF bytes`() {
        val result = HudBleClient.parseInstanceId(
            byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()),
        )
        assertEquals("ffffffff", result)
    }

    @Test
    fun `parseInstanceId pads single-digit hex with leading zero`() {
        val result = HudBleClient.parseInstanceId(byteArrayOf(0x01, 0x02, 0x03, 0x04))
        assertEquals("01020304", result)
    }

    @Test
    fun `parseInstanceId handles mixed high and low bytes`() {
        val result = HudBleClient.parseInstanceId(
            byteArrayOf(0x00, 0xFF.toByte(), 0x80.toByte(), 0x7F),
        )
        assertEquals("00ff807f", result)
    }

    @Test
    fun `parseInstanceId with negative byte values produces correct hex`() {
        // In Kotlin, byte -1 == 0xFF, -128 == 0x80
        val result = HudBleClient.parseInstanceId(byteArrayOf(-1, -128, 127, 0))
        assertEquals("ff80" + "7f00", result)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  parseInstanceId — consistency with GattServerManager encoding
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `parseInstanceId produces same format as phone-side encoding`() {
        // Simulate what GattServerManager would put in manufacturer data
        val instanceId = byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte())
        val hexFromPhone = instanceId.joinToString("") { "%02x".format(it) }
        val hexFromHud = HudBleClient.parseInstanceId(instanceId)
        assertEquals(hexFromPhone, hexFromHud)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Helper
    // ═══════════════════════════════════════════════════════════════════

    private fun buildPayload(
        speed: Float = 0f,
        battery: Int = 0,
        temp: Float = 0f,
        alert: Int = 0,
        useMetric: Int = 1,
    ): ByteArray = ByteBuffer.allocate(14)
        .putFloat(speed)
        .put(battery.toByte())
        .putFloat(temp)
        .putInt(alert)
        .put(useMetric.toByte())
        .array()
}
