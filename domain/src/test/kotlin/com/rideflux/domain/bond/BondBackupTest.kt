package com.rideflux.domain.bond

import com.rideflux.domain.bond.BondBackup.ExportResult
import com.rideflux.domain.bond.BondBackup.Preview
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class BondBackupTest {
    private class MemoryStore : BondStore {
        val items = linkedMapOf<String, BondEntry>()
        override suspend fun list(): List<BondEntry> = items.values.map { copy(it) }
        override suspend fun put(entry: BondEntry) { items[entry.mac] = copy(entry) }
        override suspend fun remove(mac: String): Boolean = items.remove(mac) != null
        private fun copy(e: BondEntry) = BondEntry(e.mac, e.family, e.credential(), e.label, e.model)
    }

    private val pass = "correct horse battery".toCharArray()
    private val now = Instant.parse("2026-10-05T00:00:00Z")

    private fun entry(mac: String, seed: Int = 1) =
        BondEntry(mac, BondFamily.XIAOMI_MI, ByteArray(12) { (it + seed).toByte() }, "scooter $seed")

    private fun backup(store: BondStore) = BondBackup(store, { now }, BondEnvelope.MIN_ITERATIONS)

    @Test fun `exporting an empty store or with a weak passphrase is refused`() = runTest {
        val store = MemoryStore()
        assertEquals(ExportResult.NothingToExport, backup(store).export(pass))
        store.put(entry("AA:BB:CC:DD:EE:01"))
        val weak = backup(store).export("short".toCharArray())
        assertEquals(BondEnvelope.PassphraseProblem.TOO_SHORT, (weak as ExportResult.WeakPassphrase).problem)
        assertEquals(ExportResult.NothingToExport, backup(store).export(pass, setOf("11:11:11:11:11:11")))
    }

    @Test fun `a backup moves keys to a new phone`() = runTest {
        val oldPhone = MemoryStore().apply {
            put(entry("AA:BB:CC:DD:EE:01", 1)); put(entry("AA:BB:CC:DD:EE:02", 2))
        }
        val exported = backup(oldPhone).export(pass) as ExportResult.Exported
        assertEquals(2, exported.count)
        assertEquals(2, oldPhone.list().size)
        assertArrayEquals(ByteArray(12) { (it + 1).toByte() }, oldPhone.items.getValue("AA:BB:CC:DD:EE:01").credential())

        val newPhone = MemoryStore()
        val preview = backup(newPhone).preview(exported.file, pass) as Preview.Ready
        assertTrue(preview.items.none { it.conflict })
        val summary = backup(newPhone).import(preview.items, emptySet())
        assertEquals(BondBackup.ImportSummary(2, 0, 0), summary)
        assertArrayEquals(ByteArray(12) { (it + 2).toByte() }, newPhone.items.getValue("AA:BB:CC:DD:EE:02").credential())
    }

    @Test fun `only the chosen scooters are exported`() = runTest {
        val store = MemoryStore().apply { put(entry("AA:BB:CC:DD:EE:01")); put(entry("AA:BB:CC:DD:EE:02", 2)) }
        val exported = backup(store).export(pass, setOf("AA:BB:CC:DD:EE:02")) as ExportResult.Exported
        assertEquals(1, exported.count)
        val ready = backup(MemoryStore()).preview(exported.file, pass) as Preview.Ready
        assertEquals(listOf("AA:BB:CC:DD:EE:02"), ready.items.map { it.entry.mac })
    }

    @Test fun `conflicts keep the stored key unless the user chose to overwrite`() = runTest {
        val source = MemoryStore().apply { put(entry("AA:BB:CC:DD:EE:01", 9)); put(entry("AA:BB:CC:DD:EE:02", 9)) }
        val file = (backup(source).export(pass) as ExportResult.Exported).file
        val target = MemoryStore().apply { put(entry("AA:BB:CC:DD:EE:01", 1)); put(entry("AA:BB:CC:DD:EE:02", 1)) }
        val ready = backup(target).preview(file, pass) as Preview.Ready
        assertTrue(ready.items.all { it.conflict })
        val summary = backup(target).import(ready.items, setOf("AA:BB:CC:DD:EE:02"))
        assertEquals(BondBackup.ImportSummary(0, 1, 1), summary)
        assertArrayEquals(ByteArray(12) { (it + 1).toByte() }, target.items.getValue("AA:BB:CC:DD:EE:01").credential())
        assertArrayEquals(ByteArray(12) { (it + 9).toByte() }, target.items.getValue("AA:BB:CC:DD:EE:02").credential())
    }

    @Test fun `import and preview wipe what they hold`() = runTest {
        val source = MemoryStore().apply { put(entry("AA:BB:CC:DD:EE:01")) }
        val file = (backup(source).export(pass) as ExportResult.Exported).file
        val ready = backup(MemoryStore()).preview(file, pass) as Preview.Ready
        val held = ready.items.single().entry
        backup(MemoryStore()).import(ready.items, emptySet())
        assertArrayEquals(ByteArray(12), held.credential())
        val again = backup(MemoryStore()).preview(file, pass) as Preview.Ready
        again.wipe()
        assertArrayEquals(ByteArray(12), again.items.single().entry.credential())
    }

    @Test fun `a wrong passphrase or foreign file fails the preview`() = runTest {
        val store = MemoryStore().apply { put(entry("AA:BB:CC:DD:EE:01")) }
        val file = (backup(store).export(pass) as ExportResult.Exported).file
        val wrong = backup(MemoryStore()).preview(file, "wrong wrong wrong".toCharArray()) as Preview.Failed
        assertEquals(BondEnvelope.OpenResult.WrongPassphraseOrCorrupt, wrong.reason)
        val foreign = backup(MemoryStore()).preview(ByteArray(64), pass) as Preview.Failed
        assertEquals(BondEnvelope.OpenResult.NotABondFile, foreign.reason)
        assertFalse(store.items.isEmpty())
    }

    @Test fun `a store failure during preview propagates after the decrypted keys are wiped`() = runTest {
        val source = MemoryStore().apply { put(entry("AA:BB:CC:DD:EE:01")) }
        val file = (backup(source).export(pass) as ExportResult.Exported).file
        val broken = object : BondStore {
            override suspend fun list(): List<BondEntry> = throw BondStoreUnreadableException()
            override suspend fun put(entry: BondEntry) = Unit
            override suspend fun remove(mac: String) = false
        }
        var thrown = false
        try { backup(broken).preview(file, pass) } catch (_: BondStoreUnreadableException) { thrown = true }
        assertTrue(thrown)
    }

    @Test fun `the default clock and work factor are usable`() = runTest {
        val store = MemoryStore().apply { put(entry("AA:BB:CC:DD:EE:01")) }
        val exported = BondBackup(store).export(pass) as ExportResult.Exported
        assertEquals(1, exported.count)
    }
}
