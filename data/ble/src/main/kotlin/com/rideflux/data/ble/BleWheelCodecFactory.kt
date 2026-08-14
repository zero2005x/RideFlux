/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.data.ble

import com.rideflux.domain.codec.WheelCodec
import com.rideflux.domain.codec.WheelCodecFactory
import com.rideflux.domain.wheel.WheelFamily

/**
 * The `:data:ble` view of [WheelCodecFactory].
 *
 * The domain interface deliberately knows nothing about GATT, but this
 * module needs two extra pieces of information that cannot live in
 * `:domain` without dragging BLE types into it: the device MAC to
 * thread into the codec, and the family's GATT topology.
 *
 * Consumers in this module (notably [WheelRepositoryImpl]) depend on
 * this interface rather than on [WheelCodecFactoryImpl] directly, so
 * the DI binding is meaningful and tests can substitute a fake codec
 * stack without touching the Hilt module.
 */
interface BleWheelCodecFactory : WheelCodecFactory {

    /**
     * [WheelCodecFactory.forFamily] variant that threads the device MAC
     * into the codec, so that
     * [com.rideflux.domain.wheel.WheelIdentity.address] in
     * `DecodeEvent.Identified` matches the scanned advertisement.
     *
     * Implementations must reject a blank [address] rather than
     * emitting identity payloads with an empty MAC.
     */
    fun forFamilyWithAddress(family: WheelFamily, address: String): WheelCodec

    /**
     * Full advertisement-level family hint: the advertised local name
     * first, the advertised GATT service UUIDs as the fallback.
     *
     * [WheelCodecFactory.inferFromGattServiceUuids] alone cannot see
     * families whose boards advertise no service UUID at all — Inmotion
     * legacy (V5 / V8 / V10) is the case that broke scanning, since it
     * exposes `FFE0` + `FFE5` only *after* service discovery and puts
     * nothing but its local name in the advertising PDU. Scan paths
     * should call this rather than the UUID-only overload.
     *
     * Still a hint: the family is confirmed by the bootstrap handshake.
     */
    fun inferFromAdvertisement(deviceName: String?, serviceUuids: Set<String>): WheelFamily?

    /** Classify the GATT topology a given family uses (§1.1 / §1.2). */
    fun topologyFor(family: WheelFamily): GattTopology
}
