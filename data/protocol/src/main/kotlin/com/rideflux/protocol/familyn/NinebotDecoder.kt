/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.protocol.familyn

import com.rideflux.protocol.bytes.ByteReader

/**
 * Decoder for Families N1 / N2 (`PROTOCOL_SPEC.md` §2.4 / §2.5).
 *
 * The decoder accepts the on-wire byte sequence beginning with the
 * `55 AA` prefix and returns a fully-parsed [NinebotFrame] after
 * deobfuscating with the caller-supplied keystream and verifying
 * the §6.1 checksum.
 *
 * **Family selection.** N1 and N2 share the prefix and LEN layout;
 * only the presence of a CMD byte distinguishes them. The caller
 * must supply the expected family. If the family is unknown, call
 * the decoder twice (once per family) and pick the result whose
 * checksum validates.
 */
object NinebotDecoder {

    /**
     * Decode the next frame at [offset] of [wire].
     *
     * @param wire   on-wire bytes, including the `55 AA` prefix.
     * @param family [NinebotFamily.N1] or [NinebotFamily.N2].
     * @param gamma  16-byte keystream (use [NinebotCodec.ZERO_KEYSTREAM]
     *               for N1 or before the N2 handshake).
     * @param offset starting index in [wire]; defaults to 0.
     */
    fun decode(
        wire: ByteArray,
        family: NinebotFamily,
        gamma: ByteArray = NinebotCodec.ZERO_KEYSTREAM,
        offset: Int = 0,
    ): NinebotDecodeResult {
        if (gamma.size != NinebotCodec.KEYSTREAM_SIZE) {
            return NinebotDecodeResult.Fail(NinebotDecodeError.BadKeystream(gamma.size))
        }

        // Minimum frame size (zero-length DATA):
        //   N1 = prefix(2) + LEN(1) + SRC(1) + DST(1) + PARAM(1) + CHK(2) = 8
        //   N2 = same + CMD(1)                                           = 9
        val fixedHeader = if (family == NinebotFamily.N2) 9 else 8
        if (wire.size - offset < fixedHeader) {
            return NinebotDecodeResult.Fail(NinebotDecodeError.TooShort)
        }

        val prefix0 = wire[offset].toInt() and 0xFF
        val prefix1 = wire[offset + 1].toInt() and 0xFF
        if (prefix0 != 0x55 || prefix1 != 0xAA) {
            return NinebotDecodeResult.Fail(NinebotDecodeError.BadPrefix)
        }

        // LEN is transmitted in the clear (position 0 of the γ stream).
        val lenByte = wire[offset + 2].toInt() and 0xFF
        // Per §2.4 / §2.5:
        //   N1: LEN = DATA.size + 2
        //   N2: LEN = DATA.size + 3
        val lenOverhead = if (family == NinebotFamily.N2) 3 else 2
        if (lenByte < lenOverhead) {
            return NinebotDecodeResult.Fail(NinebotDecodeError.LenOutOfRange)
        }
        val dataSize = lenByte - lenOverhead
        val frameLen = fixedHeader + dataSize
        if (wire.size - offset < frameLen) {
            return NinebotDecodeResult.Fail(NinebotDecodeError.TooShort)
        }

        // Copy the post-prefix bytes (LEN through CHK_HI inclusive)
        // and XOR-decode in place. Position 0 of this sub-array is
        // the LEN byte, which is emitted in the clear.
        val postPrefixLen = frameLen - 2
        val payload = ByteArray(postPrefixLen)
        System.arraycopy(wire, offset + 2, payload, 0, postPrefixLen)
        NinebotCodec.xorInPlace(payload, gamma)

        // After deobfuscation, parse fields.
        val reader = ByteReader
        val src = reader.u8(payload, 1)
        val dst = reader.u8(payload, 2)
        val cmd: Int?
        val paramOffset: Int
        if (family == NinebotFamily.N2) {
            cmd = reader.u8(payload, 3)
            paramOffset = 4
        } else {
            cmd = null
            paramOffset = 3
        }
        val param = reader.u8(payload, paramOffset)

        val dataStart = paramOffset + 1
        val data = ByteArray(dataSize)
        System.arraycopy(payload, dataStart, data, 0, dataSize)

        val chkLo = reader.u8(payload, dataStart + dataSize)
        val chkHi = reader.u8(payload, dataStart + dataSize + 1)
        val actualChk = chkLo or (chkHi shl 8)

        // Pre-checksum bytes = everything from LEN up to the end of DATA.
        val preChkBytes = ByteArray(dataStart + dataSize)
        System.arraycopy(payload, 0, preChkBytes, 0, preChkBytes.size)
        val expectedChk = NinebotCodec.checksum16(preChkBytes)
        if (expectedChk != actualChk) {
            return NinebotDecodeResult.Fail(NinebotDecodeError.BadChecksum(expectedChk, actualChk))
        }

        val frame = NinebotFrame(
            family = family,
            src = src,
            dst = dst,
            cmd = cmd,
            param = param,
            data = data,
        )
        return NinebotDecodeResult.Ok(frame, frameLen)
    }
}

/**
 * Live-telemetry page 0xB0 parsed per §3.4.1. Integer fields are
 * stored at their native scale; SI conversions are exposed as
 * computed properties.
 *
 * All offsets are relative to the start of the DATA byte array of
 * the parent frame.
 */
data class NinebotTelemetryB0(
    /** Battery percent (0..100). */
    val batteryPercent: Int,
    /** Signed speed, standard coding: 1/100 m/s. */
    val speedStandardRaw: Int,
    /** Unsigned total distance in metres. */
    val totalDistanceMetres: Long,
    /** Unsigned temperature in 1/10 °C. */
    val temperatureTenthsC: Int,
    /** Unsigned pack voltage in 1/100 V (0 on Mini hardware). */
    val voltageHundredthsV: Int,
    /** Signed bus current in 1/100 A. */
    val currentHundredthsA: Int,
    /** Unsigned speed, S2 coding: 1/100 km/h. */
    val speedS2Raw: Int,
) {
    /** Speed in km/h using the standard coding (raw * 0.036). */
    val speedStandardKmh: Double get() = speedStandardRaw * 0.036

    /** Speed in km/h using the S2 coding (raw / 100). */
    val speedS2Kmh: Double get() = speedS2Raw / 100.0

    /** Temperature in °C. */
    val temperatureCelsius: Double get() = temperatureTenthsC / 10.0

    /** Pack voltage in V (0.0 on Mini). */
    val voltageVolts: Double get() = voltageHundredthsV / 100.0

    /** Bus current in A. */
    val currentAmps: Double get() = currentHundredthsA / 100.0

    companion object {
        /** Minimum DATA size required to parse every §3.4.1 field. */
        const val MIN_DATA_SIZE: Int = 30

        /** Parse a `0xB0` page from the DATA field of the parent frame. */
        fun parse(data: ByteArray): NinebotTelemetryB0 {
            require(data.size >= MIN_DATA_SIZE) {
                "B0 page requires at least $MIN_DATA_SIZE DATA bytes, got ${data.size}"
            }
            val r = ByteReader
            return NinebotTelemetryB0(
                batteryPercent = r.u16LE(data, 8),
                speedStandardRaw = r.s16LE(data, 10),
                totalDistanceMetres = r.u32LE(data, 14),
                temperatureTenthsC = r.u16LE(data, 22),
                voltageHundredthsV = r.u16LE(data, 24),
                currentHundredthsA = r.s16LE(data, 26),
                speedS2Raw = r.u16LE(data, 28),
            )
        }
    }
}

/**
 * Activation-date helper per §3.4.2.
 *
 * `year = (D >> 9) + 2000`, `month = (D >> 5) & 0x0F`, `day = D & 0x1F`.
 */
object NinebotActivationDate {
    data class Ymd(val year: Int, val month: Int, val day: Int)

    fun decode(wordLE: Int): Ymd {
        val d = wordLE and 0xFFFF
        return Ymd(
            year = (d ushr 9) + 2000,
            month = (d ushr 5) and 0x0F,
            day = d and 0x1F,
        )
    }
}
