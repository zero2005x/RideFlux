/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.data.bridge

import java.util.UUID

/**
 * Constants & UUIDs that define the phone-to-glasses bridge GATT
 * service. Identical strings on both sides, kept in one place so the
 * protocol is impossible to skew.
 *
 * Wire format: see [BridgeFrame] / [BridgeCodec]. The current (v2)
 * frame is a 20-byte little-endian binary blob — deliberately small
 * enough to fit the default ATT MTU of 23 bytes (20 bytes of payload
 * after the 3-byte ATT header), so one notification always carries a
 * complete frame even when no MTU exchange happens at all. The
 * client still requests [PREFERRED_MTU] as headroom for future
 * versions, but correctness never depends on it.
 *
 * The 32-byte v1 layout is retained *decode-only* so a freshly
 * updated glasses app still understands frames from a phone still
 * running the previous release during a mixed-install upgrade
 * window. The phone (server) side only ever encodes v2.
 *
 * UUIDs are randomly generated v4 values reserved for RideFlux. Do
 * not change without bumping [PROTOCOL_VERSION] and the matching
 * client expectation.
 */
object BridgeProtocol {

    /** Official Rokid CXR message channel carrying the same v1 payload. */
    const val CXR_TELEMETRY_CHANNEL: String = "rideflux.telemetry.v1"

    /**
     * Custom 128-bit service UUID advertised by the phone. The HUD
     * scans for this exact UUID to filter out unrelated peripherals.
     */
    val SERVICE_UUID: UUID = UUID.fromString("e7810a71-73ae-499d-8c15-faa9aef0c3f2")

    /**
     * Notify-only characteristic carrying [BridgeFrame] payloads,
     * one notification per frame. Cadence matches the wheel's frame
     * cadence (~5 Hz for Family-G).
     */
    val TELEMETRY_CHAR_UUID: UUID = UUID.fromString("e7810a72-73ae-499d-8c15-faa9aef0c3f2")

    /**
     * Standard Client Characteristic Configuration Descriptor used to
     * enable / disable notifications. Defined by the Bluetooth SIG.
     */
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /**
     * Length of the pairing token carried as BLE service data under
     * [SERVICE_UUID].
     *
     * ### Why a token and not a MAC address
     * Android advertises with a *resolvable private address* that the
     * controller rotates (~15 min by convention) and that changes again
     * on every Bluetooth restart. A MAC captured during pairing is
     * therefore not an identity: it silently stops matching a few
     * minutes later, which is exactly what an allowlist keyed on
     * `ScanResult.device.address` used to do. The phone instead mints a
     * random token once, persists it, and advertises it; the glasses
     * store that token at pairing time and match on it, so address
     * rotation is irrelevant.
     *
     * ### Where it rides on the wire
     * The primary advertisement stays exactly as it was — flags (3 B)
     * plus the 128-bit [SERVICE_UUID] (18 B) — so hardware scan filters
     * and older glasses builds keep working. Service data with a
     * 128-bit UUID costs 2 + 16 + 8 = 26 bytes, which does not fit
     * alongside the UUID in the same 31-byte advertisement, so the
     * token travels in the **scan response** instead. Android merges
     * the advertisement and the scan response into a single
     * `ScanRecord` for legacy advertising, so the receiver reads it
     * through `ScanRecord.getServiceData(SERVICE_UUID)` without caring
     * which PDU carried it.
     *
     * Eight bytes is not a secret — BLE advertisements are public and
     * trivially replayed. It is a *stable name*, sized so two phones in
     * the same car park effectively never collide. Defence against a
     * deliberately spoofed peer needs LE bonding
     * ([BridgePeerFilter.Bonded]), which is tracked separately.
     */
    const val PAIRING_TOKEN_SIZE: Int = 8

    /** First byte of every [BridgeFrame] — magic that screens out non-RideFlux notifications. */
    const val MAGIC: Byte = 0x52  // 'R'

    /** Increment when the wire format becomes incompatible. */
    const val PROTOCOL_VERSION: Byte = 2

    /**
     * Length of a v2 [BridgeFrame] when serialised — 20 bytes, so it
     * fits the 20-byte payload of the default 23-byte ATT MTU. See
     * [BridgeCodec].
     */
    const val FRAME_SIZE_V2: Int = 20

    /**
     * Length of a v1 [BridgeFrame]. Kept only so [BridgeCodec] can
     * decode frames emitted by a phone still running the previous
     * release; the server no longer encodes this layout.
     */
    const val FRAME_SIZE_V1: Int = 32

    /**
     * Preferred ATT MTU.
     *
     * In BLE only the GATT client (central) can initiate an MTU
     * exchange, so it is [BridgeClient] that calls `requestMtu` — the
     * phone-side GATT server merely accepts whatever is negotiated.
     * v2 frames fit the default MTU, so a failed or silently dropped
     * exchange never prevents telemetry from flowing; the larger MTU
     * is requested only as headroom for future protocol versions.
     */
    const val PREFERRED_MTU: Int = 64

    /** Sentinel used by codec to encode "no value" for a u8 percent slot. */
    const val PERCENT_NULL: Int = 0xFF

    /** Sentinel used by codec to encode "no value" for an i32 slot. */
    const val INT32_NULL: Int = Int.MIN_VALUE

    /** Sentinel used by codec to encode "no value" for the u24 distance slot. */
    const val U24_NULL: Int = 0xFF_FFFF

    /**
     * Sentinel used by codec to encode "no value" for an i16 slot.
     *
     * Declared as a `Short` so the narrowing is explicit: as an `Int`
     * the decode-side comparison relied on implicit sign-extension of
     * `buf.short.toInt()`, and the encode side clamped valid samples
     * into an *inclusive* range that did not reserve the sentinel — a
     * legitimate sample rounding to exactly -32768 was written as
     * "absent" and decoded back as `null`. Encoders must reserve this
     * value (see [BridgeCodec]).
     */
    const val INT16_NULL: Short = Short.MIN_VALUE
}
