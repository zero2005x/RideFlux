package com.wheellog.next.core.common.mvi

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Abstract base ViewModel implementing the MVI pattern with unidirectional data flow.
 *
 * Subclasses must:
 * 1. Provide the [initialState].
 * 2. Implement [handleIntent] to map intents to state mutations / effects.
 *
 * The class is framework-agnostic (no android.* imports); feature modules extend
 * this via `androidx.lifecycle.ViewModel`.
 */
abstract class BaseMviViewModel<I, S, E>(initialState: S) : MviViewModel<I, S, E> {

    private val _state = MutableStateFlow(initialState)
    override val state: StateFlow<S> = _state.asStateFlow()

    private val _effect = MutableSharedFlow<E>(extraBufferCapacity = 1)
    override val effect: SharedFlow<E> = _effect.asSharedFlow()

    override fun onIntent(intent: I) {
        handleIntent(intent)
    }

    protected abstract fun handleIntent(intent: I)

    protected fun updateState(reducer: S.() -> S) {
        _state.value = _state.value.reducer()
    }

    protected fun emitEffect(effect: E) {
        _effect.tryEmit(effect)
    }
}
