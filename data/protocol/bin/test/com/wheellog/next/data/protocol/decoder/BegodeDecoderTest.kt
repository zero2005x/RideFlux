package com.wheellog.next.data.protocol.decoder

import com.wheellog.next.domain.model.AlertFlag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BegodeDecoderTest {

    private lateinit var decoder: BegodeDecoder

    @Before
    fun setUp() {
        decoder = BegodeDecoder()
    }

    // ---- Frame validation ----

    @Test
    fun `decode returns null for frames shorter than 24 bytes`() {
        assertNull(decoder.decode(ByteArray(20)))
    }

    @Test
    fun `decode returns null when header is wrong`() {
        val frame = buildFrame(0x00) // wrong header
        frame[0] = 0x00
        assertNull(decoder.decode(frame))
    }

    @Test
    fun `decode returns null when footer is invalid`() {
        val frame = buildFrame(0x00)
        frame[20] = 0x00 // corrupt footer
        assertNull(decoder.decode(frame))
    }

    @Test
    fun `decode returns null for unknown frame type`() {
        val frame = buildFrame(0xFF)
        assertNull(decoder.decode(frame))
    }

    // ---- Frame A (type 0x00): real-time data ----

    @Test
    fun `frame A parses voltage from BE uint16 at offset 2`() {
        // voltage = 8400 (84.0V) = 0x20D0
        val frame = buildFrame(0x00) {
            it[2] = 0x20.toByte(); it[3] = 0xD0.toByte()
        }
        val result = decoder.decode(frame)
        assertNotNull(result)
        assertEquals(84.0f, result!!.voltageV, 0.01f)
    }

    @Test
    fun `frame A parses speed from BE signed int16 at offset 4`() {
        // raw = 3000 = 0x0BB8 → speed = 3000 × 3.6 / 100 = 108.0 km/h
        val frame = buildFrame(0x00) {
            it[4] = 0x0B.toByte(); it[5] = 0xB8.toByte()
            // set voltage so battery isn't zero → avoid LOW_BATTERY
            it[2] = 0x23.toByte(); it[3] = 0x28.toByte()
        }
        val result = decoder.decode(frame)
        assertNotNull(result)
        assertEquals(108.0f, result!!.speedKmh, 0.01f)
    }

    @Test
    fun `frame A parses negative speed`() {
        // raw = -1000 = 0xFC18 → speed = -1000 × 3.6 / 100 = -36.0 km/h
        val frame = buildFrame(0x00) {
            it[4] = 0xFC.toByte(); it[5] = 0x18.toByte()
            it[2] = 0x23.toByte(); it[3] = 0x28.toByte()
        }
        val result = decoder.decode(frame)
        assertNotNull(result)
        assertEquals(-36.0f, result!!.speedKmh, 0.01f)
    }

    @Test
    fun `frame A parses trip distance from BE uint32 at offset 8`() {
        // distance = 5000 = 0x00001388
        val frame = buildFrame(0x00) {
            it[8] = 0x00.toByte(); it[9] = 0x00.toByte()
            it[10] = 0x13.toByte(); it[11] = 0x88.toByte()
            it[2] = 0x23.toByte(); it[3] = 0x28.toByte()
        }
        val result = decoder.decode(frame)
        assertNotNull(result)
        assertEquals(5.0f, result!!.tripDistanceKm, 0.01f)
    }

    @Test
    fun `frame A parses phase current from BE signed int16 at offset 10`() {
        // current = 800 (8.0A) = 0x0320
        val frame = buildFrame(0x00) {
            it[10] = 0x03.toByte(); it[11] = 0x20.toByte()
            it[2] = 0x23.toByte(); it[3] = 0x28.toByte()
        }
        // Note: distance offsets overlap current offsets — check the 24-byte layout
        // Actually offset 10 for current is readInt16BE(bytes, 10). In 24-byte frame
        // distance is readUInt32BE at offset 8 which uses offsets 8-11, while current
        // is readInt16BE at offset 10 which uses 10-11. They share bytes 10-11.
        // The actual Frame A code: distance@8 (32-bit), phaseCurrent@10 (16-bit).
        // Let me set distance to 0 and current separately:
        val frame2 = buildFrame(0x00) {
            it[8] = 0x00.toByte(); it[9] = 0x00.toByte()
            it[10] = 0x03.toByte(); it[11] = 0x20.toByte()
            it[2] = 0x23.toByte(); it[3] = 0x28.toByte()
        }
        val result = decoder.decode(frame2)
        assertNotNull(result)
        assertEquals(8.0f, result!!.currentA, 0.01f)
    }

    @Test
    fun `frame A parses temperature using MPU6050 formula`() {
        // MPU6050 temp = (rawInt16BE >> 8) + 80 - 256
        // For raw = 0x2600 => 0x2600 >> 8 = 0x26 = 38 => 38 + 80 - 256 = -138
        // For temp ~35°C: 35 = (raw >> 8) + 80 - 256 => raw >>8 = 211 = 0xD3
        // raw = 0xD300 (big-endian signed) => readInt16BE = -11520
        val frame = buildFrame(0x00) {
            it[12] = 0xD3.toByte(); it[13] = 0x00.toByte()
            it[2] = 0x23.toByte(); it[3] = 0x28.toByte()
        }
        val result = decoder.decode(frame)
        assertNotNull(result)
        // (0xD3) = 211, 211 + 80 - 256 = 35
        assertEquals(35.0f, result!!.temperatureC, 0.1f)
    }

    @Test
    fun `frame A triggers LOW_BATTERY when voltage is low`() {
        // voltage = 4900 (49.0V) → 16S group: (49-48)/(67.2-48)*100 = 5% < 10
        val frame = buildFrame(0x00) {
            it[2] = 0x13.toByte(); it[3] = 0x24.toByte()
        }
        val result = decoder.decode(frame)
        assertNotNull(result)
        assertTrue(result!!.alertFlags.contains(AlertFlag.LOW_BATTERY))
    }

    @Test
    fun `frame A triggers HIGH_TEMPERATURE when temp exceeds 65`() {
        // raw temp: (raw>>8) + 80 - 256 = 70 => raw>>8 = 246 = 0xF6
        val frame = buildFrame(0x00) {
            it[12] = 0xF6.toByte(); it[13] = 0x00.toByte()
            it[2] = 0x23.toByte(); it[3] = 0x28.toByte()
        }
        val result = decoder.decode(frame)
        assertNotNull(result)
        assertTrue(result!!.alertFlags.contains(AlertFlag.HIGH_TEMPERATURE))
    }

    @Test
    fun `frame A triggers OVERSPEED when speed greater than 35`() {
        // raw = 1000 = 0x03E8 → speed = 1000 × 3.6 / 100 = 36.0 km/h > 35
        val frame = buildFrame(0x00) {
            it[4] = 0x03.toByte(); it[5] = 0xE8.toByte()
            it[2] = 0x23.toByte(); it[3] = 0x28.toByte()
        }
        val result = decoder.decode(frame)
        assertNotNull(result)
        assertTrue(result!!.alertFlags.contains(AlertFlag.OVERSPEED))
    }

    // ---- Frame B (type 0x04): total distance / settings ----

    @Test
    fun `frame B parses total distance`() {
        // total = 50000 (50.0 km) = 0x0000C350
        val frame = buildFrame(0x04) {
            it[2] = 0x00.toByte(); it[3] = 0x00.toByte()
            it[4] = 0xC3.toByte(); it[5] = 0x50.toByte()
        }
        val result = decoder.decode(frame)
        assertNotNull(result)
        assertEquals(50.0f, result!!.totalDistanceKm, 0.01f)
    }

    @Test
    fun `frame B parses tiltback speed and LED mode`() {
        val frame = buildFrame(0x04) {
            it[10] = 0x00.toByte(); it[11] = 0x32.toByte() // tiltBackSpeed=50
            it[13] = 0x03.toByte() // ledMode=3
            it[15] = 0x01.toByte() // lightMode=1
        }
        decoder.decode(frame)
        assertEquals(50, decoder.tiltBackSpeed)
        assertEquals(3, decoder.ledMode)
        assertEquals(1, decoder.lightMode)
    }

    // ---- Frame 0x01: BMS overview ----

    @Test
    fun `bms overview frame returns null and stores bms voltage`() {
        val frame = buildFrame(0x01) {
            // bmsVoltage at offset 6: readUInt16BE/10
            it[6] = 0x03.toByte(); it[7] = 0x48.toByte() // 840 / 10 = 84.0V
        }
        assertNull(decoder.decode(frame))
        assertEquals(84.0f, decoder.bmsVoltage, 0.01f)
    }

    // ---- Frame 0x02/0x03: BMS cells ----

    @Test
    fun `bms cells frame 0x02 stores cell voltages 0-7`() {
        val frame = buildFrame(0x02) {
            // cell 0 at offset 2: 4200mV = 0x1068
            it[2] = 0x10.toByte(); it[3] = 0x68.toByte()
        }
        assertNull(decoder.decode(frame))
        assertEquals(4.2f, decoder.cellVoltages[0], 0.01f)
    }

    @Test
    fun `bms cells frame 0x03 stores cell voltages 8-15`() {
        val frame = buildFrame(0x03) {
            // cell 8 at offset 2: 4100mV = 0x1004
            it[2] = 0x10.toByte(); it[3] = 0x04.toByte()
        }
        assertNull(decoder.decode(frame))
        assertEquals(4.1f, decoder.cellVoltages[8], 0.01f)
    }

    // ---- Frame 0x07: Extended ----

    @Test
    fun `extended frame stores motor temperature`() {
        val frame = buildFrame(0x07) {
            // motorTemp at offset 6: readInt16BE
            it[6] = 0x00.toByte(); it[7] = 0x37.toByte() // 55°C
        }
        assertNull(decoder.decode(frame))
        assertEquals(55.0f, decoder.motorTemp, 0.01f)
    }

    // ---- Battery estimation (multi-voltage-group) ----

    @Test
    fun `battery estimation 24S group`() {
        // 90V falls in 24S range (72–100.8): (90-72)/(100.8-72)*100 = 62%
        assertEquals(62, decoder.estimateBattery(90f))
    }

    @Test
    fun `battery estimation 16S group`() {
        // 60V falls in 16S range (48–67.2): (60-48)/(67.2-48)*100 = 62%
        assertEquals(62, decoder.estimateBattery(60f))
    }

    @Test
    fun `battery estimation 20S group`() {
        // 72V falls in 20S range (60–84): (72-60)/(84-60)*100 = 50%
        assertEquals(50, decoder.estimateBattery(72f))
    }

    @Test
    fun `battery estimation clamps to 0-100`() {
        assertEquals(0, decoder.estimateBattery(40f))
        assertEquals(100, decoder.estimateBattery(140f))
    }

    // ---- ASCII command builders ----

    @Test
    fun `pedalHard returns h`() {
        assertEquals("h", String(BegodeDecoder.pedalHard(), Charsets.US_ASCII))
    }

    @Test
    fun `pedalMedium returns f`() {
        assertEquals("f", String(BegodeDecoder.pedalMedium(), Charsets.US_ASCII))
    }

    @Test
    fun `pedalSoft returns s`() {
        assertEquals("s", String(BegodeDecoder.pedalSoft(), Charsets.US_ASCII))
    }

    @Test
    fun `pedalComfort returns i`() {
        assertEquals("i", String(BegodeDecoder.pedalComfort(), Charsets.US_ASCII))
    }

    @Test
    fun `lightOn returns Q`() {
        assertEquals("Q", String(BegodeDecoder.lightOn(), Charsets.US_ASCII))
    }

    @Test
    fun `lightOff returns E`() {
        assertEquals("E", String(BegodeDecoder.lightOff(), Charsets.US_ASCII))
    }

    @Test
    fun `lightStrobe returns T`() {
        assertEquals("T", String(BegodeDecoder.lightStrobe(), Charsets.US_ASCII))
    }

    @Test
    fun `beep returns b`() {
        assertEquals("b", String(BegodeDecoder.beep(), Charsets.US_ASCII))
    }

    @Test
    fun `readVersion returns V`() {
        assertEquals("V", String(BegodeDecoder.readVersion(), Charsets.US_ASCII))
    }

    @Test
    fun `readName returns N`() {
        assertEquals("N", String(BegodeDecoder.readName(), Charsets.US_ASCII))
    }

    @Test
    fun `calibration step1 returns c and step2 returns y`() {
        assertEquals("c", String(BegodeDecoder.calibrationStep1(), Charsets.US_ASCII))
        assertEquals("y", String(BegodeDecoder.calibrationStep2(), Charsets.US_ASCII))
    }

    // ---- Frame accumulation (BLE MTU fragmentation) ----

    @Test
    fun `fragmented frame 20 plus 4 bytes produces valid result`() {
        // Simulate BLE MTU splitting: first 20 bytes, then remaining 4
        // raw = 2000 = 0x07D0 → speed = 2000 × 3.6 / 100 = 72.0 km/h
        val frame = buildFrame(0x00) {
            it[2] = 0x23.toByte(); it[3] = 0x28.toByte() // voltage 90.0V
            it[4] = 0x07.toByte(); it[5] = 0xD0.toByte()
        }
        val chunk1 = frame.copyOfRange(0, 20)
        val chunk2 = frame.copyOfRange(20, 24)

        // First chunk: not enough for a complete frame
        assertNull(decoder.decode(chunk1))
        // Second chunk completes the frame
        val result = decoder.decode(chunk2)
        assertNotNull(result)
        assertEquals(72.0f, result!!.speedKmh, 0.01f)
    }

    @Test
    fun `garbage bytes before header are skipped`() {
        // raw = 1000 = 0x03E8 → speed = 1000 × 3.6 / 100 = 36.0 km/h
        val frame = buildFrame(0x00) {
            it[2] = 0x23.toByte(); it[3] = 0x28.toByte()
            it[4] = 0x03.toByte(); it[5] = 0xE8.toByte()
        }
        // Send garbage followed by a valid frame
        val garbage = byteArrayOf(0x01, 0x02, 0x03)
        assertNull(decoder.decode(garbage))
        val result = decoder.decode(frame)
        assertNotNull(result)
        assertEquals(36.0f, result!!.speedKmh, 0.01f)
    }

    @Test
    fun `buffer overflow protection clears buffer`() {
        // Send 600 bytes of garbage — exceeds MAX_BUFFER_SIZE (512)
        val bigChunk = ByteArray(600) { 0x42.toByte() }
        assertNull(decoder.decode(bigChunk))
        // After overflow, a valid frame should still work
        val frame = buildFrame(0x00) {
            it[2] = 0x23.toByte(); it[3] = 0x28.toByte()
        }
        val result = decoder.decode(frame)
        assertNotNull(result)
    }

    @Test
    fun `multiple complete frames in sequence decode correctly`() {
        // frame1: raw = 1000 → 36.0 km/h, frame2: raw = 2000 → 72.0 km/h
        val frame1 = buildFrame(0x00) {
            it[2] = 0x23.toByte(); it[3] = 0x28.toByte() // voltage 90.0V
            it[4] = 0x03.toByte(); it[5] = 0xE8.toByte()
        }
        val frame2 = buildFrame(0x00) {
            it[2] = 0x23.toByte(); it[3] = 0x28.toByte()
            it[4] = 0x07.toByte(); it[5] = 0xD0.toByte()
        }
        val r1 = decoder.decode(frame1)
        val r2 = decoder.decode(frame2)
        assertNotNull(r1)
        assertNotNull(r2)
        assertEquals(36.0f, r1!!.speedKmh, 0.01f)
        assertEquals(72.0f, r2!!.speedKmh, 0.01f)
    }

    // ---- Helper ----

    private fun buildFrame(type: Int, modifier: (ByteArray) -> Unit = {}): ByteArray {
        val frame = ByteArray(24).apply {
            this[0] = 0x55.toByte()
            this[1] = 0xAA.toByte()
            this[18] = type.toByte()
            // Footer: 5A 5A 5A 5A
            this[20] = 0x5A.toByte()
            this[21] = 0x5A.toByte()
            this[22] = 0x5A.toByte()
            this[23] = 0x5A.toByte()
        }
        modifier(frame)
        return frame
    }
}
