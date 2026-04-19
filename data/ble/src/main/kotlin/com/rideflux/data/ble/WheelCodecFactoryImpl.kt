/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.data.ble

import com.rideflux.domain.codec.WheelCodec
import com.rideflux.domain.codec.WheelCodecFactory
import com.rideflux.domain.wheel.WheelFamily
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
 * ### `inferFromGattServiceUuids`
 * Hint resolver used at scan time. The mapping follows
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
class WheelCodecFactoryImpl : WheelCodecFactory {

    override fun forFamily(family: WheelFamily): WheelCodec =
        forFamilyWithAddress(family, address = "")

    /**
     * [forFamily] variant that threads the device MAC into the
     * codec. `:data:ble` uses this internally so that
     * [com.rideflux.domain.wheel.WheelIdentity.address] in
     * `DecodeEvent.Identified` matches the scanned advertisement.
     */
    fun forFamilyWithAddress(family: WheelFamily, address: String): WheelCodec =
        when (family) {
            WheelFamily.G, WheelFamily.GX -> BegodeWheelCodec(deviceAddress = address)
            WheelFamily.K -> KingSongWheelCodec(deviceAddress = address)
            WheelFamily.V -> VeteranWheelCodec(deviceAddress = address)
            WheelFamily.N1 -> NinebotN1WheelCodec(deviceAddress = address)
            WheelFamily.N2 -> NinebotN2WheelCodec(deviceAddress = address)
            WheelFamily.I1 -> InmotionI1WheelCodec(deviceAddress = address)
            WheelFamily.I2 -> InmotionI2WheelCodec(deviceAddress = address)
        }

    override fun inferFromGattServiceUuids(uuids: Set<String>): WheelFamily? {
        val normalised = uuids.asSequence()
            .map { it.lowercase(Locale.ROOT) }
            .toSet()
        val hasFfe0 = GattUuids.SERVICE_FFE0.toString() in normalised
        val hasFfe5 = GattUuids.SERVICE_FFE5.toString() in normalised
        val hasNus = GattUuids.SERVICE_NUS.toString() in normalised
        return when {
            hasFfe0 && hasFfe5 -> WheelFamily.I1
            hasNus -> WheelFamily.I2
            hasFfe0 -> WheelFamily.G
            else -> null
        }
    }

    /** Classify the GATT topology a given family uses (§1.1 / §1.2). */
    internal fun topologyFor(family: WheelFamily): GattTopology = when (family) {
        WheelFamily.G, WheelFamily.GX,
        WheelFamily.K, WheelFamily.N1 -> GattTopology.SINGLE_CHAR
        WheelFamily.I1 -> GattTopology.SPLIT_CHAR
        WheelFamily.N2, WheelFamily.I2 -> GattTopology.NORDIC_UART
        WheelFamily.V -> GattTopology.SINGLE_CHAR // V uses the same FFE0/FFE1 single-char link.
    }
}
