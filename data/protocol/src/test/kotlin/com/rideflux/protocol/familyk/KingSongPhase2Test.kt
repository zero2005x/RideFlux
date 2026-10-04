/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.protocol.familyk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SYNTHETIC_VENDOR_STATIC vectors: no KingSong hardware capture is available. */
class KingSongPhase2Test {
    private fun frame(magic0: Int = 0xaa, magic1: Int = 0x55, page: Int = 0xa9, size: Int = 20) =
        ByteArray(size).also {
            it[0] = magic0.toByte(); it[1] = magic1.toByte(); it[16] = page.toByte()
            it[18] = 0x5a; it[19] = 0x5a
        }

    @Test fun `classifier records both magic families and extended frames without production promotion`() {
        val extended = frame(0xf1, 0xef, 0xc2, 26)
        val shape = KingSongShapeClassifier.classify(extended)
        assertEquals(KingSongFrameShape.Magic.F1EF, shape.magic)
        assertEquals(KingSongFrameShape.LengthClass.EXTENDED, shape.lengthClass)
        assertEquals(KingSongFrameShape.PageKind.C2, shape.pageKind)
        assertNull(KingSongDecoder.decode(extended))
        assertNull(KingSongDecoder.decode(frame(size = 26)))
        val short = KingSongShapeClassifier.classify(frame().copyOf(19))
        assertEquals(KingSongFrameShape.LengthClass.TOO_SHORT, short.lengthClass)
        assertNull(short.page)
        assertNull(short.subCode)
    }

    @Test fun `classifier reports absent tail but production still rejects it`() {
        val raw = frame().also { it[19] = 0 }
        assertEquals(KingSongFrameShape.TailObservation.OTHER, KingSongShapeClassifier.classify(raw).tail)
        assertNull(KingSongDecoder.decode(raw))
    }

    @Test fun `BMS page exposes only seven raw little endian values`() {
        val raw = frame(page = 0xf1).also {
            it[17] = 3
            for (i in 0 until 7) { it[2 + 2 * i] = (i + 1).toByte(); it[3 + 2 * i] = 1 }
        }
        val shape = KingSongShapeClassifier.classify(raw)
        assertEquals(KingSongFrameShape.PageKind.BMS_F1, shape.pageKind)
        assertEquals(listOf(257, 258, 259, 260, 261, 262, 263), shape.bmsRawValues())
    }

    @Test fun `D1 lists require complete extended body`() {
        val raw = frame(page = 0xf2, size = 26).also {
            it[17] = 0xd1.toByte(); it[20] = 2; it[21] = 2
            it[22] = 10; it[23] = 11; it[24] = 12; it[25] = 13
        }
        val ext = KingSongShapeClassifier.classify(raw).extension() as KingSongFrameShape.Extension.D1
        assertTrue(ext.first.contentEquals(byteArrayOf(10, 11)))
        assertTrue(ext.second.contentEquals(byteArrayOf(12, 13)))
        assertNull(KingSongShapeClassifier.classify(raw.copyOf(25)).extension())
    }

    @Test fun `checksum class is structural and uses template CRC`() {
        assertEquals(193, KingSongCommandTaxonomy.FIXED_TAIL_BUILDERS +
            KingSongCommandTaxonomy.CHECKSUM_TAILED_BUILDERS + KingSongCommandTaxonomy.UNCLASSIFIED_BUILDERS)
        val fixed = KingSongCommandBuilder.requestSerialNumber()
        assertEquals(KingSongCommandTaxonomy.TailClass.FIXED_5A5A, KingSongCommandTaxonomy.classify(fixed))
        val candidate = frame(page = 0xe3).also {
            it[17] = 0xbf.toByte()
            val crc = KingSongCommandTaxonomy.crc16Ccitt(it, 2, 18)
            it[18] = (crc ushr 8).toByte(); it[19] = crc.toByte()
        }
        assertEquals(KingSongCommandTaxonomy.TailClass.CRC16_CCITT_CANDIDATE,
            KingSongCommandTaxonomy.classify(candidate))
        assertEquals(KingSongCommandTaxonomy.TailClass.UNCLASSIFIED,
            KingSongCommandTaxonomy.classify(candidate.also { it[19] = (it[19].toInt() xor 1).toByte() }))
    }

    @Test fun `model matching prioritizes specific names and leaves unsupported limits unknown`() {
        assertTrue(KingSongModelRegistry.models.size >= 17)
        assertEquals("KS-18XL", KingSongModelRegistry.identify("KS18L4")?.name)
        assertEquals("KS-S22 PRO", KingSongModelRegistry.identify("KingSong KS-S22 Pro")?.name)
        val model = KingSongModelRegistry.identify("KS-16XS")!!
        assertEquals("KS-16XS", model.name)
        assertEquals(16, model.wheelDiameterInches)
        assertNull(model.packSeriesCells)
        assertNull(model.tiltbackLimitKmh)
        assertNull(model.pwmAlarmPercent)
        assertFalse(KingSongModelRegistry.identify("Ninebot Max") != null)
    }
}
