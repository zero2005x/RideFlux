package com.rideflux.domain.transport

import kotlinx.coroutines.flow.Flow

enum class MiAuthChar { UPNP, AVDTP }

/**
 * A single-consumer owned notification. Construction copies the platform buffer;
 * the auth consumer must [wipe] this object after copying or consuming it.
 * Implementations must not replay or drop notifications during authentication.
 */
class MiAuthNotification(val characteristic: MiAuthChar, bytes: ByteArray) {
    private val payload = bytes.copyOf()
    fun copyBytes(): ByteArray = payload.copyOf()
    fun wipe() { payload.fill(0) }
    override fun toString() = "MiAuthNotification($characteristic, <redacted>)"
}

/** Separate capability: existing [BleTransport] implementations remain source compatible. */
interface MiAuthTransport : BleTransport {
    val authNotifications: Flow<MiAuthNotification>

    /** Same bounded-write and borrowed-buffer ownership contract as [BleTransport.write]. */
    suspend fun writeAuth(characteristic: MiAuthChar, bytes: ByteArray)
}
