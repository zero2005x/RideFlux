package com.rideflux.protocol

import com.rideflux.protocol.familyg.BegodeFrame
import com.rideflux.protocol.familyk.KingSongCommandTaxonomy
import com.rideflux.protocol.familyv.VeteranDecoder
import com.rideflux.protocol.familyscooter.m365.M365Codec
import com.rideflux.protocol.familyscooter.ninebot.NinebotRetailCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic L1 vectors. These pin framing behaviour, not hardware validation. */
class Phase2WireInvariantTest {
    @Test fun `M365 reply matches independently calculated checksum and rejects semantic mismatch`() {
        // Body sum 04+23+01+B0+41+00 = 0x119; inverted 16-bit sum = 0xFEE6, LE.
        val reply = hex("55 AA 04 23 01 B0 41 00 E6 FE")
        assertArrayEquals(hex("41 00"), M365Codec.readReplyPayload(reply, 0xb0, 2))
        assertNull(M365Codec.readReplyPayload(reply, 0xb1, 2))
        assertNull(M365Codec.readReplyPayload(reply, 0xb0, 1))
        assertNull(M365Codec.readReplyPayload(reply.copyOf(9), 0xb0, 2))
        assertNull(M365Codec.readReplyPayload(reply.clone().also { it[8] = 0 }, 0xb0, 2))
    }

    @Test fun `Ninebot SHU reply includes length in checksum and rejects wrong register`() {
        // Covered sum 02+23+3E+22+04+41+00 = 0xCA; inverted 16-bit sum = 0xFF35, LE.
        val reply = hex("5A A5 02 23 3E 22 04 41 00 35 FF")
        assertArrayEquals(hex("41 00"), NinebotRetailCodec.readReplyPayload(
            reply, 0x22, 2))
        assertNull(NinebotRetailCodec.readReplyPayload(
            reply, 0x23, 2))
        assertNull(NinebotRetailCodec.readReplyPayload(
            reply.clone().also { it[9] = 0 }, 0x22, 2))
    }

    @Test fun `KingSong CRC candidate uses a pinned CCITT result and corruption is unclassified`() {
        // CRC-16/CCITT with initial zero over 14 zero bytes, E3 BF = 0x13D5.
        val frame = hex("AA 55 00 00 00 00 00 00 00 00 00 00 00 00 00 00 E3 BF 13 D5")
        assertEquals(0x13d5, KingSongCommandTaxonomy.crc16Ccitt(frame, 2, 18))
        assertEquals(KingSongCommandTaxonomy.TailClass.CRC16_CCITT_CANDIDATE,
            KingSongCommandTaxonomy.classify(frame))
        assertEquals(KingSongCommandTaxonomy.TailClass.UNCLASSIFIED,
            KingSongCommandTaxonomy.classify(frame.clone().also { it[19] = 0 }))
    }

    @Test fun `Begode mode and alarm masks remain independent with lower bits set`() {
        val rawWord = (5 shl 13) or (3 shl 10) or 0x03ff
        val frame = BegodeFrame.SettingsAndOdometer(0, rawWord, 0, 0, 0, 0, 0, 0)
        assertEquals(5, frame.rideMode)
        assertEquals(3, frame.speedAlarmMode)
    }

    @Test fun `Veteran extended frame checks independently pinned CRC32 and rejects corruption`() {
        // CRC-32/ISO-HDLC of DC 5A 5C 28 followed by 36 zero bytes = 0x9B3B8DB5.
        val frame = hex("DC 5A 5C 28") + ByteArray(36) + hex("9B 3B 8D B5")
        val accepted = VeteranDecoder.decode(frame)
        assertTrue(accepted is VeteranDecoder.DecodeResult.Ok)
        val decoded = accepted as VeteranDecoder.DecodeResult.Ok
        assertEquals(44, decoded.consumedBytes)
        assertTrue(decoded.frame.crc32Present)
        val corrupted = frame.clone().also { it[4] = 1 }
        val rejected = VeteranDecoder.decode(corrupted)
        assertTrue(rejected is VeteranDecoder.DecodeResult.Fail)
        assertTrue((rejected as VeteranDecoder.DecodeResult.Fail).error is VeteranDecoder.DecodeError.BadCrc)
    }

    private fun hex(value: String): ByteArray =
        value.split(' ').map { it.toInt(16).toByte() }.toByteArray()
}
