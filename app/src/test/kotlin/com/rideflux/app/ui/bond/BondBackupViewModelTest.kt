package com.rideflux.app.ui.bond

import com.rideflux.domain.bond.BondBackup
import com.rideflux.domain.bond.BondEntry
import com.rideflux.domain.bond.BondEnvelope
import com.rideflux.domain.bond.BondFamily
import com.rideflux.domain.bond.BondStore
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BondBackupViewModelTest {
    private class MemoryStore(var failList: Boolean = false, var failPut: Boolean = false) : BondStore {
        val items = linkedMapOf<String, BondEntry>()
        override suspend fun list(): List<BondEntry> {
            if (failList) throw IllegalStateException("unreadable")
            return items.values.map { copy(it) }
        }
        override suspend fun put(entry: BondEntry) {
            if (failPut) throw IllegalStateException("disk full")
            items[entry.mac] = copy(entry)
        }
        override suspend fun remove(mac: String) = items.remove(mac) != null
        private fun copy(e: BondEntry) = BondEntry(e.mac, e.family, e.credential(), e.label, e.model)
    }

    private class FakeIo : BondFileIo {
        var content: ByteArray? = null
        var written: ByteArray? = null
        var writeOk = true
        var lastLocation: String? = null
        override suspend fun read(location: String, maxBytes: Int): ByteArray? = content?.copyOf()
        override suspend fun write(location: String, bytes: ByteArray): Boolean {
            lastLocation = location
            written = bytes.copyOf()
            return writeOk
        }
    }

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = UnconfinedTestDispatcher(scheduler)
    private val pass = "correct horse battery"
    private fun chars(s: String = pass) = s.toCharArray()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun entry(mac: String, seed: Int = 1, label: String = "scooter", model: String? = null) =
        BondEntry(mac, BondFamily.XIAOMI_MI, ByteArray(12) { (it + seed).toByte() }, label, model)

    private fun testHexToken(bytes: Int = 12, prefix: String = "", separator: String = ""): CharArray {
        val sb = StringBuilder(prefix)
        for (i in 0 until bytes) {
            if (i > 0 && separator.isNotEmpty()) sb.append(separator)
            sb.append("%02x".format(i))
        }
        return sb.toString().toCharArray()
    }

    private fun viewModel(store: BondStore, io: FakeIo = FakeIo()) = BondBackupViewModel(
        store, BondBackup(store, { Instant.parse("2026-10-05T00:00:00Z") }, BondEnvelope.MIN_ITERATIONS),
        io, dispatcher,
    )

    private fun collectEvents(vm: BondBackupViewModel, scope: kotlinx.coroutines.CoroutineScope): MutableList<BondEvent> {
        val events = mutableListOf<BondEvent>()
        scope.launch(dispatcher) { vm.events.collect { events += it } }
        return events
    }

    private fun notices(events: List<BondEvent>) = events.filterIsInstance<BondEvent.ShowNotice>().map { it.notice }

    @Test fun `rows are loaded masked and an unreadable store is flagged`() = runTest(dispatcher) {
        val store = MemoryStore().apply { put(entry("AA:BB:CC:DD:EE:01")) }
        val vm = viewModel(store)
        assertEquals("••:••:••:••:EE:01", vm.state.value.rows.single().maskedMac)
        assertFalse(vm.state.value.loadFailed)
        store.failList = true
        vm.refresh()
        assertTrue(vm.state.value.loadFailed)
        assertTrue(vm.state.value.rows.isEmpty())
    }

    @Test fun `export asks for re-authentication only when there is something to export`() = runTest(dispatcher) {
        val empty = viewModel(MemoryStore())
        val emptyEvents = collectEvents(empty, backgroundScope)
        empty.requestExport()
        assertTrue(emptyEvents.isEmpty())

        val vm = viewModel(MemoryStore().apply { put(entry("AA:BB:CC:DD:EE:01")) })
        val events = collectEvents(vm, backgroundScope)
        vm.requestExport()
        assertEquals(listOf<BondEvent>(BondEvent.NeedReauth), events)
        vm.reauthUnavailable()
        assertEquals(BondNotice.ReauthUnavailable, notices(events).single())
    }

    @Test fun `a failed or cancelled re-authentication opens nothing`() = runTest(dispatcher) {
        val vm = viewModel(MemoryStore().apply { put(entry("AA:BB:CC:DD:EE:01")) })
        vm.onReauthResult(false, 0)
        assertEquals(BondDialog.None, vm.state.value.dialog)
        vm.onReauthResult(true, 0)
        assertEquals(BondDialog.ExportPassphrase, vm.state.value.dialog)
    }

    @Test fun `the passphrase prompt cannot be used without a fresh re-authentication`() = runTest(dispatcher) {
        val vm = viewModel(MemoryStore().apply { put(entry("AA:BB:CC:DD:EE:01")) })
        val events = collectEvents(vm, backgroundScope)
        val p = chars(); val c = chars()
        vm.submitExportPassphrase(p, c, 1_000)
        assertEquals(listOf(BondNotice.ReauthExpired), notices(events))
        assertTrue(p.all { it == '\u0000' } && c.all { it == '\u0000' })

        vm.onReauthResult(true, 1_000)
        vm.submitExportPassphrase(chars(), chars(), 1_000 + BondBackupViewModel.REAUTH_WINDOW_MILLIS + 1)
        assertEquals(2, notices(events).size)
        assertEquals(BondDialog.None, vm.state.value.dialog)
        vm.onReauthResult(true, 5_000)
        vm.submitExportPassphrase(chars(), chars(), 4_000)
        assertEquals(3, notices(events).size)
    }

    @Test fun `mismatched and weak passphrases are rejected and wiped`() = runTest(dispatcher) {
        val vm = viewModel(MemoryStore().apply { put(entry("AA:BB:CC:DD:EE:01")) })
        vm.onReauthResult(true, 0)
        val p = chars(); val c = chars("something else entirely")
        vm.submitExportPassphrase(p, c, 10)
        assertEquals(BondPassphraseError.MISMATCH, vm.state.value.passphraseError)
        assertTrue(p.all { it == '\u0000' } && c.all { it == '\u0000' })
        val weak = chars("short"); val weak2 = chars("short")
        vm.submitExportPassphrase(weak, weak2, 10)
        assertEquals(BondPassphraseError.WEAK, vm.state.value.passphraseError)
        assertEquals(BondDialog.ExportPassphrase, vm.state.value.dialog)
        assertFalse(vm.state.value.busy)
    }

    @Test fun `a confirmed export writes an encrypted file once per confirmation`() = runTest(dispatcher) {
        val io = FakeIo()
        val vm = viewModel(MemoryStore().apply { put(entry("AA:BB:CC:DD:EE:01")) }, io)
        val events = collectEvents(vm, backgroundScope)
        vm.onReauthResult(true, 0)
        val p = chars(); val c = chars()
        vm.submitExportPassphrase(p, c, 30_000)
        assertTrue(p.all { it == '\u0000' } && c.all { it == '\u0000' })
        val create = events.filterIsInstance<BondEvent.CreateDocument>().single()
        assertEquals("rideflux-bonds-20261005.rfbond".length, create.fileName.length)
        assertTrue(create.fileName.endsWith(".rfbond"))
        assertEquals(BondDialog.None, vm.state.value.dialog)

        vm.onExportDocumentCreated("content://x/backup")
        assertEquals("content://x/backup", io.lastLocation)
        assertEquals(listOf(BondNotice.ExportDone(1)), notices(events))
        val opened = BondEnvelope.open(io.written!!, chars()) as BondEnvelope.OpenResult.Opened
        assertEquals("AA:BB:CC:DD:EE:01", opened.payload.entries.single().mac)

        vm.submitExportPassphrase(chars(), chars(), 31_000)
        assertEquals(BondNotice.ReauthExpired, notices(events).last())
    }

    @Test fun `cancelling the file picker discards the export and a failed write is reported`() = runTest(dispatcher) {
        val io = FakeIo()
        val vm = viewModel(MemoryStore().apply { put(entry("AA:BB:CC:DD:EE:01")) }, io)
        val events = collectEvents(vm, backgroundScope)
        vm.onExportDocumentCreated("content://nothing-pending")
        assertNull(io.written)
        vm.onReauthResult(true, 0)
        vm.submitExportPassphrase(chars(), chars(), 1)
        vm.onExportDocumentCreated(null)
        assertNull(io.written)
        vm.onReauthResult(true, 0)
        vm.submitExportPassphrase(chars(), chars(), 1)
        io.writeOk = false
        vm.onExportDocumentCreated("content://x/y")
        assertEquals(BondNotice.IoFailed, notices(events).single())
    }

    @Test fun `an export of a store emptied meanwhile just refreshes`() = runTest(dispatcher) {
        val store = MemoryStore().apply { put(entry("AA:BB:CC:DD:EE:01")) }
        val vm = viewModel(store)
        vm.onReauthResult(true, 0)
        store.items.clear()
        vm.submitExportPassphrase(chars(), chars(), 1)
        assertEquals(BondDialog.None, vm.state.value.dialog)
        assertTrue(vm.state.value.rows.isEmpty())
    }

    private fun backupFile(vararg entries: BondEntry): ByteArray =
        BondEnvelope.seal(entries.toList(), chars(), Instant.parse("2026-10-05T00:00:00Z"), BondEnvelope.MIN_ITERATIONS)

    @Test fun `import walks from file to passphrase to preview to the store`() = runTest(dispatcher) {
        val store = MemoryStore().apply { put(entry("AA:BB:CC:DD:EE:01", seed = 1, label = "old")) }
        val io = FakeIo().apply {
            content = backupFile(entry("AA:BB:CC:DD:EE:01", 9, "new"), entry("AA:BB:CC:DD:EE:02", 9), entry("AA:BB:CC:DD:EE:03", 9))
        }
        val vm = viewModel(store, io)
        val events = collectEvents(vm, backgroundScope)
        vm.onImportDocumentPicked(null)
        assertEquals(BondDialog.None, vm.state.value.dialog)
        vm.onImportDocumentPicked("content://x/in")
        assertEquals(BondDialog.ImportPassphrase, vm.state.value.dialog)

        vm.submitImportPassphrase(chars("wrong wrong wrong"))
        assertEquals(BondPassphraseError.WRONG_OR_CORRUPT, vm.state.value.passphraseError)
        assertEquals(BondDialog.ImportPassphrase, vm.state.value.dialog)

        val good = chars()
        vm.submitImportPassphrase(good)
        assertTrue(good.all { it == '\u0000' })
        val preview = vm.state.value.dialog as BondDialog.ImportPreview
        assertEquals(listOf(true, false, false), preview.rows.map { it.conflict })

        vm.confirmImport(selected = setOf(0, 1), replace = setOf(0))
        assertEquals(listOf<BondNotice>(BondNotice.ImportDone(imported = 1, overwritten = 1, kept = 0)), notices(events))
        assertEquals(BondDialog.None, vm.state.value.dialog)
        assertEquals(setOf("AA:BB:CC:DD:EE:01", "AA:BB:CC:DD:EE:02"), store.items.keys)
        assertEquals("new", store.items.getValue("AA:BB:CC:DD:EE:01").label)
        assertArrayEquals(ByteArray(12) { (it + 9).toByte() }, store.items.getValue("AA:BB:CC:DD:EE:02").credential())
        assertEquals(2, vm.state.value.rows.size)
    }

    @Test fun `an unreadable store during export or preview is reported instead of crashing`() = runTest(dispatcher) {
        val store = MemoryStore().apply { put(entry("AA:BB:CC:DD:EE:01")) }
        val io = FakeIo().apply { content = backupFile(entry("AA:BB:CC:DD:EE:02")) }
        val vm = viewModel(store, io)
        val events = collectEvents(vm, backgroundScope)
        vm.onReauthResult(true, 0)
        store.failList = true
        vm.submitExportPassphrase(chars(), chars(), 1)
        assertEquals(listOf(BondNotice.IoFailed), notices(events))
        assertEquals(BondDialog.None, vm.state.value.dialog)
        assertFalse(vm.state.value.busy)
        vm.onImportDocumentPicked("content://x/in")
        vm.submitImportPassphrase(chars())
        assertEquals(listOf(BondNotice.IoFailed, BondNotice.IoFailed), notices(events))
        assertEquals(BondDialog.None, vm.state.value.dialog)
    }

    @Test fun `unreadable foreign and oversized files are reported`() = runTest(dispatcher) {
        val io = FakeIo()
        val vm = viewModel(MemoryStore(), io)
        val events = collectEvents(vm, backgroundScope)
        vm.onImportDocumentPicked("content://x/in")
        assertEquals(listOf(BondNotice.IoFailed), notices(events))
        io.content = ByteArray(200)
        vm.onImportDocumentPicked("content://x/in")
        vm.submitImportPassphrase(chars())
        assertEquals(BondNotice.InvalidFile, notices(events).last())
        assertEquals(BondDialog.None, vm.state.value.dialog)
        vm.submitImportPassphrase(chars())
        assertEquals(2, notices(events).size)
    }

    @Test fun `a failing store during import is reported and nothing is left pending`() = runTest(dispatcher) {
        val store = MemoryStore()
        val io = FakeIo().apply { content = backupFile(entry("AA:BB:CC:DD:EE:02")) }
        val vm = viewModel(store, io)
        val events = collectEvents(vm, backgroundScope)
        vm.onImportDocumentPicked("content://x/in")
        vm.submitImportPassphrase(chars())
        store.failPut = true
        vm.confirmImport(setOf(0), emptySet())
        assertEquals(BondNotice.IoFailed, notices(events).single())
        vm.confirmImport(setOf(0), emptySet())
        assertEquals(1, notices(events).size)
    }

    @Test fun `dismissing and clearing the model wipes pending secrets`() = runTest(dispatcher) {
        val io = FakeIo().apply { content = backupFile(entry("AA:BB:CC:DD:EE:02")) }
        val vm = viewModel(MemoryStore(), io)
        vm.onImportDocumentPicked("content://x/in")
        vm.submitImportPassphrase(chars())
        assertNotNull(vm.state.value.dialog as? BondDialog.ImportPreview)
        vm.dismissDialog()
        assertEquals(BondDialog.None, vm.state.value.dialog)
        vm.confirmImport(setOf(0), emptySet())

        val store = MemoryStore().apply { put(entry("AA:BB:CC:DD:EE:01")) }
        val other = viewModel(store)
        other.onReauthResult(true, 0)
        other.submitExportPassphrase(chars(), chars(), 1)
        val method = BondBackupViewModel::class.java.getDeclaredMethod("onCleared")
        method.isAccessible = true
        method.invoke(other)
        other.onExportDocumentCreated("content://x/late")
    }

    @Test fun `selective export exports only chosen entries`() = runTest(dispatcher) {
        val store = MemoryStore().apply {
            put(entry("AA:BB:CC:DD:EE:01"))
            put(entry("AA:BB:CC:DD:EE:02"))
            put(entry("AA:BB:CC:DD:EE:03"))
        }
        val io = FakeIo()
        val vm = viewModel(store, io)
        val events = collectEvents(vm, backgroundScope)

        assertEquals(3, vm.state.value.selectedExportMacs.size)
        vm.toggleExportSelection("AA:BB:CC:DD:EE:02")
        assertEquals(setOf("AA:BB:CC:DD:EE:01", "AA:BB:CC:DD:EE:03"), vm.state.value.selectedExportMacs)
        vm.toggleExportSelection("AA:BB:CC:DD:EE:02")
        assertEquals(setOf("AA:BB:CC:DD:EE:01", "AA:BB:CC:DD:EE:03", "AA:BB:CC:DD:EE:02"), vm.state.value.selectedExportMacs)
        vm.toggleExportSelection("AA:BB:CC:DD:EE:02")
        assertEquals(setOf("AA:BB:CC:DD:EE:01", "AA:BB:CC:DD:EE:03"), vm.state.value.selectedExportMacs)

        vm.onReauthResult(true, 0)
        vm.submitExportPassphrase(chars(), chars(), 10)
        vm.onExportDocumentCreated("content://x/selective")

        assertEquals(listOf(BondNotice.ExportDone(2)), notices(events))
        val opened = BondEnvelope.open(io.written!!, chars()) as BondEnvelope.OpenResult.Opened
        assertEquals(listOf("AA:BB:CC:DD:EE:01", "AA:BB:CC:DD:EE:03"), opened.payload.entries.map { it.mac })
    }

    @Test fun `empty export selection prevents export`() = runTest(dispatcher) {
        val store = MemoryStore().apply { put(entry("AA:BB:CC:DD:EE:01")) }
        val vm = viewModel(store)
        val events = collectEvents(vm, backgroundScope)

        vm.selectAllExport(false)
        assertTrue(vm.state.value.selectedExportMacs.isEmpty())
        vm.requestExport()
        assertTrue(events.isEmpty())

        vm.selectAllExport(true)
        assertEquals(setOf("AA:BB:CC:DD:EE:01"), vm.state.value.selectedExportMacs)
        vm.requestExport()
        assertEquals(listOf<BondEvent>(BondEvent.NeedReauth), events)
    }

    @Test fun `manual key entry adds key with various mac formats and clears secret`() = runTest(dispatcher) {
        val store = MemoryStore()
        val vm = viewModel(store)
        val events = collectEvents(vm, backgroundScope)

        vm.openManualEntry()
        assertEquals(BondDialog.ManualEntry, vm.state.value.dialog)

        // Lowercase MAC with colons and token with spaces
        val token1 = testHexToken(12, separator = " ")
        vm.submitManualKey("aa:bb:cc:dd:ee:01", token1, "scooter 1")
        assertTrue(token1.all { it == '0' })
        assertEquals(BondDialog.None, vm.state.value.dialog)
        assertEquals(BondNotice.ManualKeyAdded("••:••:••:••:EE:01"), notices(events).last())
        assertEquals(1, store.items.size)
        assertEquals("AA:BB:CC:DD:EE:01", store.items.keys.single())
        assertArrayEquals(ByteArray(12) { it.toByte() }, store.items.getValue("AA:BB:CC:DD:EE:01").credential())
        assertEquals("scooter 1", store.items.getValue("AA:BB:CC:DD:EE:01").label)

        // 12-char hex MAC without colons and token with colons and 0x prefix
        vm.openManualEntry()
        val token2 = testHexToken(12, prefix = "0x", separator = ":")
        vm.submitManualKey("aabbccddee02", token2, "scooter 2")
        assertTrue(token2.all { it == '0' })
        assertEquals(2, store.items.size)
        assertTrue("AA:BB:CC:DD:EE:02" in store.items)

        // MAC with dashes and 0X prefix
        vm.openManualEntry()
        val token3 = testHexToken(12, prefix = "0X")
        vm.submitManualKey("AA-BB-CC-DD-EE-03", token3, "scooter 3")
        assertTrue(token3.all { it == '0' })
        assertEquals(3, store.items.size)
        assertTrue("AA:BB:CC:DD:EE:03" in store.items)

        // Ninebot 16-byte key with 0x prefix
        vm.openManualEntry()
        val token4 = testHexToken(16, prefix = "0x")
        vm.submitManualKey("AA:BB:CC:DD:EE:04", token4, "ninebot 1", BondFamily.NINEBOT_CRYPTO)
        assertTrue(token4.all { it == '0' })
        assertEquals(4, store.items.size)
        assertEquals(16, store.items.getValue("AA:BB:CC:DD:EE:04").credential().size)
    }

    @Test fun `manual key entry rejects invalid mac token or label`() = runTest(dispatcher) {
        val store = MemoryStore()
        val vm = viewModel(store)

        // Invalid MAC
        vm.openManualEntry()
        vm.submitManualKey("not-a-mac", testHexToken(12))
        assertEquals(BondManualEntryError.INVALID, vm.state.value.manualEntryError)
        assertEquals(BondDialog.ManualEntry, vm.state.value.dialog)

        // Invalid token length (too short)
        vm.submitManualKey("AA:BB:CC:DD:EE:01", testHexToken(4))
        assertEquals(BondManualEntryError.INVALID, vm.state.value.manualEntryError)

        // Invalid token characters: high nibble and low nibble
        vm.submitManualKey("AA:BB:CC:DD:EE:01", ("z" + testHexToken(12).concatToString().drop(1)).toCharArray())
        assertEquals(BondManualEntryError.INVALID, vm.state.value.manualEntryError)

        vm.submitManualKey("AA:BB:CC:DD:EE:01", ("0z" + testHexToken(12).concatToString().drop(2)).toCharArray())
        assertEquals(BondManualEntryError.INVALID, vm.state.value.manualEntryError)

        // Invalid label (control character)
        vm.submitManualKey("AA:BB:CC:DD:EE:01", testHexToken(12), "label\nwith\nnewline")
        assertEquals(BondManualEntryError.INVALID, vm.state.value.manualEntryError)

        // Invalid label (too long)
        vm.submitManualKey("AA:BB:CC:DD:EE:01", testHexToken(12), "x".repeat(65))
        assertEquals(BondManualEntryError.INVALID, vm.state.value.manualEntryError)

        assertTrue(store.items.isEmpty())
        vm.dismissDialog()
        assertEquals(BondDialog.None, vm.state.value.dialog)
        assertNull(vm.state.value.manualEntryError)
    }

    @Test fun `manual key entry reports store failure and wipes entry`() = runTest(dispatcher) {
        val store = MemoryStore(failPut = true)
        val vm = viewModel(store)
        val events = collectEvents(vm, backgroundScope)

        vm.openManualEntry()
        vm.submitManualKey("AA:BB:CC:DD:EE:01", testHexToken(12))
        assertEquals(BondDialog.None, vm.state.value.dialog)
        assertEquals(BondNotice.IoFailed, notices(events).single())
        assertTrue(store.items.isEmpty())
    }

    @Test fun `manual key entry prompts for overwrite when mac already exists`() = runTest(dispatcher) {
        val store = MemoryStore().apply { put(entry("AA:BB:CC:DD:EE:01", label = "original")) }
        val vm = viewModel(store)
        val events = collectEvents(vm, backgroundScope)

        // Submitting key for existing MAC prompts confirmation instead of putting directly
        vm.openManualEntry()
        val token = testHexToken(12)
        vm.submitManualKey("AA:BB:CC:DD:EE:01", token, "new label")
        val confirmDialog = vm.state.value.dialog as? BondDialog.ConfirmOverwriteManual
        assertNotNull(confirmDialog)
        assertEquals("••:••:••:••:EE:01", confirmDialog!!.entry.maskedMac())
        assertEquals("original", store.items.getValue("AA:BB:CC:DD:EE:01").label)

        // User cancels overwrite; subsequent confirm does nothing
        vm.cancelOverwriteManual()
        assertEquals(BondDialog.None, vm.state.value.dialog)
        assertFalse(vm.state.value.busy)
        assertEquals("original", store.items.getValue("AA:BB:CC:DD:EE:01").label)
        vm.confirmOverwriteManual()
        assertEquals(BondDialog.None, vm.state.value.dialog)
        assertEquals("original", store.items.getValue("AA:BB:CC:DD:EE:01").label)

        // Submit again and confirm overwrite; subsequent cancel does not wipe the stored entry
        vm.openManualEntry()
        val token2 = testHexToken(12)
        vm.submitManualKey("AA:BB:CC:DD:EE:01", token2, "new label")
        assertNotNull(vm.state.value.dialog as? BondDialog.ConfirmOverwriteManual)
        vm.confirmOverwriteManual()
        assertEquals(BondDialog.None, vm.state.value.dialog)
        vm.cancelOverwriteManual()
        assertEquals(BondDialog.None, vm.state.value.dialog)
        assertEquals("new label", store.items.getValue("AA:BB:CC:DD:EE:01").label)
        assertArrayEquals(ByteArray(12) { it.toByte() }, store.items.getValue("AA:BB:CC:DD:EE:01").credential())
        assertEquals(BondNotice.ManualKeyAdded("••:••:••:••:EE:01"), notices(events).last())
    }

    @Test fun `refresh and import preview preserve vehicle model in rows`() = runTest(dispatcher) {
        val entryWithModel = entry("AA:BB:CC:DD:EE:01", label = "Pro", model = "Mi Scooter Pro 2")
        val store = MemoryStore().apply { put(entryWithModel) }
        val io = FakeIo()
        val vm = viewModel(store, io)

        assertEquals(1, vm.state.value.rows.size)
        val row = vm.state.value.rows.single()
        assertEquals("AA:BB:CC:DD:EE:01", row.mac)
        assertEquals("Pro", row.label)
        assertEquals("Mi Scooter Pro 2", row.model)

        io.content = backupFile(entry("AA:BB:CC:DD:EE:02", label = "Max", model = "Ninebot Max"))
        vm.onImportDocumentPicked("content://media/backup.rfbond")
        vm.submitImportPassphrase(chars())
        val previewDialog = vm.state.value.dialog as BondDialog.ImportPreview
        assertEquals(1, previewDialog.rows.size)
        val importRow = previewDialog.rows.single()
        assertEquals("Max", importRow.label)
        assertEquals("Ninebot Max", importRow.model)
    }

    @Test fun `import document pre-checks for rfbond magic and ignores non-content uris`() = runTest(dispatcher) {
        val io = FakeIo()
        val vm = viewModel(MemoryStore(), io)
        val events = collectEvents(vm, backgroundScope)

        // Non-content URI is ignored
        vm.onImportDocumentPicked("file:///storage/backup.rfbond")
        assertEquals(BondDialog.None, vm.state.value.dialog)
        assertTrue(events.isEmpty())

        // Invalid / non-RFBOND file reports InvalidFile without asking for passphrase
        io.content = "NOT_A_BOND_FILE_HEADER_PADDING_PADDING".toByteArray()
        vm.onImportDocumentPicked("content://media/notbond.rfbond")
        assertEquals(BondDialog.None, vm.state.value.dialog)
        assertEquals(listOf(BondNotice.InvalidFile), notices(events))

        // Valid RFBOND file opens passphrase prompt
        io.content = backupFile(entry("AA:BB:CC:DD:EE:01"))
        vm.onImportDocumentPicked("content://media/valid.rfbond")
        assertEquals(BondDialog.ImportPassphrase, vm.state.value.dialog)
    }
}
