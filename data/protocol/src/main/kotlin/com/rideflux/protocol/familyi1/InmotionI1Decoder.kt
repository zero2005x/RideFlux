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

    /** preamble (2) + 16-byte unstuffed body + CHECK (1) + trailer (2). */
    private const val MIN_FRAME_LEN: Int = 21

    /** Unstuffed bytes needed before LEN (body offset 12) can be read. */
    private const val HEADER_BODY_LEN: Int = 13
    private const val LEN_OFFSET: Int = 12
    private const val EX_LEN_OFFSET: Int = 4
    private const val BASE_BODY_LEN: Int = 16
    private const val LEN_STANDARD: Int = 0x08
    private const val LEN_EXTENDED: Int = 0xFE

    /** Wire cursor just past a successfully unstuffed region, or a failure. */
    private sealed interface Scan {
        class Ok(val cursor: Int) : Scan
        class Fail(val error: InmotionI1DecodeError) : Scan
    }

    /** Resolved total body length, or the LEN/EX-LEN failure that stopped it. */
    private sealed interface BodyLen {
        class Ok(val length: Int) : BodyLen
        class Fail(val error: InmotionI1DecodeError) : BodyLen
    }

    fun decode(wire: ByteArray, offset: Int = 0): InmotionI1DecodeResult {
        // Minimum possible frame: preamble (2) + 16-byte body unstuffed
        // (best case 16 escaped bytes) + CHECK (1) + trailer (2) = 21.
        // A negative offset is rejected too: `wire.size - offset < 21`
        // alone passes for offset < 0 (subtracting a negative grows the
        // size) and the subsequent wire[offset] then throws
        // ArrayIndexOutOfBoundsException instead of a decode Fail.
        if (offset < 0 || wire.size - offset < MIN_FRAME_LEN) {
            return InmotionI1DecodeResult.Fail(InmotionI1DecodeError.TooShort)
        }
        if (wire[offset] != InmotionI1Codec.PREAMBLE_BYTE ||
            wire[offset + 1] != InmotionI1Codec.PREAMBLE_BYTE
        ) return InmotionI1DecodeResult.Fail(InmotionI1DecodeError.BadPreamble)

        val body = ArrayList<Byte>(BASE_BODY_LEN)
        val end = wire.size
        var cursor = when (val scan = scanBody(wire, offset + 2, body)) {
            is Scan.Fail -> return InmotionI1DecodeResult.Fail(scan.error)
            is Scan.Ok -> scan.cursor
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

    /**
     * Escape-decodes one frame body into [body], in two passes: the
     * 13-byte header that carries LEN, then the remainder once the total
     * length is known. Returns the wire cursor just past the body.
     */
    private fun scanBody(wire: ByteArray, start: Int, body: ArrayList<Byte>): Scan {
        val afterHeader = when (val header = unstuff(wire, start, body, HEADER_BODY_LEN)) {
            is Scan.Fail -> return header
            is Scan.Ok -> header.cursor
        }
        val expected = when (val len = resolveBodyLength(body)) {
            is BodyLen.Fail -> return Scan.Fail(len.error)
            is BodyLen.Ok -> len.length
        }
        return unstuff(wire, afterHeader, body, expected)
    }

    /**
     * Appends escape-decoded wire bytes to [body] until it holds [target]
     * bytes. A body already at or past [target] is left untouched.
     */
    private fun unstuff(wire: ByteArray, start: Int, body: ArrayList<Byte>, target: Int): Scan {
        var cursor = start
        val end = wire.size
        while (body.size < target) {
            if (cursor >= end) return Scan.Fail(InmotionI1DecodeError.TooShort)
            val b = wire[cursor]
            if (b == InmotionI1Codec.ESCAPE_BYTE) {
                if (cursor + 1 >= end) return Scan.Fail(InmotionI1DecodeError.BadEscape)
                body.add(wire[cursor + 1])
                cursor += 2
            } else {
                body.add(b)
                cursor += 1
            }
        }
        return Scan.Ok(cursor)
    }

    /** Reads LEN at body offset 12 and resolves the total body length. */
    private fun resolveBodyLength(body: List<Byte>): BodyLen {
        val lenByte = body[LEN_OFFSET].toInt() and 0xFF
        return when (lenByte) {
            LEN_STANDARD -> BodyLen.Ok(BASE_BODY_LEN)
            LEN_EXTENDED -> {
                // EX-LEN is U32LE at body offsets 4..7.
                val raw = ByteArray(4) { body[EX_LEN_OFFSET + it] }
                val exLen = ByteReader.u32LE(raw, 0)
                if (exLen > MAX_EX_LEN) BodyLen.Fail(InmotionI1DecodeError.BadExLen(exLen))
                else BodyLen.Ok(BASE_BODY_LEN + exLen.toInt())
            }
            else -> BodyLen.Fail(InmotionI1DecodeError.BadLen(lenByte))
        }
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
    /**
     * Raw 8-byte slice at offsets 44..51 (model-dependent, §8.7).
     *
     * NOTE: this slice overlaps [tripDistanceMetres] (a U32LE at
     * offsets 48..51). Verified against the spec before changing either
     * field; the overlap is preserved here to keep the historical
     * encoding stable.
     */
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
                phaseCurrentHundredthsA = ByteReader.s32LE(exData, 20),
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
