/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.protocol.familyi2

import com.rideflux.protocol.bytes.ByteReader

/**
 * Length-driven decoder for Family I2 wire frames
 * (`PROTOCOL_SPEC.md` §2.7).
 *
 * Algorithm:
 *  1. verify the `AA AA` preamble at [offset];
 *  2. escape-decode wire bytes into the `body` buffer until its
 *     size reaches 2, then read LEN from `body[1]`;
 *  3. continue escape-decoding until the body reaches `LEN + 2`
 *     bytes (`FLAGS + LEN + CMD + DATA[LEN-1]`);
 *  4. read one raw wire byte as CHECK and verify the XOR sum
 *     (§6.4.2);
 *  5. mask the high bit of CMD per §2.7.1 and expose the pre-mask
 *     state via [InmotionI2Frame.cmdRawHighBitSet].
 *
 * FLAGS values other than `0x11` (Init) and `0x14` (Default) are
 * rejected by §2.7.2.
 */
object InmotionI2Decoder {

    /** preamble (2) + FLAGS + LEN + CHECK, the shortest decodable shape. */
    private const val MIN_FRAME_LEN: Int = 5

    /** Unstuffed bytes needed before LEN (body offset 1) can be read. */
    private const val HEADER_BODY_LEN: Int = 2
    private const val LEN_OFFSET: Int = 1

    /** FLAGS + LEN + CMD + DATA[LEN-1] = LEN + 2 bytes. */
    private const val BODY_LEN_OVERHEAD: Int = 2

    /** Wire cursor just past a successfully unstuffed region, or a failure. */
    private sealed interface Scan {
        class Ok(val cursor: Int) : Scan
        class Fail(val error: InmotionI2DecodeError) : Scan
    }

    fun decode(wire: ByteArray, offset: Int = 0): InmotionI2DecodeResult {
        // Reject negative offsets: without this, `wire.size - offset`
        // passes the bounds check for offset < 0 and wire[offset]
        // throws ArrayIndexOutOfBoundsException instead of returning a
        // Fail result like every other malformed input.
        if (offset < 0 || wire.size - offset < MIN_FRAME_LEN) {
            return InmotionI2DecodeResult.Fail(InmotionI2DecodeError.TooShort)
        }
        if (wire[offset] != InmotionI2Codec.PREAMBLE_BYTE ||
            wire[offset + 1] != InmotionI2Codec.PREAMBLE_BYTE
        ) return InmotionI2DecodeResult.Fail(InmotionI2DecodeError.BadPreamble)

        val body = ArrayList<Byte>(16)
        val end = wire.size
        var cursor = when (val scan = scanBody(wire, offset + 2, body)) {
            is Scan.Fail -> return InmotionI2DecodeResult.Fail(scan.error)
            is Scan.Ok -> scan.cursor
        }

        if (cursor >= end) return InmotionI2DecodeResult.Fail(InmotionI2DecodeError.TooShort)
        val checkRaw = wire[cursor].toInt() and 0xFF
        cursor += 1

        val bodyBytes = body.toByteArray()

        // Verify the XOR checksum BEFORE checking FLAGS so that a frame
        // corrupted in both places reports BadChecksum (an unambiguous
        // corruption signal) rather than masking it as BadFlags.
        val expectedCheck = InmotionI2Codec.xorChecksum8(bodyBytes)
        if (expectedCheck != checkRaw) {
            return InmotionI2DecodeResult.Fail(
                InmotionI2DecodeError.BadChecksum(expected = expectedCheck, actual = checkRaw),
            )
        }

        val flags = bodyBytes[0].toInt() and 0xFF
        if (flags != InmotionI2Codec.FLAGS_INIT && flags != InmotionI2Codec.FLAGS_DEFAULT) {
            return InmotionI2DecodeResult.Fail(InmotionI2DecodeError.BadFlags(flags))
        }

        val cmdRaw = bodyBytes[2].toInt() and 0xFF
        val cmdMasked = cmdRaw and 0x7F
        val dataSize = (bodyBytes[1].toInt() and 0xFF) - 1
        val data = if (dataSize > 0) bodyBytes.copyOfRange(3, 3 + dataSize) else ByteArray(0)

        val frame = InmotionI2Frame(
            flags = flags,
            cmd = cmdMasked,
            data = data,
            cmdRawHighBitSet = (cmdRaw and 0x80) != 0,
        )
        return InmotionI2DecodeResult.Ok(frame, consumedBytes = cursor - offset)
    }

    /**
     * Escape-decodes one frame body into [body], in two passes: the
     * 2-byte header that carries LEN, then the remainder once the total
     * length is known. Returns the wire cursor just past the body.
     */
    private fun scanBody(wire: ByteArray, start: Int, body: ArrayList<Byte>): Scan {
        val afterHeader = when (val header = unstuff(wire, start, body, HEADER_BODY_LEN)) {
            is Scan.Fail -> return header
            is Scan.Ok -> header.cursor
        }
        val len = body[LEN_OFFSET].toInt() and 0xFF
        if (len == 0) return Scan.Fail(InmotionI2DecodeError.BadLen(len))
        return unstuff(wire, afterHeader, body, len + BODY_LEN_OVERHEAD)
    }

    /**
     * Appends escape-decoded wire bytes to [body] until it holds [target]
     * bytes. A body already at or past [target] is left untouched.
     */
    private fun unstuff(wire: ByteArray, start: Int, body: ArrayList<Byte>, target: Int): Scan {
        var cursor = start
        val end = wire.size
        while (body.size < target) {
            if (cursor >= end) return Scan.Fail(InmotionI2DecodeError.TooShort)
            val b = wire[cursor]
            if (b == InmotionI2Codec.ESCAPE_BYTE) {
                if (cursor + 1 >= end) return Scan.Fail(InmotionI2DecodeError.BadEscape)
                body.add(wire[cursor + 1])
                cursor += 2
            } else {
                body.add(b)
                cursor += 1
            }
        }
        return Scan.Ok(cursor)
    }
}

/**
 * Decoder for the Family I2 real-time-telemetry record on V11 main-
 * board firmware `< 1.4` (§3.5.4.A). Multi-byte integers are
 * little-endian.
 */
data class InmotionI2RealtimeV11Early(
    val voltageHundredthsV: Int,
    val phaseCurrentHundredthsA: Int,
    val speedHundredthsKmh: Int,
    val torqueHundredthsNm: Int,
    val batteryPowerWatts: Int,
    val motorPowerWatts: Int,
    val tripDistanceTenMetres: Int,
    val remainingRangeTenMetres: Int,
) {
    val voltageV: Double get() = voltageHundredthsV / 100.0
    val phaseCurrentA: Double get() = phaseCurrentHundredthsA / 100.0
    val speedKmh: Double get() = speedHundredthsKmh / 100.0
    val torqueNm: Double get() = torqueHundredthsNm / 100.0
    val tripDistanceMetres: Int get() = tripDistanceTenMetres * 10
    val remainingRangeMetres: Int get() = remainingRangeTenMetres * 10

    companion object {
        const val MIN_DATA_SIZE: Int = 16

        fun parse(data: ByteArray): InmotionI2RealtimeV11Early {
            require(data.size >= MIN_DATA_SIZE) {
                "DATA too short for V11 early telemetry: ${data.size} < $MIN_DATA_SIZE"
            }
            return InmotionI2RealtimeV11Early(
                voltageHundredthsV = ByteReader.u16LE(data, 0),
                phaseCurrentHundredthsA = ByteReader.s16LE(data, 2),
                speedHundredthsKmh = ByteReader.s16LE(data, 4),
                torqueHundredthsNm = ByteReader.s16LE(data, 6),
                batteryPowerWatts = ByteReader.s16LE(data, 8),
                motorPowerWatts = ByteReader.s16LE(data, 10),
                tripDistanceTenMetres = ByteReader.u16LE(data, 12),
                remainingRangeTenMetres = ByteReader.u16LE(data, 14),
            )
        }
    }
}

/**
 * Shared "core electrical" view of a Family I2 real-time-telemetry
 * record. Offsets 0 (voltage) and 2 (current) are the only fields
 * whose meaning is constant across every I2 variant per §8.8; all
 * other fields are variant-dependent and must be resolved after the
 * per-model identification of §9.5.2.
 */
data class InmotionI2RealtimeCore(
    val voltageHundredthsV: Int,
    val currentHundredthsA: Int,
) {
    val voltageV: Double get() = voltageHundredthsV / 100.0
    val currentA: Double get() = currentHundredthsA / 100.0

    companion object {
        const val MIN_DATA_SIZE: Int = 4

        fun parse(data: ByteArray): InmotionI2RealtimeCore {
            require(data.size >= MIN_DATA_SIZE) {
                "DATA too short for core telemetry: ${data.size} < $MIN_DATA_SIZE"
            }
            return InmotionI2RealtimeCore(
                voltageHundredthsV = ByteReader.u16LE(data, 0),
                currentHundredthsA = ByteReader.s16LE(data, 2),
            )
        }
    }
}
