/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.domain.wheel

/**
 * Wire-protocol family of an electric-unicycle mainboard.
 *
 * Values correspond one-to-one with the family keys defined in
 * `clean-room/spec/PROTOCOL_SPEC.md` §0 and are the single source of
 * truth used by the domain layer to route to a specific
 * [com.rideflux.domain.codec.WheelCodec] implementation.
 *
 * A family is an immutable property of a discovered device. It is
 * resolved once, during the identification handshake of the relevant
 * family (§9.*), and does not change for the lifetime of a
 * [com.rideflux.domain.connection.WheelConnection].
 */
enum class WheelFamily {
    /** Begode / Gotway / ExtremeBull — §2.1 serial byte stream. */
    G,
    /** Begode Extended (dual-BMS) — §2.1 + smart-BMS pages §3.1.x. */
    GX,
    /** KingSong — §2.2 fixed 20-byte frames. */
    K,
    /** Veteran (Sherman / Abrams / Patton / Lynx / Oryx / Nosfet / …) — §2.3. */
    V,
    /** Ninebot One / E+ / S2 / Mini — §2.4 short CAN-like with zero keystream. */
    N1,
    /** Ninebot Z / ZT / KickScooter Z — §2.5 long CAN-like with session key. */
    N2,
    /** Inmotion legacy (V5 / V8 / V10 / …) — §2.6 escape-byte framing. */
    I1,
    /** Inmotion current (V9 / V11 / V12 / V13 / V14) — §2.7 XOR-check framing. */
    I2,
}
