package com.rideflux.protocol.familyk

import com.rideflux.protocol.familyk.KingSongFrameShape.Extension
import com.rideflux.protocol.familyk.KingSongFrameShape.LengthClass
import com.rideflux.protocol.familyk.KingSongFrameShape.Magic
import com.rideflux.protocol.familyk.KingSongFrameShape.PageKind
import com.rideflux.protocol.familyk.KingSongFrameShape.TailObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KingSongFrameShapeTest {
    private fun frame(size: Int = 20, m0: Int = 0xaa, m1: Int = 0x55, page: Int = 0xa9, sub: Int = 0,
                      tail: Boolean = true): ByteArray = ByteArray(size).also {
        it[0] = m0.toByte(); it[1] = m1.toByte()
        if (size >= 20) {
            it[16] = page.toByte(); it[17] = sub.toByte()
            val t = if (tail) 0x5a else 0
            it[18] = t.toByte(); it[19] = t.toByte()
        }
    }

    private fun classify() = KingSongShapeClassifier.classify(frame())

    @Test fun `magic and length classes`() {
        assertEquals(Magic.AA55, classify().magic)
        assertEquals(Magic.F1EF, KingSongShapeClassifier.classify(frame(m0 = 0xf1, m1 = 0xef)).magic)
        assertEquals(Magic.UNKNOWN, KingSongShapeClassifier.classify(frame(m0 = 1, m1 = 2)).magic)
        assertEquals(Magic.UNKNOWN, KingSongShapeClassifier.classify(ByteArray(1)).magic)
        assertEquals(Magic.UNKNOWN, KingSongShapeClassifier.classify(ByteArray(0)).magic)
        val short = KingSongShapeClassifier.classify(ByteArray(19))
        assertEquals(LengthClass.TOO_SHORT, short.lengthClass)
        assertNull(short.page)
        assertNull(short.subCode)
        assertEquals(TailObservation.NOT_PRESENT, short.tail)
        assertEquals(LengthClass.EXACTLY_20, classify().lengthClass)
        assertEquals(LengthClass.EXTENDED, KingSongShapeClassifier.classify(frame(size = 30)).lengthClass)
    }

    @Test fun `page kinds depend on magic and page`() {
        val aa = mapOf(0xa9 to PageKind.LIVE_A, 0xb9 to PageKind.LIVE_B, 0x8a to PageKind.SETTINGS_ACK,
            0xf1 to PageKind.BMS_F1, 0xf2 to PageKind.BMS_F2, 0x01 to PageKind.OTHER)
        for ((page, kind) in aa) assertEquals(kind, KingSongShapeClassifier.classify(frame(page = page)).pageKind)
        val ef = mapOf(0xc2 to PageKind.C2, 0xc5 to PageKind.C5, 0xc9 to PageKind.C9, 0xa9 to PageKind.OTHER)
        for ((page, kind) in ef) {
            assertEquals(kind, KingSongShapeClassifier.classify(frame(m0 = 0xf1, m1 = 0xef, page = page)).pageKind)
        }
        assertEquals(PageKind.OTHER, KingSongShapeClassifier.classify(ByteArray(5)).pageKind)
    }

    @Test fun `tail observation distinguishes the fixed trailer`() {
        assertEquals(TailObservation.FIXED_5A5A, classify().tail)
        assertEquals(TailObservation.OTHER, KingSongShapeClassifier.classify(frame(tail = false)).tail)
        val half = frame().also { it[19] = 0 }
        assertEquals(TailObservation.OTHER, KingSongShapeClassifier.classify(half).tail)
    }

    @Test fun `bms raw values need a bms page and a small sub code`() {
        val bms = KingSongShapeClassifier.classify(frame(page = 0xf1, sub = 3).also { it[2] = 1; it[3] = 2 })
        assertEquals(7, bms.bmsRawValues()!!.size)
        assertEquals(1 or (2 shl 8), bms.bmsRawValues()!![0])
        assertEquals(7, KingSongShapeClassifier.classify(frame(page = 0xf2, sub = 7)).bmsRawValues()!!.size)
        assertNull(KingSongShapeClassifier.classify(frame(page = 0xf1, sub = 8)).bmsRawValues())
        assertNull(KingSongShapeClassifier.classify(frame(page = 0xa9, sub = 1)).bmsRawValues())
        assertNull(KingSongShapeClassifier.classify(ByteArray(10)).bmsRawValues())
    }

    @Test fun `extensions exist only on extended bms frames`() {
        assertNull(KingSongShapeClassifier.classify(frame(page = 0xf1, sub = 0xd0)).extension())
        assertNull(KingSongShapeClassifier.classify(frame(size = 30, page = 0xa9, sub = 0xd0)).extension())
        assertNull(KingSongShapeClassifier.classify(frame(size = 30, page = 0xf1, sub = 1)).extension())
        val d0 = KingSongShapeClassifier.classify(
            frame(size = 30, page = 0xf2, sub = 0xd0).also { it[2] = 7; it[3] = 8; it[4] = 9 }).extension()
        assertEquals(Extension.D0(7, 8, 9), d0)
    }

    @Test fun `d1 extension checks its own length fields`() {
        assertNull(KingSongShapeClassifier.classify(frame(size = 21, page = 0xf1, sub = 0xd1)).extension())
        val overflow = frame(size = 30, page = 0xf1, sub = 0xd1).also { it[20] = 5; it[21] = 5 }
        assertNull(KingSongShapeClassifier.classify(overflow).extension())
        val ok = frame(size = 30, page = 0xf1, sub = 0xd1).also {
            it[20] = 2; it[21] = 3
            for (i in 22 until 27) it[i] = (i - 21).toByte()
        }
        val d1 = KingSongShapeClassifier.classify(ok).extension() as Extension.D1
        assertEquals(listOf<Byte>(1, 2), d1.first.toList())
        assertEquals(listOf<Byte>(3, 4, 5), d1.second.toList())
        val same = KingSongShapeClassifier.classify(ok).extension() as Extension.D1
        assertEquals(d1, same)
        assertEquals(d1.hashCode(), same.hashCode())
        assertNotEquals(d1, Extension.D1(byteArrayOf(1), byteArrayOf(2)))
        assertTrue(d1 != Extension.D0(1, 2, 3))
    }
}
