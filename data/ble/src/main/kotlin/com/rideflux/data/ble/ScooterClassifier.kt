package com.rideflux.data.ble

import java.util.Locale

/** Advertisement-only scooter candidate recognition; it does not establish a protocol profile. */
internal object ScooterClassifier {
    private val scooterName = Regex(
        "^(?:MIScooter|Ninebot(?!\\s*(?:One|Z\\b))|KickScooter|ES[124](?:\\b|[-_])|Max(?:\\b|[-_])|G30(?:\\b|[-_])|F[2-4]0(?:\\b|[-_])).*",
        RegexOption.IGNORE_CASE,
    )
    private val wheelName = Regex(
        "^(?:Begode|Gotway|Inmotion|King\\s*Song|Kingsong|Veteran|Leaperkim|Ninebot\\s*(?:One|Z\\b)).*",
        RegexOption.IGNORE_CASE,
    )

    fun classify(name: String?, serviceUuids: Set<String>): String? {
        val cleanName = name?.trim()?.takeIf(String::isNotEmpty)
        if (cleanName != null && wheelName.matches(cleanName)) return null
        if (cleanName != null && scooterName.matches(cleanName)) return cleanName
        val fe95 = serviceUuids.any { raw ->
            val value = raw.trim().removePrefix("0x").lowercase(Locale.ROOT)
            value == "fe95" || value == GattUuids.SERVICE_FE95.toString()
        }
        return if (fe95) cleanName ?: "Ninebot/Xiaomi Scooter" else null
    }

    /** Only explicit Ninebot retail name candidates may enter the 5A A5 session path. */
    fun isNinebotRetailCandidate(model: String): Boolean =
        model.startsWith("Ninebot", ignoreCase = true) ||
            model.startsWith("KickScooter", ignoreCase = true) ||
            Regex("^(?:ES[124]|G30|F[2-4]0)(?:\\b|[-_]).*", RegexOption.IGNORE_CASE)
                .matches(model)
}
