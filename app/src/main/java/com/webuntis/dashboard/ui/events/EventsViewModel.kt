package com.webuntis.dashboard.ui.events

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.webuntis.dashboard.api.WebUntisRepository
import com.webuntis.dashboard.model.SchoolEvent
import com.webuntis.dashboard.model.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class EventsViewModel @Inject constructor(
    private val repository: WebUntisRepository
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<List<SchoolEvent>>>(UiState.Loading)
    val state: StateFlow<UiState<List<SchoolEvent>>> = _state

    init { load() }

    fun load(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            if (!forceRefresh && repository.isEventsCacheFresh()) return@launch
            _state.value = UiState.Loading
            repository.getEvents(forceRefresh).fold(
                onSuccess = { _state.value = UiState.Success(it) },
                onFailure = { _state.value = UiState.Error(it.message ?: "Fehler beim Laden") }
            )
        }
    }
}
