package com.rideflux.protocol.familyvesc

/** VESC COMM_GET_VALUES_SETUP (0x2F), never COMM_GET_VALUES (0x04). */
object VescSetupCodec {
    data class Values(
        val motorRpm: Int,
        val motorCurrentA: Float,
        val inputCurrentA: Float,
        val inputVoltageV: Float,
        val dutyPercent: Float,
        val speedKmh: Float,
        val batteryPercent: Float,
        val mosTemperatureC: Float,
        val odometerMetres: Long,
    )

    /** A read poll only; this class contains no setting or firmware command builder. */
    fun setupPoll(): ByteArray = byteArrayOf(0x02, 0x01, 0x2f, 0xd5.toByte(), 0x8d.toByte(), 0x03)

    fun decode(frame: ByteArray): Values? {
        if (frame.size < 5 || frame[0].u() != 0x02 || frame.last().u() != 0x03) return null
        val length = frame[1].u()
        if (length != 70 || frame.size != length + 5) return null
        val payload = frame.copyOfRange(2, 2 + length)
        if (payload[0].u() != 0x2f) return null
        val crc = (frame[2 + length].u() shl 8) or frame[3 + length].u()
        if (crc16Xmodem(payload) != crc) return null
        val duty = be16(payload, 13).toShort() / 10f
        val speed = be32(payload, 19).toInt() / 1_000f
        val battery = be16(payload, 25).toShort() / 10f
        val voltage = be16(payload, 23).toShort() / 10f
        if (duty !in -100f..100f || battery !in 0f..100f || voltage !in 0f..300f ||
            !speed.isFinite() || kotlin.math.abs(speed) > 250f) return null
        return Values(
            motorRpm = be32(payload, 15).toInt(),
            motorCurrentA = be32(payload, 5).toInt() / 100f,
            inputCurrentA = be32(payload, 9).toInt() / 100f,
            inputVoltageV = voltage,
            dutyPercent = duty,
            speedKmh = speed,
            batteryPercent = battery,
            mosTemperatureC = be16(payload, 1).toShort() / 10f,
            odometerMetres = be32(payload, 62),
        )
    }

    fun crc16Xmodem(bytes: ByteArray): Int {
        var crc = 0
        for (byte in bytes) {
            crc = crc xor (byte.u() shl 8)
            repeat(8) { crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x1021) and 0xffff else (crc shl 1) and 0xffff }
        }
        return crc
    }

    private fun be16(b: ByteArray, i: Int) = (b[i].u() shl 8) or b[i + 1].u()
    private fun be32(b: ByteArray, i: Int) = (b[i].u().toLong() shl 24) or
        (b[i + 1].u().toLong() shl 16) or (b[i + 2].u().toLong() shl 8) or b[i + 3].u().toLong()
    private fun Byte.u() = toInt() and 0xff
}
