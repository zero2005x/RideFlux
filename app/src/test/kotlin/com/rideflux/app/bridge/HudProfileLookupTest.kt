package com.rideflux.app.bridge

import com.rideflux.domain.settings.HudLayoutProfile
import org.junit.Assert.assertEquals
import org.junit.Test

class HudProfileLookupTest {
    @Test
    fun pairedTokenOverridesLegacyMacProfile() {
        val macProfile = HudLayoutProfile(leftInset = 5)
        val tokenProfile = HudLayoutProfile(rightInset = 12)
        val approved = listOf(ApprovedGlasses("A1B2C3D4E5F60708", "AA:BB:CC:DD:EE:FF", "A1B2"))
        val profiles = mapOf("AABBCCDDEEFF" to macProfile, "A1B2C3D4E5F60708" to tokenProfile)

        assertEquals(tokenProfile, resolveHudProfile("aabbccddeeff", profiles, approved))
        assertEquals(tokenProfile, resolveHudProfile("A1B2C3D4E5F60708", profiles, approved))
    }

    @Test
    fun legacyMacAndUnconfiguredGlassesUseExpectedFallbacks() {
        val profile = HudLayoutProfile(fontPercent = 120)
        val approved = listOf(ApprovedGlasses(null, "AA:BB:CC:DD:EE:FF", "B2C3"))

        assertEquals(profile, resolveHudProfile("AABBCCDDEEFF", mapOf("AABBCCDDEEFF" to profile), approved))
        assertEquals(profile, resolveHudProfile("AABBCCDDEEFF", mapOf("AABBCCDDEEFF" to profile)))
        assertEquals(HudLayoutProfile(), resolveHudProfile("FFFFFFFFFFFF", emptyMap(), approved))
    }
}
