/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app

import android.view.KeyEvent
import com.rideflux.app.ui.settings.RingKeyLearner
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RingKeyResolutionTest {

    private fun isTargetRingKey(keyCode: Int, customRingKey: Int?): Boolean {
        return if (customRingKey != null) {
            keyCode == customRingKey
        } else {
            keyCode in setOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN)
        }
    }

    @Test
    fun `default unconfigured ring key matches volume buttons`() {
        assertTrue(isTargetRingKey(KeyEvent.KEYCODE_VOLUME_UP, customRingKey = null))
        assertTrue(isTargetRingKey(KeyEvent.KEYCODE_VOLUME_DOWN, customRingKey = null))
        assertFalse(isTargetRingKey(KeyEvent.KEYCODE_ENTER, customRingKey = null))
        assertFalse(isTargetRingKey(KeyEvent.KEYCODE_BUTTON_1, customRingKey = null))
    }

    @Test
    fun `custom learned ring key overrides default volume keys`() {
        val customKey = KeyEvent.KEYCODE_ENTER
        assertTrue(isTargetRingKey(KeyEvent.KEYCODE_ENTER, customRingKey = customKey))
        assertFalse(isTargetRingKey(KeyEvent.KEYCODE_VOLUME_UP, customRingKey = customKey))
        assertFalse(isTargetRingKey(KeyEvent.KEYCODE_VOLUME_DOWN, customRingKey = customKey))
    }

    @Test
    fun `ring key learner captures key and resets listening state`() = runTest {
        assertFalse(RingKeyLearner.isListening.value)
        RingKeyLearner.startListening()
        assertTrue(RingKeyLearner.isListening.value)

        val handled = RingKeyLearner.onKeyEvent(KeyEvent.KEYCODE_BUTTON_A)
        assertTrue(handled)
        assertFalse(RingKeyLearner.isListening.value)

        val captured = RingKeyLearner.lastLearnedKey.first()
        assertEquals(KeyEvent.KEYCODE_BUTTON_A, captured)
    }
}
