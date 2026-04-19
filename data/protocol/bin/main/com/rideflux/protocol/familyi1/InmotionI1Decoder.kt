/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.protocol.familyi1

import com.rideflux.protocol.bytes.ByteReader

/**
 * Length-driven decoder for Family I1 wire frames
 * (`PROTOCOL_SPEC.md` §2.6).
 *
 * The algorithm is:
 *  1. verify the `AA AA` preamble at [offset];
 *  2. unescape wire bytes one at a time into the unstuffed body;
 *  3. once the body holds 13 bytes, inspect LEN at body offset 12.
 *     For standard frames (`0x08`) the body is 16 bytes total; for
 *     extended frames (`0xFE`) the body is `16 + EX-LEN`, where
 *     `EX-LEN` is the U32LE at body offset 4..7;
 *  4. keep unescaping until the body reaches its expected length;
 *  5. read one raw wire byte as CHECK and verify the additive sum;
 *  6. require the next two raw wire bytes to be `55 55`.
 */
object InmotionI1Decoder {

    /** Hard cap on EX-DATA length to short-circuit garbage frames. */
    private const val MAX_EX_LEN: Long = 1L shl 20 // 1 MiB

    fun decode(wire: ByteArray, offset: Int = 0): InmotionI1DecodeResult {
        // Minimum possible frame: preamble (2) + 16-byte body unstuffed
        // (best case 16 escaped bytes) + CHECK (1) + trailer (2) = 21.
        if (wire.size - offset < 21) return InmotionI1DecodeResult.Fail(InmotionI1DecodeError.TooShort)
        if (wire[offset] != InmotionI1Codec.PREAMBLE_BYTE ||
            wire[offset + 1] != InmotionI1Codec.PREAMBLE_BYTE
        ) return InmotionI1DecodeResult.Fail(InmotionI1DecodeError.BadPreamble)

        val body = ArrayList<Byte>(16)
        var cursor = offset + 2
        val end = wire.size

        // Step through wire, building the unstuffed body up to the
        // expected length (resolved after body reaches 13 bytes).
        var expectedBodyLen = -1
        while (expectedBodyLen == -1 || body.size < expectedBodyLen) {
            if (cursor >= end) return InmotionI1DecodeResult.Fail(InmotionI1DecodeError.TooShort)
            val b = wire[cursor]
            if (b == InmotionI1Codec.ESCAPE_BYTE) {
                if (cursor + 1 >= end) return InmotionI1DecodeResult.Fail(InmotionI1DecodeError.BadEscape)
                body.add(wire[cursor + 1])
                cursor += 2
            } else {
                body.add(b)
                cursor += 1
            }
            if (expectedBodyLen == -1 && body.size == 13) {
                val lenByte = body[12].toInt() and 0xFF
                expectedBodyLen = when (lenByte) {
                    0x08 -> 16
                    0xFE -> {
                        // EX-LEN is U32LE at body offsets 4..7.
                        val raw = ByteArray(4) { body[4 + it] }
                        val exLen = ByteReader.u32LE(raw, 0)
                        if (exLen < 0 || exLen > MAX_EX_LEN) {
                            return InmotionI1DecodeResult.Fail(InmotionI1DecodeError.BadExLen(exLen))
                        }
                        16 + exLen.toInt()
                    }
                    else -> return InmotionI1DecodeResult.Fail(InmotionI1DecodeError.BadLen(lenByte))
                }
            }
        }

        if (cursor >= end) return InmotionI1DecodeResult.Fail(InmotionI1DecodeError.TooShort)
        val checkRaw = wire[cursor].toInt() and 0xFF
        cursor += 1

        if (cursor + 2 > end) return InmotionI1DecodeResult.Fail(InmotionI1DecodeError.TooShort)
        if (wire[cursor] != InmotionI1Codec.TRAILER_BYTE ||
            wire[cursor + 1] != InmotionI1Codec.TRAILER_BYTE
        ) return InmotionI1DecodeResult.Fail(InmotionI1DecodeError.BadTrailer)
        cursor += 2

        val bodyBytes = body.toByteArray()
        val expectedCheck = InmotionI1Codec.checksum8(bodyBytes)
        if (expectedCheck != checkRaw) {
            return InmotionI1DecodeResult.Fail(
                InmotionI1DecodeError.BadChecksum(expected = expectedCheck, actual = checkRaw),
            )
        }

        val canId = ByteReader.u32LE(bodyBytes, 0)
        val data8 = bodyBytes.copyOfRange(4, 12)
        val lenByte = bodyBytes[12].toInt() and 0xFF
        val chan = bodyBytes[13].toInt() and 0xFF
        val fmt = bodyBytes[14].toInt() and 0xFF
        val type = bodyBytes[15].toInt() and 0xFF
        val exData = if (bodyBytes.size > 16) bodyBytes.copyOfRange(16, bodyBytes.size) else ByteArray(0)

        val frame = InmotionI1Frame(
            canId = canId,
            data8 = data8,
            lenByte = lenByte,
            chan = chan,
            fmt = fmt,
            type = type,
            exData = exData,
        )
        return InmotionI1DecodeResult.Ok(frame, consumedBytes = cursor - offset)
    }
}

/**
 * Minimal parser for the Family I1 extended-telemetry record
 * (CAN-ID `0x0F550113`, §3.5.2).
 *
 * Only the fields whose encoding is unambiguous at the spec level
 * are surfaced here. The total-distance field at EX-DATA offset 44
 * requires per-model scaling (§8.7) and is exposed as
 * [totalDistanceRaw8]; the work-mode word at offset 60 references a
 * §4.3 enumeration that is not currently present in the spec file
 * and is surfaced raw.
 */
data class InmotionI1ExtendedTelemetry(
    /** Raw U32 at EX-DATA offset 0; degrees = raw / 65536. */
    val pitchRaw: Long,
    /** U32 component A at offset 12 (used by speed computation). */
    val speedARaw: Long,
    /** U32 component B at offset 16. */
    val speedBRaw: Long,
    /** S32 1/100 A at offset 20. */
    val phaseCurrentHundredthsA: Int,
    /** U32 1/100 V at offset 24. */
    val voltageHundredthsV: Long,
    /** S8 °C at offset 32. */
    val temperature1Celsius: Int,
    /** S8 °C at offset 34. */
    val temperature2Celsius: Int,
    /** Raw 8-byte slice at offsets 44..51 (model-dependent, §8.7). */
    val totalDistanceRaw8: ByteArray,
    /** U32 trip distance (metres) at offset 48. */
    val tripDistanceMetres: Long,
    /** Raw U32 work-mode / state word at offset 60; §4.3 not yet in spec. */
    val stateWordRaw: Long,
    /** Raw U32 roll at offset 72; degrees = raw / 90. */
    val rollRaw: Long,
) {
    /** Convenience: voltage in volts. */
    val voltageV: Double get() = voltageHundredthsV / 100.0

    /** Convenience: phase current in amperes. */
    val phaseCurrentA: Double get() = phaseCurrentHundredthsA / 100.0

    /**
     * Ground speed in km/h, given a per-model calibration constant [f]
     * (§3.5.2 "Speed computation" table: 1000 for R1S/R1Sample/R0,
     * 3810 for R1T, 3812 otherwise). Returned value is `|S| / (2·F)`
     * m/s converted to km/h via ×3.6.
     */
    fun speedKmh(f: Double = 3812.0): Double {
        val s = speedARaw + speedBRaw
        val mps = kotlin.math.abs(s).toDouble() / (2.0 * f)
        return mps * 3.6
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is InmotionI1ExtendedTelemetry) return false
        return pitchRaw == other.pitchRaw &&
            speedARaw == other.speedARaw &&
            speedBRaw == other.speedBRaw &&
            phaseCurrentHundredthsA == other.phaseCurrentHundredthsA &&
            voltageHundredthsV == other.voltageHundredthsV &&
            temperature1Celsius == other.temperature1Celsius &&
            temperature2Celsius == other.temperature2Celsius &&
            totalDistanceRaw8.contentEquals(other.totalDistanceRaw8) &&
            tripDistanceMetres == other.tripDistanceMetres &&
            stateWordRaw == other.stateWordRaw &&
            rollRaw == other.rollRaw
    }

    override fun hashCode(): Int {
        var h = pitchRaw.hashCode()
        h = 31 * h + speedARaw.hashCode()
        h = 31 * h + speedBRaw.hashCode()
        h = 31 * h + phaseCurrentHundredthsA
        h = 31 * h + voltageHundredthsV.hashCode()
        h = 31 * h + temperature1Celsius
        h = 31 * h + temperature2Celsius
        h = 31 * h + totalDistanceRaw8.contentHashCode()
        h = 31 * h + tripDistanceMetres.hashCode()
        h = 31 * h + stateWordRaw.hashCode()
        h = 31 * h + rollRaw.hashCode()
        return h
    }

    companion object {
        /** Minimum EX-DATA size required to decode all surfaced fields. */
        const val MIN_EX_DATA_SIZE: Int = 76

        fun parse(exData: ByteArray): InmotionI1ExtendedTelemetry {
            require(exData.size >= MIN_EX_DATA_SIZE) {
                "EX-DATA too short: ${exData.size} < $MIN_EX_DATA_SIZE"
            }
            return InmotionI1ExtendedTelemetry(
                pitchRaw = ByteReader.u32LE(exData, 0),
                speedARaw = ByteReader.u32LE(exData, 12),
                speedBRaw = ByteReader.u32LE(exData, 16),
                phaseCurrentHundredthsA = ByteReader.u32LE(exData, 20).toInt(),
                voltageHundredthsV = ByteReader.u32LE(exData, 24),
                temperature1Celsius = exData[32].toInt(),
                temperature2Celsius = exData[34].toInt(),
                totalDistanceRaw8 = exData.copyOfRange(44, 52),
                tripDistanceMetres = ByteReader.u32LE(exData, 48),
                stateWordRaw = ByteReader.u32LE(exData, 60),
                rollRaw = ByteReader.u32LE(exData, 72),
            )
        }
    }
}
