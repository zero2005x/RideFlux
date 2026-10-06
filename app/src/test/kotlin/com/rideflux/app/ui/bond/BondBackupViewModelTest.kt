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

    private fun entry(mac: String, seed: Int = 1, label: String = "scooter") =
        BondEntry(mac, BondFamily.XIAOMI_MI, ByteArray(12) { (it + seed).toByte() }, label)

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
}
