package com.rideflux.protocol.familyv

/** Explicit parser selection; no frame or voltage heuristic assigns a model. */
enum class VeteranProtocolProfile { LEGACY, MODERN_NOSFET }

/** Vendor-static model keys. Hardware keys are distinct from WheelLog's mVer values. */
object VeteranModelRegistry {
    data class Model(val name: String, val hardwareKey: String, val seriesCells: Int)

    val models = listOf(
        Model("Sherman", "0010", 24),
        Model("Sherman Max", "0011", 24),
        Model("Abrams", "0020", 24),
        Model("Sherman S", "0030", 24),
        Model("Patton", "0040", 30),
        Model("Lynx", "0050", 36),
        Model("Sherman L", "0060", 36),
        Model("Patton S", "0070", 30),
        Model("Apex", "5010", 36),
        Model("Aero", "5020", 30),
    )

    fun fromHardwareKey(key: String): Model? = models.firstOrNull { it.hardwareKey == key }

    /** NOSFET parses the hex bytes 30,28,29 as a number, then zero-pads in decimal. */
    fun modernHardwareKey(byte30: Int, byte28: Int, byte29: Int): String {
        val raw = ((byte30 and 0xff) shl 16) or ((byte28 and 0xff) shl 8) or (byte29 and 0xff)
        return raw.toString().padStart(6, '0').take(4)
    }
}
