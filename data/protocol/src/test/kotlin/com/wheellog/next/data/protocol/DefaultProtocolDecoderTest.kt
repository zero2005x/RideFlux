package com.wheellog.next.data.protocol

import com.wheellog.next.domain.model.WheelType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class DefaultProtocolDecoderTest {

    private lateinit var decoder: DefaultProtocolDecoder

    @Before
    fun setUp() {
        decoder = DefaultProtocolDecoder()
    }

    // ---- detectWheelType: KingSong ----

    @Test
    fun `detectWheelType returns KINGSONG for KS- prefix`() {
        assertEquals(WheelType.KINGSONG, decoder.detectWheelType("KS-18XL"))
    }

    @Test
    fun `detectWheelType returns KINGSONG for KS_ prefix`() {
        assertEquals(WheelType.KINGSONG, decoder.detectWheelType("KS_S18"))
    }

    // ---- detectWheelType: Veteran (checked before Begode) ----

    @Test
    fun `detectWheelType returns VETERAN for Sherman`() {
        assertEquals(WheelType.VETERAN, decoder.detectWheelType("SHERMAN MAX"))
    }

    @Test
    fun `detectWheelType returns VETERAN for Lynx`() {
        assertEquals(WheelType.VETERAN, decoder.detectWheelType("LYNX"))
    }

    @Test
    fun `detectWheelType returns VETERAN for Patton`() {
        assertEquals(WheelType.VETERAN, decoder.detectWheelType("PATTON"))
    }

    @Test
    fun `detectWheelType returns VETERAN for Abrams`() {
        assertEquals(WheelType.VETERAN, decoder.detectWheelType("ABRAMS"))
    }

    // ---- detectWheelType: Begode ----

    @Test
    fun `detectWheelType returns BEGODE for GW- prefix`() {
        assertEquals(WheelType.BEGODE, decoder.detectWheelType("GW-MCM5"))
    }

    @Test
    fun `detectWheelType returns BEGODE for GOTWAY prefix`() {
        assertEquals(WheelType.BEGODE, decoder.detectWheelType("GOTWAY MCM5"))
    }

    @Test
    fun `detectWheelType returns BEGODE for BEGODE prefix`() {
        assertEquals(WheelType.BEGODE, decoder.detectWheelType("BEGODE_EX20"))
    }

    @Test
    fun `detectWheelType returns BEGODE for known model prefixes`() {
        assertEquals(WheelType.BEGODE, decoder.detectWheelType("EX30"))
        assertEquals(WheelType.BEGODE, decoder.detectWheelType("RS19"))
        assertEquals(WheelType.BEGODE, decoder.detectWheelType("MCM5"))
        assertEquals(WheelType.BEGODE, decoder.detectWheelType("MTEN4"))
        assertEquals(WheelType.BEGODE, decoder.detectWheelType("NIKOLA+"))
        assertEquals(WheelType.BEGODE, decoder.detectWheelType("MONSTER"))
        assertEquals(WheelType.BEGODE, decoder.detectWheelType("MSUPER"))
        assertEquals(WheelType.BEGODE, decoder.detectWheelType("MSP"))
        assertEquals(WheelType.BEGODE, decoder.detectWheelType("HERO"))
        assertEquals(WheelType.BEGODE, decoder.detectWheelType("MASTER"))
        assertEquals(WheelType.BEGODE, decoder.detectWheelType("A2"))
        assertEquals(WheelType.BEGODE, decoder.detectWheelType("T3"))
        assertEquals(WheelType.BEGODE, decoder.detectWheelType("T4"))
        assertEquals(WheelType.BEGODE, decoder.detectWheelType("S2"))
    }

    // ---- detectWheelType: Inmotion v2 ----

    @Test
    fun `detectWheelType returns INMOTION_V2 for V11- prefix`() {
        assertEquals(WheelType.INMOTION_V2, decoder.detectWheelType("V11-12345"))
    }

    @Test
    fun `detectWheelType returns INMOTION_V2 for V12- prefix`() {
        assertEquals(WheelType.INMOTION_V2, decoder.detectWheelType("V12-HT"))
    }

    @Test
    fun `detectWheelType returns INMOTION_V2 for V13- prefix`() {
        assertEquals(WheelType.INMOTION_V2, decoder.detectWheelType("V13-PRO"))
    }

    @Test
    fun `detectWheelType returns INMOTION_V2 for V14- prefix`() {
        assertEquals(WheelType.INMOTION_V2, decoder.detectWheelType("V14-2024"))
    }

    @Test
    fun `detectWheelType returns INMOTION_V2 for V11Y`() {
        assertEquals(WheelType.INMOTION_V2, decoder.detectWheelType("V11Y"))
    }

    @Test
    fun `detectWheelType returns INMOTION_V2 for V9-`() {
        assertEquals(WheelType.INMOTION_V2, decoder.detectWheelType("V9-123"))
    }

    @Test
    fun `detectWheelType returns INMOTION_V2 for V12S`() {
        assertEquals(WheelType.INMOTION_V2, decoder.detectWheelType("V12S"))
    }

    // ---- detectWheelType: Inmotion v1 ----

    @Test
    fun `detectWheelType returns INMOTION for short V-prefix names`() {
        assertEquals(WheelType.INMOTION, decoder.detectWheelType("V10"))
        assertEquals(WheelType.INMOTION, decoder.detectWheelType("V8"))
    }

    @Test
    fun `detectWheelType returns INMOTION for INMOTION prefix`() {
        assertEquals(WheelType.INMOTION, decoder.detectWheelType("INMOTION V12"))
    }

    // ---- detectWheelType: Ninebot Z ----

    @Test
    fun `detectWheelType returns NINEBOT_Z for Ninebot Z names`() {
        assertEquals(WheelType.NINEBOT_Z, decoder.detectWheelType("NINEBOT Z10"))
    }

    @Test
    fun `detectWheelType returns NINEBOT_Z for Segway with Z`() {
        assertEquals(WheelType.NINEBOT_Z, decoder.detectWheelType("SEGWAY Z10"))
    }

    // ---- detectWheelType: Ninebot ----

    @Test
    fun `detectWheelType returns NINEBOT for standard Ninebot`() {
        assertEquals(WheelType.NINEBOT, decoder.detectWheelType("NINEBOT S1"))
    }

    @Test
    fun `detectWheelType returns NINEBOT for Segway without Z`() {
        assertEquals(WheelType.NINEBOT, decoder.detectWheelType("SEGWAY S1"))
    }

    // ---- detectWheelType: Unknown ----

    @Test
    fun `detectWheelType returns UNKNOWN for null`() {
        assertEquals(WheelType.UNKNOWN, decoder.detectWheelType(null))
    }

    @Test
    fun `detectWheelType returns UNKNOWN for unrecognized name`() {
        assertEquals(WheelType.UNKNOWN, decoder.detectWheelType("RANDOM_DEVICE"))
    }

    // ---- bleProfileFor ----

    @Test
    fun `bleProfileFor returns non-null for all supported types`() {
        assertNotNull(decoder.bleProfileFor(WheelType.KINGSONG))
        assertNotNull(decoder.bleProfileFor(WheelType.BEGODE))
        assertNotNull(decoder.bleProfileFor(WheelType.GOTWAY))
        assertNotNull(decoder.bleProfileFor(WheelType.VETERAN))
        assertNotNull(decoder.bleProfileFor(WheelType.INMOTION))
        assertNotNull(decoder.bleProfileFor(WheelType.INMOTION_V2))
        assertNotNull(decoder.bleProfileFor(WheelType.NINEBOT))
        assertNotNull(decoder.bleProfileFor(WheelType.NINEBOT_Z))
    }

    @Test
    fun `bleProfileFor returns null for UNKNOWN`() {
        assertNull(decoder.bleProfileFor(WheelType.UNKNOWN))
    }

    // ---- decode dispatches to correct decoder ----

    @Test
    fun `decode dispatches KingSong frame to KingSongDecoder`() {
        val frame = ByteArray(20).apply {
            this[0] = 0xAA.toByte(); this[1] = 0x55.toByte()
            // voltage = 7840 using getInt2R: [0xA0, 0x1E] reverses to 0x1EA0 = 7840
            this[2] = 0xA0.toByte(); this[3] = 0x1E.toByte()
            this[16] = 0xA9.toByte()
        }
        val result = decoder.decode(WheelType.KINGSONG, frame)
        assertNotNull(result)
    }

    @Test
    fun `decode dispatches Begode frame to BegodeDecoder`() {
        val frame = ByteArray(24).apply {
            this[0] = 0x55.toByte(); this[1] = 0xAA.toByte()
            this[2] = 0x20.toByte(); this[3] = 0xD0.toByte() // voltage
            this[18] = 0x00.toByte() // Frame A
            this[20] = 0x5A.toByte(); this[21] = 0x5A.toByte()
            this[22] = 0x5A.toByte(); this[23] = 0x5A.toByte()
        }
        val result = decoder.decode(WheelType.BEGODE, frame)
        assertNotNull(result)
    }

    @Test
    fun `decode returns null for UNKNOWN wheel type`() {
        assertNull(decoder.decode(WheelType.UNKNOWN, ByteArray(20)))
    }
}
