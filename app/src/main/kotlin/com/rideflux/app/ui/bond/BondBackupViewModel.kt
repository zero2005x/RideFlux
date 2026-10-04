/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.ui.bond

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rideflux.domain.bond.BondBackup
import com.rideflux.domain.bond.BondEnvelope
import com.rideflux.domain.bond.BondFamily
import com.rideflux.domain.bond.BondStore
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Reads and writes backup files the user picked. Locations are opaque URI strings. */
interface BondFileIo {
    /** The file's bytes, or null when it cannot be read or exceeds [maxBytes]. */
    suspend fun read(location: String, maxBytes: Int): ByteArray?
    suspend fun write(location: String, bytes: ByteArray): Boolean
}

data class BondRow(val maskedMac: String, val label: String, val family: BondFamily)

data class BondImportRow(
    val index: Int,
    val maskedMac: String,
    val label: String,
    val family: BondFamily,
    val conflict: Boolean,
)

sealed interface BondDialog {
    data object None : BondDialog
    data object ExportPassphrase : BondDialog
    data object ImportPassphrase : BondDialog
    data class ImportPreview(val rows: List<BondImportRow>, val skippedUnsupported: Int) : BondDialog
}

enum class BondPassphraseError { WEAK, MISMATCH, WRONG_OR_CORRUPT }

sealed interface BondNotice {
    data class ExportDone(val count: Int) : BondNotice
    data class ImportDone(val imported: Int, val overwritten: Int, val kept: Int) : BondNotice
    data object InvalidFile : BondNotice
    data object IoFailed : BondNotice
    data object ReauthUnavailable : BondNotice
    data object ReauthExpired : BondNotice
}

data class BondUiState(
    val rows: List<BondRow> = emptyList(),
    val loadFailed: Boolean = false,
    val busy: Boolean = false,
    val dialog: BondDialog = BondDialog.None,
    val passphraseError: BondPassphraseError? = null,
)

sealed interface BondEvent {
    /** Ask the platform to confirm the user before the passphrase prompt is shown. */
    data object NeedReauth : BondEvent
    data class CreateDocument(val fileName: String) : BondEvent
    data class ShowNotice(val notice: BondNotice) : BondEvent
}

/**
 * Drives the pairing-key backup screen.
 *
 * Exporting needs a fresh re-authentication: [onReauthResult] opens a [REAUTH_WINDOW_MILLIS]
 * window and [submitExportPassphrase] refuses to run outside it, so a screen that skips the
 * prompt still cannot produce a file. Passphrases arrive as `CharArray` and are zeroed here;
 * decrypted entries and the sealed file are zeroed when a flow ends or the model is cleared.
 */
@HiltViewModel
class BondBackupViewModel internal constructor(
    private val store: BondStore,
    private val backup: BondBackup,
    private val io: BondFileIo,
    private val work: CoroutineDispatcher,
) : ViewModel() {
    @Inject constructor(store: BondStore, backup: BondBackup, io: BondFileIo) :
        this(store, backup, io, Dispatchers.Default)

    private val _state = MutableStateFlow(BondUiState())
    val state: StateFlow<BondUiState> = _state.asStateFlow()
    private val _events = Channel<BondEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var reauthAtMillis: Long? = null
    private var pendingExport: BondBackup.ExportResult.Exported? = null
    private var pendingFile: ByteArray? = null
    private var pendingPreview: BondBackup.Preview.Ready? = null

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            val entries = try { store.list() } catch (_: Exception) {
                _state.update { it.copy(rows = emptyList(), loadFailed = true) }
                return@launch
            }
            val rows = entries.map { BondRow(it.maskedMac(), it.label, it.family) }
            entries.forEach { it.wipe() }
            _state.update { it.copy(rows = rows, loadFailed = false) }
        }
    }

    fun requestExport() {
        if (_state.value.rows.isEmpty()) return
        send(BondEvent.NeedReauth)
    }

    fun reauthUnavailable() = send(BondEvent.ShowNotice(BondNotice.ReauthUnavailable))

    fun onReauthResult(success: Boolean, nowMillis: Long) {
        if (!success) return
        reauthAtMillis = nowMillis
        _state.update { it.copy(dialog = BondDialog.ExportPassphrase, passphraseError = null) }
    }

    fun submitExportPassphrase(passphrase: CharArray, confirm: CharArray, nowMillis: Long) {
        val reauthAt = reauthAtMillis
        if (reauthAt == null || nowMillis - reauthAt !in 0..REAUTH_WINDOW_MILLIS) {
            wipe(passphrase, confirm)
            reauthAtMillis = null
            _state.update { it.copy(dialog = BondDialog.None) }
            send(BondEvent.ShowNotice(BondNotice.ReauthExpired))
            return
        }
        if (!passphrase.contentEquals(confirm)) {
            wipe(passphrase, confirm)
            _state.update { it.copy(passphraseError = BondPassphraseError.MISMATCH) }
            return
        }
        wipe(confirm)
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val result = try {
                withContext(work) { backup.export(passphrase) }
            } catch (_: Exception) {
                null
            } finally {
                wipe(passphrase)
            }
            _state.update { it.copy(busy = false) }
            when (result) {
                null -> {
                    _state.update { it.copy(dialog = BondDialog.None, passphraseError = null) }
                    send(BondEvent.ShowNotice(BondNotice.IoFailed))
                }
                is BondBackup.ExportResult.Exported -> {
                    reauthAtMillis = null // one confirmation covers one export
                    pendingExport = result
                    _state.update { it.copy(dialog = BondDialog.None, passphraseError = null) }
                    send(BondEvent.CreateDocument(BondEnvelope.suggestedFileName(Instant.now())))
                }
                is BondBackup.ExportResult.WeakPassphrase ->
                    _state.update { it.copy(passphraseError = BondPassphraseError.WEAK) }
                BondBackup.ExportResult.NothingToExport -> {
                    _state.update { it.copy(dialog = BondDialog.None) }
                    refresh()
                }
            }
        }
    }

    fun onExportDocumentCreated(location: String?) {
        val exported = pendingExport ?: return
        pendingExport = null
        if (location == null) { exported.file.fill(0); return }
        viewModelScope.launch {
            val ok = try { withContext(work) { io.write(location, exported.file) } } finally { exported.file.fill(0) }
            send(BondEvent.ShowNotice(if (ok) BondNotice.ExportDone(exported.count) else BondNotice.IoFailed))
        }
    }

    fun onImportDocumentPicked(location: String?) {
        if (location == null) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val bytes = withContext(work) { io.read(location, BondEnvelope.MAX_FILE_BYTES) }
            _state.update { it.copy(busy = false) }
            if (bytes == null) {
                send(BondEvent.ShowNotice(BondNotice.IoFailed))
                return@launch
            }
            pendingFile = bytes
            _state.update { it.copy(dialog = BondDialog.ImportPassphrase, passphraseError = null) }
        }
    }

    fun submitImportPassphrase(passphrase: CharArray) {
        val file = pendingFile ?: run { wipe(passphrase); return }
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val preview = try {
                withContext(work) { backup.preview(file, passphrase) }
            } catch (_: Exception) {
                null
            } finally {
                wipe(passphrase)
            }
            _state.update { it.copy(busy = false) }
            when (preview) {
                null -> {
                    clearPending()
                    _state.update { it.copy(dialog = BondDialog.None, passphraseError = null) }
                    send(BondEvent.ShowNotice(BondNotice.IoFailed))
                }
                is BondBackup.Preview.Ready -> showPreview(preview)
                is BondBackup.Preview.Failed -> onPreviewFailed(preview.reason)
            }
        }
    }

    private fun showPreview(preview: BondBackup.Preview.Ready) {
        pendingFile?.fill(0)
        pendingFile = null
        pendingPreview = preview
        val rows = preview.items.mapIndexed { index, item ->
            BondImportRow(index, item.entry.maskedMac(), item.entry.label, item.entry.family, item.conflict)
        }
        _state.update { it.copy(dialog = BondDialog.ImportPreview(rows, preview.skippedUnsupported)) }
    }

    private fun onPreviewFailed(reason: BondEnvelope.OpenResult) {
        if (reason == BondEnvelope.OpenResult.WrongPassphraseOrCorrupt) {
            _state.update { it.copy(passphraseError = BondPassphraseError.WRONG_OR_CORRUPT) }
            return
        }
        clearPending()
        _state.update { it.copy(dialog = BondDialog.None, passphraseError = null) }
        send(BondEvent.ShowNotice(BondNotice.InvalidFile))
    }

    /** Import the rows at [selected]; rows in [replace] overwrite an existing key. */
    fun confirmImport(selected: Set<Int>, replace: Set<Int>) {
        val preview = pendingPreview ?: return
        pendingPreview = null
        val chosen = preview.items.filterIndexed { index, _ -> index in selected }
        preview.items.filterIndexed { index, _ -> index !in selected }.forEach { it.entry.wipe() }
        val overwrite = preview.items.filterIndexed { index, _ -> index in replace }.map { it.entry.mac }.toSet()
        viewModelScope.launch {
            val summary = try { backup.import(chosen, overwrite) } catch (_: Exception) {
                send(BondEvent.ShowNotice(BondNotice.IoFailed))
                return@launch
            } finally {
                _state.update { it.copy(dialog = BondDialog.None) }
            }
            send(BondEvent.ShowNotice(BondNotice.ImportDone(summary.imported, summary.overwritten, summary.keptExisting)))
            refresh()
        }
    }

    fun dismissDialog() {
        clearPending()
        _state.update { it.copy(dialog = BondDialog.None, passphraseError = null) }
    }

    override fun onCleared() {
        clearPending()
        pendingExport?.file?.fill(0)
        pendingExport = null
        reauthAtMillis = null
    }

    private fun clearPending() {
        pendingFile?.fill(0)
        pendingFile = null
        pendingPreview?.wipe()
        pendingPreview = null
    }

    private fun send(event: BondEvent) { _events.trySend(event) }

    private fun wipe(vararg arrays: CharArray) = arrays.forEach { it.fill('\u0000') }

    companion object {
        const val REAUTH_WINDOW_MILLIS = 60_000L
    }
}
