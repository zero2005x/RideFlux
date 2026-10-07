/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.ui.bond

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rideflux.domain.bond.BondBackup
import com.rideflux.domain.bond.BondEntry
import com.rideflux.domain.bond.BondEnvelope
import com.rideflux.domain.bond.BondFamily
import com.rideflux.domain.repository.ScooterRepository
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
    private val removeKey: suspend (String) -> Unit = { store.remove(it); Unit },
) : ViewModel() {
    @Inject constructor(store: BondStore, backup: BondBackup, io: BondFileIo, scooters: ScooterRepository) :
        this(store, backup, io, Dispatchers.Default, scooters::removePairingKey)

    private val _state = MutableStateFlow(BondUiState())
    val state: StateFlow<BondUiState> = _state.asStateFlow()
    private val _events = Channel<BondEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var reauthAtMillis: Long? = null
    private var pendingExport: BondBackup.ExportResult.Exported? = null
    private var pendingFile: ByteArray? = null
    private var pendingPreview: BondBackup.Preview.Ready? = null

    private var loadedOnce = false
    private var refreshGeneration = 0L

    init { refresh() }

    fun refresh() {
        val generation = ++refreshGeneration
        viewModelScope.launch {
            val entries = try { store.list() } catch (_: Exception) {
                if (generation != refreshGeneration) return@launch
                _state.update { it.copy(rows = emptyList(), selectedExportMacs = emptySet(), loadFailed = true) }
                return@launch
            }
            if (generation != refreshGeneration) { entries.forEach(BondEntry::wipe); return@launch }
            val rows = entries.map { BondRow(it.mac, it.maskedMac(), it.label, it.family, it.model) }
            entries.forEach { it.wipe() }
            _state.update {
                it.copy(
                    rows = rows,
                    selectedExportMacs = rows.map { r -> r.mac }.toSet().let { known ->
                        if (loadedOnce) it.selectedExportMacs.intersect(known) + (known - it.rows.map { row -> row.mac }.toSet()) else known
                    },
                    loadFailed = false,
                )
            }
            loadedOnce = true
        }
    }

    fun toggleExportSelection(mac: String) {
        if (_state.value.busy || _state.value.rows.none { it.mac == mac }) return
        _state.update { current ->
            val set = current.selectedExportMacs
            val updated = if (mac in set) set - mac else set + mac
            current.copy(selectedExportMacs = updated)
        }
    }

    fun selectAllExport(select: Boolean) {
        if (_state.value.busy) return
        _state.update { current ->
            current.copy(selectedExportMacs = if (select) current.rows.map { it.mac }.toSet() else emptySet())
        }
    }

    fun requestExport() {
        if (_state.value.busy || _state.value.dialog != BondDialog.None) return
        if (_state.value.selectedExportMacs.isEmpty()) return
        send(BondEvent.NeedReauth)
    }

    fun reauthUnavailable() = send(BondEvent.ShowNotice(BondNotice.ReauthUnavailable))

    fun onReauthResult(success: Boolean, nowMillis: Long) {
        if (!success || _state.value.busy || _state.value.dialog is BondDialog.ConfirmDelete) return
        reauthAtMillis = nowMillis
        _state.update { it.copy(dialog = BondDialog.ExportPassphrase, passphraseError = null) }
    }

    fun submitExportPassphrase(passphrase: CharArray, confirm: CharArray, nowMillis: Long) {
        if (_state.value.busy) { wipe(passphrase, confirm); return }
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
        val macs = _state.value.selectedExportMacs
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val result = try {
                withContext(work) { backup.export(passphrase, macs) }
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
        if (location == null || !location.startsWith("content://") || _state.value.busy ||
            _state.value.dialog != BondDialog.None) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val bytes = withContext(work) { io.read(location, BondEnvelope.MAX_FILE_BYTES) }
            _state.update { it.copy(busy = false) }
            if (bytes == null) {
                send(BondEvent.ShowNotice(BondNotice.IoFailed))
                return@launch
            }
            if (!BondEnvelope.isBondFile(bytes)) {
                bytes.fill(0)
                send(BondEvent.ShowNotice(BondNotice.InvalidFile))
                return@launch
            }
            pendingFile = bytes
            _state.update { it.copy(dialog = BondDialog.ImportPassphrase, passphraseError = null) }
        }
    }

    fun submitImportPassphrase(passphrase: CharArray) {
        if (_state.value.busy) { wipe(passphrase); return }
        val file = pendingFile ?: run { wipe(passphrase); return }
        _state.update { it.copy(busy = true) }
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
            BondImportRow(
                index = index,
                maskedMac = item.entry.maskedMac(),
                label = item.entry.label,
                family = item.entry.family,
                conflict = item.conflict,
                model = item.entry.model,
            )
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
        if (_state.value.busy) return
        val preview = pendingPreview ?: return
        pendingPreview = null
        _state.update { it.copy(busy = true) }
        val chosen = preview.items.filterIndexed { index, _ -> index in selected }
        preview.items.filterIndexed { index, _ -> index !in selected }.forEach { it.entry.wipe() }
        val overwrite = preview.items.filterIndexed { index, _ -> index in replace }.map { it.entry.mac }.toSet()
        viewModelScope.launch {
            val summary = try { backup.import(chosen, overwrite) } catch (_: Exception) {
                send(BondEvent.ShowNotice(BondNotice.IoFailed))
                return@launch
            } finally {
                _state.update { it.copy(dialog = BondDialog.None, busy = false) }
            }
            send(BondEvent.ShowNotice(BondNotice.ImportDone(summary.imported, summary.overwritten, summary.keptExisting)))
            refresh()
        }
    }

    fun openManualEntry() {
        if (_state.value.busy || _state.value.dialog != BondDialog.None) return
        _state.update { it.copy(dialog = BondDialog.ManualEntry, manualEntryError = null) }
    }

    fun submitManualKey(
        rawMac: String,
        tokenHex: CharArray,
        label: String = "",
        family: BondFamily = BondFamily.XIAOMI_MI,
    ) {
        try {
            if (_state.value.busy) return
            val trimmedMac = rawMac.trim()
            val normalizedMac = try {
                val formatted = when {
                    trimmedMac.matches(Regex("^[0-9a-fA-F]{12}$")) -> trimmedMac.chunked(2).joinToString(":")
                    trimmedMac.contains('-') -> trimmedMac.replace('-', ':')
                    else -> trimmedMac
                }
                BondEntry.normalizeMac(formatted)
            } catch (_: IllegalArgumentException) {
                _state.update { it.copy(manualEntryError = BondManualEntryError.INVALID) }
                return
            }

            val secret = parseHexSecret(tokenHex, family.credentialBytes)
            if (secret == null) {
                _state.update { it.copy(manualEntryError = BondManualEntryError.INVALID) }
                return
            }

            val cleanLabel = label.trim()
            val entry = try {
                BondEntry(mac = normalizedMac, family = family, credential = secret, label = cleanLabel)
            } catch (_: IllegalArgumentException) {
                _state.update { it.copy(manualEntryError = BondManualEntryError.INVALID) }
                return
            } finally {
                secret.fill(0)
            }

            _state.update { it.copy(busy = true) }
            viewModelScope.launch {
                val existing = try {
                    withContext(work) {
                        val list = store.list()
                        try {
                            list.any { it.mac == normalizedMac }
                        } finally {
                            list.forEach(BondEntry::wipe)
                        }
                    }
                } catch (_: Exception) {
                    entry.wipe()
                    _state.update { it.copy(dialog = BondDialog.None, manualEntryError = null, busy = false) }
                    send(BondEvent.ShowNotice(BondNotice.IoFailed))
                    return@launch
                }

                if (existing) {
                    _state.update { it.copy(dialog = BondDialog.ConfirmOverwriteManual(entry), busy = false) }
                } else {
                    saveManualEntry(entry)
                }
            }
        } finally {
            tokenHex.fill('\u0000')
        }
    }

    fun confirmOverwriteManual() {
        if (_state.value.busy) return
        var confirmedEntry: BondEntry? = null
        _state.update { current ->
            val dialog = current.dialog as? BondDialog.ConfirmOverwriteManual
            if (dialog == null) {
                current
            } else {
                confirmedEntry = dialog.entry
                current.copy(dialog = BondDialog.None, busy = true)
            }
        }
        val entry = confirmedEntry ?: return
        viewModelScope.launch {
            saveManualEntry(entry)
        }
    }

    fun cancelOverwriteManual() {
        if (_state.value.busy) return
        var cancelledEntry: BondEntry? = null
        _state.update { current ->
            val dialog = current.dialog as? BondDialog.ConfirmOverwriteManual
            if (dialog == null) {
                current
            } else {
                cancelledEntry = dialog.entry
                current.copy(dialog = BondDialog.None)
            }
        }
        cancelledEntry?.wipe()
    }

    private suspend fun saveManualEntry(entry: BondEntry) {
        _state.update { it.copy(busy = true) }
        try {
            withContext(work) { store.put(entry) }
            _state.update { it.copy(dialog = BondDialog.None, manualEntryError = null, busy = false) }
            send(BondEvent.ShowNotice(BondNotice.ManualKeyAdded(entry.maskedMac())))
            refresh()
        } catch (_: Exception) {
            _state.update { it.copy(dialog = BondDialog.None, manualEntryError = null, busy = false) }
            send(BondEvent.ShowNotice(BondNotice.IoFailed))
        } finally {
            entry.wipe()
        }
    }

    private fun parseHexSecret(chars: CharArray, expectedBytes: Int): ByteArray? {
        var start = 0
        while (start < chars.size && chars[start].isWhitespace()) start++
        if (start + 1 < chars.size && chars[start] == '0' && (chars[start + 1] == 'x' || chars[start + 1] == 'X')) {
            start += 2
        }

        val secret = ByteArray(expectedBytes)
        var byteIndex = 0
        var highNibble = -1

        for (i in start until chars.size) {
            val c = chars[i]
            if (c.isWhitespace() || c == ':') continue
            val digit = Character.digit(c, 16)
            if (digit < 0) {
                secret.fill(0)
                return null
            }
            if (highNibble < 0) {
                highNibble = digit
            } else {
                if (byteIndex >= expectedBytes) {
                    secret.fill(0)
                    return null
                }
                secret[byteIndex++] = ((highNibble shl 4) or digit).toByte()
                highNibble = -1
            }
        }

        if (highNibble != -1 || byteIndex != expectedBytes) {
            secret.fill(0)
            return null
        }
        return secret
    }

    fun requestDelete(mac: String) {
        val current = _state.value
        if (current.busy || current.dialog != BondDialog.None) return
        val row = current.rows.firstOrNull { it.mac == mac } ?: return
        _state.update { it.copy(dialog = BondDialog.ConfirmDelete(row), deleteFailed = false) }
    }

    fun confirmDelete() {
        val current = _state.value
        if (current.busy) return
        val dialog = current.dialog as? BondDialog.ConfirmDelete ?: return
        // Claim the operation before launching; a second tap cannot start another removal.
        _state.update { it.copy(busy = true, deleteFailed = false) }
        ++refreshGeneration // invalidate any list read started before deletion
        viewModelScope.launch {
            try {
                removeKey(dialog.row.mac)
                _state.update {
                    it.copy(rows = it.rows.filterNot { row -> row.mac == dialog.row.mac },
                        selectedExportMacs = it.selectedExportMacs - dialog.row.mac,
                        dialog = BondDialog.None, busy = false)
                }
                refresh()
            } catch (_: Exception) {
                _state.update { it.copy(busy = false, deleteFailed = true) }
            }
        }
    }

    fun dismissDialog() {
        if (_state.value.busy) return
        val currentDialog = _state.value.dialog
        if (currentDialog is BondDialog.ConfirmOverwriteManual) {
            currentDialog.entry.wipe()
        }
        clearPending()
        _state.update { it.copy(dialog = BondDialog.None, passphraseError = null, manualEntryError = null, deleteFailed = false) }
    }

    override fun onCleared() {
        clearPending()
        pendingExport?.file?.fill(0)
        pendingExport = null
        val currentDialog = _state.value.dialog
        if (currentDialog is BondDialog.ConfirmOverwriteManual) {
            currentDialog.entry.wipe()
        }
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
