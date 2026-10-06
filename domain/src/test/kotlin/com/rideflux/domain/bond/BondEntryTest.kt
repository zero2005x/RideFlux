package com.rideflux.domain.bond

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BondEntryTest {
    private fun secret(size: Int, seed: Int = 1) = ByteArray(size) { (it + seed).toByte() }
    private fun mi(mac: String = "aa:bb:cc:dd:ee:ff", seed: Int = 1) =
        BondEntry(mac, BondFamily.XIAOMI_MI, secret(12, seed), "My M365", "M365")

    @Test fun `address is normalised and validated`() {
        assertEquals("AA:BB:CC:DD:EE:FF", mi(" aa:bb:cc:dd:ee:ff ").mac)
        for (bad in listOf("", "AABBCCDDEEFF", "AA:BB:CC:DD:EE", "AA:BB:CC:DD:EE:GG", "AA-BB-CC-DD-EE-FF")) {
            assertThrows(IllegalArgumentException::class.java) { mi(bad) }
        }
    }

    @Test fun `credential length must match the family`() {
        assertThrows(IllegalArgumentException::class.java) {
            BondEntry("AA:BB:CC:DD:EE:FF", BondFamily.XIAOMI_MI, secret(16))
        }
        assertThrows(IllegalArgumentException::class.java) {
            BondEntry("AA:BB:CC:DD:EE:FF", BondFamily.NINEBOT_CRYPTO, secret(12))
        }
        assertEquals(16, BondEntry("AA:BB:CC:DD:EE:FF", BondFamily.NINEBOT_CRYPTO, secret(16)).credential().size)
    }

    @Test fun `labels and models are length limited and free of control characters`() {
        val long = "x".repeat(BondEntry.MAX_TEXT + 1)
        assertThrows(IllegalArgumentException::class.java) { BondEntry("AA:BB:CC:DD:EE:FF", BondFamily.XIAOMI_MI, secret(12), long) }
        assertThrows(IllegalArgumentException::class.java) { BondEntry("AA:BB:CC:DD:EE:FF", BondFamily.XIAOMI_MI, secret(12), "a\nb") }
        assertThrows(IllegalArgumentException::class.java) { BondEntry("AA:BB:CC:DD:EE:FF", BondFamily.XIAOMI_MI, secret(12), "", long) }
        assertThrows(IllegalArgumentException::class.java) { BondEntry("AA:BB:CC:DD:EE:FF", BondFamily.XIAOMI_MI, secret(12), "", "m\u0000") }
        assertNull(BondEntry("AA:BB:CC:DD:EE:FF", BondFamily.XIAOMI_MI, secret(12)).model)
    }

    @Test fun `the entry keeps its own copy and hands out copies`() {
        val input = secret(12)
        val entry = BondEntry("AA:BB:CC:DD:EE:FF", BondFamily.XIAOMI_MI, input)
        input.fill(0)
        assertArrayEquals(secret(12), entry.credential())
        entry.credential().fill(0)
        assertArrayEquals(secret(12), entry.credential())
    }

    @Test fun `wipe zeroes the held secret`() {
        val entry = mi()
        entry.wipe()
        assertArrayEquals(ByteArray(12), entry.credential())
    }

    @Test fun `equality covers the secret and the printed form hides it`() {
        assertEquals(mi(), mi())
        assertEquals(mi().hashCode(), mi().hashCode())
        assertNotEquals(mi(), mi(seed = 2))
        assertNotEquals(mi(), mi("11:22:33:44:55:66"))
        assertNotEquals(mi(), "not an entry")
        assertTrue(mi().sameCredential(mi()))
        assertFalse(mi().sameCredential(mi(seed = 2)))
        val text = mi().toString()
        assertFalse(text.contains("AA:BB:CC"))
        assertFalse(text.contains("010203"))
        assertTrue(text.contains("***"))
        assertEquals("••:••:••:••:EE:FF", mi().maskedMac())
    }

    @Test fun `family ids round trip`() {
        BondFamily.entries.forEach { assertEquals(it, BondFamily.fromId(it.id)) }
        assertNull(BondFamily.fromId("rokid_bridge"))
    }
}
