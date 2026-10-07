package com.rideflux.data.preferences

import com.rideflux.domain.bond.BondEntry
import com.rideflux.domain.bond.BondFamily
import com.rideflux.domain.bond.BondStoreUnreadableException
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EncryptedFileBondStoreTest {
    @get:Rule val folder = TemporaryFolder()

    /** A real AES-GCM cipher with an in-memory key, standing in for the Keystore. */
    private class MemoryCipher(seed: Int = 1) : BondCipher {
        private val key = SecretKeySpec(ByteArray(32) { (it + seed).toByte() }, "AES")
        override fun encrypt(plain: ByteArray): ByteArray {
            val iv = ByteArray(12).also(SecureRandom()::nextBytes)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            return iv + c.doFinal(plain)
        }
        override fun decrypt(blob: ByteArray): ByteArray {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob, 0, 12))
            return c.doFinal(blob, 12, blob.size - 12)
        }
    }

    private fun store(file: File = File(folder.root, "bonds.bin"), cipher: BondCipher = MemoryCipher()) =
        EncryptedFileBondStore(file, cipher, Dispatchers.Unconfined)

    private fun entry(mac: String = "AA:BB:CC:DD:EE:FF", seed: Int = 1, label: String = "M365") =
        BondEntry(mac, BondFamily.XIAOMI_MI, ByteArray(12) { (it + seed).toByte() }, label)

    @Test fun `an empty store lists nothing and creates no file`() = runTest {
        val file = File(folder.root, "none.bin")
        assertTrue(store(file).list().isEmpty())
        assertFalse(file.exists())
    }

    @Test fun `entries survive a restart and are replaced by address`() = runTest {
        val file = File(folder.root, "bonds.bin")
        store(file).put(entry(seed = 1))
        store(file).put(entry("11:22:33:44:55:66", seed = 2))
        store(file).put(entry(seed = 3, label = "renamed"))
        val listed = store(file).list()
        assertEquals(listOf("11:22:33:44:55:66", "AA:BB:CC:DD:EE:FF"), listed.map { it.mac })
        assertEquals("renamed", listed[1].label)
        assertArrayEquals(ByteArray(12) { (it + 3).toByte() }, listed[1].credential())
    }

    @Test fun `neither the secret nor the address nor the label is readable in the file`() = runTest {
        val file = File(folder.root, "bonds.bin")
        store(file).put(entry(label = "VerySecretLabel"))
        val text = String(file.readBytes(), Charsets.ISO_8859_1)
        assertFalse(text.contains("AA:BB:CC:DD:EE:FF"))
        assertFalse(text.contains("VerySecretLabel"))
        assertFalse(text.contains("0102030405"))
        assertFalse(File(folder.root, "bonds.bin.tmp").exists())
    }

    @Test fun `remove deletes one entry and reports whether it existed`() = runTest {
        val s = store()
        s.put(entry()); s.put(entry("11:22:33:44:55:66"))
        assertTrue(s.remove("aa:bb:cc:dd:ee:ff"))
        assertFalse(s.remove("AA:BB:CC:DD:EE:FF"))
        assertEquals(listOf("11:22:33:44:55:66"), s.list().map { it.mac })
        assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { s.remove("nope") } }
    }

    @Test fun `listed entries are independent copies`() = runTest {
        val s = store()
        s.put(entry())
        s.list().single().wipe()
        assertArrayEquals(ByteArray(12) { (it + 1).toByte() }, s.list().single().credential())
    }

    @Test fun `a changed key or a damaged file is reported as unreadable and never overwritten`() = runTest {
        val file = File(folder.root, "bonds.bin")
        store(file).put(entry())
        val before = file.readBytes()
        assertThrows(BondStoreUnreadableException::class.java) {
            kotlinx.coroutines.runBlocking { store(file, MemoryCipher(seed = 9)).list() }
        }
        assertThrows(BondStoreUnreadableException::class.java) {
            kotlinx.coroutines.runBlocking { store(file, MemoryCipher(seed = 9)).put(entry("11:22:33:44:55:66")) }
        }
        assertArrayEquals(before, file.readBytes())
        file.writeBytes(ByteArray(5))
        assertThrows(BondStoreUnreadableException::class.java) { kotlinx.coroutines.runBlocking { store(file).list() } }
    }

    @Test fun `a store full of entries still saves`() = runTest {
        val s = store()
        repeat(64) { s.put(entry("AA:BB:CC:DD:00:%02X".format(it), it)) }
        assertEquals(64, s.list().size)
    }

    @Test fun `removed keys stay removed after restart including the last key`() = runTest {
        val file = File(folder.root, "restart.bin")
        val s = store(file)
        s.put(entry()); s.put(entry("11:22:33:44:55:66", seed = 9, label = "kept vehicle"))
        s.remove("AA:BB:CC:DD:EE:FF")
        val remaining = store(file).list().single()
        assertEquals("11:22:33:44:55:66", remaining.mac)
        assertEquals("kept vehicle", remaining.label)
        assertArrayEquals(ByteArray(12) { (it + 9).toByte() }, remaining.credential())
        remaining.wipe()
        store(file).remove("11:22:33:44:55:66")
        assertTrue(store(file).list().isEmpty())
        assertFalse(File(folder.root, "restart.bin.tmp").exists())
    }

    @Test fun `failed removal preserves durable entries and can be retried`() = runTest {
        val file = File(folder.root, "retry.bin")
        val cipher = MemoryCipher()
        var failSave = false
        val flaky = object : BondCipher {
            override fun encrypt(plain: ByteArray): ByteArray {
                if (failSave) throw java.io.IOException("disk")
                return cipher.encrypt(plain)
            }
            override fun decrypt(blob: ByteArray) = cipher.decrypt(blob)
        }
        val s = store(file, flaky)
        s.put(entry()); s.put(entry("11:22:33:44:55:66"))
        val before = file.readBytes()
        failSave = true
        assertThrows(java.io.IOException::class.java) {
            kotlinx.coroutines.runBlocking { s.remove("AA:BB:CC:DD:EE:FF") }
        }
        assertArrayEquals(before, file.readBytes())
        failSave = false
        s.remove("AA:BB:CC:DD:EE:FF")
        assertEquals(listOf("11:22:33:44:55:66"), store(file, cipher).list().map { it.mac })
    }
}
