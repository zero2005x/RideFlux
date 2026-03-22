package com.wheellog.next.data.protocol.decoder

import com.wheellog.next.domain.model.AlertFlag
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class InmotionV2DecoderTest {

    private lateinit var decoder: InmotionV2Decoder

    @Before
    fun setUp() {
        decoder = InmotionV2Decoder()
    }

    // ======== InmotionV2Unpacker ========

    @Test
    fun `unpacker requires AA AA header`() {
        val unpacker = InmotionV2Unpacker()
        assertNull(unpacker.feed(0x55.toByte()))
        assertNull(unpacker.feed(0x55.toByte()))
    }

    @Test
    fun `unpacker resets on wrong second header byte`() {
        val unpacker = InmotionV2Unpacker()
        assertNull(unpacker.feed(0xAA.toByte()))   // first header byte
        assertNull(unpacker.feed(0x00.toByte()))   // not AA → reset
    }

    @Test
    fun `unpacker collects complete frame based on len field`() {
        val unpacker = InmotionV2Unpacker()
        // Header
        assertNull(unpacker.feed(0xAA.toByte()))
        assertNull(unpacker.feed(0xAA.toByte()))
        // Frame: flags(1) + len(1) + command(1) + data(len-1) + check(1) = len + 3
        // flags=0x14, len=0x01, command=0x04, check=XOR
        val flags = 0x14
        val len = 0x01
        val command = 0x04
        val check = flags xor len xor command
        assertNull(unpacker.feed(flags.toByte()))  // flags
        assertNull(unpacker.feed(len.toByte()))    // len → expectedLen = 1 + 3 = 4
        assertNull(unpacker.feed(command.toByte())) // command
        val result = unpacker.feed(check.toByte()) // check → frame complete
        assertNotNull(result)
        assertEquals(4, result!!.size)
        assertEquals(flags.toByte(), result[0])
        assertEquals(len.toByte(), result[1])
        assertEquals(command.toByte(), result[2])
        assertEquals(check.toByte(), result[3])
    }

    @Test
    fun `unpacker handles A5 escape for AA`() {
        val unpacker = InmotionV2Unpacker()
        unpacker.feed(0xAA.toByte())
        unpacker.feed(0xAA.toByte())
        // flags=0x14, len=0x01 (total 4 bytes)
        unpacker.feed(0x14.toByte())
        unpacker.feed(0x01.toByte())
        // Next byte is A5 escape → next byte is the actual value
        unpacker.feed(0xA5.toByte())
        // Escaped byte 0xAA
        val result = unpacker.feed(0xAA.toByte())
        // Frame is now: [0x14, 0x01, 0xAA, ...] — but we need 4 bytes total (len=1, len+3=4)
        // After escape, buffer has: [0x14, 0x01, 0xAA] = 3 bytes, need 4
        assertNull(result) // still collecting
    }

    @Test
    fun `unpacker handles A5 escape for A5`() {
        val unpacker = InmotionV2Unpacker()
        unpacker.feed(0xAA.toByte())
        unpacker.feed(0xAA.toByte())
        unpacker.feed(0x14.toByte())
        unpacker.feed(0x01.toByte()) // len=1 → total 4 bytes
        unpacker.feed(0xA5.toByte()) // escape marker
        unpacker.feed(0xA5.toByte()) // escaped byte = 0xA5
        // Buffer: [0x14, 0x01, 0xA5], need 4 total
        assertNull(null) // still collecting
    }

    @Test
    fun `unpacker restarts on new AA during collection`() {
        val unpacker = InmotionV2Unpacker()
        unpacker.feed(0xAA.toByte())
        unpacker.feed(0xAA.toByte())
        unpacker.feed(0x14.toByte()) // flags
        // Encounter 0xAA during collection → treated as potential new header start
        val result = unpacker.feed(0xAA.toByte())
        assertNull(result)
    }

    @Test
    fun `unpacker reset clears state`() {
        val unpacker = InmotionV2Unpacker()
        unpacker.feed(0xAA.toByte())
        unpacker.feed(0xAA.toByte())
        unpacker.feed(0x14.toByte())
        unpacker.reset()
        // After reset, should need new header
        assertNull(unpacker.feed(0x01.toByte()))
    }

    // ======== calcCheck (XOR checksum) ========

    @Test
    fun `calcCheck computes XOR of all bytes`() {
        val data = byteArrayOf(0x14, 0x02, 0x04, 0x01)
        val check = InmotionV2Decoder.calcCheck(data)
        assertEquals(0x14 xor 0x02 xor 0x04 xor 0x01, check)
    }

    @Test
    fun `calcCheck with range computes partial XOR`() {
        val data = byteArrayOf(0x10, 0x20, 0x30, 0x40)
        val check = InmotionV2Decoder.calcCheck(data, 1, 3)
        assertEquals(0x20 xor 0x30, check)
    }

    @Test
    fun `calcCheck returns 0 for empty range`() {
        val data = byteArrayOf(0x10, 0x20)
        assertEquals(0, InmotionV2Decoder.calcCheck(data, 0, 0))
    }

    // ======== buildFrame ========

    @Test
    fun `buildFrame prepends AA AA header`() {
        val frame = InmotionV2Decoder.buildFrame(Flag.DEFAULT, Command.REAL_TIME_INFO)
        assertEquals(0xAA.toByte(), frame[0])
        assertEquals(0xAA.toByte(), frame[1])
    }

    @Test
    fun `buildFrame includes correct flags and command`() {
        val frame = InmotionV2Decoder.buildFrame(Flag.DEFAULT, Command.REAL_TIME_INFO)
        // After AA AA, the payload starts; it may include escapes
        // With no escaping needed: frame = [AA, AA, flags, len, command, check]
        // flags=0x14, len=0x01, command=0x04, check=0x14^0x01^0x04=0x11
        assertEquals(0x14.toByte(), frame[2]) // flags
        assertEquals(0x01.toByte(), frame[3]) // len (command only, no extra data, so 1)
        assertEquals(0x04.toByte(), frame[4]) // command
        assertEquals(0x11.toByte(), frame[5]) // XOR check
    }

    @Test
    fun `buildFrame with data has correct length field`() {
        val data = byteArrayOf(0x50, 0x01) // setLight data
        val frame = InmotionV2Decoder.buildFrame(Flag.DEFAULT, Command.CONTROL, data)
        // len = data.size + 1 = 3, flags=0x14, command=0x60
        assertEquals(0x14.toByte(), frame[2]) // flags
        assertEquals(0x03.toByte(), frame[3]) // len = 3
        assertEquals(0x60.toByte(), frame[4]) // command
    }

    @Test
    fun `buildFrame escapes AA in payload`() {
        // Create a payload where 0xAA appears naturally
        // flags=0xAA would be escaped to A5 AA
        val frame = InmotionV2Decoder.buildFrame(0xAA, Command.MAIN_VERSION)
        assertEquals(0xAA.toByte(), frame[0])
        assertEquals(0xAA.toByte(), frame[1])
        // 0xAA should be escaped as A5 AA
        assertEquals(0xA5.toByte(), frame[2])
        assertEquals(0xAA.toByte(), frame[3])
    }

    @Test
    fun `buildFrame escapes A5 in payload`() {
        // flags=0xA5 would be escaped to A5 A5
        val frame = InmotionV2Decoder.buildFrame(0xA5, Command.MAIN_VERSION)
        assertEquals(0xAA.toByte(), frame[0])
        assertEquals(0xAA.toByte(), frame[1])
        assertEquals(0xA5.toByte(), frame[2])
        assertEquals(0xA5.toByte(), frame[3])
    }

    @Test
    fun `buildFrame checksum is valid`() {
        val data = byteArrayOf(0x01, 0x02, 0x03)
        val frame = InmotionV2Decoder.buildFrame(Flag.DEFAULT, Command.CONTROL, data)
        // Unescape and verify checksum
        val unescaped = unescape(frame.sliceArray(2 until frame.size))
        val checkByte = unescaped.last().toInt() and 0xFF
        val computed = InmotionV2Decoder.calcCheck(unescaped, 0, unescaped.size - 1)
        assertEquals(computed, checkByte)
    }

    /** Helper: remove A5 escapes from payload bytes. */
    private fun unescape(data: ByteArray): ByteArray {
        val result = mutableListOf<Byte>()
        var i = 0
        while (i < data.size) {
            if (data[i] == 0xA5.toByte() && i + 1 < data.size) {
                result.add(data[i + 1])
                i += 2
            } else {
                result.add(data[i])
                i++
            }
        }
        return result.toByteArray()
    }

    // ======== processFrame ========

    /** Build a raw (unescaped) frame for processFrame. */
    private fun buildRawFrame(flags: Int, command: Int, data: ByteArray = ByteArray(0)): ByteArray {
        val len = data.size + 1
        val inner = ByteArray(3 + data.size)
        inner[0] = flags.toByte()
        inner[1] = len.toByte()
        inner[2] = command.toByte()
        data.copyInto(inner, destinationOffset = 3)
        val check = InmotionV2Decoder.calcCheck(inner)
        return inner + byteArrayOf(check.toByte())
    }

    @Test
    fun `processFrame rejects frame too short`() {
        assertNull(decoder.processFrame(ByteArray(3)))
    }

    @Test
    fun `processFrame rejects frame with bad checksum`() {
        val frame = buildRawFrame(Flag.DEFAULT, Command.REAL_TIME_INFO, ByteArray(37))
        // Corrupt the check byte
        frame[frame.size - 1] = (frame[frame.size - 1].toInt() xor 0xFF).toByte()
        assertNull(decoder.processFrame(frame))
    }

    // ======== processFrame — MainInfo ========

    @Test
    fun `processFrame parses model ID from MainInfo`() {
        // MainInfo data: subCommand=0x01, reserved=0x00, modelId=61 (V11)
        val data = byteArrayOf(0x01, 0x00, 61)
        val frame = buildRawFrame(Flag.DEFAULT, Command.MAIN_INFO, data)
        decoder.processFrame(frame)
        assertEquals(61, decoder.modelId)
    }

    @Test
    fun `processFrame parses serial number from MainInfo`() {
        // MainInfo data: subCommand=0x02, then 16 bytes of serial
        val serial = "V11-1234567890AB"
        val data = ByteArray(17)
        data[0] = 0x02
        serial.toByteArray(Charsets.UTF_8).copyInto(data, destinationOffset = 1)
        val frame = buildRawFrame(Flag.DEFAULT, Command.MAIN_INFO, data)
        decoder.processFrame(frame)
        assertEquals("V11-1234567890AB", decoder.serialNumber)
    }

    @Test
    fun `processFrame parses version info from MainInfo`() {
        // subCommand=0x06, driver=1.2, main=3.4, ble=5.6
        val data = byteArrayOf(0x06, 1, 2, 3, 4, 5, 6)
        val frame = buildRawFrame(Flag.DEFAULT, Command.MAIN_INFO, data)
        decoder.processFrame(frame)
        assertEquals("1.2", decoder.driverBoardVersion)
        assertEquals("3.4", decoder.mainBoardVersion)
        assertEquals("5.6", decoder.bleVersion)
    }

    // ======== processFrame — RealTimeInfo V11 layout (default) ========

    @Test
    fun `processFrame parses V11 RealTimeInfo`() {
        // modelId=61 → V11 layout (default)
        setModelId(61)
        val data = ByteArray(37)
        // voltage at 0-1 LE: 8400 = 84.00V
        data[0] = 0xD0.toByte(); data[1] = 0x20.toByte() // 8400 LE
        // current at 2-3 LE (signed): 150 = 1.50A
        data[2] = 0x96.toByte(); data[3] = 0x00
        // speed at 4-5 LE (signed): 2050 = 20.50 km/h
        data[4] = 0x02.toByte(); data[5] = 0x08 // 2050 LE
        // mileage at 12-13 LE: 100 (* 10 / 1000 = 1.0 km)
        data[12] = 100.toByte(); data[13] = 0x00
        // batLevel at 16: 75 (bit 7 clear)
        data[16] = 75.toByte()
        // mosTemp at 17: convertTemp(200) = 200 + 80 - 256 = 24
        data[17] = 200.toByte()
        // motTemp at 18: convertTemp(210) = 210 + 80 - 256 = 34
        data[18] = 210.toByte()

        val frame = buildRawFrame(Flag.DEFAULT, Command.REAL_TIME_INFO, data)
        val result = decoder.processFrame(frame)

        assertNotNull(result)
        assertEquals(84.0f, result!!.voltageV, 0.01f)
        assertEquals(1.50f, result.currentA, 0.01f)
        assertEquals(20.50f, result.speedKmh, 0.01f)
        assertEquals(1.0f, result.tripDistanceKm, 0.01f)
        assertEquals(75, result.batteryPercent)
        assertEquals(24.0f, result.temperatureC, 0.1f)
    }

    @Test
    fun `processFrame V11 rejects data shorter than 37`() {
        setModelId(61)
        val data = ByteArray(30) // too short
        val frame = buildRawFrame(Flag.DEFAULT, Command.REAL_TIME_INFO, data)
        assertNull(decoder.processFrame(frame))
    }

    // ======== processFrame — RealTimeInfo V12 layout ========

    @Test
    fun `processFrame parses V12 RealTimeInfo`() {
        // modelId=71 → V12HS → V12 layout
        setModelId(71)
        val data = ByteArray(55)
        // voltage at 0-1 LE: 10000 = 100.00V
        data[0] = 0x10.toByte(); data[1] = 0x27.toByte() // 10000 LE
        // current at 2-3 LE: 300 = 3.00A
        data[2] = 0x2C.toByte(); data[3] = 0x01
        // speed at 4-5 LE: 3000 = 30.00 km/h
        data[4] = 0xB8.toByte(); data[5] = 0x0B // 3000 LE
        // mileage at 22-23 LE: 200 (* 10 / 1000 = 2.0 km)
        data[22] = 200.toByte(); data[23] = 0x00
        // batLevel at 24: 60
        data[24] = 60.toByte()
        // mosTemp at 40: convertTemp(176) = 176 + 80 - 256 = 0
        data[40] = 176.toByte()

        val frame = buildRawFrame(Flag.DEFAULT, Command.REAL_TIME_INFO, data)
        val result = decoder.processFrame(frame)

        assertNotNull(result)
        assertEquals(100.0f, result!!.voltageV, 0.01f)
        assertEquals(3.00f, result.currentA, 0.01f)
        assertEquals(30.0f, result.speedKmh, 0.01f)
        assertEquals(2.0f, result.tripDistanceKm, 0.01f)
        assertEquals(60, result.batteryPercent)
        assertEquals(0.0f, result.temperatureC, 0.1f)
    }

    @Test
    fun `processFrame V12 rejects data shorter than 55`() {
        setModelId(71)
        val data = ByteArray(50)
        val frame = buildRawFrame(Flag.DEFAULT, Command.REAL_TIME_INFO, data)
        assertNull(decoder.processFrame(frame))
    }

    // ======== processFrame — RealTimeInfo V13 layout ========

    @Test
    fun `processFrame parses V13 RealTimeInfo`() {
        // modelId=81 → V13 layout
        setModelId(81)
        val data = ByteArray(65)
        // voltage at 0-1 LE: 12600 = 126.00V
        data[0] = 0x38.toByte(); data[1] = 0x31.toByte() // 12600 LE
        // current at 2-3 LE: -500 = -5.00A (signed)
        val currentVal = -500
        data[2] = (currentVal and 0xFF).toByte(); data[3] = ((currentVal shr 8) and 0xFF).toByte()
        // speed at 8-9 LE: 2500 = 25.00 km/h
        data[8] = 0xC4.toByte(); data[9] = 0x09 // 2500 LE
        // mileage at 10-13 LE 32-bit: 10000 = 10.0 km (* 1000)
        data[10] = 0x10.toByte(); data[11] = 0x27.toByte(); data[12] = 0x00; data[13] = 0x00
        // batLevel1 at 34-35 LE: 80
        data[34] = 80.toByte(); data[35] = 0x00
        // batLevel2 at 36-37 LE: 82
        data[36] = 82.toByte(); data[37] = 0x00
        // mosTemp at 58: convertTemp(196) = 196 + 80 - 256 = 20
        data[58] = 196.toByte()

        val frame = buildRawFrame(Flag.DEFAULT, Command.REAL_TIME_INFO, data)
        val result = decoder.processFrame(frame)

        assertNotNull(result)
        assertEquals(126.0f, result!!.voltageV, 0.01f)
        assertEquals(-5.00f, result.currentA, 0.01f)
        assertEquals(25.0f, result.speedKmh, 0.01f)
        assertEquals(10.0f, result.tripDistanceKm, 0.1f)
        assertEquals(81, result.batteryPercent) // (80 + 82) / 2
        assertEquals(20.0f, result.temperatureC, 0.1f)
    }

    @Test
    fun `processFrame V13 rejects data shorter than 65`() {
        setModelId(81)
        val data = ByteArray(60)
        val frame = buildRawFrame(Flag.DEFAULT, Command.REAL_TIME_INFO, data)
        assertNull(decoder.processFrame(frame))
    }

    // ======== Model routing ========

    @Test
    fun `V12PRO uses V12 layout`() {
        setModelId(73)
        val data = ByteArray(55)
        data[0] = 0x10.toByte(); data[1] = 0x27.toByte() // voltage
        data[40] = 176.toByte() // temp
        val frame = buildRawFrame(Flag.DEFAULT, Command.REAL_TIME_INFO, data)
        val result = decoder.processFrame(frame)
        assertNotNull(result)
    }

    @Test
    fun `V11Y uses V13 layout`() {
        setModelId(62) // V11Y
        val data = ByteArray(65)
        data[0] = 0x10.toByte(); data[1] = 0x27.toByte()
        data[34] = 50; data[35] = 0; data[36] = 50; data[37] = 0
        data[58] = 176.toByte()
        val frame = buildRawFrame(Flag.DEFAULT, Command.REAL_TIME_INFO, data)
        val result = decoder.processFrame(frame)
        assertNotNull(result)
    }

    @Test
    fun `V9 uses V13 layout`() {
        setModelId(121) // V9
        val data = ByteArray(65)
        data[0] = 0x01; data[1] = 0x00
        data[34] = 90.toByte(); data[35] = 0; data[36] = 92.toByte(); data[37] = 0
        data[58] = 176.toByte()
        val frame = buildRawFrame(Flag.DEFAULT, Command.REAL_TIME_INFO, data)
        val result = decoder.processFrame(frame)
        assertNotNull(result)
        assertEquals(91, result!!.batteryPercent)
    }

    // ======== processFrame — TotalStats ========

    @Test
    fun `processFrame parses total distance from TotalStats`() {
        val data = ByteArray(20)
        // totalDistance at 0-3 LE: 123456 meters = 123.456 km
        data[0] = 0x40.toByte(); data[1] = 0xE2.toByte(); data[2] = 0x01; data[3] = 0x00
        val frame = buildRawFrame(Flag.DEFAULT, Command.TOTAL_STATS, data)
        decoder.processFrame(frame)
        assertEquals(123.456f, decoder.totalDistance, 0.01f)
    }

    @Test
    fun `processFrame TotalStats ignores data shorter than 20`() {
        val data = ByteArray(10)
        val frame = buildRawFrame(Flag.DEFAULT, Command.TOTAL_STATS, data)
        decoder.processFrame(frame) // should not crash
        assertEquals(0f, decoder.totalDistance, 0.01f)
    }

    // ======== Alert flags ========

    @Test
    fun `V11 sets HIGH_TEMPERATURE when mosTemp exceeds 65`() {
        setModelId(61)
        val data = ByteArray(37)
        // mosTemp: convertTemp(242) = 242 + 80 - 256 = 66 (> 65)
        data[17] = 242.toByte()
        data[18] = 176.toByte()
        val frame = buildRawFrame(Flag.DEFAULT, Command.REAL_TIME_INFO, data)
        val result = decoder.processFrame(frame)
        assertNotNull(result)
        assertTrue(result!!.alertFlags.contains(AlertFlag.HIGH_TEMPERATURE))
    }

    @Test
    fun `V11 sets LOW_BATTERY when battery below 10`() {
        setModelId(61)
        val data = ByteArray(37)
        data[16] = 5.toByte() // battery = 5
        data[17] = 176.toByte(); data[18] = 176.toByte()
        val frame = buildRawFrame(Flag.DEFAULT, Command.REAL_TIME_INFO, data)
        val result = decoder.processFrame(frame)
        assertNotNull(result)
        assertTrue(result!!.alertFlags.contains(AlertFlag.LOW_BATTERY))
    }

    @Test
    fun `V11 sets OVERSPEED when speed exceeds 35`() {
        setModelId(61)
        val data = ByteArray(37)
        // speed at 4-5: 3600 = 36.00 km/h
        data[4] = 0x10.toByte(); data[5] = 0x0E // 3600 LE
        data[17] = 176.toByte(); data[18] = 176.toByte()
        val frame = buildRawFrame(Flag.DEFAULT, Command.REAL_TIME_INFO, data)
        val result = decoder.processFrame(frame)
        assertNotNull(result)
        assertTrue(result!!.alertFlags.contains(AlertFlag.OVERSPEED))
    }

    // ======== convertTemp ========

    @Test
    fun `convertTemp calculates correctly`() {
        // (byte & 0xFF) + 80 - 256
        // 176 + 80 - 256 = 0
        // 200 + 80 - 256 = 24
        // 0 + 80 - 256 = -176
        // 255 + 80 - 256 = 79
        setModelId(61)
        val data = ByteArray(37)
        data[17] = 176.toByte() // mosTemp → 0°C
        data[18] = 176.toByte()
        val frame = buildRawFrame(Flag.DEFAULT, Command.REAL_TIME_INFO, data)
        val result = decoder.processFrame(frame)
        assertNotNull(result)
        assertEquals(0.0f, result!!.temperatureC, 0.1f)
    }

    // ======== Command builders ========

    @Test
    fun `setLight on builds correct frame`() {
        val frame = InmotionV2Decoder.setLight(true)
        val unesc = unescape(frame.sliceArray(2 until frame.size))
        assertEquals(Flag.DEFAULT.toByte(), unesc[0])
        assertEquals(Command.CONTROL.toByte(), unesc[2])
        assertEquals(0x50.toByte(), unesc[3]) // sub-command
        assertEquals(0x01.toByte(), unesc[4]) // on
    }

    @Test
    fun `setLight off builds correct frame`() {
        val frame = InmotionV2Decoder.setLight(false)
        val unesc = unescape(frame.sliceArray(2 until frame.size))
        assertEquals(0x50.toByte(), unesc[3])
        assertEquals(0x00.toByte(), unesc[4]) // off
    }

    @Test
    fun `setMaxSpeed builds correct frame`() {
        val frame = InmotionV2Decoder.setMaxSpeed(35)
        val unesc = unescape(frame.sliceArray(2 until frame.size))
        assertEquals(Command.CONTROL.toByte(), unesc[2])
        assertEquals(0x21.toByte(), unesc[3])
        assertEquals(35.toByte(), unesc[4]) // lo byte
        assertEquals(0x00.toByte(), unesc[5]) // hi byte
    }

    @Test
    fun `setSpeakerVolume builds correct frame`() {
        val frame = InmotionV2Decoder.setSpeakerVolume(50)
        val unesc = unescape(frame.sliceArray(2 until frame.size))
        assertEquals(0x26.toByte(), unesc[3])
        assertEquals(50.toByte(), unesc[4])
    }

    @Test
    fun `setLock builds correct frame`() {
        val frame = InmotionV2Decoder.setLock(true)
        val unesc = unescape(frame.sliceArray(2 until frame.size))
        assertEquals(0x31.toByte(), unesc[3])
        assertEquals(0x01.toByte(), unesc[4])
    }

    @Test
    fun `calibration builds correct frame`() {
        val frame = InmotionV2Decoder.calibration()
        val unesc = unescape(frame.sliceArray(2 until frame.size))
        assertEquals(0x52.toByte(), unesc[3])
    }

    @Test
    fun `beep builds correct frame`() {
        val frame = InmotionV2Decoder.beep()
        val unesc = unescape(frame.sliceArray(2 until frame.size))
        assertEquals(0x51.toByte(), unesc[3])
        assertEquals(0x64.toByte(), unesc[4])
    }

    @Test
    fun `setFan builds correct frame`() {
        val frame = InmotionV2Decoder.setFan(true)
        val unesc = unescape(frame.sliceArray(2 until frame.size))
        assertEquals(0x43.toByte(), unesc[3])
        assertEquals(0x01.toByte(), unesc[4])
    }

    @Test
    fun `setDrl builds correct frame`() {
        val frame = InmotionV2Decoder.setDrl(true)
        val unesc = unescape(frame.sliceArray(2 until frame.size))
        assertEquals(0x2D.toByte(), unesc[3])
        assertEquals(0x01.toByte(), unesc[4])
    }

    @Test
    fun `setHandleButton builds correct frame`() {
        val frame = InmotionV2Decoder.setHandleButton(2)
        val unesc = unescape(frame.sliceArray(2 until frame.size))
        assertEquals(0x2E.toByte(), unesc[3])
        assertEquals(0x02.toByte(), unesc[4])
    }

    @Test
    fun `setPedalSensitivity builds correct frame`() {
        val frame = InmotionV2Decoder.setPedalSensitivity(3)
        val unesc = unescape(frame.sliceArray(2 until frame.size))
        assertEquals(0x25.toByte(), unesc[3])
        assertEquals(0x03.toByte(), unesc[4])
    }

    @Test
    fun `setTransportMode builds correct frame`() {
        val frame = InmotionV2Decoder.setTransportMode(true)
        val unesc = unescape(frame.sliceArray(2 until frame.size))
        assertEquals(0x32.toByte(), unesc[3])
        assertEquals(0x01.toByte(), unesc[4])
    }

    // ======== Request commands ========

    @Test
    fun `requestRealTimeInfo uses DEFAULT flag and REAL_TIME_INFO command`() {
        val frame = InmotionV2Decoder.requestRealTimeInfo()
        val unesc = unescape(frame.sliceArray(2 until frame.size))
        assertEquals(Flag.DEFAULT.toByte(), unesc[0])
        assertEquals(Command.REAL_TIME_INFO.toByte(), unesc[2])
    }

    @Test
    fun `requestMainInfo uses INITIAL flag and MAIN_INFO command`() {
        val frame = InmotionV2Decoder.requestMainInfo()
        val unesc = unescape(frame.sliceArray(2 until frame.size))
        assertEquals(Flag.INITIAL.toByte(), unesc[0])
        assertEquals(Command.MAIN_INFO.toByte(), unesc[2])
    }

    @Test
    fun `requestTotalStats uses DEFAULT flag and TOTAL_STATS command`() {
        val frame = InmotionV2Decoder.requestTotalStats()
        val unesc = unescape(frame.sliceArray(2 until frame.size))
        assertEquals(Flag.DEFAULT.toByte(), unesc[0])
        assertEquals(Command.TOTAL_STATS.toByte(), unesc[2])
    }

    // ======== Flag and Command constants ========

    @Test
    fun `Flag constants have expected values`() {
        assertEquals(0x11, Flag.INITIAL)
        assertEquals(0x14, Flag.DEFAULT)
    }

    @Test
    fun `Command constants have expected values`() {
        assertEquals(0x01, Command.MAIN_VERSION)
        assertEquals(0x02, Command.MAIN_INFO)
        assertEquals(0x04, Command.REAL_TIME_INFO)
        assertEquals(0x05, Command.BATTERY_REAL_TIME_INFO)
        assertEquals(0x11, Command.TOTAL_STATS)
        assertEquals(0x60, Command.CONTROL)
    }

    // ======== Stream integration ========

    @Test
    fun `decode processes complete escaped frame from byte stream`() {
        setModelId(61)
        // Build a frame via buildFrame, then feed it to decode
        val realTimeData = ByteArray(37)
        realTimeData[0] = 0xD0.toByte(); realTimeData[1] = 0x20.toByte() // voltage=8400=84V
        realTimeData[16] = 50 // battery
        realTimeData[17] = 200.toByte(); realTimeData[18] = 200.toByte()

        val wireFrame = InmotionV2Decoder.buildFrame(Flag.DEFAULT, Command.REAL_TIME_INFO, realTimeData)
        val result = decoder.decode(wireFrame)
        assertNotNull(result)
        assertEquals(84.0f, result!!.voltageV, 0.01f)
        assertEquals(50, result.batteryPercent)
    }

    // ======== Helpers ========

    /** Set modelId via MainInfo frame. */
    private fun setModelId(id: Int) {
        val data = byteArrayOf(0x01, 0x00, id.toByte())
        val frame = buildRawFrame(Flag.DEFAULT, Command.MAIN_INFO, data)
        decoder.processFrame(frame)
    }
}
