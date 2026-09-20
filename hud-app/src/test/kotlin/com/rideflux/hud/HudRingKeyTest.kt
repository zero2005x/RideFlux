/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.hud

import android.view.KeyEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HudRingKeyTest {

    private fun isRingKey(keyCode: Int, customKeyCode: Int?): Boolean {
        return if (customKeyCode != null) {
            keyCode == customKeyCode
        } else {
            keyCode in setOf(KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER)
        }
    }

    @Test
    fun `default unconfigured ring key matches ENTER and DPAD_CENTER`() {
        assertTrue(isRingKey(KeyEvent.KEYCODE_ENTER, customKeyCode = null))
        assertTrue(isRingKey(KeyEvent.KEYCODE_DPAD_CENTER, customKeyCode = null))
        assertFalse(isRingKey(KeyEvent.KEYCODE_VOLUME_UP, customKeyCode = null))
        assertFalse(isRingKey(KeyEvent.KEYCODE_SPACE, customKeyCode = null))
    }

    @Test
    fun `custom learned ring key overrides defaults`() {
        val customKey = KeyEvent.KEYCODE_VOLUME_DOWN
        assertTrue(isRingKey(KeyEvent.KEYCODE_VOLUME_DOWN, customKeyCode = customKey))
        assertFalse(isRingKey(KeyEvent.KEYCODE_ENTER, customKeyCode = customKey))
        assertFalse(isRingKey(KeyEvent.KEYCODE_DPAD_CENTER, customKeyCode = customKey))
    }
}
