package com.wheellog.next.data.protocol.decoder

import com.wheellog.next.domain.model.AlertFlag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NinebotDecoderTest {

    private lateinit var decoder: NinebotDecoder

    @Before
    fun setUp() {
        decoder = NinebotDecoder()
    }

    // ---- NinebotUnpacker tests ----

    @Test
    fun `unpacker returns null for incomplete header`() {
        val unpacker = NinebotUnpacker()
        assertNull(unpacker.feed(0x55.toByte()))
        assertNull(unpacker.feed(0x00.toByte())) // not 0xAA, resets
    }

    @Test
    fun `unpacker collects frame after 55 AA header`() {
        val unpacker = NinebotUnpacker()
        assertNull(unpacker.feed(0x55.toByte()))
        assertNull(unpacker.feed(0xAA.toByte()))
        // First byte after header is len; total collect = len + 6
        // len = 2, so collect 8 bytes total
        assertNull(unpacker.feed(0x02.toByte())) // len=2
        for (i in 0 until 6) {
            assertNull(unpacker.feed(0x00.toByte()))
        }
        // 8th byte completes the frame
        val frame = unpacker.feed(0x00.toByte())
        assertNotNull(frame)
        assertEquals(8, frame!!.size)
    }

    @Test
    fun `unpacker reset clears state`() {
        val unpacker = NinebotUnpacker()
        unpacker.feed(0x55.toByte())
        unpacker.feed(0xAA.toByte())
        unpacker.feed(0x05.toByte())
        unpacker.reset()
        // After reset, feeding non-header bytes should not produce frame
        assertNull(unpacker.feed(0x00.toByte()))
    }

    // ---- Checksum ----

    @Test
    fun `computeChecksum produces correct complement`() {
        // Sum of bytes XOR 0xFFFF
        val data = byteArrayOf(0x02, 0x09, 0x01, 0x01, 0xB0.toByte())
        val crc = NinebotDecoder.computeChecksum(data)
        val expected = ((0x02 + 0x09 + 0x01 + 0x01 + 0xB0) xor 0xFFFF) and 0xFFFF
        assertEquals(expected, crc)
    }

    @Test
    fun `computeChecksum handles empty array`() {
        assertEquals(0xFFFF, NinebotDecoder.computeChecksum(ByteArray(0)))
    }

    // ---- Crypto ----

    @Test
    fun `crypto with zero gamma returns same bytes except byte 0`() {
        // gamma is all zeros by default, so XOR does nothing
        val input = byteArrayOf(0x10, 0x20, 0x30, 0x40, 0x50)
        val result = decoder.crypto(input.copyOf())
        assertEquals(input[0], result[0]) // byte 0 not encrypted
        assertEquals(input[1], result[1])
        assertEquals(input[2], result[2])
    }

    @Test
    fun `crypto with non-zero gamma XORs from byte 1 onward`() {
        val gamma = ByteArray(16) { (it + 1).toByte() }
        decoder.updateGamma(gamma)
        val input = byteArrayOf(0x10, 0x20, 0x30, 0x40)
        val result = decoder.crypto(input.copyOf())
        assertEquals(0x10.toByte(), result[0]) // unchanged
        assertEquals((0x20 xor 0x01).toByte(), result[1]) // XOR gamma[0]
        assertEquals((0x30 xor 0x02).toByte(), result[2]) // XOR gamma[1]
        assertEquals((0x40 xor 0x03).toByte(), result[3]) // XOR gamma[2]
    }

    // ---- CANMessage parsing ----

    @Test
    fun `CANMessage parse extracts fields correctly`() {
        // body: len=4, src=0x09, dst=0x01, cmd=0x04, param=0xB0, data=[0x10, 0x20]
        val body = byteArrayOf(0x04, 0x09, 0x01, 0x04, 0xB0.toByte(), 0x10, 0x20)
        val msg = CANMessage.parse(body)
        assertNotNull(msg)
        assertEquals(0x09, msg!!.source)
        assertEquals(0x01, msg.destination)
        assertEquals(0x04, msg.command)
        assertEquals(0xB0, msg.parameter)
        assertEquals(2, msg.data.size)
        assertEquals(0x10.toByte(), msg.data[0])
    }

    @Test
    fun `CANMessage parse returns null for too-short body`() {
        assertNull(CANMessage.parse(byteArrayOf(0x01, 0x02)))
    }

    @Test
    fun `CANMessage parse handles zero data length`() {
        // len=2 means dataLen=0 (len - 2 = command + parameter)
        val body = byteArrayOf(0x02, 0x09, 0x01, 0x04, 0xB0.toByte())
        val msg = CANMessage.parse(body)
        assertNotNull(msg)
        assertEquals(0, msg!!.data.size)
    }

    // ---- processFrame: full pipeline with checksum ----

    @Test
    fun `processFrame returns null for too-short frame`() {
        assertNull(decoder.processFrame(ByteArray(3)))
    }

    @Test
    fun `processFrame returns null when checksum mismatch`() {
        // Build a frame with wrong checksum (gamma is zero so crypto is no-op)
        val body = byteArrayOf(0x02, 0x09, 0x01, 0x04, 0xB0.toByte())
        val wrongCrc = byteArrayOf(0xFF.toByte(), 0xFF.toByte())
        assertNull(decoder.processFrame(body + wrongCrc))
    }

    @Test
    fun `processFrame parses live data with correct checksum`() {
        val result = decoder.processFrame(buildLiveDataFrame())
        assertNotNull(result)
    }

    // ---- LiveData decoding (Param 0xB0) ----

    @Test
    fun `live data parses battery at offset 8`() {
        // Battery = 75
        val result = decoder.processFrame(buildLiveDataFrame(battery = 75))
        assertNotNull(result)
        assertEquals(75, result!!.batteryPercent)
    }

    @Test
    fun `live data parses speed at offset 10`() {
        // Speed = 2550 (25.50 km/h)
        val result = decoder.processFrame(buildLiveDataFrame(speed = 2550))
        assertNotNull(result)
        assertEquals(25.50f, result!!.speedKmh, 0.01f)
    }

    @Test
    fun `live data parses negative speed`() {
        val result = decoder.processFrame(buildLiveDataFrame(speed = -1000))
        assertNotNull(result)
        assertEquals(-10.0f, result!!.speedKmh, 0.01f)
    }

    @Test
    fun `live data parses distance at offset 14`() {
        // distance = 5200 (5.2 km)
        val result = decoder.processFrame(buildLiveDataFrame(distance = 5200))
        assertNotNull(result)
        assertEquals(5.2f, result!!.tripDistanceKm, 0.01f)
    }

    @Test
    fun `live data parses temperature at offset 22`() {
        // temperature = 380 (38.0°C)
        val result = decoder.processFrame(buildLiveDataFrame(temperature = 380))
        assertNotNull(result)
        assertEquals(38.0f, result!!.temperatureC, 0.1f)
    }

    @Test
    fun `live data parses voltage at offset 24`() {
        // voltage = 5600 (56.00V)
        val result = decoder.processFrame(buildLiveDataFrame(voltage = 5600))
        assertNotNull(result)
        assertEquals(56.0f, result!!.voltageV, 0.01f)
    }

    @Test
    fun `live data parses current at offset 26`() {
        // current = 320 (3.20A)
        val result = decoder.processFrame(buildLiveDataFrame(current = 320))
        assertNotNull(result)
        assertEquals(3.20f, result!!.currentA, 0.01f)
    }

    @Test
    fun `live data triggers LOW_BATTERY when battery below 10`() {
        val result = decoder.processFrame(buildLiveDataFrame(battery = 5))
        assertNotNull(result)
        assertTrue(result!!.alertFlags.contains(AlertFlag.LOW_BATTERY))
    }

    @Test
    fun `live data triggers HIGH_TEMPERATURE when temp above 65`() {
        val result = decoder.processFrame(buildLiveDataFrame(temperature = 660))
        assertNotNull(result)
        assertTrue(result!!.alertFlags.contains(AlertFlag.HIGH_TEMPERATURE))
    }

    @Test
    fun `live data triggers OVERSPEED when speed above 35`() {
        val result = decoder.processFrame(buildLiveDataFrame(speed = 3600))
        assertNotNull(result)
        assertTrue(result!!.alertFlags.contains(AlertFlag.OVERSPEED))
    }

    // ---- Serial number parsing ----

    @Test
    fun `processFrame stores serial number on SERIAL_NUMBER param`() {
        val serial = "N2GWC1234567890"
        val frame = buildParamFrame(Comm.GET, Param.SERIAL_NUMBER, serial.toByteArray())
        decoder.processFrame(frame)
        assertTrue(decoder.serialNumber.startsWith("N2GWC"))
    }

    // ---- Battery level parsing ----

    @Test
    fun `processFrame updates battery on BATTERY_LEVEL param`() {
        // battery = 88
        val data = byteArrayOf(88.toByte(), 0x00)
        val frame = buildParamFrame(Comm.GET, Param.BATTERY_LEVEL, data)
        val result = decoder.processFrame(frame)
        assertNotNull(result)
        assertEquals(88, result!!.batteryPercent)
    }

    // ---- GetKey updates gamma ----

    @Test
    fun `processFrame updates gamma on GET_KEY command`() {
        val key = ByteArray(16) { (0x42 + it).toByte() }
        val frame = buildParamFrame(Comm.GET_KEY, 0x00, key)
        assertNull(decoder.processFrame(frame)) // GetKey doesn't produce telemetry
        // Verify gamma was updated by encrypting a test buffer
        val test = byteArrayOf(0x00, 0x00, 0x00)
        val encrypted = decoder.crypto(test)
        assertEquals(key[0], encrypted[1]) // 0x00 XOR gamma[0] = gamma[0]
    }

    // ---- Command builders ----

    @Test
    fun `buildCommand produces correct structure`() {
        val cmd = NinebotDecoder.buildCommand(
            source = Addr.APP,
            destination = Addr.CONTROLLER,
            command = Comm.READ,
            parameter = Param.LIVE_DATA,
        )
        // body: len(1) + src(1) + dst(1) + cmd(1) + param(1) = 5 bytes
        // plus CRC (2) = 7 bytes
        assertEquals(7, cmd.size)
        assertEquals(0x02.toByte(), cmd[0]) // len = 2 (command + parameter)
        assertEquals(Addr.APP.toByte(), cmd[1])
        assertEquals(Addr.CONTROLLER.toByte(), cmd[2])
        assertEquals(Comm.READ.toByte(), cmd[3])
        assertEquals(Param.LIVE_DATA.toByte(), cmd[4])
    }

    @Test
    fun `buildCommand checksum is valid`() {
        val cmd = NinebotDecoder.buildCommand(Addr.APP, Addr.CONTROLLER, Comm.READ, Param.LIVE_DATA)
        val body = cmd.sliceArray(0 until cmd.size - 2)
        val crcLow = cmd[cmd.size - 2].toInt() and 0xFF
        val crcHigh = cmd[cmd.size - 1].toInt() and 0xFF
        val receivedCrc = crcLow or (crcHigh shl 8)
        assertEquals(NinebotDecoder.computeChecksum(body), receivedCrc)
    }

    @Test
    fun `requestSerialNumber builds command with READ and SERIAL_NUMBER param`() {
        val cmd = NinebotDecoder.requestSerialNumber()
        assertEquals(Comm.READ.toByte(), cmd[3])
        assertEquals(Param.SERIAL_NUMBER.toByte(), cmd[4])
    }

    @Test
    fun `requestLiveData builds command with READ and LIVE_DATA param`() {
        val cmd = NinebotDecoder.requestLiveData()
        assertEquals(Comm.READ.toByte(), cmd[3])
        assertEquals(Param.LIVE_DATA.toByte(), cmd[4])
    }

    @Test
    fun `requestGetKey builds command with GET_KEY command`() {
        val cmd = NinebotDecoder.requestGetKey()
        assertEquals(Comm.GET_KEY.toByte(), cmd[3])
    }

    // ---- Full decode via stream (unpacker integration) ----

    @Test
    fun `decode feeds bytes through unpacker and returns telemetry`() {
        // Build a valid frame with 55 AA header for the unpacker
        val innerFrame = buildLiveDataFrame(speed = 2000, voltage = 5000)
        // Wrap with 55 AA header
        val stream = byteArrayOf(0x55.toByte(), 0xAA.toByte()) + innerFrame
        val result = decoder.decode(stream)
        assertNotNull(result)
        assertEquals(20.0f, result!!.speedKmh, 0.01f)
    }

    // ---- Helpers ----

    /**
     * Build a valid Ninebot CANMessage frame (without 55 AA header) that contains
     * LiveData (Param.LIVE_DATA) with a correct checksum. The gamma is zero, so
     * crypto is a no-op.
     */
    private fun buildLiveDataFrame(
        battery: Int = 50,
        speed: Int = 0,
        distance: Long = 0,
        temperature: Int = 300,
        voltage: Int = 5000,
        current: Int = 0,
    ): ByteArray {
        // LiveData needs 28 bytes of data at minimum
        val data = ByteArray(28)
        writeUInt16LE(data, 8, battery)
        writeInt16LE(data, 10, speed)
        writeUInt32LE(data, 14, distance)
        writeInt16LE(data, 22, temperature)
        writeUInt16LE(data, 24, voltage)
        writeInt16LE(data, 26, current)

        return buildParamFrame(Comm.GET, Param.LIVE_DATA, data)
    }

    /**
     * Build a CANMessage frame with correct checksum.
     */
    private fun buildParamFrame(command: Int, parameter: Int, data: ByteArray): ByteArray {
        val len = data.size + 2 // command + parameter + data
        val body = ByteArray(5 + data.size)
        body[0] = (len and 0xFF).toByte()
        body[1] = Addr.CONTROLLER.toByte() // source
        body[2] = Addr.APP.toByte()        // destination
        body[3] = command.toByte()
        body[4] = parameter.toByte()
        data.copyInto(body, destinationOffset = 5)

        val crc = NinebotDecoder.computeChecksum(body)
        return body + byteArrayOf(
            (crc and 0xFF).toByte(),
            ((crc shr 8) and 0xFF).toByte(),
        )
    }

    private fun writeUInt16LE(arr: ByteArray, offset: Int, value: Int) {
        arr[offset] = (value and 0xFF).toByte()
        arr[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun writeInt16LE(arr: ByteArray, offset: Int, value: Int) {
        arr[offset] = (value and 0xFF).toByte()
        arr[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun writeUInt32LE(arr: ByteArray, offset: Int, value: Long) {
        arr[offset] = (value and 0xFF).toByte()
        arr[offset + 1] = ((value shr 8) and 0xFF).toByte()
        arr[offset + 2] = ((value shr 16) and 0xFF).toByte()
        arr[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }
}
