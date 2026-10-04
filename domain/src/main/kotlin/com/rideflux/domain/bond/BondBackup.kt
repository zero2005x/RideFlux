package com.rideflux.domain.bond

import java.time.Instant

/** Where bond entries live between sessions. Implementations must keep them encrypted at rest. */
interface BondStore {
    /** All entries. Each returned entry owns its secret; the caller wipes it when done. */
    suspend fun list(): List<BondEntry>

    /** Insert or replace the entry for [BondEntry.mac]. The store keeps its own copy. */
    suspend fun put(entry: BondEntry)

    suspend fun remove(mac: String): Boolean
}

/** The stored bonds cannot be read, for example because the Keystore key is gone. */
class BondStoreUnreadableException(cause: Throwable? = null) :
    Exception("Stored pairing keys cannot be read", cause)

/** Export and import of bond backups on top of a [BondStore]. */
class BondBackup(
    private val store: BondStore,
    private val clock: () -> Instant = Instant::now,
    private val iterations: Int = BondEnvelope.DEFAULT_ITERATIONS,
) {
    sealed interface ExportResult {
        class Exported(val file: ByteArray, val count: Int) : ExportResult
        data object NothingToExport : ExportResult
        data class WeakPassphrase(val problem: BondEnvelope.PassphraseProblem) : ExportResult
    }

    /** One entry found in a backup file and whether it would replace an existing one. */
    class PreviewItem(val entry: BondEntry, val conflict: Boolean)

    sealed interface Preview {
        class Ready(val items: List<PreviewItem>, val skippedUnsupported: Int) : Preview {
            fun wipe() = items.forEach { it.entry.wipe() }
        }
        class Failed(val reason: BondEnvelope.OpenResult) : Preview
    }

    data class ImportSummary(val imported: Int, val overwritten: Int, val keptExisting: Int)

    /** Encrypt the chosen entries (all when [macs] is null). Secrets are wiped before returning. */
    suspend fun export(passphrase: CharArray, macs: Set<String>? = null): ExportResult {
        BondEnvelope.passphraseProblem(passphrase)?.let { return ExportResult.WeakPassphrase(it) }
        val all = store.list()
        val chosen = all.filter { macs == null || it.mac in macs }
        try {
            if (chosen.isEmpty()) return ExportResult.NothingToExport
            val file = BondEnvelope.seal(chosen, passphrase, clock(), iterations)
            return ExportResult.Exported(file, chosen.size)
        } finally {
            all.forEach(BondEntry::wipe)
        }
    }

    /** Decrypt [file] without touching the store. Call [Preview.Ready.wipe] when finished. */
    suspend fun preview(file: ByteArray, passphrase: CharArray): Preview =
        when (val opened = BondEnvelope.open(file, passphrase)) {
            is BondEnvelope.OpenResult.Opened -> {
                val existing = try {
                    store.list()
                } catch (e: Exception) {
                    opened.payload.wipe()
                    throw e
                }
                try {
                    val known = existing.associateBy(BondEntry::mac)
                    Preview.Ready(
                        opened.payload.entries.map { PreviewItem(it, it.mac in known) },
                        opened.payload.skippedUnsupported,
                    )
                } finally {
                    existing.forEach(BondEntry::wipe)
                }
            }
            else -> Preview.Failed(opened)
        }

    /**
     * Store [selected] items. A conflicting item replaces the stored one only when its address is
     * in [overwrite]; otherwise the stored one is kept. All secrets in [selected] are wiped.
     */
    suspend fun import(selected: List<PreviewItem>, overwrite: Set<String>): ImportSummary {
        var imported = 0
        var overwritten = 0
        var kept = 0
        try {
            for (item in selected) {
                when {
                    !item.conflict -> { store.put(item.entry); imported++ }
                    item.entry.mac in overwrite -> { store.put(item.entry); overwritten++ }
                    else -> kept++
                }
            }
        } finally {
            selected.forEach { it.entry.wipe() }
        }
        return ImportSummary(imported, overwritten, kept)
    }
}
