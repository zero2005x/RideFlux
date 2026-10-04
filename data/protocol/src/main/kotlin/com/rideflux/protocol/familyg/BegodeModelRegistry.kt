package com.rideflux.protocol.familyg

/** Catalog names are search hints, not proof of pack voltage or protocol generation. */
object BegodeModelRegistry {
    data class Model(val name: String, val seriesCells: Int? = null)

    val models: List<Model> = listOf(
        Model("A2"), Model("Mten3"), Model("Mten4"), Model("MCM5"),
        Model("Tesla"), Model("Nikola"), Model("MSX"), Model("MSP"),
        Model("RS"), Model("EX"), Model("EX-N"), Model("Master"),
        Model("Master Pro"), Model("Commander"), Model("Hero"),
        Model("Extreme"), Model("Blitz"), Model("ET Max"),
        Model("T4"), Model("Falcon"), Model("Mten5"), Model("A3"),
    )

    /** Longest match first prevents EX from swallowing EX-N and Master from swallowing Master Pro. */
    fun identify(advertisedName: String?): Model? {
        val normalized = advertisedName?.trim()?.uppercase() ?: return null
        return models.sortedByDescending { it.name.length }.firstOrNull {
            Regex("(?<![A-Z0-9])${Regex.escape(it.name.uppercase())}(?![A-Z0-9])")
                .containsMatchIn(normalized)
        }
    }
}
