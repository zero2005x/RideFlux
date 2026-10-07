/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.ui.bond

import com.rideflux.domain.bond.BondEntry
import com.rideflux.domain.bond.BondFamily
import kotlinx.coroutines.flow.MutableStateFlow

/** Reads and writes backup files the user picked. Locations are opaque URI strings. */
interface BondFileIo {
    /** The file's bytes, or null when it cannot be read or exceeds [maxBytes]. */
    suspend fun read(location: String, maxBytes: Int): ByteArray?
    suspend fun write(location: String, bytes: ByteArray): Boolean
}

data class BondRow(
    val mac: String,
    val maskedMac: String,
    val label: String,
    val family: BondFamily,
    val model: String? = null,
)

data class BondImportRow(
    val index: Int,
    val maskedMac: String,
    val label: String,
    val family: BondFamily,
    val conflict: Boolean,
    val model: String? = null,
)

sealed interface BondDialog {
    data object None : BondDialog
    data object ExportPassphrase : BondDialog
    data object ImportPassphrase : BondDialog
    data class ImportPreview(val rows: List<BondImportRow>, val skippedUnsupported: Int) : BondDialog
    data class ConfirmDelete(val row: BondRow) : BondDialog
    data object ManualEntry : BondDialog
    data class ConfirmOverwriteManual(val entry: BondEntry) : BondDialog
}

enum class BondPassphraseError { WEAK, MISMATCH, WRONG_OR_CORRUPT }

enum class BondManualEntryError { INVALID }

sealed interface BondNotice {
    data class ExportDone(val count: Int) : BondNotice
    data class ImportDone(val imported: Int, val overwritten: Int, val kept: Int) : BondNotice
    data object InvalidFile : BondNotice
    data object IoFailed : BondNotice
    data object ReauthUnavailable : BondNotice
    data object ReauthExpired : BondNotice
    data class ManualKeyAdded(val maskedMac: String) : BondNotice
}

data class BondUiState(
    val rows: List<BondRow> = emptyList(),
    val selectedExportMacs: Set<String> = emptySet(),
    val loadFailed: Boolean = false,
    val busy: Boolean = false,
    val dialog: BondDialog = BondDialog.None,
    val passphraseError: BondPassphraseError? = null,
    val deleteFailed: Boolean = false,
    val manualEntryError: BondManualEntryError? = null,
)

object PendingBondImport {
    val pendingUri = MutableStateFlow<String?>(null)
}

sealed interface BondEvent {
    /** Ask the platform to confirm the user before the passphrase prompt is shown. */
    data object NeedReauth : BondEvent
    data class CreateDocument(val fileName: String) : BondEvent
    data class ShowNotice(val notice: BondNotice) : BondEvent
}
