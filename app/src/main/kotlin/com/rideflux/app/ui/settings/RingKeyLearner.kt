/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.ui.settings

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Coordination hook between [com.rideflux.app.MainActivity.dispatchKeyEvent]
 * and [SettingsViewModel] for interactive ring key learning.
 */
object RingKeyLearner {
    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _lastLearnedKey = MutableSharedFlow<Int>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val lastLearnedKey: SharedFlow<Int> = _lastLearnedKey.asSharedFlow()

    fun startListening() {
        _isListening.value = true
    }

    fun stopListening() {
        _isListening.value = false
    }

    fun onKeyEvent(keyCode: Int): Boolean {
        if (_isListening.value) {
            _isListening.value = false
            _lastLearnedKey.tryEmit(keyCode)
            return true
        }
        return false
    }
}
