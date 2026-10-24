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
 * Binary codec for [BridgeFrame].
 *
 * ### v2 layout (current, little-endian, 20 bytes)
 * ```
 * 0       u8   magic         = 0x52 ('R')
 * 1       u8   version       = 2
 * 2       u8   flags         bit0=stale  bit1=ready  bits2..3=signal wire (0/1/2)
 *                            bit4=hudHidden  bits5..7 reserved (must decode as don't-care)
 * 3..6    i32  timestampSec  unix seconds (decoded unsigned — covers 2106)
 * 7..8    i16  speedKmhX10   speed * 10, INT16_NULL = absent
 * 9       u8   vehBatPct     0..100, 0xFF = absent
 * 10      u8   phoneBatPct   0..100, 0xFF = absent
 * 11..12  i16  voltageVx10   voltage * 10, INT16_NULL = absent
 * 13..15  u24  tripDistM     trip distance in metres, 0xFFFFFF = absent (max ~16 777 km)
 * 16..19  i32  tripDurSec    trip duration in seconds, INT32_NULL = absent
 * ```
 * Twenty bytes fit the 20-byte payload of the default 23-byte ATT MTU,
 * so a single notification always carries a complete frame regardless
 * of MTU negotiation. Timestamp resolution drops from milliseconds to
 * seconds — irrelevant for the HUD, whose staleness thresholds are
 * multi-second. Any future compression or extension must bump
 * [BridgeProtocol.PROTOCOL_VERSION].
 *
 * ### v1 layout (decode-only, 32 bytes)
 * Kept so a glasses APK updated ahead of the phone still reads frames
 * from a phone running the previous release. The server never encodes
 * v1 anymore.
 * ```
 * 0       u8   magic         = 0x52 ('R')
 * 1       u8   version       = 1
 * 2       u8   flags         bit0=stale  bit1=ready
 * 3       u8   signal        SignalLevel.wire (0/1/2)
 * 4..7    i32  timestampSec  unix seconds
 * 8..11   i32  timestampMs   millisecond remainder
 * 12..13  i16  speedKmhX10   speed * 10, INT16_NULL = absent
 * 14      u8   vehBatPct     0..100, 0xFF = absent
 * 15      u8   phoneBatPct   0..100, 0xFF = absent
 * 16..17  i16  voltageVx10   voltage * 10, INT16_NULL = absent
 * 18..21  i32  tripDistM     trip distance in metres, INT32_NULL = absent
 * 22..29  i64  tripDurSec    trip duration in seconds, Long.MIN_VALUE = absent
 * 30..31  u16  reserved      always 0; rejected if non-zero
 * ```
 *
 * This is intentionally unencrypted — the bridge channel rides on
 * top of BLE encryption negotiated at link layer when the user pairs
 * (LE Secure Connections); for unpaired bring-up the data is no more
 * sensitive than what the wheel emits unencrypted on FFE1 anyway.
 */
object BridgeCodec {

    /**
     * Encode [frame] to its 24-byte v3 wire representation. Always
     * succeeds; absent fields are encoded with their sentinel values.
     */
    fun encode(frame: BridgeFrame): ByteArray {
        val out = ByteArray(BridgeProtocol.FRAME_SIZE_V3)
        val buf = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)

        buf.put(BridgeProtocol.MAGIC)
        buf.put(BridgeProtocol.PROTOCOL_VERSION)

        var flags = 0
        if (frame.stale) flags = flags or 0x01
        if (frame.ready) flags = flags or 0x02
        flags = flags or (frame.signal.wire shl 2)
        if (frame.hudHidden) flags = flags or FLAG_HUD_HIDDEN
        flags = flags or (frame.vehicleType.wire shl VEHICLE_TYPE_SHIFT)
        buf.put(flags.toByte())

        // Seconds resolution; decoded as unsigned on the receive side
        // so the field covers the same 2106 horizon the v1 sec+ms pair
        // did, without two words of payload.
        buf.putInt((frame.timestampMillis / 1_000L).toInt())

        // Speed / voltage in 0.1-unit steps. An out-of-range or
        // non-finite reading must degrade to the null sentinel: the
        // previous coerceIn saturated it to Short.MAX/MIN and turned
        // NaN into 0, so the receiver rendered a plausible-but-wrong
        // measurement instead of "no data".
        buf.putShort(toScaledI16(frame.speedKmh))

        buf.put(percentToByte(frame.vehicleBatteryPercent))
        buf.put(percentToByte(frame.phoneBatteryPercent?.toFloat()))

        buf.putShort(toScaledI16(frame.voltageV))

        putU24(buf, frame.tripDistanceMetres)

        buf.putInt(frame.tripDurationSeconds
            ?.takeIf { it <= Int.MAX_VALUE.toLong() }
            ?.toInt() ?: BridgeProtocol.INT32_NULL)

        // Category-specific slots. aux1 shares the 0.1-resolution i16
        // convention with speed and voltage; aux2/aux3 are one byte each,
        // where 0xFF means absent. A producer that has nothing to say for
        // its category leaves all three null.
        buf.putShort(toScaledI16(frame.aux1))
        buf.put((frame.aux2 ?: BridgeProtocol.PERCENT_NULL).toByte())
        buf.put((frame.aux3 ?: BridgeProtocol.PERCENT_NULL).toByte())
        return out
    }

    /**
     * Decode a bridge frame. Returns null for anything that does not
     * match a known layout (wrong size, magic, version, signal value,
     * or non-zero v1 reserved bytes).
     */
    fun decode(bytes: ByteArray): BridgeFrame? = try {
        when {
            bytes.size == BridgeProtocol.FRAME_SIZE_V3 && bytes[0] == BridgeProtocol.MAGIC &&
                bytes[1] == BridgeProtocol.PROTOCOL_VERSION -> decodeV3(bytes)

            bytes.size == BridgeProtocol.FRAME_SIZE_V2 && bytes[0] == BridgeProtocol.MAGIC &&
                bytes[1] == PROTOCOL_VERSION_V2 -> decodeV2(bytes)

            bytes.size == BridgeProtocol.FRAME_SIZE_V1 && bytes[0] == BridgeProtocol.MAGIC &&
                bytes[1] == PROTOCOL_VERSION_V1 -> decodeV1(bytes)

            else -> null
        }
    } catch (_: IllegalArgumentException) {
        // Correct framing does not make the payload valid. BridgeFrame
        // rejects negative measurements and invalid percentages; malformed
        // radio data must be dropped, never thrown onto a BLE/CXR callback.
        null
    }

    /**
     * The fields the twenty-byte v2 prefix and the 24-byte v3 frame share.
     *
     * v3 appends the category slots after this part, so both decoders read
     * it through one function instead of walking the same offsets twice.
     */
    private data class Prefix(
        val flags: Int,
        val signal: SignalLevel,
        val timestampMillis: Long,
        val speedKmh: Float?,
        val vehicleBatteryPercent: Float?,
        val phoneBatteryPercent: Int?,
        val voltageV: Float?,
        val tripDistanceMetres: Int?,
        val tripDurationSeconds: Long?,
    )

    private fun readPrefix(buf: ByteBuffer): Prefix? {
        val flags = buf.get().toInt() and 0xFF
        val signal = SignalLevel.fromWire((flags shr 2) and 0x03) ?: return null

        // Treat sec as unsigned so the field covers up to 2106 rather
        // than sign-extending and corrupting post-2038 stamps.
        val timestampMillis = Integer.toUnsignedLong(buf.int) * 1_000L

        val sx10 = buf.short
        val speed = if (sx10 == BridgeProtocol.INT16_NULL) null else sx10 / 10f

        val vehBat = byteToPercent(buf.get())
        val phoneBat = byteToPercent(buf.get())?.roundToInt()

        val vx10 = buf.short
        val voltage = if (vx10 == BridgeProtocol.INT16_NULL) null else vx10 / 10f

        val distance = readU24(buf).takeUnless { it == BridgeProtocol.U24_NULL }
        val duration = buf.int.let {
            if (it == BridgeProtocol.INT32_NULL) null else it.toLong()
        }

        return Prefix(
            flags = flags,
            signal = signal,
            timestampMillis = timestampMillis,
            speedKmh = speed,
            vehicleBatteryPercent = vehBat,
            phoneBatteryPercent = phoneBat,
            voltageV = voltage,
            tripDistanceMetres = distance,
            tripDurationSeconds = duration,
        )
    }

    private fun frameOf(
        prefix: Prefix,
        vehicleType: VehicleType = VehicleType.UNKNOWN,
        aux1: Float? = null,
        aux2: Int? = null,
        aux3: Int? = null,
    ): BridgeFrame = BridgeFrame(
        timestampMillis = prefix.timestampMillis,
        speedKmh = prefix.speedKmh,
        vehicleBatteryPercent = prefix.vehicleBatteryPercent,
        phoneBatteryPercent = prefix.phoneBatteryPercent,
        voltageV = prefix.voltageV,
        tripDistanceMetres = prefix.tripDistanceMetres,
        tripDurationSeconds = prefix.tripDurationSeconds,
        signal = prefix.signal,
        stale = (prefix.flags and 0x01) != 0,
        ready = (prefix.flags and 0x02) != 0,
        hudHidden = (prefix.flags and FLAG_HUD_HIDDEN) != 0,
        vehicleType = vehicleType,
        aux1 = aux1,
        aux2 = aux2,
        aux3 = aux3,
    )

    private fun decodeV2(bytes: ByteArray): BridgeFrame? {
        val buf = ByteBuffer.wrap(bytes, 0, BridgeProtocol.FRAME_SIZE_V2)
            .order(ByteOrder.LITTLE_ENDIAN)
        buf.position(2)
        return frameOf(readPrefix(buf) ?: return null)
    }

    private fun decodeV3(bytes: ByteArray): BridgeFrame? {
        val buf = ByteBuffer.wrap(bytes, 0, BridgeProtocol.FRAME_SIZE_V3)
            .order(ByteOrder.LITTLE_ENDIAN)
        buf.position(2)

        val prefix = readPrefix(buf) ?: return null
        val vehicleType =
            VehicleType.fromWire((prefix.flags shr VEHICLE_TYPE_SHIFT) and 0x03) ?: return null

        val ax10 = buf.short
        val aux1 = if (ax10 == BridgeProtocol.INT16_NULL) null else ax10 / 10f
        val aux2 = buf.get().toInt().and(0xFF).takeUnless { it == BridgeProtocol.PERCENT_NULL }
        val aux3 = buf.get().toInt().and(0xFF).takeUnless { it == BridgeProtocol.PERCENT_NULL }

        return frameOf(prefix, vehicleType, aux1, aux2, aux3)
    }

    private fun decodeV1(bytes: ByteArray): BridgeFrame? {
        val buf = ByteBuffer.wrap(bytes, 0, BridgeProtocol.FRAME_SIZE_V1)
            .order(ByteOrder.LITTLE_ENDIAN)
        buf.position(2)

        val flags = buf.get().toInt() and 0xFF
        val signal = SignalLevel.fromWire(buf.get().toInt() and 0xFF) ?: return null
        val sec = buf.int
        val ms = buf.int
        if (ms !in 0..999) return null
        val timestampMillis = Integer.toUnsignedLong(sec) * 1000L + ms.toLong()

        val sx10 = buf.short
        val speed = if (sx10 == BridgeProtocol.INT16_NULL) null else sx10 / 10f

        val vehBat = byteToPercent(buf.get())
        val phoneBat = byteToPercent(buf.get())?.roundToInt()

        val vx10 = buf.short
        val voltage = if (vx10 == BridgeProtocol.INT16_NULL) null else vx10 / 10f

        val dist = buf.int.let { if (it == BridgeProtocol.INT32_NULL) null else it }
        val dur = buf.long.let { if (it == Long.MIN_VALUE) null else it }

        // Reserved bytes 30..31 must be zero per the v1 wire contract;
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

    /**
     * Distance to a 24-bit unsigned slot, mapping null, negative or
     * beyond-range values (max ~16 777 km — far beyond any single EUC
     * trip) to [BridgeProtocol.U24_NULL] instead of silently wrapping.
     */
    private fun putU24(buf: ByteBuffer, value: Int?) {
        val v = value ?: BridgeProtocol.U24_NULL
        if (v < 0 || v > BridgeProtocol.U24_NULL - 1) {
            buf.put(0xFF.toByte())
            buf.put(0xFF.toByte())
            buf.put(0xFF.toByte())
            return
        }
        buf.put((v and 0xFF).toByte())
        buf.put(((v shr 8) and 0xFF).toByte())
        buf.put(((v shr 16) and 0xFF).toByte())
    }

    private fun readU24(buf: ByteBuffer): Int {
        val b0 = buf.get().toInt() and 0xFF
        val b1 = buf.get().toInt() and 0xFF
        val b2 = buf.get().toInt() and 0xFF
        return b0 or (b1 shl 8) or (b2 shl 16)
    }

    private fun byteToPercent(b: Byte): Float? {
        val v = b.toInt() and 0xFF
        return if (v == BridgeProtocol.PERCENT_NULL) null else v.toFloat()
    }

    /**
     * Flags bit 4 — the phone asking the glasses to blank the HUD.
     *
     * Bits 0..3 carry stale, ready and the two signal bits. Bits 5-6 now
     * carry the vehicle type (see [VEHICLE_TYPE_SHIFT]); bit 7 remains
     * free for the "not fully measured" marker.
     */
    private const val FLAG_HUD_HIDDEN = 0x10

    /** Flags bits 5-6 — which kind of vehicle produced this frame. */
    private const val VEHICLE_TYPE_SHIFT = 5

    private const val PROTOCOL_VERSION_V1: Byte = 1
    private const val PROTOCOL_VERSION_V2: Byte = 2
}
