package com.wheellog.next.feature.hudgateway

import androidx.lifecycle.viewModelScope
import com.wheellog.next.core.ui.mvi.ComposeViewModel
import com.wheellog.next.domain.repository.HudGatewayRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HudGatewayViewModel @Inject constructor(
    private val hudGateway: HudGatewayRepository,
) : ComposeViewModel<HudGatewayIntent, HudGatewayState, HudGatewayEffect>(HudGatewayState()) {

    init {
        observeAdvertisingState()
        observeConnectedDeviceCount()
    }

    override fun handleIntent(intent: HudGatewayIntent) {
        when (intent) {
            HudGatewayIntent.ToggleAdvertising -> toggleAdvertising()
        }
    }

    private fun observeAdvertisingState() {
        hudGateway.isAdvertising
            .onEach { advertising ->
                updateState { copy(isAdvertising = advertising) }
            }
            .catch { e ->
                emitEffect(HudGatewayEffect.ShowError(e.message ?: "Advertising state error"))
            }
            .launchIn(viewModelScope)
    }

    private fun observeConnectedDeviceCount() {
        hudGateway.connectedDeviceCount
            .onEach { count ->
                updateState { copy(connectedDeviceCount = count) }
            }
            .catch { e ->
                emitEffect(HudGatewayEffect.ShowError(e.message ?: "Connection state error"))
            }
            .launchIn(viewModelScope)
    }

    private fun toggleAdvertising() {
        viewModelScope.launch {
            try {
                if (state.value.isAdvertising) {
                    hudGateway.stopAdvertising()
                } else {
                    hudGateway.startAdvertising()
                }
            } catch (e: Exception) {
                emitEffect(HudGatewayEffect.ShowError(e.message ?: "Failed to toggle advertising"))
            }
        }
    }
}
