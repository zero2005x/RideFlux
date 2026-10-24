/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.ui.scanner

import androidx.annotation.StringRes
import com.rideflux.app.R
import com.rideflux.domain.device.PlevCategory
import com.rideflux.domain.repository.DiscoveredWheel
import com.rideflux.domain.wheel.WheelFamily

/**
 * What the rider picked on the scanner screen.
 *
 * A plain tap and a brand choice are deliberately different events. The
 * [DiscoveredWheel.family] on a scan result is a weak advertisement hint; only
 * [Brand] is an explicit rider decision that the repository must honour as
 * `SessionHints.manualFamily`. Collapsing both into one `DiscoveredWheel`
 * callback is how the invoice used to lose the choice: the route had a single
 * lambda that always forwarded `family = null`.
 */
sealed interface ScannerSelection {
    val device: DiscoveredWheel

    /** An ordinary tap: the discovered GATT table decides the family. */
    data class Scanned(override val device: DiscoveredWheel) : ScannerSelection

    /** A brand picked in the "change brand" dialog: authoritative. */
    data class Brand(
        override val device: DiscoveredWheel,
        val family: WheelFamily,
    ) : ScannerSelection
}

/** Dashboard/HUD navigation arguments produced by a scanner selection. */
data class WheelNavigationSelection(
    val address: String,
    val family: WheelFamily?,
    val category: PlevCategory,
)

/**
 * Map one scanner event to the navigation arguments.
 *
 * This is the only place where "plain tap" becomes `family = null` and a brand
 * choice keeps its family. [ScannerRoute] must not assemble the arguments
 * inline: a lambda that always passed `null` is exactly the defect this type
 * and its regression exist to prevent.
 */
fun ScannerSelection.toNavigationSelection(): WheelNavigationSelection = when (this) {
    is ScannerSelection.Scanned ->
        WheelNavigationSelection(device.address, null, PlevCategory.WHEEL)
    is ScannerSelection.Brand ->
        WheelNavigationSelection(device.address, family, PlevCategory.WHEEL)
}

/**
 * Build the `onDeviceSelected` callback [ScannerRoute] installs.
 *
 * Kept as a named production function (rather than an inline composable
 * lambda) so the route's event → navigation-argument mapping can be asserted
 * without a Compose UI host.
 */
fun scannerDeviceNavigator(
    navigate: (address: String, family: WheelFamily?, category: PlevCategory) -> Unit,
): (ScannerSelection) -> Unit = { selection ->
    val target = selection.toNavigationSelection()
    navigate(target.address, target.family, target.category)
}

/**
 * Brands offered by the "change brand" dialog, in display order.
 *
 * Every entry is an explicit rider choice, so the dialog renders it as a
 * [ScannerSelection.Brand] — there is no path from this list to a `null`
 * family. Keeping the list as data also means a new family becomes one entry
 * instead of another hand-written `TextButton` that can drift from the others.
 */
enum class ScannerBrand(
    val family: WheelFamily,
    @param:StringRes val labelRes: Int,
) {
    VETERAN(WheelFamily.V, R.string.scanner_brand_veteran),
    BEGODE(WheelFamily.G, R.string.scanner_brand_begode),
    KINGSONG(WheelFamily.K, R.string.scanner_brand_kingsong),
    INMOTION_I2(WheelFamily.I2, R.string.scanner_brand_inmotion_i2),
    INMOTION_I1(WheelFamily.I1, R.string.scanner_brand_inmotion_i1),
    NINEBOT_N1(WheelFamily.N1, R.string.scanner_brand_ninebot_n1),
    NINEBOT_N2(WheelFamily.N2, R.string.scanner_brand_ninebot_n2),

    /** Read-only telemetry only: the family exposes no setting encoder. */
    VESC(WheelFamily.VESC, R.string.scanner_brand_vesc),
}

/** The explicit event a brand button emits for [device]. */
fun ScannerBrand.select(device: DiscoveredWheel): ScannerSelection.Brand =
    ScannerSelection.Brand(device, family)
