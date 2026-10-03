package com.rideflux.domain.settings

/** Percentages are relative to the physical display, independent of pixel density. */
data class HudLayoutProfile(
    val leftInset: Int = 0,
    val rightInset: Int = 0,
    val topInset: Int = 0,
    val bottomInset: Int = 0,
    val offsetX: Int = 0,
    val offsetY: Int = 0,
    val fontPercent: Int = 100,
    val visibleItems: Int = ALL_ITEMS,
) {
    fun normalized() = copy(
        leftInset = leftInset.coerceIn(0, 30),
        rightInset = rightInset.coerceIn(0, 30),
        topInset = topInset.coerceIn(0, 30),
        bottomInset = bottomInset.coerceIn(0, 30),
        offsetX = offsetX.coerceIn(-20, 20),
        offsetY = offsetY.coerceIn(-20, 20),
        fontPercent = fontPercent.coerceIn(70, 150),
        visibleItems = visibleItems and ALL_ITEMS,
    )

    fun shows(item: Int) = visibleItems and item != 0

    companion object {
        const val CLOCK = 1
        const val PHONE_BATTERY = 2
        const val GLASSES_BATTERY = 4
        const val SIGNAL = 8
        const val WHEEL_BATTERY = 16
        const val DISTANCE = 32
        const val DURATION = 64
        const val ALL_ITEMS = 127

        fun fromCsv(raw: String): HudLayoutProfile? {
            val numbers = raw.split(',').map { it.toIntOrNull() ?: return null }
            if (numbers.size != 8) return null
            return HudLayoutProfile(
                numbers[0], numbers[1], numbers[2], numbers[3],
                numbers[4], numbers[5], numbers[6], numbers[7],
            ).normalized()
        }
    }

    fun toCsv(): String = listOf(
        leftInset, rightInset, topInset, bottomInset,
        offsetX, offsetY, fontPercent, visibleItems,
    ).joinToString(",")
}
