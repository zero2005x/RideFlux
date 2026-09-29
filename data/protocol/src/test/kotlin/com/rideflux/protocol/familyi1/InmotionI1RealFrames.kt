/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.protocol.familyi1

import com.rideflux.protocol.testutil.hex

/**
 * Complete family I1 live-telemetry frames as they appeared on the wire, copied from raw
 * serial captures of a V5F published with another open-source app's tests (the capture rows
 * are not frame-aligned; these are the rows of one frame joined). Every frame is
 * `AA AA` + escaped body + escaped CHECK + `55 55`, carries CAN id `0x0F550113` and 96 bytes of
 * extended data.
 *
 * The wheel was standing still on a bench, so the values are small and only the sign and the
 * framing are informative, not the physical quantities.
 */
internal object InmotionI1RealFrames {

    /** CHECK = 0x77, sent as is. */
    val plainCheck: ByteArray = hex(
        "aaaa1301a5550f60000000b4720020fe000100ca" +
            "e9ffffd28f010004000000000000000000000000" +
            "000000141d0000000000001b1b00000000000000" +
            "000000636f0d0000000000003a0b000702e50721" +
            "0000005dffffff5dffffff32fdffffb1ffffff0b" +
            "010000070000000000000000000000775555",
    )

    /** CHECK = 0x55, which is one of the three escaped values, so it is sent as `A5 55`. */
    val escapedCheck: ByteArray = hex(
        "aaaa1301a5550f60000000b4720020fe000100e3" +
            "e8ffffa0fe0100f1ffffff000000000000000000" +
            "000000141d0000000000001b1b00000000000000" +
            "000000636f0d0000000000003a0b000702e50721" +
            "0000004fffffff4fffffff31fdffffccffffffad" +
            "000000070000000000000000000000a5555555",
    )

    /** Pitch, roll and both speed components negative. */
    val negativeAngles: ByteArray = hex(
        "aaaa1301a5550f60000000b4720020fe0001006cf0ffff91660000fcffffff21fbffff21fbffff" +
            "07000000141d0000000000001b1b00000000000000000000616f0d000000000000340b000702" +
            "e5072100000094ffffff94ffffffb6ffffffe0fcffffa7fdffff070000000000000000000000" +
            "935555",
    )

    /** Pitch negative, roll positive (+50), speed components positive. */
    val positiveRoll: ByteArray = hex(
        "aaaa1301a5550f60000000b4720020fe000100e0faffff40630900d8feffff6506000065060000" +
            "00000000141d0000000000001b1b00000000000000000000626f0d000000000000360b000702" +
            "e507210000001c0000001c00000032000000880400006e010000070000000000000000000000" +
            "535555",
    )

    /** Pitch positive (+1497), roll negative. */
    val positivePitch: ByteArray = hex(
        "aaaa1301a5550f60000000b4720020fe000100d90500006017010085ffffffa7fcffffa7fcffff" +
            "00000000141d0000000000001b1b00000000000000000000616f0d000000000000350b000702" +
            "e50721000000a9ffffffa9ffffffbdfdffffa3f8ffff35020000070000000000000000000000" +
            "ac5555",
    )

    val all: List<ByteArray> = listOf(plainCheck, escapedCheck, negativeAngles, positiveRoll, positivePitch)
}
