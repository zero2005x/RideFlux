/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.data.bridge

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Binary codec for [BridgeFrame]. Layout (little-endian, 32 bytes):
 *
 * ```
 * 0       u8   magic         = 0x52 ('R')
 * 1       u8   version       = 1
 * 2       u8   flags         bit0=stale  bit1=ready
 * 3       u8   signal        SignalLevel.wire (0/1/2)
 * 4..7    i32  timestampSec  unix seconds — fits 2106
 * 8..11   i32  timestampMs   millisecond remainder; or full upper bits
 * 12..13  i16  speedKmhX10   speed * 10, INT16_NULL = absent
 * 14      u8   vehBatPct     0..100, 0xFF = absent
 * 15      u8   phoneBatPct   0..100, 0xFF = absent
 * 16..17  i16  voltageVx10   voltage * 10, INT16_NULL = absent
 * 18..21  i32  tripDistM     trip distance in metres, INT32_NULL = absent
 * 22..29  i64  tripDurSec    trip duration in seconds, Long.MIN_VALUE = absent
 * 30..31  u16  reserved      always 0; rejected if non-zero
 * ```
 *
 * The 8-byte timestamp split (sec + ms) is wasteful but lets the
 * receiver tolerate phones with a clock skew without integer-overflow
 * surprises around 2038. If you need to compress further later, bump
 * [BridgeProtocol.PROTOCOL_VERSION].
 *
 * This is intentionally unencrypted — the bridge channel rides on
 * top of BLE encryption negotiated at link layer when the user pairs
 * (LE Secure Connections); for unpaired bring-up the data is no more
 * sensitive than what the wheel emits unencrypted on FFE1 anyway.
 */
object BridgeCodec {

    /**
     * Encode [frame] to its 32-byte wire representation. Always
     * succeeds; absent fields are encoded with their sentinel values.
     */
    fun encode(frame: BridgeFrame): ByteArray {
        val out = ByteArray(BridgeProtocol.FRAME_SIZE_V1)
        val buf = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)

        buf.put(BridgeProtocol.MAGIC)
        buf.put(BridgeProtocol.PROTOCOL_VERSION)

        var flags = 0
        if (frame.stale) flags = flags or 0x01
        if (frame.ready) flags = flags or 0x02
        buf.put(flags.toByte())

        buf.put(frame.signal.wire.toByte())

        // Split timestamp into seconds + millisecond remainder. The
        // receiver re-assembles via `sec * 1000 + ms`.
        val ts = frame.timestampMillis
        val sec = (ts / 1000L).toInt()
        val ms = (ts % 1000L).toInt()
        buf.putInt(sec)
        buf.putInt(ms)

        // Speed / voltage in 0.1-unit steps. An out-of-range or
        // non-finite reading must degrade to the null sentinel: the
        // previous coerceIn saturated it to Short.MAX/MIN and turned
        // NaN into 0, so the receiver rendered a plausible-but-wrong
        // measurement instead of "no data".
        buf.putShort(toScaledI16(frame.speedKmh))

        buf.put(percentToByte(frame.vehicleBatteryPercent))
        buf.put(percentToByte(frame.phoneBatteryPercent?.toFloat()))

        buf.putShort(toScaledI16(frame.voltageV))

        buf.putInt(frame.tripDistanceMetres ?: BridgeProtocol.INT32_NULL)
        buf.putLong(frame.tripDurationSeconds ?: Long.MIN_VALUE)

        // 2 reserved bytes — already zero from ByteArray init.
        return out
    }

    /**
     * Decode a 32-byte v1 frame, or return null if the bytes don't
     * match the expected magic/version/length.
     */
    fun decode(bytes: ByteArray): BridgeFrame? {
        // Exact length, not a lower bound: this is a fixed-size wire
        // format, and accepting an oversized buffer silently truncated
        // a mis-framed payload to its first 32 bytes and returned it as
        // a valid frame.
        if (bytes.size != BridgeProtocol.FRAME_SIZE_V1) return null
        if (bytes[0] != BridgeProtocol.MAGIC) return null
        if (bytes[1] != BridgeProtocol.PROTOCOL_VERSION) return null

        val buf = ByteBuffer.wrap(bytes, 0, BridgeProtocol.FRAME_SIZE_V1)
            .order(ByteOrder.LITTLE_ENDIAN)
        buf.position(2)

        val flags = buf.get().toInt() and 0xFF
        val signal = SignalLevel.fromWire(buf.get().toInt() and 0xFF) ?: return null
        val sec = buf.int
        val ms = buf.int
        // Treat sec as unsigned so the field truly covers up to 2106
        // rather than sign-extending and corrupting post-2038 stamps.
        val timestampMillis = Integer.toUnsignedLong(sec) * 1000L + ms.toLong()

        val sx10 = buf.short
        val speed = if (sx10 == BridgeProtocol.INT16_NULL) null else sx10 / 10f

        val vehBat = byteToPercent(buf.get())
        val phoneBat = byteToPercent(buf.get())?.roundToInt()

        val vx10 = buf.short
        val voltage = if (vx10 == BridgeProtocol.INT16_NULL) null else vx10 / 10f

        val dist = buf.int.let { if (it == BridgeProtocol.INT32_NULL) null else it }
        val dur = buf.long.let { if (it == Long.MIN_VALUE) null else it }

        // Reserved bytes 30..31 must be zero per the wire contract;
        // reject frames carrying garbage there.
        if (buf.short != 0.toShort()) return null

        return BridgeFrame(
            timestampMillis = timestampMillis,
            speedKmh = speed,
            vehicleBatteryPercent = vehBat,
            phoneBatteryPercent = phoneBat,
            voltageV = voltage,
            tripDistanceMetres = dist,
            tripDurationSeconds = dur,
            signal = signal,
            stale = (flags and 0x01) != 0,
            ready = (flags and 0x02) != 0,
        )
    }

    /**
     * Scale to 0.1-unit steps and narrow to i16, mapping anything
     * non-finite or out of range to [BridgeProtocol.INT16_NULL].
     *
     * The sentinel itself is reserved: a legitimate sample that rounds
     * to exactly `Short.MIN_VALUE` is treated as out of range rather
     * than being written as "absent" and silently decoded back as null.
     */
    private fun toScaledI16(value: Float?): Short {
        if (value == null || !value.isFinite()) return BridgeProtocol.INT16_NULL
        val scaled = (value * 10f).roundToInt()
        val min = Short.MIN_VALUE.toInt() + 1 // MIN_VALUE is the sentinel
        val max = Short.MAX_VALUE.toInt()
        return if (scaled < min || scaled > max) {
            BridgeProtocol.INT16_NULL
        } else {
            scaled.toShort()
        }
    }

    /**
     * Percent to u8, mapping non-finite or out-of-range input to
     * [BridgeProtocol.PERCENT_NULL] rather than clamping: a NaN battery
     * reading previously encoded as a healthy-looking 0 %, and -5 / 120
     * became 0 / 100.
     */
    private fun percentToByte(pct: Float?): Byte {
        if (pct == null || !pct.isFinite()) return BridgeProtocol.PERCENT_NULL.toByte()
        val rounded = pct.roundToInt()
        if (rounded !in 0..100) return BridgeProtocol.PERCENT_NULL.toByte()
        return rounded.toByte()
    }

    private fun byteToPercent(b: Byte): Float? {
        val v = b.toInt() and 0xFF
        return if (v == BridgeProtocol.PERCENT_NULL) null else v.toFloat()
    }
}
