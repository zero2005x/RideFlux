/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.data.bridge

import java.security.SecureRandom
import java.util.Locale

/**
 * Mint, encode and render the phone's bridge pairing token.
 *
 * The token is the phone's stable identity on the air — see
 * [BridgeProtocol.PAIRING_TOKEN_SIZE] for why a BLE MAC address cannot
 * play that role. This object owns the three representations it needs:
 *
 *  - **raw bytes** — what goes into the advertisement and what
 *    [BridgePeerFilter.PairingToken] compares.
 *  - **hex** ([toHex] / [fromHex]) — what both sides persist in
 *    `SharedPreferences`, chosen over a raw byte blob so a preference
 *    file stays readable during a bug report.
 *  - **display code** ([displayCode] / [shortCode]) — what a human
 *    reads off the phone and matches on the glasses so they pair with
 *    their own phone rather than the nearest stranger's.
 */
object BridgePairingToken {

    /** A fresh cryptographically-random token. */
    fun generate(): ByteArray =
        ByteArray(BridgeProtocol.PAIRING_TOKEN_SIZE).also(SECURE_RANDOM::nextBytes)

    /** Lowercase hex, suitable for persisting. */
    fun toHex(token: ByteArray): String =
        token.joinToString("") { HEX_DIGITS[it.toInt() and 0xFF] }

    /**
     * Inverse of [toHex]. Returns `null` for anything that is not
     * exactly [BridgeProtocol.PAIRING_TOKEN_SIZE] bytes of valid hex, so
     * a corrupted or truncated preference degrades to "not paired"
     * instead of producing a token that can never match.
     */
    fun fromHex(hex: String?): ByteArray? {
        val trimmed = hex?.trim() ?: return null
        if (trimmed.length != BridgeProtocol.PAIRING_TOKEN_SIZE * 2) return null
        val out = ByteArray(BridgeProtocol.PAIRING_TOKEN_SIZE)
        for (i in out.indices) {
            val hi = Character.digit(trimmed[i * 2], 16)
            val lo = Character.digit(trimmed[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    /**
     * Full token as `A1B2-C3D4-E5F6-0718` — shown on the phone so the
     * rider can confirm the glasses paired with the right device.
     */
    fun displayCode(token: ByteArray): String =
        toHex(token).uppercase(Locale.ROOT).chunked(4).joinToString("-")

    /**
     * First two bytes as `A1B2`. The glasses list several nearby
     * phones at once and the panel is narrow; four characters is enough
     * to pick the right row, and the full token is still what gets
     * matched afterwards.
     */
    fun shortCode(token: ByteArray): String =
        toHex(token.copyOf(SHORT_CODE_BYTES)).uppercase(Locale.ROOT)

    /** [shortCode] for an already-hex-encoded token, or null if unparseable. */
    fun shortCodeOfHex(hex: String?): String? = fromHex(hex)?.let(::shortCode)

    private const val SHORT_CODE_BYTES = 2

    private val SECURE_RANDOM = SecureRandom()

    private val HEX_DIGITS: Array<String> =
        Array(256) { it.toString(16).padStart(2, '0') }
}
