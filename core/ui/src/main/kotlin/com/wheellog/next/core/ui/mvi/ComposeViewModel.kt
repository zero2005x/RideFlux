package com.wheellog.next.core.ui.mvi

import androidx.lifecycle.ViewModel
import com.wheellog.next.core.common.mvi.MviViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Compose-friendly MVI ViewModel base class.
 * Extends [ViewModel] so it survives configuration changes and provides [viewModelScope].
 * Feature-module ViewModels extend this instead of [BaseMviViewModel].
 */
abstract class ComposeViewModel<I, S, E>(initialState: S) : ViewModel(), MviViewModel<I, S, E> {

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
