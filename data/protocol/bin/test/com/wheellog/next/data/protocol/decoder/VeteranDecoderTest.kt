package com.wheellog.next.data.protocol.decoder

import com.wheellog.next.domain.model.AlertFlag
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VeteranDecoderTest {

    private lateinit var decoder: VeteranDecoder

    @Before
    fun setUp() {
        decoder = VeteranDecoder()
    }

    // ======== VeteranUnpacker ========

    @Test
    fun `unpacker requires DC 5A 5C header`() {
        val unpacker = VeteranUnpacker()
        assertNull(unpacker.feed(0xAA.toByte()))
        assertNull(unpacker.feed(0x55.toByte()))
    }

    @Test
    fun `unpacker resets on wrong second byte`() {
        val unpacker = VeteranUnpacker()
        assertNull(unpacker.feed(0xDC.toByte())) // first header
        assertNull(unpacker.feed(0x00.toByte())) // not 0x5A → reset
    }

    @Test
    fun `unpacker resets on wrong third byte`() {
        val unpacker = VeteranUnpacker()
        assertNull(unpacker.feed(0xDC.toByte()))
        assertNull(unpacker.feed(0x5A.toByte()))
        assertNull(unpacker.feed(0x00.toByte())) // not 0x5C → reset
    }

    @Test
    fun `unpacker rejects zero length`() {
        val unpacker = VeteranUnpacker()
        unpacker.feed(0xDC.toByte())
        unpacker.feed(0x5A.toByte())
        unpacker.feed(0x5C.toByte())
        assertNull(unpacker.feed(0x00.toByte())) // len=0 → reset
    }

    @Test
    fun `unpacker collects frame of specified length`() {
        val unpacker = VeteranUnpacker()
        unpacker.feed(0xDC.toByte())
        unpacker.feed(0x5A.toByte())
        unpacker.feed(0x5C.toByte())
        unpacker.feed(5.toByte()) // len = 5

        var result: ByteArray? = null
        for (i in 0 until 5) {
            result = unpacker.feed((i + 1).toByte())
        }
        assertNotNull(result)
        assertEquals(5, result!!.size)
        assertEquals(1.toByte(), result[0])
        assertEquals(5.toByte(), result[4])
    }

    @Test
    fun `unpacker reset clears state`() {
        val unpacker = VeteranUnpacker()
        unpacker.feed(0xDC.toByte())
        unpacker.feed(0x5A.toByte())
        unpacker.reset()
        assertNull(unpacker.feed(0x5C.toByte())) // not recognized without DC 5A
    }

    // ======== processFrame — validation ========

    /**
     * Build a minimal valid Veteran frame (36 bytes, no CRC).
     * Offsets: voltage@0, speed@2, tripDist@4, totalDist@8,
     * phaseCurrent@12, temperature@14, autoOff@16, (padding@18-19),
     * speedAlert@20, speedTiltback@22 (must be 0x00 for validation),
     * mVer@24, pedalsMode@26 (byte only in frame[26]),
     * byte[22]=0x00, (byte[23]&0xFE)=0x00, byte[30] valid.
     *
     * NOTE: processFrame checks:
     *   frame[22]==0x00, (frame[23]&0xFE)==0x00, frame[30] in {0,1,2,7}
     */
    private fun buildValidFrame(len: Int = 36): ByteArray {
        val frame = ByteArray(len)
        // voltage at 0-1 BE: 8400 = 84.00V → 0x20D0
        frame[0] = 0x20.toByte(); frame[1] = 0xD0.toByte()
        // speed at 2-3 BE (signed): 150 = 15.0 km/h → 0x0096
        frame[2] = 0x00; frame[3] = 0x96.toByte()
        // Validation: frame[22]=0x00 (already zero), frame[23]&0xFE=0x00
        frame[22] = 0x00
        frame[23] = 0x00
        // pedalsMode at frame[26] = actual byte[30] in raw → but processFrame uses frame[30]
        // Wait — the comment says frame starts at original offset[4]. But actually
        // looking at the code: frame[22]==0x00, frame[23]&0xFE... these are frame array indices.
        // frame[30] = pedalsMode must be 0x00, 0x01, 0x02, or 0x07
        frame[30] = 0x00
        return frame
    }

    @Test
    fun `processFrame rejects frame shorter than 36`() {
        assertNull(decoder.processFrame(ByteArray(30)))
    }

    @Test
    fun `processFrame rejects frame where byte 22 is not zero`() {
        val frame = buildValidFrame()
        frame[22] = 0x01
        assertNull(decoder.processFrame(frame))
    }

    @Test
    fun `processFrame rejects frame where byte 23 FE bits set`() {
        val frame = buildValidFrame()
        frame[23] = 0x02 // (0x02 & 0xFE) != 0
        assertNull(decoder.processFrame(frame))
    }

    @Test
    fun `processFrame rejects frame with invalid pedalsMode`() {
        val frame = buildValidFrame()
        frame[30] = 0x05 // not in {0, 1, 2, 7}
        assertNull(decoder.processFrame(frame))
    }

    @Test
    fun `processFrame accepts pedalsMode 0`() {
        val frame = buildValidFrame()
        frame[30] = 0x00
        assertNotNull(decoder.processFrame(frame))
    }

    @Test
    fun `processFrame accepts pedalsMode 1`() {
        val frame = buildValidFrame()
        frame[30] = 0x01
        assertNotNull(decoder.processFrame(frame))
    }

    @Test
    fun `processFrame accepts pedalsMode 2`() {
        val frame = buildValidFrame()
        frame[30] = 0x02
        assertNotNull(decoder.processFrame(frame))
    }

    @Test
    fun `processFrame accepts pedalsMode 7`() {
        val frame = buildValidFrame()
        frame[30] = 0x07
        assertNotNull(decoder.processFrame(frame))
    }

    // ======== processFrame — field parsing ========

    @Test
    fun `processFrame parses voltage`() {
        val frame = buildValidFrame()
        // voltage at 0-1 BE: 8400 → 84.00V
        frame[0] = 0x20.toByte(); frame[1] = 0xD0.toByte() // 8400
        val result = decoder.processFrame(frame)
        assertNotNull(result)
        assertEquals(84.0f, result!!.voltageV, 0.01f)
    }

    @Test
    fun `processFrame parses speed`() {
        val frame = buildValidFrame()
        // speed at 2-3 BE signed: 250 → 25.0 km/h
        frame[2] = 0x00; frame[3] = 0xFA.toByte() // 250
        val result = decoder.processFrame(frame)
        assertNotNull(result)
        assertEquals(25.0f, result!!.speedKmh, 0.1f)
    }

    @Test
    fun `processFrame parses negative speed`() {
        val frame = buildValidFrame()
        // speed at 2-3 BE signed: -100 → -10.0 km/h (0xFF9C)
        frame[2] = 0xFF.toByte(); frame[3] = 0x9C.toByte()
        val result = decoder.processFrame(frame)
        assertNotNull(result)
        assertEquals(-10.0f, result!!.speedKmh, 0.1f)
    }

    @Test
    fun `processFrame parses trip distance via intRevBE`() {
        val frame = buildValidFrame()
        // tripDistance at 4-7 using intRevBE (getInt4R):
        // intRevBE swaps bytes within each pair: [A, B, C, D] → [B, A, D, C] read as BE
        // For result = 5000 (5.0 km when / 1000):
        // 5000 = 0x00001388
        // intRevBE([b0, b1, b2, b3]) = BE(b1, b0, b3, b2)
        // We want BE(b1, b0, b3, b2) = 0x00001388
        // So b1=0x00, b0=0x00, b3=0x13, b2=0x88
        frame[4] = 0x00; frame[5] = 0x00 // pair 1
        frame[6] = 0x88.toByte(); frame[7] = 0x13 // pair 2
        val result = decoder.processFrame(frame)
        assertNotNull(result)
        assertEquals(5.0f, result!!.tripDistanceKm, 0.01f)
    }

    @Test
    fun `processFrame parses total distance via intRevBE`() {
        val frame = buildValidFrame()
        // totalDistance at 8-11 using intRevBE
        // For result = 100000 (100.0 km when / 1000):
        // 100000 = 0x000186A0
        // intRevBE: b1=0x01, b0=0x00, b3=0x86, b2=0xA0
        // → frame[8]=0x00, frame[9]=0x01, frame[10]=0xA0, frame[11]=0x86
        frame[8] = 0x00; frame[9] = 0x01
        frame[10] = 0xA0.toByte(); frame[11] = 0x86.toByte()
        val result = decoder.processFrame(frame)
        assertNotNull(result)
        assertEquals(100.0f, result!!.totalDistanceKm, 0.1f)
    }

    @Test
    fun `processFrame parses phase current`() {
        val frame = buildValidFrame()
        // phaseCurrent at 12-13 BE signed: 500 → 50.0A
        frame[12] = 0x01; frame[13] = 0xF4.toByte() // 500
        val result = decoder.processFrame(frame)
        assertNotNull(result)
        assertEquals(50.0f, result!!.currentA, 0.1f)
    }

    @Test
    fun `processFrame parses negative current`() {
        val frame = buildValidFrame()
        // phaseCurrent at 12-13 BE signed: -200 → -20.0A (0xFF38)
        frame[12] = 0xFF.toByte(); frame[13] = 0x38.toByte()
        val result = decoder.processFrame(frame)
        assertNotNull(result)
        assertEquals(-20.0f, result!!.currentA, 0.1f)
    }

    @Test
    fun `processFrame parses temperature`() {
        val frame = buildValidFrame()
        // temperature at 14-15 BE signed: 42 → 42°C
        frame[14] = 0x00; frame[15] = 0x2A.toByte()
        val result = decoder.processFrame(frame)
        assertNotNull(result)
        assertEquals(42.0f, result!!.temperatureC, 0.1f)
    }

    @Test
    fun `processFrame parses mVer and model name`() {
        val frame = buildValidFrame()
        // mVer at 24-25 BE: 2 → Abrams
        frame[24] = 0x00; frame[25] = 0x02
        decoder.processFrame(frame)
        assertEquals(2, decoder.mVer)
        assertEquals("Abrams", decoder.modelName)
    }

    @Test
    fun `processFrame stores pedalsMode`() {
        val frame = buildValidFrame()
        frame[30] = 0x02
        decoder.processFrame(frame)
        assertEquals(2, decoder.pedalsMode)
    }

    @Test
    fun `processFrame stores speedAlert`() {
        val frame = buildValidFrame()
        // speedAlert at 20-21 BE: 350 → 35.0 km/h
        frame[20] = 0x01; frame[21] = 0x5E.toByte() // 350
        val result = decoder.processFrame(frame)
        assertNotNull(result)
        assertEquals(35.0f, decoder.speedAlert, 0.1f)
    }

    @Test
    fun `processFrame stores pitchAngle`() {
        val frame = buildValidFrame()
        // pitchAngle at 28-29 BE: 100
        frame[28] = 0x00; frame[29] = 0x64.toByte()
        decoder.processFrame(frame)
        assertEquals(100.0f, decoder.pitchAngle, 0.1f)
    }

    // ======== CRC32 for new format ========

    @Test
    fun `processFrame validates CRC32 for frames longer than 38 bytes`() {
        val dataLen = 40
        val frame = ByteArray(dataLen + 4) // +4 for CRC
        // Set validation bytes
        frame[22] = 0x00; frame[23] = 0x00; frame[30] = 0x00
        // voltage for battery calc
        frame[0] = 0x27.toByte(); frame[1] = 0x10.toByte() // 10000 = 100V

        // Compute CRC32
        val crc = VeteranDecoder.computeCrc32(frame, 0, dataLen)
        frame[dataLen] = ((crc shr 24) and 0xFF).toByte()
        frame[dataLen + 1] = ((crc shr 16) and 0xFF).toByte()
        frame[dataLen + 2] = ((crc shr 8) and 0xFF).toByte()
        frame[dataLen + 3] = (crc and 0xFF).toByte()

        val result = decoder.processFrame(frame)
        assertNotNull(result)
    }

    @Test
    fun `processFrame rejects new format frame with bad CRC32`() {
        val dataLen = 40
        val frame = ByteArray(dataLen + 4)
        frame[22] = 0x00; frame[23] = 0x00; frame[30] = 0x00
        // Wrong CRC
        frame[dataLen] = 0x01; frame[dataLen + 1] = 0x02
        frame[dataLen + 2] = 0x03; frame[dataLen + 3] = 0x04
        assertNull(decoder.processFrame(frame))
    }

    // ======== computeCrc32 ========

    @Test
    fun `computeCrc32 returns expected value for known input`() {
        val data = "123456789".toByteArray(Charsets.US_ASCII)
        // CRC32 of "123456789" is 0xCBF43926
        assertEquals(0xCBF43926L, VeteranDecoder.computeCrc32(data))
    }

    @Test
    fun `computeCrc32 returns 0 for empty input`() {
        assertEquals(0x00000000L, VeteranDecoder.computeCrc32(ByteArray(0)))
    }

    @Test
    fun `computeCrc32 with range computes partial CRC`() {
        val data = "ABCDE".toByteArray(Charsets.US_ASCII)
        val full = VeteranDecoder.computeCrc32(data, 1, 4)
        val sub = VeteranDecoder.computeCrc32("BCD".toByteArray(Charsets.US_ASCII))
        assertEquals(sub, full)
    }

    // ======== estimateBattery ========

    @Test
    fun `estimateBattery Sherman series (mVer 0)`() {
        // min=75, max=100.8
        // At midpoint: (87.9 - 75) / (100.8 - 75) * 100 = 50
        assertEquals(50, decoder.estimateBattery(87.9f, 0))
    }

    @Test
    fun `estimateBattery clamps to 0 below min`() {
        assertEquals(0, decoder.estimateBattery(70.0f, 0))
    }

    @Test
    fun `estimateBattery clamps to 100 above max`() {
        assertEquals(100, decoder.estimateBattery(105.0f, 0))
    }

    @Test
    fun `estimateBattery Patton (mVer 4) uses 30S range`() {
        // min=93.75, max=126
        val mid = (93.75f + 126f) / 2 // 109.875
        val expected = ((mid - 93.75f) / (126f - 93.75f) * 100).toInt()
        assertEquals(expected, decoder.estimateBattery(mid, 4))
    }

    @Test
    fun `estimateBattery Lynx (mVer 5) uses 36S range`() {
        // min=112.5, max=151.2
        assertEquals(0, decoder.estimateBattery(112.5f, 5))
        assertEquals(100, decoder.estimateBattery(151.2f, 5))
    }

    @Test
    fun `estimateBattery Oryx (mVer 8) uses 42S range`() {
        // min=131.25, max=176.4
        assertEquals(0, decoder.estimateBattery(131.25f, 8))
        assertEquals(100, decoder.estimateBattery(176.4f, 8))
    }

    @Test
    fun `estimateBattery Patton S (mVer 7) uses 30S range`() {
        assertEquals(0, decoder.estimateBattery(93.75f, 7))
    }

    @Test
    fun `estimateBattery unknown mVer defaults to Sherman range`() {
        assertEquals(0, decoder.estimateBattery(75.0f, 99))
        assertEquals(100, decoder.estimateBattery(100.8f, 99))
    }

    // ======== Model identification ========

    @Test
    fun `identifyModel via processFrame for each mVer`() {
        val expected = mapOf(
            0 to "Sherman", 1 to "Sherman", 2 to "Abrams", 3 to "Sherman S",
            4 to "Patton", 5 to "Lynx", 6 to "Sherman L", 7 to "Patton S",
            8 to "Oryx", 42 to "Nosfet Apex", 43 to "Nosfet Aero", 44 to "Nosfet Aeon",
        )
        for ((ver, name) in expected) {
            val d = VeteranDecoder()
            val frame = buildValidFrame()
            frame[24] = ((ver shr 8) and 0xFF).toByte() // mVer BE high
            frame[25] = (ver and 0xFF).toByte()          // mVer BE low
            d.processFrame(frame)
            assertEquals("mVer=$ver", name, d.modelName)
        }
    }

    @Test
    fun `identifyModel returns Veteran N for unknown mVer`() {
        val d = VeteranDecoder()
        val frame = buildValidFrame()
        frame[24] = 0x00; frame[25] = 50.toByte() // mVer=50
        d.processFrame(frame)
        assertEquals("Veteran (50)", d.modelName)
    }

    // ======== Alert flags ========

    @Test
    fun `processFrame sets HIGH_TEMPERATURE when temp above 65`() {
        val frame = buildValidFrame()
        // temperature at 14-15: 66
        frame[14] = 0x00; frame[15] = 0x42.toByte() // 66
        val result = decoder.processFrame(frame)
        assertNotNull(result)
        assertTrue(result!!.alertFlags.contains(AlertFlag.HIGH_TEMPERATURE))
    }

    @Test
    fun `processFrame sets LOW_BATTERY when battery below 10`() {
        val frame = buildValidFrame()
        // voltage → low battery: 76V for Sherman (mVer=0)
        // battery = (76 - 75) / (100.8 - 75) * 100 = 3.88 → 3
        frame[0] = 0x1D.toByte(); frame[1] = 0xB0.toByte() // 7600
        frame[24] = 0x00; frame[25] = 0x00 // mVer=0
        val result = decoder.processFrame(frame)
        assertNotNull(result)
        assertTrue(result!!.alertFlags.contains(AlertFlag.LOW_BATTERY))
    }

    @Test
    fun `processFrame sets OVERSPEED when speed exceeds speedAlert`() {
        val frame = buildValidFrame()
        // speedAlert at 20-21: 300 → 30.0 km/h
        frame[20] = 0x01; frame[21] = 0x2C.toByte()
        // speed at 2-3: 310 → 31.0 km/h (> 30.0)
        frame[2] = 0x01; frame[3] = 0x36.toByte()
        val result = decoder.processFrame(frame)
        assertNotNull(result)
        assertTrue(result!!.alertFlags.contains(AlertFlag.OVERSPEED))
    }

    // ======== Command builders ========

    @Test
    fun `pedalHard returns SETh`() {
        assertArrayEquals("SETh".toByteArray(Charsets.US_ASCII), VeteranDecoder.pedalHard())
    }

    @Test
    fun `pedalMedium returns SETm`() {
        assertArrayEquals("SETm".toByteArray(Charsets.US_ASCII), VeteranDecoder.pedalMedium())
    }

    @Test
    fun `pedalSoft returns SETs`() {
        assertArrayEquals("SETs".toByteArray(Charsets.US_ASCII), VeteranDecoder.pedalSoft())
    }

    @Test
    fun `lightOn returns SetLightON`() {
        assertArrayEquals("SetLightON".toByteArray(Charsets.US_ASCII), VeteranDecoder.lightOn())
    }

    @Test
    fun `lightOff returns SetLightOFF`() {
        assertArrayEquals("SetLightOFF".toByteArray(Charsets.US_ASCII), VeteranDecoder.lightOff())
    }

    @Test
    fun `clearMeter returns CLEARMETER`() {
        assertArrayEquals("CLEARMETER".toByteArray(Charsets.US_ASCII), VeteranDecoder.clearMeter())
    }

    @Test
    fun `beepOld returns b`() {
        assertArrayEquals("b".toByteArray(Charsets.US_ASCII), VeteranDecoder.beepOld())
    }

    // ======== Stream integration ========

    @Test
    fun `decode processes stream with DC 5A 5C header`() {
        val payload = buildValidFrame()
        val stream = ByteArray(4 + payload.size)
        stream[0] = 0xDC.toByte()
        stream[1] = 0x5A.toByte()
        stream[2] = 0x5C.toByte()
        stream[3] = payload.size.toByte() // len
        payload.copyInto(stream, destinationOffset = 4)

        val result = decoder.decode(stream)
        assertNotNull(result)
        assertEquals(84.0f, result!!.voltageV, 0.01f)
    }

    @Test
    fun `decode returns null for garbage bytes`() {
        val garbage = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)
        assertNull(decoder.decode(garbage))
    }
}
