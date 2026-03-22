package com.wheellog.next.feature.hudgateway.gatt

import com.wheellog.next.domain.model.AlertFlag
import com.wheellog.next.domain.model.HudPayload
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer

class GattServerManagerTest {

    // ═══════════════════════════════════════════════════════════════════
    //  Constants
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `COMPANY_ID equals 0xFFFF`() {
        assertEquals(0xFFFF, GattServerManager.COMPANY_ID)
    }

    @Test
    fun `SERVICE_UUID matches expected value`() {
        assertEquals(
            "0000ff10-0000-1000-8000-00805f9b34fb",
            GattServerManager.SERVICE_UUID.toString(),
        )
    }

    @Test
    fun `TELEMETRY_CHAR_UUID matches expected value`() {
        assertEquals(
            "0000ff11-0000-1000-8000-00805f9b34fb",
            GattServerManager.TELEMETRY_CHAR_UUID.toString(),
        )
    }

    // ═══════════════════════════════════════════════════════════════════
    //  encodePayload — size
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `encodePayload produces 14-byte array`() {
        val bytes = encode(speedKmh = 0f, battery = 0, temp = 0f)
        assertEquals(14, bytes.size)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  encodePayload — speed (bytes 0-3)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `encodePayload writes speed as first 4 bytes`() {
        val bytes = encode(speedKmh = 36.0f)
        assertEquals(36.0f, ByteBuffer.wrap(bytes, 0, 4).getFloat(), 0.001f)
    }

    @Test
    fun `encodePayload writes negative speed`() {
        val bytes = encode(speedKmh = -12.5f)
        assertEquals(-12.5f, ByteBuffer.wrap(bytes, 0, 4).getFloat(), 0.001f)
    }

    @Test
    fun `encodePayload writes zero speed`() {
        val bytes = encode(speedKmh = 0f)
        assertEquals(0f, ByteBuffer.wrap(bytes, 0, 4).getFloat(), 0f)
    }

    @Test
    fun `encodePayload writes very large speed`() {
        val bytes = encode(speedKmh = 999.99f)
        assertEquals(999.99f, ByteBuffer.wrap(bytes, 0, 4).getFloat(), 0.01f)
    }

    @Test
    fun `encodePayload writes very small fractional speed`() {
        val bytes = encode(speedKmh = 0.01f)
        assertEquals(0.01f, ByteBuffer.wrap(bytes, 0, 4).getFloat(), 0.001f)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  encodePayload — battery (byte 4)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `encodePayload writes battery at byte 4`() {
        val bytes = encode(battery = 85)
        assertEquals(85, bytes[4].toInt() and 0xFF)
    }

    @Test
    fun `encodePayload writes battery 0`() {
        val bytes = encode(battery = 0)
        assertEquals(0, bytes[4].toInt() and 0xFF)
    }

    @Test
    fun `encodePayload writes battery 1`() {
        val bytes = encode(battery = 1)
        assertEquals(1, bytes[4].toInt() and 0xFF)
    }

    @Test
    fun `encodePayload writes battery 254`() {
        val bytes = encode(battery = 254)
        assertEquals(254, bytes[4].toInt() and 0xFF)
    }

    @Test
    fun `encodePayload writes battery 255`() {
        val bytes = encode(battery = 255)
        assertEquals(255, bytes[4].toInt() and 0xFF)
    }

    @Test
    fun `encodePayload clamps battery above 255 to 255`() {
        val bytes = encode(battery = 300)
        assertEquals(255, bytes[4].toInt() and 0xFF)
    }

    @Test
    fun `encodePayload clamps battery below 0 to 0`() {
        val bytes = encode(battery = -5)
        assertEquals(0, bytes[4].toInt() and 0xFF)
    }

    @Test
    fun `encodePayload clamps battery far below 0`() {
        val bytes = encode(battery = -999)
        assertEquals(0, bytes[4].toInt() and 0xFF)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  encodePayload — temperature (bytes 5-8)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `encodePayload writes temperature as bytes 5-8`() {
        val bytes = encode(temp = 42.5f)
        assertEquals(42.5f, ByteBuffer.wrap(bytes, 5, 4).getFloat(), 0.001f)
    }

    @Test
    fun `encodePayload writes negative temperature`() {
        val bytes = encode(temp = -10.0f)
        assertEquals(-10.0f, ByteBuffer.wrap(bytes, 5, 4).getFloat(), 0.001f)
    }

    @Test
    fun `encodePayload writes zero temperature`() {
        val bytes = encode(temp = 0f)
        assertEquals(0f, ByteBuffer.wrap(bytes, 5, 4).getFloat(), 0f)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  encodePayload — alert mask (bytes 9-12)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `encodePayload writes empty alerts as zero mask`() {
        val bytes = encode(alerts = emptySet())
        assertEquals(0, ByteBuffer.wrap(bytes, 9, 4).getInt())
    }

    @Test
    fun `encodePayload encodes OVERSPEED as bit 0`() {
        val bytes = encode(alerts = setOf(AlertFlag.OVERSPEED))
        assertEquals(0x01, ByteBuffer.wrap(bytes, 9, 4).getInt())
    }

    @Test
    fun `encodePayload encodes LOW_BATTERY as bit 1`() {
        val bytes = encode(alerts = setOf(AlertFlag.LOW_BATTERY))
        assertEquals(0x02, ByteBuffer.wrap(bytes, 9, 4).getInt())
    }

    @Test
    fun `encodePayload encodes HIGH_TEMPERATURE as bit 2`() {
        val bytes = encode(alerts = setOf(AlertFlag.HIGH_TEMPERATURE))
        assertEquals(0x04, ByteBuffer.wrap(bytes, 9, 4).getInt())
    }

    @Test
    fun `encodePayload encodes TILT_BACK as bit 3`() {
        val bytes = encode(alerts = setOf(AlertFlag.TILT_BACK))
        assertEquals(0x08, ByteBuffer.wrap(bytes, 9, 4).getInt())
    }

    @Test
    fun `encodePayload encodes OVERSPEED and HIGH_TEMPERATURE`() {
        val bytes = encode(alerts = setOf(AlertFlag.OVERSPEED, AlertFlag.HIGH_TEMPERATURE))
        assertEquals(0x05, ByteBuffer.wrap(bytes, 9, 4).getInt())
    }

    @Test
    fun `encodePayload encodes LOW_BATTERY and TILT_BACK`() {
        val bytes = encode(alerts = setOf(AlertFlag.LOW_BATTERY, AlertFlag.TILT_BACK))
        assertEquals(0x0A, ByteBuffer.wrap(bytes, 9, 4).getInt())
    }

    @Test
    fun `encodePayload encodes all alert flags`() {
        val bytes = encode(alerts = AlertFlag.entries.toSet())
        assertEquals(0x0F, ByteBuffer.wrap(bytes, 9, 4).getInt())
    }

    // ═══════════════════════════════════════════════════════════════════
    //  encodePayload — useMetric (byte 13)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `encodePayload writes useMetric true as 1 at byte 13`() {
        val bytes = encode(useMetric = true)
        assertEquals(1, bytes[13].toInt())
    }

    @Test
    fun `encodePayload writes useMetric false as 0 at byte 13`() {
        val bytes = encode(useMetric = false)
        assertEquals(0, bytes[13].toInt())
    }

    // ═══════════════════════════════════════════════════════════════════
    //  encodePayload — full round-trip
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `encodePayload full round-trip`() {
        val bytes = encode(
            speedKmh = 72.5f,
            battery = 42,
            temp = 35.0f,
            alerts = setOf(AlertFlag.OVERSPEED, AlertFlag.TILT_BACK),
            useMetric = false,
        )
        val buf = ByteBuffer.wrap(bytes)
        assertEquals(72.5f, buf.getFloat(), 0.001f)
        assertEquals(42, buf.get().toInt() and 0xFF)
        assertEquals(35.0f, buf.getFloat(), 0.001f)
        assertEquals(0x09, buf.getInt()) // OVERSPEED(0x01) | TILT_BACK(0x08)
        assertEquals(0, buf.get().toInt())
    }

    @Test
    fun `encodePayload full round-trip with all alerts and metric`() {
        val bytes = encode(
            speedKmh = 0.5f,
            battery = 100,
            temp = 55.5f,
            alerts = AlertFlag.entries.toSet(),
            useMetric = true,
        )
        val buf = ByteBuffer.wrap(bytes)
        assertEquals(0.5f, buf.getFloat(), 0.001f)
        assertEquals(100, buf.get().toInt() and 0xFF)
        assertEquals(55.5f, buf.getFloat(), 0.001f)
        assertEquals(0x0F, buf.getInt())
        assertEquals(1, buf.get().toInt())
    }

    @Test
    fun `encodePayload all-zero values`() {
        val bytes = encode()
        val buf = ByteBuffer.wrap(bytes)
        assertEquals(0f, buf.getFloat(), 0f)
        assertEquals(0, buf.get().toInt() and 0xFF)
        assertEquals(0f, buf.getFloat(), 0f)
        assertEquals(0, buf.getInt())
        assertEquals(1, buf.get().toInt()) // useMetric defaults to true
    }

    // ═══════════════════════════════════════════════════════════════════
    //  encodeIdHex
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `encodeIdHex converts bytes to lowercase hex`() {
        val result = GattServerManager.encodeIdHex(byteArrayOf(0xAB.toByte(), 0xCD.toByte(), 0x12, 0x34))
        assertEquals("abcd1234", result)
    }

    @Test
    fun `encodeIdHex pads single-digit hex values with zero`() {
        val result = GattServerManager.encodeIdHex(byteArrayOf(0x01, 0x02, 0x03, 0x04))
        assertEquals("01020304", result)
    }

    @Test
    fun `encodeIdHex handles all zeros`() {
        val result = GattServerManager.encodeIdHex(byteArrayOf(0, 0, 0, 0))
        assertEquals("00000000", result)
    }

    @Test
    fun `encodeIdHex handles all 0xFF bytes`() {
        val result = GattServerManager.encodeIdHex(
            byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()),
        )
        assertEquals("ffffffff", result)
    }

    @Test
    fun `encodeIdHex handles mixed high and low bytes`() {
        val result = GattServerManager.encodeIdHex(byteArrayOf(0x00, 0xFF.toByte(), 0x80.toByte(), 0x7F))
        assertEquals("00ff807f", result)
    }

    @Test
    fun `encodeIdHex handles empty array`() {
        val result = GattServerManager.encodeIdHex(byteArrayOf())
        assertEquals("", result)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  decodeIdHex
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `decodeIdHex converts valid hex to bytes`() {
        val result = GattServerManager.decodeIdHex("abcd1234")!!
        assertArrayEquals(byteArrayOf(0xAB.toByte(), 0xCD.toByte(), 0x12, 0x34), result)
    }

    @Test
    fun `decodeIdHex handles uppercase hex`() {
        val result = GattServerManager.decodeIdHex("ABCD1234")!!
        assertArrayEquals(byteArrayOf(0xAB.toByte(), 0xCD.toByte(), 0x12, 0x34), result)
    }

    @Test
    fun `decodeIdHex handles all zeros`() {
        val result = GattServerManager.decodeIdHex("00000000")!!
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), result)
    }

    @Test
    fun `decodeIdHex handles all FFs`() {
        val result = GattServerManager.decodeIdHex("ffffffff")!!
        assertArrayEquals(
            byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()),
            result,
        )
    }

    @Test
    fun `decodeIdHex returns null for too-short string`() {
        assertNull(GattServerManager.decodeIdHex("abcd12"))
    }

    @Test
    fun `decodeIdHex returns null for too-long string`() {
        assertNull(GattServerManager.decodeIdHex("abcd123456"))
    }

    @Test
    fun `decodeIdHex returns null for empty string`() {
        assertNull(GattServerManager.decodeIdHex(""))
    }

    @Test
    fun `decodeIdHex returns null for non-hex characters`() {
        assertNull(GattServerManager.decodeIdHex("ghijklmn"))
    }

    @Test
    fun `decodeIdHex returns null for mixed valid and invalid chars`() {
        assertNull(GattServerManager.decodeIdHex("abcdXX12"))
    }

    // ═══════════════════════════════════════════════════════════════════
    //  encodeIdHex ↔ decodeIdHex round-trip
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `encodeIdHex then decodeIdHex round-trip preserves bytes`() {
        val original = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
        val hex = GattServerManager.encodeIdHex(original)
        val decoded = GattServerManager.decodeIdHex(hex)!!
        assertArrayEquals(original, decoded)
    }

    @Test
    fun `decodeIdHex then encodeIdHex round-trip preserves hex`() {
        val original = "1a2b3c4d"
        val bytes = GattServerManager.decodeIdHex(original)!!
        val hex = GattServerManager.encodeIdHex(bytes)
        assertEquals(original, hex)
    }

    @Test
    fun `round-trip with boundary values`() {
        val original = byteArrayOf(0x00, 0x01, 0xFE.toByte(), 0xFF.toByte())
        val hex = GattServerManager.encodeIdHex(original)
        assertEquals("0001feff", hex)
        val decoded = GattServerManager.decodeIdHex(hex)!!
        assertArrayEquals(original, decoded)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════════════

    private fun encode(
        speedKmh: Float = 0f,
        battery: Int = 0,
        temp: Float = 0f,
        alerts: Set<AlertFlag> = emptySet(),
        useMetric: Boolean = true,
    ): ByteArray = GattServerManager.encodePayload(
        HudPayload(
            speedKmh = speedKmh,
            batteryPercent = battery,
            temperatureC = temp,
            alertFlags = alerts,
            useMetric = useMetric,
        ),
    )
}
