package com.rideflux.data.preferences

import com.rideflux.domain.bond.BondEntry
import com.rideflux.domain.bond.BondPayloadCodec
import com.rideflux.domain.bond.BondStore
import com.rideflux.domain.bond.BondStoreUnreadableException
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Encrypts and decrypts the stored blob. The production key lives in the Android Keystore. */
interface BondCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(blob: ByteArray): ByteArray
}

/**
 * Pairing keys kept in one app-private file, encrypted with a [BondCipher].
 *
 * The file is written to a temporary sibling and renamed, so a crash never leaves a half-written
 * store. Android backup is off for the app (`allowBackup="false"`), and the Keystore key could not
 * be restored on another phone anyway; moving keys is what the `.rfbond` backup is for.
 */
class EncryptedFileBondStore(
    private val file: File,
    private val cipher: BondCipher,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : BondStore {
    private val mutex = Mutex()

    override suspend fun list(): List<BondEntry> = mutex.withLock { withContext(dispatcher) { load() } }

    override suspend fun put(entry: BondEntry) = mutex.withLock {
        withContext(dispatcher) {
            val loaded = load()
            val replacement = copyOf(entry)
            try {
                save(loaded.filterNot { it.mac == entry.mac } + replacement)
            } finally {
                loaded.forEach(BondEntry::wipe)
                replacement.wipe()
            }
        }
    }

    override suspend fun remove(mac: String): Boolean = mutex.withLock {
        withContext(dispatcher) {
            val normalized = BondEntry.normalizeMac(mac)
            val entries = load()
            try {
                val kept = entries.filterNot { it.mac == normalized }
                (kept.size != entries.size).also { if (it) save(kept) }
            } finally {
                entries.forEach(BondEntry::wipe)
            }
        }
    }

    private fun load(): List<BondEntry> {
        if (!file.exists()) return emptyList()
        try {
            val plain = cipher.decrypt(file.readBytes())
            try {
                return BondPayloadCodec.read(plain).entries
            } finally {
                plain.fill(0)
            }
        } catch (e: Exception) {
            throw BondStoreUnreadableException(e)
        }
    }

    private fun save(entries: List<BondEntry>) {
        val plain = BondPayloadCodec.write(entries, STORED_MARKER)
        try {
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeBytes(cipher.encrypt(plain))
            try {
                Files.move(temp.toPath(), file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            plain.fill(0)
        }
    }

    private fun copyOf(e: BondEntry): BondEntry {
        val secret = e.credential()
        try {
            return BondEntry(e.mac, e.family, secret, e.label, e.model)
        } finally {
            secret.fill(0)
        }
    }

    private companion object {
        /** `createdAt` carries no meaning for the live store; a marker keeps the schema intact. */
        const val STORED_MARKER = "store"
    }
}
