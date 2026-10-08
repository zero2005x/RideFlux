package com.rideflux.protocol.familyscooter

import com.rideflux.protocol.familyscooter.m365.M365Codec
import com.rideflux.protocol.familyscooter.ninebot.NinebotRetailCodec
import io.github.zero2005x.pev.core.codec.xiaomi.XiaomiMotorInfoDecoder
import io.github.zero2005x.pev.core.codec.xiaomi.XiaomiPdu
import io.github.zero2005x.pev.core.telemetry.FieldId
import io.github.zero2005x.pev.core.telemetry.FieldState
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Synthetic consumer-contract tests; these do not establish physical vehicle verification. */
class M365CoreConsumerTest {
    private fun payload(soc: Int = 65, speed: Int = 2500, temperature: Int = 237): ByteArray =
        ByteArray(32).also { bytes ->
            fun word(offset: Int, value: Int) {
                bytes[offset] = value.toByte()
                bytes[offset + 1] = (value ushr 8).toByte()
            }
            word(0, 0x1234)
            word(2, 7)
            word(8, soc)
            word(10, speed)
            word(14, 0xFFFE)
            word(16, 0xFFFF)
            word(18, 123)
            word(22, temperature)
        }

    @Test
    fun m365SocOdometerAndFrameTemperatureConsumeSharedCore() {
        for (temperature in listOf(-32768, -237, -1, 0, 237, 32767)) {
            val bytes = payload(temperature = temperature)
            val core = XiaomiMotorInfoDecoder.decode(bytes, 123)
            val consumer = requireNotNull(M365Codec.decodeB0(bytes))
            assertEquals(core.usableValue(FieldId.SOC_PERCENT)!!.toInt(), consumer.batteryPercent)
            assertEquals(core.usableValue(FieldId.TOTAL_DISTANCE_M)!!.toLong(), consumer.totalDistanceMetres)
            assertEquals(4294967294L, consumer.totalDistanceMetres)
            assertEquals(temperature, consumer.frameTemperatureRaw)
            assertEquals(core.usableValue(FieldId.TEMP_FRAME)!!, consumer.frameTemperatureRaw / 10.0, 1e-9)
        }
    }

    @Test
    fun invalidCoreSocNeverBecomesAClampedRideFluxReading() {
        for (soc in listOf(101, 65535)) {
            val bytes = payload(soc = soc)
            assertEquals(FieldState.INVALID, XiaomiMotorInfoDecoder.decode(bytes, 1)[FieldId.SOC_PERCENT].state)
            assertNull(M365Codec.decodeB0(bytes))
        }
    }

    @Test
    fun consumerRetainsExactFullBlockRequirement() {
        for (length in listOf(0, 21, 22, 23, 24, 31, 33)) {
            assertNull(M365Codec.decodeB0(payload().copyOf(length)))
        }
    }

    @Test
    fun retailRequestWrapsTheSharedLogicalReadPduWithoutChangingChecksum() {
        for ((register, count, destination) in listOf(Triple(0xB0, 32, 0x20), Triple(0x31, 12, 0x22))) {
            val logical = XiaomiPdu.read(register, count, destination)
            val retail = M365Codec.readRequest(register, count, destination)
            assertArrayEquals(logical, retail.copyOfRange(2, retail.size - 2))
            assertEquals(true, RetailFraming.verify(retail, 2))
        }
    }

    @Test
    fun reverseMagnitudeAndDocumentedHighWordPolicyConflictStayExplicit() {
        for ((raw, magnitude) in listOf(0xFC18 to 1f, 0xFFFF to 0.001f, 0x8000 to 32.768f)) {
            assertEquals(magnitude, requireNotNull(M365Codec.decodeB0(payload(speed = raw))).speedKmh!!, 1e-6f)
        }
        val bytes = payload(speed = 0x9C40)
        assertEquals(40.0, XiaomiMotorInfoDecoder.decode(bytes, 1).usableValue(FieldId.SPEED_KMH)!!, 1e-9)
        // Preserve the existing RideFlux display interpretation until its high-word contract is reviewed.
        assertEquals(25.536f, requireNotNull(M365Codec.decodeB0(bytes)).speedKmh!!, 1e-6f)
    }

    @Test
    fun es2RetainsIndependentDiagnosticDecodeWithoutM365CoreModelEvidence() {
        val bytes = payload(speed = 0xFEDC, temperature = -10)
        val diagnostic = requireNotNull(NinebotRetailCodec.decodeEs2B0(bytes))
        assertEquals(65, diagnostic.batteryPercent)
        assertEquals(0xFEDC, diagnostic.speedRaw)
        assertEquals(4294967294L, diagnostic.totalDistanceMetres)
        assertEquals(-10, diagnostic.frameTemperatureRaw)
        assertNull(NinebotRetailCodec.decodeEs2B0(bytes.copyOf(31)))
        assertNull(NinebotRetailCodec.decodeEs2B0(payload(soc = 101)))
    }
}
