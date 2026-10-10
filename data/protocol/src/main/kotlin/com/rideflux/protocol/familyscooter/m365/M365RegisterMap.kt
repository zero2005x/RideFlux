package com.rideflux.protocol.familyscooter.m365

/**
 * Experimental passive register decoding. No request or write encoder.
 * L1 inventory/sizes: findings/NINEBOT_XIAOMI_UPSTREAM_REFERENCE.md:104-160.
 * L3 B0 offsets/temperature scale: scooter-apps/hardware-evidence/SPEED-FIELD-ANALYSIS.md:12-30.
 * Inventory is accessible only with explicit Experimental opt-in; source rows are pinned in tests.
 * Existing reverse-speed behavior is retained, not adjudicated against the hardware sentinel hypothesis.
 */
object M365RegisterMap {
    enum class WriteState { Unsupported, NotYetEnabled }
    data class Capability(val sizeBytes: Int, val readable: Boolean, val writeState: WriteState)

    // L1 community wiki inventory; unknown write meanings (74/7A) remain unnamed and disabled.
    private val WRITE_SIZES_L1 = mapOf(
        0x17 to 6, 0x70 to 2, 0x71 to 2, 0x74 to 2, 0x75 to 2, 0x78 to 2,
        0x79 to 2, 0x7A to 2, 0x7B to 2, 0x7C to 2, 0x7D to 2, 0xBE to 2,
    )
    // L1 documented read inventory; raw-only entries do not acquire guessed units.
    private val READ_SIZES_L1 = mapOf(
        0x00 to 2, 0x0D to 2, 0x0E to 2, 0x0F to 2, 0x10 to 14, 0x1A to 2,
        0x1B to 2, 0x1C to 2, 0x1D to 2, 0x22 to 2, 0x24 to 2, 0x25 to 2,
        0x26 to 2, 0x29 to 4, 0x2F to 2, 0x32 to 4, 0x34 to 4, 0x3A to 2,
        0x3B to 2, 0x3E to 2, 0x47 to 2, 0x48 to 2, 0x50 to 2, 0x65 to 2,
        0x67 to 2, 0x68 to 2, 0xB0 to 2, 0xB1 to 2, 0xB2 to 2, 0xB3 to 2,
        0xB4 to 2, 0xB5 to 2, 0xB6 to 2, 0xB7 to 4, 0xB9 to 2, 0xBB to 2, 0xDA to 12,
    )

    fun capabilities(experimental: Boolean = false): Map<Int, Capability> {
        if (!experimental) return emptyMap()
        return READ_SIZES_L1.mapValues { Capability(it.value, true, WriteState.Unsupported) } +
            WRITE_SIZES_L1.mapValues { Capability(it.value, false, WriteState.NotYetEnabled) }
    }

    /** Payload-only decode; callers validate the wire envelope with M365Codec.readReplyPayload. */
    fun decode(register: Int, payload: ByteArray, experimental: Boolean = false): Result {
        val capability = capabilities(experimental)[register] ?: return Result.Unsupported
        if (!capability.readable) return Result.Unsupported
        if (register == 0xB0 && payload.size == 32) {
            val block = M365Codec.decodeB0(payload) ?: return Result.Malformed
            return Result.Decoded(Readings(
                batteryPercent = block.batteryPercent,
                speedRaw = block.speedRaw,
                speedMagnitudeKmh = block.speedKmh,
                totalDistanceMetres = block.totalDistanceMetres,
                frameTemperatureC = block.frameTemperatureRaw / 10f,
                // B9 units (m×10 versus raw/100 km) are disputed; retain the wire word.
                tripDistanceRaw = word(payload, 18),
            ))
        }
        if (payload.size != capability.sizeBytes) return Result.Malformed
        return when (register) {
            0xB4 -> {
                val percent = word(payload)
                if (percent > 100) Result.Malformed else Result.Decoded(Readings(batteryPercent = percent))
            }
            0xB5 -> {
                val raw = word(payload)
                // Reuse the existing policy from docs/M365_REVERSE_SPEED_2026-10-07.md:9-16.
                val legacy = M365Codec.B0Block(0, 0, 0, raw, 0, 0, 0)
                Result.Decoded(Readings(speedRaw = raw, speedMagnitudeKmh = legacy.speedKmh))
            }
            0xB7 -> Result.Decoded(Readings(totalDistanceMetres =
                payload.indices.fold(0L) { value, index -> value or (unsigned(payload[index]).toLong() shl (8 * index)) }))
            0xBB -> Result.Decoded(Readings(frameTemperatureC = word(payload).toShort().toInt() / 10f))
            0x48 -> Result.Decoded(Readings(batteryVoltageV = M365Codec.decodeBatteryVoltageV(payload)))
            0x50 -> Result.Decoded(Readings(batteryCurrentRaw = M365Codec.decodeBatteryCurrentRaw(payload)))
            0xB9 -> Result.Decoded(Readings(tripDistanceRaw = word(payload)))
            else -> Result.Raw(register, payload.toList())
        }
    }

    private fun unsigned(byte: Byte) = byte.toInt() and 255
    private fun word(payload: ByteArray, offset: Int = 0) =
        unsigned(payload[offset]) or (unsigned(payload[offset + 1]) shl 8)

    data class Readings(
        val batteryPercent: Int? = null,
        val speedRaw: Int? = null,
        val speedMagnitudeKmh: Float? = null,
        val totalDistanceMetres: Long? = null,
        val tripDistanceRaw: Int? = null,
        val frameTemperatureC: Float? = null,
        val batteryVoltageV: Float? = null,
        val batteryCurrentRaw: Int? = null,
        /** The ESC 50 sign/scale is not established; do not borrow BMS-board 33 semantics. */
        val batteryCurrentA: Float? = null,
    ) {
        /** Calculated V×I only if both quantities are known; no power register. */
        val powerW: Float? get() = batteryVoltageV?.let { voltage -> batteryCurrentA?.let { voltage * it } }
    }

    sealed interface Result {
        data class Decoded(val readings: Readings) : Result
        data class Raw(val register: Int, val bytes: List<Byte>) : Result
        data object Malformed : Result
        data object Unsupported : Result
    }
}
