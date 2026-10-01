/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.ui.permission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPermissionPromptTest {

    private class Rig(needsPrompt: Boolean) {
        var asked = 0
        val ran = mutableListOf<String>()
        val prompt = NotificationPermissionPrompt(needsPrompt = { needsPrompt }).also {
            it.launchDialog = { asked += 1 }
        }
    }

    @Test
    fun runsStraightAwayWhenNothingNeedsAsking() {
        val rig = Rig(needsPrompt = false)

        rig.prompt.request { rig.ran += "start" }

        assertEquals(listOf("start"), rig.ran)
        assertEquals(0, rig.asked)
    }

    @Test
    fun waitsForTheAnswerBeforeRunningAndRunsOnlyOnce() {
        val rig = Rig(needsPrompt = true)

        rig.prompt.request { rig.ran += "start" }
        assertEquals(1, rig.asked)
        assertTrue("the action must wait for the dialog", rig.ran.isEmpty())

        rig.prompt.onResult()
        assertEquals(listOf("start"), rig.ran)

        rig.prompt.onResult() // a repeated callback must not run it again
        assertEquals(listOf("start"), rig.ran)
    }

    @Test
    fun theLatestRequestWinsWhenTheDialogIsStillUp() {
        val rig = Rig(needsPrompt = true)

        rig.prompt.request { rig.ran += "first" }
        rig.prompt.request { rig.ran += "second" }
        assertEquals(2, rig.asked)
        rig.prompt.onResult()

        assertEquals(listOf("second"), rig.ran)
    }

    @Test
    fun anAnswerWithNothingPendingIsIgnored() {
        val rig = Rig(needsPrompt = true)

        rig.prompt.onResult()

        assertTrue(rig.ran.isEmpty())
        assertEquals(0, rig.asked)
    }

    @Test
    fun aDialogThatCannotBeShownStillRunsTheAction() {
        val rig = Rig(needsPrompt = true)
        rig.prompt.launchDialog = { throw IllegalStateException("no activity") }

        rig.prompt.request { rig.ran += "start" }
        assertEquals(listOf("start"), rig.ran)

        // The failed request left nothing behind to fire on a later, unrelated answer.
        rig.prompt.onResult()
        assertEquals(listOf("start"), rig.ran)
    }

    @Test
    fun theQuestionIsAskedAgainForEachRequestSoAGrantLaterIsPickedUp() {
        var granted = false
        var asked = 0
        val ran = mutableListOf<String>()
        val prompt = NotificationPermissionPrompt(needsPrompt = { !granted }).also {
            it.launchDialog = { asked += 1 }
        }

        prompt.request { ran += "one" }
        prompt.onResult()
        granted = true // the rider allowed it in system settings in the meantime
        prompt.request { ran += "two" }

        assertEquals(listOf("one", "two"), ran)
        assertEquals(1, asked)
    }
}
