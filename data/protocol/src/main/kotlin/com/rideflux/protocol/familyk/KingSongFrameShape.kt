/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.protocol.familyk

/** Offline description of a vendor frame. It is not a production acceptance decision. */
data class KingSongFrameShape(
    val magic: Magic,
    val lengthClass: LengthClass,
    val page: Int?,
    val subCode: Int?,
    val pageKind: PageKind,
    val tail: TailObservation,
    val raw: ByteArray,
) {
    enum class Magic { AA55, F1EF, UNKNOWN }
    enum class LengthClass { TOO_SHORT, EXACTLY_20, EXTENDED }
    enum class PageKind { LIVE_A, LIVE_B, SETTINGS_ACK, BMS_F1, BMS_F2, C2, C5, C9, OTHER }
    enum class TailObservation { FIXED_5A5A, OTHER, NOT_PRESENT }

    /** The vendor forwards these seven values as raw BMS data; their physical meaning is unknown. */
    fun bmsRawValues(): List<Int>? {
        if (pageKind != PageKind.BMS_F1 && pageKind != PageKind.BMS_F2) return null
        if (subCode !in 0..7 || raw.size < 20) return null
        return (2..14 step 2).map { (raw[it].toInt() and 0xff) or ((raw[it + 1].toInt() and 0xff) shl 8) }
    }

    sealed class Extension {
        data class D0(val field2: Int, val field3: Int, val field4: Int) : Extension()
        data class D1(val first: ByteArray, val second: ByteArray) : Extension() {
            override fun equals(other: Any?): Boolean = other is D1 &&
                first.contentEquals(other.first) && second.contentEquals(other.second)
            override fun hashCode(): Int = 31 * first.contentHashCode() + second.contentHashCode()
        }
    }

    fun extension(): Extension? {
        if (lengthClass != LengthClass.EXTENDED || pageKind !in setOf(PageKind.BMS_F1, PageKind.BMS_F2)) return null
        return when (subCode) {
            0xd0 -> Extension.D0(raw[2].toInt() and 0xff, raw[3].toInt() and 0xff, raw[4].toInt() and 0xff)
            0xd1 -> {
                if (raw.size < 22) return null
                val firstCount = raw[20].toInt() and 0xff
                val secondCount = raw[21].toInt() and 0xff
                if (raw.size < 22 + firstCount + secondCount) return null
                Extension.D1(
                    raw.copyOfRange(22, 22 + firstCount),
                    raw.copyOfRange(22 + firstCount, 22 + firstCount + secondCount),
                )
            }
            else -> null
        }
    }
}

/** Vendor-static classification. No caller should use this as a transport delimiter. */
object KingSongShapeClassifier {
    fun classify(bytes: ByteArray): KingSongFrameShape {
        val magic = when {
            bytes.size >= 2 && bytes[0] == 0xaa.toByte() && bytes[1] == 0x55.toByte() -> KingSongFrameShape.Magic.AA55
            bytes.size >= 2 && bytes[0] == 0xf1.toByte() && bytes[1] == 0xef.toByte() -> KingSongFrameShape.Magic.F1EF
            else -> KingSongFrameShape.Magic.UNKNOWN
        }
        val length = when {
            bytes.size < 20 -> KingSongFrameShape.LengthClass.TOO_SHORT
            bytes.size == 20 -> KingSongFrameShape.LengthClass.EXACTLY_20
            else -> KingSongFrameShape.LengthClass.EXTENDED
        }
        val page = if (length == KingSongFrameShape.LengthClass.TOO_SHORT) null else bytes[16].toInt() and 0xff
        val sub = if (length == KingSongFrameShape.LengthClass.TOO_SHORT) null else bytes[17].toInt() and 0xff
        val kind = when (magic to page) {
            KingSongFrameShape.Magic.AA55 to 0xa9 -> KingSongFrameShape.PageKind.LIVE_A
            KingSongFrameShape.Magic.AA55 to 0xb9 -> KingSongFrameShape.PageKind.LIVE_B
            KingSongFrameShape.Magic.AA55 to 0x8a -> KingSongFrameShape.PageKind.SETTINGS_ACK
            KingSongFrameShape.Magic.AA55 to 0xf1 -> KingSongFrameShape.PageKind.BMS_F1
            KingSongFrameShape.Magic.AA55 to 0xf2 -> KingSongFrameShape.PageKind.BMS_F2
            KingSongFrameShape.Magic.F1EF to 0xc2 -> KingSongFrameShape.PageKind.C2
            KingSongFrameShape.Magic.F1EF to 0xc5 -> KingSongFrameShape.PageKind.C5
            KingSongFrameShape.Magic.F1EF to 0xc9 -> KingSongFrameShape.PageKind.C9
            else -> KingSongFrameShape.PageKind.OTHER
        }
        val tail = when {
            bytes.size < 20 -> KingSongFrameShape.TailObservation.NOT_PRESENT
            bytes[18] == 0x5a.toByte() && bytes[19] == 0x5a.toByte() -> KingSongFrameShape.TailObservation.FIXED_5A5A
            else -> KingSongFrameShape.TailObservation.OTHER
        }
        return KingSongFrameShape(magic, length, page, sub, kind, tail, bytes.copyOf())
    }
}
