/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.data.ble

import com.rideflux.domain.codec.WheelCodec
import com.rideflux.domain.codec.WheelCodecFactory
import com.rideflux.domain.wheel.WheelFamily
import com.rideflux.domain.wheel.WheelNameClassifier
import com.rideflux.protocol.familyg.BegodeWheelCodec
import com.rideflux.protocol.familyi1.InmotionI1WheelCodec
import com.rideflux.protocol.familyi2.InmotionI2WheelCodec
import com.rideflux.protocol.familyk.KingSongWheelCodec
import com.rideflux.protocol.familyn.NinebotN1WheelCodec
import com.rideflux.protocol.familyn.NinebotN2WheelCodec
import com.rideflux.protocol.familyv.VeteranWheelCodec
import java.util.Locale

/**
 * Default [WheelCodecFactory] for the app process.
 *
 * ### `forFamily`
 * Pure mapping from [WheelFamily] → the family's concrete [WheelCodec]
 * implementation in `:data:protocol`. The `deviceAddress` passed into
 * each constructor is threaded through from the caller (see
 * [forFamilyWithAddress]) so that codec state objects can carry the
 * GATT MAC in their logs / identified-event payloads.
 *
 * ### `inferFromAdvertisement`
 * The resolver scan paths should use: it consults
 * [WheelNameClassifier] first and only falls back to
 * [inferFromGattServiceUuids]. Boards that advertise no service UUID
 * at all (Inmotion legacy V5 / V8 / V10) are invisible to the UUID
 * table below and are resolved by name alone.
 *
 * ### `inferFromGattServiceUuids`
 * UUID-only hint resolver. The mapping follows
 * `PROTOCOL_SPEC.md` §1.1 and §1.2:
 *
 * | Advertised services                                        | Best-guess family |
 * |------------------------------------------------------------|-------------------|
 * | `FFE0` + `FFE5` (split profile)                            | [WheelFamily.I1]  |
 * | `FFE0` alone (single-characteristic profile)               | [WheelFamily.G]   |
 * | Nordic UART `6E400001…`                                    | [WheelFamily.I2]  |
 * | nothing recognised                                         | `null`            |
 *
 * The single-char and Nordic-UART profiles are shared by more than one
 * family (G/K/N1 and I2/N2 respectively), so the returned family is
 * necessarily a guess — the true family is only confirmed after the
 * bootstrap handshake of §9. Callers that already know the family
 * MUST pass `expectedFamily` to
 * [com.rideflux.domain.repository.WheelRepository.connect].
 */
class WheelCodecFactoryImpl : BleWheelCodecFactory {

    /**
     * Not supported: every codec produced here carries the device MAC
     * so that `DecodeEvent.Identified` payloads are attributable.
     *
     * The address-less domain overload used to silently pass `""`,
     * which produced codecs whose identified events carried a blank MAC
     * with no warning — a silent data-integrity failure for any
     * consumer that went through the interface. Fail fast instead;
     * `:data:ble` callers must use [forFamilyWithAddress].
     */
    override fun forFamily(family: WheelFamily): WheelCodec =
        throw UnsupportedOperationException(
            "WheelCodecFactoryImpl requires a device address; " +
                "call forFamilyWithAddress(family, address) instead",
        )

    override fun forFamilyWithAddress(family: WheelFamily, address: String): WheelCodec {
        require(address.isNotBlank()) { "address must not be blank" }
        return when (family) {
            WheelFamily.G, WheelFamily.GX -> BegodeWheelCodec(deviceAddress = address)
            WheelFamily.K -> KingSongWheelCodec(deviceAddress = address)
            WheelFamily.V -> VeteranWheelCodec(deviceAddress = address)
            WheelFamily.N1 -> NinebotN1WheelCodec(deviceAddress = address)
            WheelFamily.N2 -> NinebotN2WheelCodec(deviceAddress = address)
            WheelFamily.I1 -> InmotionI1WheelCodec(deviceAddress = address)
            WheelFamily.I2 -> InmotionI2WheelCodec(deviceAddress = address)
        }
    }

    override fun inferFromAdvertisement(
        deviceName: String?,
        serviceUuids: Set<String>,
    ): WheelFamily? =
        // Name first: it is the only signal a board that advertises no
        // service UUID gives us before connecting, and where both
        // signals exist the name is the more specific of the two (the
        // UUID set cannot tell K from G, or I2 from N2).
        WheelNameClassifier.classify(deviceName)
            ?: inferFromGattServiceUuids(serviceUuids)

    override fun inferFromGattServiceUuids(uuids: Set<String>): WheelFamily? {
        val normalised = uuids.asSequence()
            .map(::canonicaliseUuid)
            .toSet()
        val hasFfe0 = FFE0 in normalised
        val hasFfe5 = FFE5 in normalised
        val hasNus = NUS in normalised
        return when {
            hasFfe0 && hasFfe5 -> WheelFamily.I1
            // FFE0 is checked before NUS per the precedence table in the
            // class KDoc (FFE0-alone → G is listed before NUS → I2), so a
            // device advertising both NUS and FFE0 is classified as G.
            hasFfe0 -> WheelFamily.G
            hasNus -> WheelFamily.I2
            else -> null
        }
    }

    /** Classify the GATT topology a given family uses (§1.1 / §1.2). */
    override fun topologyFor(family: WheelFamily): GattTopology = when (family) {
        WheelFamily.G, WheelFamily.GX,
        WheelFamily.K, WheelFamily.N1 -> GattTopology.SINGLE_CHAR
        WheelFamily.I1 -> GattTopology.SPLIT_CHAR
        WheelFamily.N2, WheelFamily.I2 -> GattTopology.NORDIC_UART
        WheelFamily.V -> GattTopology.SINGLE_CHAR // V uses the same FFE0/FFE1 single-char link.
    }

    private companion object {
        // Canonical, lower-cased 128-bit forms computed once. The
        // constant side needs normalising too: java.util.UUID.toString()
        // casing is not guaranteed across platforms (JVM emits
        // lowercase, Android has historically emitted uppercase), so a
        // case-sensitive comparison would silently fail on-device.
        val FFE0: String = canonicaliseUuid(GattUuids.SERVICE_FFE0.toString())
        val FFE5: String = canonicaliseUuid(GattUuids.SERVICE_FFE5.toString())
        val NUS: String = canonicaliseUuid(GattUuids.SERVICE_NUS.toString())

        /** Bluetooth SIG base UUID; 16- and 32-bit forms expand into it. */
        private const val BASE_SUFFIX = "-0000-1000-8000-00805f9b34fb"

        /**
         * Expand a Bluetooth UUID to its canonical lower-case 128-bit
         * string.
         *
         * The interface accepts arbitrary strings, and BLE stacks and
         * scan records routinely use the short 16-bit (`"ffe0"`) or
         * 32-bit forms. Comparing those against a full
         * `UUID.toString()` always missed, so an advertised service
         * could go unrecognised and the family resolve to `null`.
         */
        fun canonicaliseUuid(raw: String): String {
            val s = raw.trim().removePrefix("0x").removePrefix("0X").lowercase(Locale.ROOT)
            return when (s.length) {
                4 -> "0000$s$BASE_SUFFIX"
                8 -> "$s$BASE_SUFFIX"
                else -> s
            }
        }
    }
}
