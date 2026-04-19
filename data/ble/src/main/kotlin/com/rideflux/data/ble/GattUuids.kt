/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.data.ble

import java.util.UUID

/**
 * GATT service / characteristic UUIDs and topology classification for
 * every wheel family supported by the app.
 *
 * The three topologies correspond one-to-one with sections of
 * `clean-room/spec/PROTOCOL_SPEC.md` §1:
 *
 *   * [GattTopology.SINGLE_CHAR] — §1.1 first table. One primary
 *     service `FFE0`, one notify+write characteristic `FFE1`. Used by
 *     Family G (Begode), Family K (KingSong) and Family N1 (Ninebot
 *     One/E+/S2/Mini).
 *
 *   * [GattTopology.SPLIT_CHAR] — §1.1 second table. Notify service
 *     `FFE0` + characteristic `FFE4`, write service `FFE5` +
 *     characteristic `FFE9`. Used by Family I1 (legacy Inmotion
 *     V5/V8/V10).
 *
 *   * [GattTopology.NORDIC_UART] — §1.2. Nordic UART service
 *     `6E400001…`, TX `…0002` (write), RX `…0003` (notify). Used by
 *     Family N2 (Ninebot Z / KickScooter Z) and Family I2 (current
 *     Inmotion V9/V11/V12/V13/V14).
 */
internal object GattUuids {

    // ---- §1.1 single-characteristic (G / K / N1) -----------------------

    val SERVICE_FFE0: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
    val CHAR_FFE1: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")

    // ---- §1.1 split (I1) -----------------------------------------------

    val CHAR_FFE4: UUID = UUID.fromString("0000ffe4-0000-1000-8000-00805f9b34fb")
    val SERVICE_FFE5: UUID = UUID.fromString("0000ffe5-0000-1000-8000-00805f9b34fb")
    val CHAR_FFE9: UUID = UUID.fromString("0000ffe9-0000-1000-8000-00805f9b34fb")

    // ---- §1.2 Nordic UART (N2 / I2) ------------------------------------

    val SERVICE_NUS: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
    val CHAR_NUS_TX: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
    val CHAR_NUS_RX: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")

    // ---- CCC descriptor shared by every family -------------------------

    val DESCRIPTOR_CCC: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** The union of all primary service UUIDs we advertise-filter on during scan. */
    val ALL_PRIMARY_SERVICES: Set<UUID> = setOf(SERVICE_FFE0, SERVICE_FFE5, SERVICE_NUS)
}

/** Three GATT topologies defined in §1.1 and §1.2. */
internal enum class GattTopology { SINGLE_CHAR, SPLIT_CHAR, NORDIC_UART }
