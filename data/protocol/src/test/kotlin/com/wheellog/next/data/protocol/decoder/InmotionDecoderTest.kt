package com.wheellog.next.data.protocol.decoder

import com.wheellog.next.domain.model.AlertFlag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class InmotionDecoderTest {

    private lateinit var decoder: InmotionDecoder

    @Before
    fun setUp() {
        decoder = InmotionDecoder()
    }

    // ---- InmotionUnpacker tests ----

    @Test
    fun `unpacker returns null for incomplete header`() {
        val unpacker = InmotionUnpacker()
        assertNull(unpacker.feed(0xAA.toByte()))
        assertNull(unpacker.feed(0x00.toByte())) // not AA, resets
    }

    @Test
    fun `unpacker collects frame between AA AA and 55 55`() {
        val unpacker = InmotionUnpacker()
        assertNull(unpacker.feed(0xAA.toByte()))
        assertNull(unpacker.feed(0xAA.toByte()))
        // Feed some data bytes
        assertNull(unpacker.feed(0x10.toByte()))
        assertNull(unpacker.feed(0x20.toByte()))
        // Footer: 55 55
        assertNull(unpacker.feed(0x55.toByte()))
        val frame = unpacker.feed(0x55.toByte())
        assertNotNull(frame)
        assertEquals(2, frame!!.size)
        assertEquals(0x10.toByte(), frame[0])
        assertEquals(0x20.toByte(), frame[1])
    }

    @Test
    fun `unpacker handles A5 escape sequences`() {
        val unpacker = InmotionUnpacker()
        // AA AA header
        unpacker.feed(0xAA.toByte())
        unpacker.feed(0xAA.toByte())
        // A5 AA should decode to actual 0xAA
        unpacker.feed(0xA5.toByte())
        unpacker.feed(0xAA.toByte())
        // A5 55 should decode to actual 0x55
        unpacker.feed(0xA5.toByte())
        unpacker.feed(0x55.toByte())
        // A5 A5 should decode to actual 0xA5
        unpacker.feed(0xA5.toByte())
        unpacker.feed(0xA5.toByte())
        // Footer: 55 55
        unpacker.feed(0x55.toByte())
        val frame = unpacker.feed(0x55.toByte())
        assertNotNull(frame)
        assertEquals(3, frame!!.size)
        assertEquals(0xAA.toByte(), frame[0])
        assertEquals(0x55.toByte(), frame[1])
        assertEquals(0xA5.toByte(), frame[2])
    }

    @Test
    fun `unpacker false footer - single 55 followed by non-55`() {
        val unpacker = InmotionUnpacker()
        unpacker.feed(0xAA.toByte())
        unpacker.feed(0xAA.toByte())
        unpacker.feed(0x10.toByte())
        // 55 followed by non-55 means the 55 was data
        unpacker.feed(0x55.toByte())
        assertNull(unpacker.feed(0x20.toByte())) // false footer, continues collecting
        // Now send real footer
        unpacker.feed(0x55.toByte())
        val frame = unpacker.feed(0x55.toByte())
        assertNotNull(frame)
        // Frame should contain: 0x10, 0x55, 0x20
        assertEquals(3, frame!!.size)
        assertEquals(0x10.toByte(), frame[0])
        assertEquals(0x55.toByte(), frame[1])
        assertEquals(0x20.toByte(), frame[2])
    }

    @Test
    fun `unpacker reset clears state`() {
        val unpacker = InmotionUnpacker()
        unpacker.feed(0xAA.toByte())
        unpacker.feed(0xAA.toByte())
        unpacker.feed(0x10.toByte())
        unpacker.reset()
        // After reset, data should not produce frames
        unpacker.feed(0x55.toByte())
        assertNull(unpacker.feed(0x55.toByte()))
    }

    // ---- Checksum ----

    @Test
    fun `computeChecksum returns unsigned byte sum AND 0xFF`() {
        val data = byteArrayOf(0x10, 0x20, 0x30, 0xFF.toByte())
        val expected = (0x10 + 0x20 + 0x30 + 0xFF) and 0xFF
        assertEquals(expected, InmotionDecoder.computeChecksum(data))
    }

    @Test
    fun `computeChecksum with start and end range`() {
        val data = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)
        val expected = (0x02 + 0x03 + 0x04) and 0xFF
        assertEquals(expected, InmotionDecoder.computeChecksum(data, 1, 4))
    }

    // ---- encodeFrame ----

    @Test
    fun `encodeFrame wraps with AA AA header and 55 55 footer`() {
        val payload = byteArrayOf(0x10, 0x20)
        val encoded = InmotionDecoder.encodeFrame(payload)
        assertEquals(0xAA.toByte(), encoded[0])
        assertEquals(0xAA.toByte(), encoded[1])
        assertEquals(0x10.toByte(), encoded[2])
        assertEquals(0x20.toByte(), encoded[3])
        assertEquals(0x55.toByte(), encoded[encoded.size - 2])
        assertEquals(0x55.toByte(), encoded[encoded.size - 1])
    }

    @Test
    fun `encodeFrame escapes AA bytes in payload`() {
        val payload = byteArrayOf(0xAA.toByte())
        val encoded = InmotionDecoder.encodeFrame(payload)
        // AA AA [A5 AA] 55 55
        assertEquals(6, encoded.size)
        assertEquals(0xA5.toByte(), encoded[2])
        assertEquals(0xAA.toByte(), encoded[3])
    }

    @Test
    fun `encodeFrame escapes 55 bytes in payload`() {
        val payload = byteArrayOf(0x55.toByte())
        val encoded = InmotionDecoder.encodeFrame(payload)
        // AA AA [A5 55] 55 55
        assertEquals(6, encoded.size)
        assertEquals(0xA5.toByte(), encoded[2])
        assertEquals(0x55.toByte(), encoded[3])
    }

    @Test
    fun `encodeFrame escapes A5 bytes in payload`() {
        val payload = byteArrayOf(0xA5.toByte())
        val encoded = InmotionDecoder.encodeFrame(payload)
        // AA AA [A5 A5] 55 55
        assertEquals(6, encoded.size)
        assertEquals(0xA5.toByte(), encoded[2])
        assertEquals(0xA5.toByte(), encoded[3])
    }

    // ---- chunkCommand ----

    @Test
    fun `chunkCommand splits into 20-byte chunks`() {
        val cmd = ByteArray(50) { it.toByte() }
        val chunks = InmotionDecoder.chunkCommand(cmd)
        assertEquals(3, chunks.size)
        assertEquals(20, chunks[0].size)
        assertEquals(20, chunks[1].size)
        assertEquals(10, chunks[2].size)
    }

    @Test
    fun `chunkCommand with command smaller than 20 bytes`() {
        val cmd = ByteArray(10)
        val chunks = InmotionDecoder.chunkCommand(cmd)
        assertEquals(1, chunks.size)
        assertEquals(10, chunks[0].size)
    }

    // ---- processFrame ----

    @Test
    fun `processFrame returns null for frame smaller than 17 bytes`() {
        assertNull(decoder.processFrame(ByteArray(10)))
    }

    @Test
    fun `processFrame returns null when checksum is wrong`() {
        val frame = buildFastInfoFrame()
        // Corrupt the last byte (checksum)
        frame[frame.size - 1] = ((frame[frame.size - 1].toInt() + 1) and 0xFF).toByte()
        assertNull(decoder.processFrame(frame))
    }

    @Test
    fun `processFrame parses FastInfo with correct voltage`() {
        // voltage at offset 24 in ex_data (LE uint32, /100)
        val result = decoder.processFrame(buildFastInfoFrame(voltage = 840000))
        assertNotNull(result)
        assertEquals(8400.0f, result!!.voltageV, 0.01f)
    }

    @Test
    fun `processFrame parses FastInfo speed from speed1 and speed2`() {
        // speed = (speed1 + speed2) / (factor * 2) / 100
        // With default factor=1.0: (1000+1000) / (1.0*2) / 100 = 10.0 km/h
        val result = decoder.processFrame(
            buildFastInfoFrame(speed1 = 1000, speed2 = 1000)
        )
        assertNotNull(result)
        assertEquals(10.0f, result!!.speedKmh, 0.01f)
    }

    @Test
    fun `processFrame parses FastInfo current`() {
        // current at offset 20 in ex_data (LE int32, /100)
        val result = decoder.processFrame(buildFastInfoFrame(current = 520))
        assertNotNull(result)
        assertEquals(5.2f, result!!.currentA, 0.01f)
    }

    @Test
    fun `processFrame parses FastInfo temperature`() {
        // temperature at offset 32 in ex_data (unsigned byte)
        val result = decoder.processFrame(buildFastInfoFrame(temperature = 45))
        assertNotNull(result)
        assertEquals(45.0f, result!!.temperatureC, 0.1f)
    }

    @Test
    fun `processFrame parses FastInfo distances`() {
        // totalDistance at offset 44 (LE uint32 /1000)
        // tripDistance at offset 48 (LE uint32 /1000)
        val result = decoder.processFrame(
            buildFastInfoFrame(totalDistance = 123456, tripDistance = 5200)
        )
        assertNotNull(result)
        assertEquals(123.456f, result!!.totalDistanceKm, 0.01f)
        assertEquals(5.2f, result.tripDistanceKm, 0.01f)
    }

    @Test
    fun `processFrame triggers HIGH_TEMPERATURE alert`() {
        val result = decoder.processFrame(buildFastInfoFrame(temperature = 70))
        assertNotNull(result)
        assertTrue(result!!.alertFlags.contains(AlertFlag.HIGH_TEMPERATURE))
    }

    @Test
    fun `processFrame triggers LOW_BATTERY alert`() {
        // Very low voltage → battery < 10%
        val result = decoder.processFrame(buildFastInfoFrame(voltage = 5800))
        assertNotNull(result)
        assertTrue(result!!.alertFlags.contains(AlertFlag.LOW_BATTERY))
    }

    // ---- Alert parsing ----

    @Test
    fun `processFrame handles alert with high temp ID`() {
        // First set up lastState with decode
        decoder.processFrame(buildFastInfoFrame())
        // Then send an alert frame with alertId=0x05 (high temp)
        val alertFrame = buildAlertFrame(0x05)
        decoder.processFrame(alertFrame)
        // No direct return value to check since alerts are stored in lastState
    }

    // ---- Full decode (stream via unpacker) ----

    @Test
    fun `decode returns null for random bytes`() {
        assertNull(decoder.decode(byteArrayOf(0x01, 0x02, 0x03)))
    }

    @Test
    fun `decode processes wrapped frame through unpacker`() {
        val innerFrame = buildFastInfoFrame(voltage = 840000, speed1 = 500, speed2 = 500)
        // Wrap with AA AA header and 55 55 footer using encodeFrame
        val encoded = InmotionDecoder.encodeFrame(innerFrame)
        val result = decoder.decode(encoded)
        assertNotNull(result)
        assertEquals(8400.0f, result!!.voltageV, 0.01f)
    }

    // ---- Helpers ----

    /**
     * Build a raw processFrame-compatible frame for GetFastInfo.
     * Structure: 16-byte header (ID at [0..3] LE) + ex_data + checksum
     */
    private fun buildFastInfoFrame(
        voltage: Long = 840000,   // 8400.00V (uint32 LE at exData[24])
        speed1: Int = 0,          // int32 LE at exData[12]
        speed2: Int = 0,          // int32 LE at exData[16]
        current: Int = 0,         // int32 LE at exData[20]
        temperature: Int = 30,    // byte at exData[32]
        totalDistance: Long = 0,  // uint32 LE at exData[44]
        tripDistance: Long = 0,   // uint32 LE at exData[48]
    ): ByteArray {
        // Build 16-byte CAN header with extended mode
        val header = ByteArray(16)
        // ID = GET_FAST_INFO = 0x0F550113 in LE
        writeUInt32LE(header, 0, IDValue.GET_FAST_INFO)
        // Set len=0xFE for extended mode
        header[12] = 0xFE.toByte()

        // Build ex_data (52 bytes minimum for parseFastInfo)
        val exData = ByteArray(52)
        writeInt32LE(exData, 12, speed1)
        writeInt32LE(exData, 16, speed2)
        writeInt32LE(exData, 20, current)
        writeUInt32LE(exData, 24, voltage)
        exData[32] = temperature.toByte()
        writeUInt32LE(exData, 44, totalDistance)
        writeUInt32LE(exData, 48, tripDistance)

        // Combine: header + exData + checksum
        val body = header + exData
        val checksum = InmotionDecoder.computeChecksum(body)
        return body + byteArrayOf(checksum.toByte())
    }

    /**
     * Build a raw processFrame-compatible frame for Alert.
     */
    private fun buildAlertFrame(alertId: Int): ByteArray {
        val header = ByteArray(16)
        writeUInt32LE(header, 0, IDValue.ALERT)
        header[4] = alertId.toByte()
        val checksum = InmotionDecoder.computeChecksum(header)
        return header + byteArrayOf(checksum.toByte())
    }

    private fun writeUInt32LE(arr: ByteArray, offset: Int, value: Long) {
        arr[offset] = (value and 0xFF).toByte()
        arr[offset + 1] = ((value shr 8) and 0xFF).toByte()
        arr[offset + 2] = ((value shr 16) and 0xFF).toByte()
        arr[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun writeInt32LE(arr: ByteArray, offset: Int, value: Int) {
        arr[offset] = (value and 0xFF).toByte()
        arr[offset + 1] = ((value shr 8) and 0xFF).toByte()
        arr[offset + 2] = ((value shr 16) and 0xFF).toByte()
        arr[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }
}
