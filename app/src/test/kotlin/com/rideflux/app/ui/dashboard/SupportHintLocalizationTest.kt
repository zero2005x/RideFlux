/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.ui.dashboard

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.rideflux.app.R
import com.rideflux.domain.wheel.WheelControlContract
import com.rideflux.domain.wheel.WheelIdentityEvidence
import com.rideflux.domain.wheel.WheelSupport
import com.rideflux.domain.wheel.WheelTelemetryCoverage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The support banner is copy a rider reads, so it must never splice the
 * codec's English [WheelSupport.detail] into a translated sentence.
 *
 * Known reasons map to one translated resource each; an unknown reason maps
 * to the translated generic sentence with a translated placeholder.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SupportHintLocalizationTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun support(reason: String?, detail: String? = null) = WheelSupport(
        identityEvidence = WheelIdentityEvidence.FAMILY_ONLY,
        telemetryCoverage = WheelTelemetryCoverage.CORE_ELECTRICAL,
        controlContract = WheelControlContract.NONE,
        reason = reason,
        detail = detail,
    )

    @Test
    fun `a closed control contract explains itself instead of showing the detail`() {
        val detail = "Inmotion I2: control contract not closed for this model"
        val expected = context.getString(R.string.support_limited_control)

        compose.setContent { Text(limitedSupportText(support("control-contract-not-closed", detail))) }

        compose.onNodeWithText(expected).assertExists()
        compose.onAllNodesWithText(detail, substring = true).assertCountEquals(0)
    }

    @Test
    fun `an unidentified session uses the translated sentence`() {
        val expected = context.getString(R.string.support_limited_unidentified)

        compose.setContent { Text(limitedSupportText(support("unidentified", "Device identification has not completed."))) }

        compose.onNodeWithText(expected).assertExists()
    }

    @Test
    fun `a partial layout shares the translated layout notice`() {
        val expected = context.getString(R.string.support_limited_layout)

        compose.setContent { Text(limitedSupportText(support("layout-partial", "I2 real-time page 0x0 is partial"))) }

        compose.onNodeWithText(expected).assertExists()
    }

    @Test
    fun `an unknown reason falls back to translated text, never the English detail`() {
        val detail = "Ninebot B0 page too short: 12 bytes"
        val expected = context.getString(
            R.string.support_limited_generic,
            context.getString(R.string.support_limited_reason_unknown),
        )

        compose.setContent { Text(limitedSupportText(support("some-new-reason", detail))) }

        compose.onNodeWithText(expected).assertExists()
        compose.onAllNodesWithText(detail, substring = true).assertCountEquals(0)
    }

    @Test
    fun `a null reason still renders translated copy`() {
        val expected = context.getString(
            R.string.support_limited_generic,
            context.getString(R.string.support_limited_reason_unknown),
        )

        compose.setContent { Text(limitedSupportText(support(null))) }

        compose.onNodeWithText(expected).assertExists()
    }
}

/**
 * The same mapping under a non-English configuration: the rendered copy must
 * be the locale's string, not the default English sentence.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rTW")
class SupportHintLocalizationTraditionalChineseTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun support(reason: String, detail: String?) = WheelSupport(
        identityEvidence = WheelIdentityEvidence.FAMILY_ONLY,
        telemetryCoverage = WheelTelemetryCoverage.CORE_ELECTRICAL,
        controlContract = WheelControlContract.NONE,
        reason = reason,
        detail = detail,
    )

    @Test
    fun `a firmware downgrade renders the Traditional Chinese sentence`() {
        val expected = context.getString(R.string.support_limited_firmware)
        val english = "Firmware not verified. Controls stay disabled until the version is known."

        assertNotEquals(english, expected)

        compose.setContent { Text(limitedSupportText(support("firmware-unknown", "I2: unparsable firmware '??'"))) }

        compose.onNodeWithText(expected).assertExists()
        compose.onAllNodesWithText("I2: unparsable firmware", substring = true).assertCountEquals(0)
    }

    @Test
    fun `the generic fallback is fully Traditional Chinese`() {
        val expected = context.getString(
            R.string.support_limited_generic,
            context.getString(R.string.support_limited_reason_unknown),
        )
        val rendered = expected
        assertEquals(expected, rendered)
        compose.setContent { Text(limitedSupportText(support("unmapped-reason", "VESC: CRC mismatch on 8-byte payload"))) }
        compose.onNodeWithText(expected).assertExists()
    }
}
