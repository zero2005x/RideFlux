/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.data.ble

import com.rideflux.domain.wheel.WheelFamily
import com.rideflux.domain.wheel.WheelNameClassifier
import java.util.Locale
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T02 rework: the post-connect resolver must keep the pre-T02 precedence —
 * the I1 split profile first, then Nordic UART — ahead of the GATT signature
 * detector.
 *
 * The detector only knows the `FFE0`/`FFE1` single-char rows, so on a table
 * that also carries NUS (or the I1 split) it answers `PROBABLE` G/K/V from
 * the device name and would bypass the earlier branches. Both halves below
 * pin the legacy answer on exactly those tables: full NUS resolves to N2
 * (I2 for an Inmotion name) and a split table that also carries `FFE1`
 * resolves to I1, whatever G/K/V name the board advertises.
 */
class LegacyPrecedenceRegressionTest {

    private val factory = WheelCodecFactoryImpl()

    private val singleCharPlusNus = mapOf(
        GattUuids.SERVICE_FFE0 to listOf(GattUuids.CHAR_FFE1),
        GattUuids.SERVICE_NUS to listOf(GattUuids.CHAR_NUS_RX, GattUuids.CHAR_NUS_TX),
    )

    private val splitPlusSingleChar = mapOf(
        GattUuids.SERVICE_FFE0 to listOf(GattUuids.CHAR_FFE4, GattUuids.CHAR_FFE1),
        GattUuids.SERVICE_FFE5 to listOf(GattUuids.CHAR_FFE9),
    )

    @Test
    fun `full NUS plus FFE0-FFE1 with a Begode name still resolves to N2`() {
        assertEquals(WheelFamily.N2, factory.inferFromGattTable(singleCharPlusNus, name = "Begode A2"))
    }

    @Test
    fun `full NUS plus FFE0-FFE1 with a KingSong name still resolves to N2`() {
        assertEquals(WheelFamily.N2, factory.inferFromGattTable(singleCharPlusNus, name = "KS-16X"))
    }

    @Test
    fun `full NUS plus FFE0-FFE1 with a Veteran name still resolves to N2`() {
        assertEquals(
            WheelFamily.N2,
            factory.inferFromGattTable(singleCharPlusNus, name = "Veteran Sherman-S"),
        )
    }

    @Test
    fun `full NUS plus FFE0-FFE1 with an Inmotion name still resolves to I2`() {
        assertEquals(WheelFamily.I2, factory.inferFromGattTable(singleCharPlusNus, name = "V14"))
    }

    @Test
    fun `I1 split that also carries FFE1 with a Begode name still resolves to I1`() {
        assertEquals(WheelFamily.I1, factory.inferFromGattTable(splitPlusSingleChar, name = "Begode A2"))
    }

    @Test
    fun `I1 split that also carries FFE1 with a KingSong name still resolves to I1`() {
        assertEquals(WheelFamily.I1, factory.inferFromGattTable(splitPlusSingleChar, name = "KS-16X"))
    }

    @Test
    fun `I1 split that also carries FFE1 with a Veteran name still resolves to I1`() {
        assertEquals(
            WheelFamily.I1,
            factory.inferFromGattTable(splitPlusSingleChar, name = "Veteran Sherman-S"),
        )
    }

    /**
     * Every table below, with every name below it, must resolve through
     * [WheelCodecFactoryImpl.inferFromGattTable] exactly as the pre-T02
     * inference did. The pre-T02 copy lives only in this test so production
     * keeps a single implementation; it is a verbatim copy of the fallback
     * as merged in T02, before the rework reordered the call.
     */
    @Test
    fun `the resolver agrees with the verbatim pre-T02 inference on every probed table`() {
        val a2 = mapOf(GattUuids.SERVICE_FFE0 to listOf(GattUuids.CHAR_FFE1))
        val nusOnly = mapOf(
            GattUuids.SERVICE_NUS to listOf(GattUuids.CHAR_NUS_RX, GattUuids.CHAR_NUS_TX),
        )
        val splitOnly = mapOf(
            GattUuids.SERVICE_FFE0 to listOf(GattUuids.CHAR_FFE4),
            GattUuids.SERVICE_FFE5 to listOf(GattUuids.CHAR_FFE9),
        )
        val partialNus = mapOf(
            GattUuids.SERVICE_FFE0 to listOf(GattUuids.CHAR_FFE1),
            GattUuids.SERVICE_NUS to listOf(GattUuids.CHAR_NUS_RX),
        )
        val ffe4WithoutSplit = mapOf(
            GattUuids.SERVICE_FFE0 to listOf(GattUuids.CHAR_FFE4, GattUuids.CHAR_FFE1),
        )
        val everything = mapOf(
            GattUuids.SERVICE_FFE0 to listOf(GattUuids.CHAR_FFE4, GattUuids.CHAR_FFE1),
            GattUuids.SERVICE_FFE5 to listOf(GattUuids.CHAR_FFE9),
            GattUuids.SERVICE_NUS to listOf(GattUuids.CHAR_NUS_RX, GattUuids.CHAR_NUS_TX),
        )
        val unrelated = mapOf(GattUuids.SERVICE_FE95 to listOf(GattUuids.CHAR_MI_UPNP))

        val tables = listOf(
            "a2" to a2,
            "singleCharPlusNus" to singleCharPlusNus,
            "nusOnly" to nusOnly,
            "splitOnly" to splitOnly,
            "splitPlusSingleChar" to splitPlusSingleChar,
            "partialNus" to partialNus,
            "ffe4WithoutSplit" to ffe4WithoutSplit,
            "everything" to everything,
            "unrelated" to unrelated,
            "empty" to emptyMap(),
        )
        val names = listOf(
            null,
            "Begode A2",
            "KS-16X",
            "Veteran Sherman-S",
            "V14",
            "NINEBOT E+",
            "unrecognised board",
        )

        for ((label, table) in tables) {
            for (name in names) {
                assertEquals(
                    "table=$label name=$name",
                    verbatimPreT02Inference(table, name),
                    factory.inferFromGattTable(table, name),
                )
            }
        }
    }

    /**
     * Verbatim copy of the pre-T02 GATT table inference (the fallback as
     * merged in T02): I1 split first, then Nordic UART, then the single-FFE1
     * name-hint branch. Test-only; production must not drift from it.
     */
    private fun verbatimPreT02Inference(
        services: Map<UUID, List<UUID>>,
        name: String?,
    ): WheelFamily? {
        val table = services.mapKeys { verbatimCanonicalise(it.key.toString()) }
            .mapValues { (_, chars) -> chars.map { verbatimCanonicalise(it.toString()) }.toSet() }
        val nameHint = WheelNameClassifier.classify(name)

        val ffe0Chars = table[LEGACY_FFE0].orEmpty()
        val ffe5Chars = table[LEGACY_FFE5].orEmpty()
        val hasSplitI1 = LEGACY_FFE4 in ffe0Chars && LEGACY_FFE9 in ffe5Chars
        if (hasSplitI1) return WheelFamily.I1

        val nusChars = table[LEGACY_NUS].orEmpty()
        val hasNus = LEGACY_NUS_RX in nusChars && LEGACY_NUS_TX in nusChars
        if (hasNus) {
            return if (nameHint == WheelFamily.I2) WheelFamily.I2 else WheelFamily.N2
        }

        val hasSingle = LEGACY_FFE1 in ffe0Chars
        if (hasSingle) {
            return when (nameHint) {
                WheelFamily.K -> WheelFamily.K
                WheelFamily.V -> WheelFamily.V
                WheelFamily.N1, WheelFamily.N2 -> WheelFamily.N1
                WheelFamily.G, WheelFamily.GX -> WheelFamily.G
                else -> WheelFamily.G
            }
        }
        return null
    }

    private companion object {
        val LEGACY_FFE0: String = verbatimCanonicalise(GattUuids.SERVICE_FFE0.toString())
        val LEGACY_FFE1: String = verbatimCanonicalise(GattUuids.CHAR_FFE1.toString())
        val LEGACY_FFE4: String = verbatimCanonicalise(GattUuids.CHAR_FFE4.toString())
        val LEGACY_FFE5: String = verbatimCanonicalise(GattUuids.SERVICE_FFE5.toString())
        val LEGACY_FFE9: String = verbatimCanonicalise(GattUuids.CHAR_FFE9.toString())
        val LEGACY_NUS: String = verbatimCanonicalise(GattUuids.SERVICE_NUS.toString())
        val LEGACY_NUS_RX: String = verbatimCanonicalise(GattUuids.CHAR_NUS_RX.toString())
        val LEGACY_NUS_TX: String = verbatimCanonicalise(GattUuids.CHAR_NUS_TX.toString())
    }
}

/**
 * Verbatim copy of the production UUID normaliser, so the pre-T02 copy above
 * compares exactly what production compares. File-private: only this test's
 * verbatim copy may use it.
 */
private fun verbatimCanonicalise(raw: String): String {
    val s = raw.trim().removePrefix("0x").removePrefix("0X").lowercase(Locale.ROOT)
    return when (s.length) {
        4 -> "0000$s-0000-1000-8000-00805f9b34fb"
        8 -> "$s-0000-1000-8000-00805f9b34fb"
        else -> s
    }
}
