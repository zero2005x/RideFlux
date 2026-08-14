/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.domain.wheel

import java.util.Locale

/**
 * Resolves a [WheelFamily] from the BLE advertised local name.
 *
 * ### Why a name classifier exists at all
 * The advertising PDU is capped at 31 bytes, so most wheel mainboards
 * spend it on the local name and manufacturer data and never include a
 * 16-bit service UUID. Inmotion legacy boards (V5 / V8 / V10) are the
 * clearest example: they expose `FFE0` + `FFE5` over GATT but advertise
 * *neither*, so a UUID-only resolver
 * ([com.rideflux.domain.codec.WheelCodecFactory.inferFromGattServiceUuids])
 * returns `null` for them and the wheel is either dropped by the scan
 * filter or reaches `connect()` with no family to route on.
 *
 * The advertised name is the only pre-connection signal those boards
 * give us, and it is stable per model line, so it is matched first and
 * the UUID set is used as the fallback.
 *
 * ### Confidence
 * Every rule here is a *hint*, exactly like the UUID resolver: the true
 * family is only confirmed by the family's bootstrap handshake (§9).
 * Rules are deliberately narrow — a name that does not match a known
 * model line returns `null` so the caller falls back to UUID inference
 * rather than acting on a guess. Vendors that share a naming space with
 * another family (e.g. Veteran boards that ship advertising themselves
 * as `GotWay`) are intentionally *not* matched here; only the
 * unambiguous names are claimed.
 */
object WheelNameClassifier {

    /**
     * Best-guess family for an advertised local name, or `null` when
     * the name matches no known model line.
     *
     * Matching is case-insensitive and tolerates the separator styles
     * seen in the wild (`V5F-2A4AC0`, `INMOTION V8F`, `KS-16X`).
     */
    fun classify(advertisedName: String?): WheelFamily? {
        val name = advertisedName?.trim()?.uppercase(Locale.ROOT).orEmpty()
        if (name.isEmpty()) return null

        inmotionFamily(name)?.let { return it }

        return when {
            // KingSong: "KS-16X", "KS18L", "KingSong xxxx".
            name.contains("KINGSONG") -> WheelFamily.K
            KINGSONG_MODEL.containsMatchIn(name) -> WheelFamily.K

            // Veteran: only the model names that no other vendor uses.
            // Veteran boards that advertise as "GotWay" are left to the
            // UUID resolver — a wrong claim here would route a Begode
            // wheel through the Veteran decoder.
            VETERAN_MODEL.containsMatchIn(name) -> WheelFamily.V

            // Ninebot: the Z / KickScooter Z line speaks §2.5 (N2),
            // every other Ninebot One / E+ / S2 / Mini speaks §2.4 (N1).
            name.contains("NINEBOT") || name.startsWith("NB") ->
                if (NINEBOT_Z_MODEL.containsMatchIn(name)) WheelFamily.N2 else WheelFamily.N1

            // Begode / Gotway / ExtremeBull. Same answer the UUID
            // resolver already gives for an FFE0-only advertisement, so
            // this rule costs nothing and makes the intent explicit.
            name.contains("GOTWAY") ||
                name.contains("BEGODE") ||
                name.contains("EXTREMEBULL") -> WheelFamily.G

            else -> null
        }
    }

    /**
     * Inmotion model-number split: the legacy escape-byte protocol
     * (§2.6, [WheelFamily.I1]) versus the current XOR-check protocol
     * (§2.7, [WheelFamily.I2]).
     *
     * The model number is the only thing that distinguishes them — a
     * `V8F` and a `V11` advertise names of identical shape — so the
     * number is parsed rather than prefix-matched. An unrecognised
     * number returns `null` instead of defaulting to either protocol.
     */
    private fun inmotionFamily(name: String): WheelFamily? {
        val model = INMOTION_MODEL.find(name)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
            ?: return null
        return when (model) {
            5, 8, 10 -> WheelFamily.I1
            9, 11, 12, 13, 14 -> WheelFamily.I2
            else -> null
        }
    }

    /**
     * `V` + model number at the start of the name, with an optional
     * `INMOTION` vendor prefix: `V5F-2A4AC0`, `V10F`, `INMOTION V11`.
     *
     * The trailing `(?!\d)` pins the number to the *whole* run of
     * digits. Without it, greedy matching would read `V101…` as model
     * 10 and route a device that is not an Inmotion at all through the
     * legacy decoder; failing to match is the safe outcome because the
     * caller then falls back to UUID inference.
     */
    private val INMOTION_MODEL = Regex("""^(?:INMOTION[\s_-]*)?V(\d{1,2})(?!\d)""")

    /** `KS-16X`, `KS16S`, `KS_18L` — the KingSong model-number form. */
    private val KINGSONG_MODEL = Regex("""^KS[\s_-]?\d""")

    /** Veteran model names that are unambiguous across vendors. */
    private val VETERAN_MODEL =
        Regex("""VETERAN|SHERMAN|ABRAMS|PATTON|LYNX|ORYX|NOSFET""")

    /** Ninebot Z / ZT / KickScooter Z, e.g. "Ninebot Z10", "NBZ". */
    private val NINEBOT_Z_MODEL = Regex("""(^|[\s_-])Z(\d|T|$)|NBZ""")
}
