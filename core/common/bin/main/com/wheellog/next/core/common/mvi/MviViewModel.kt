package com.wheellog.next.core.common.mvi

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * MVI contract base interface — all feature-module ViewModels implement this.
 *
 * @param I Intent (user action / system event)
 * @param S State  (UI state)
 * @param E Effect (one-shot side-effect, e.g. Toast, navigation)
 */
interface MviViewModel<I, S, E> {
    val state: StateFlow<S>
    val effect: SharedFlow<E>
    fun onIntent(intent: I)
}
