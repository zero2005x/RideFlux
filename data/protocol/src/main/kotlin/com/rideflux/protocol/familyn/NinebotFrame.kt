/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.protocol.familyn

/**
 * Discriminates between the two Ninebot frame layouts.
 *
 * - [N1]: short frame, no CMD byte, identity keystream.
 * - [N2]: long frame, additional CMD byte between DST and PARAM,
 *   session-negotiated 16-byte keystream.
 */
enum class NinebotFamily { N1, N2 }

/**
 * Parsed Ninebot (Family N1 / N2) frame per `PROTOCOL_SPEC.md`
 * §2.4 / §2.5.
 *
 * A single data class covers both N1 and N2; the [cmd] field is
 * `null` for N1 frames and non-null for N2 frames. [family] is
 * stored explicitly so encoders can round-trip a frame without
 * guessing.
 *
 * Field values are the *plaintext* (deobfuscated) bytes. The
 * [data] array is a defensive copy; callers may mutate it freely.
 */
data class NinebotFrame(
    val family: NinebotFamily,
    val src: Int,
    val dst: Int,
    val cmd: Int?,
    val param: Int,
    val data: ByteArray,
) {
    init {
        require((family == NinebotFamily.N2) == (cmd != null)) {
            "N2 frames must carry a CMD byte; N1 frames must not"
        }
        require(src in 0..255) { "src must be a byte" }
        require(dst in 0..255) { "dst must be a byte" }
        require(param in 0..255) { "param must be a byte" }
        cmd?.let { require(it in 0..255) { "cmd must be a byte" } }
    }

    /** The LEN byte that would be transmitted for this frame. */
    val lenByte: Int get() = data.size + if (family == NinebotFamily.N2) 3 else 2

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NinebotFrame) return false
        return family == other.family &&
            src == other.src &&
            dst == other.dst &&
            cmd == other.cmd &&
            param == other.param &&
            data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = family.hashCode()
        result = 31 * result + src
        result = 31 * result + dst
        result = 31 * result + (cmd ?: -1)
        result = 31 * result + param
        result = 31 * result + data.contentHashCode()
        return result
    }
}

/** Sealed result type for [NinebotDecoder.decode]. */
sealed class NinebotDecodeResult {
    data class Ok(val frame: NinebotFrame, val consumedBytes: Int) : NinebotDecodeResult()
    data class Fail(val error: NinebotDecodeError) : NinebotDecodeResult()
}

/** Reason a frame could not be decoded. */
sealed class NinebotDecodeError {
    object TooShort : NinebotDecodeError()
    object BadPrefix : NinebotDecodeError()
    object LenOutOfRange : NinebotDecodeError()
    data class BadChecksum(val expected: Int, val actual: Int) : NinebotDecodeError()
    data class BadKeystream(val size: Int) : NinebotDecodeError()
}
