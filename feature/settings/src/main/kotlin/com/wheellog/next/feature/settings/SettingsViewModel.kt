package com.wheellog.next.feature.settings

import androidx.lifecycle.viewModelScope
import com.wheellog.next.core.ui.mvi.ComposeViewModel
import com.wheellog.next.domain.repository.PreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferencesRepository: PreferencesRepository,
) : ComposeViewModel<SettingsIntent, SettingsState, SettingsEffect>(SettingsState()) {

    init {
        observePreferences()
    }

    override fun handleIntent(intent: SettingsIntent) {
        when (intent) {
            is SettingsIntent.SetMetricUnits -> setMetricUnits(intent.useMetric)
            is SettingsIntent.SetOverspeedThreshold -> setOverspeedThreshold(intent.kmh)
        }
    }

    private fun observePreferences() {
        preferencesRepository.useMetricUnits()
            .onEach { metric -> updateState { copy(useMetricUnits = metric) } }
            .launchIn(viewModelScope)

        preferencesRepository.overspeedThresholdKmh()
            .onEach { kmh -> updateState { copy(overspeedThresholdKmh = kmh) } }
            .launchIn(viewModelScope)
    }

    private fun setMetricUnits(metric: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setUseMetricUnits(metric)
        }
    }

    private fun setOverspeedThreshold(kmh: Float) {
        viewModelScope.launch {
            preferencesRepository.setOverspeedThresholdKmh(kmh)
        }
    }
}
