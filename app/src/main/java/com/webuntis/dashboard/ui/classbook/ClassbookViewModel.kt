package com.webuntis.dashboard.ui.classbook

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.webuntis.dashboard.api.WebUntisRepository
import com.webuntis.dashboard.model.ClassbookEntry
import com.webuntis.dashboard.model.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ClassbookViewModel @Inject constructor(
    private val repository: WebUntisRepository
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<List<ClassbookEntry>>>(UiState.Loading)
    val state: StateFlow<UiState<List<ClassbookEntry>>> = _state

    init { load() }

    fun load(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            _state.value = UiState.Loading
            repository.getClassbookEntries(forceRefresh).fold(
                onSuccess = { _state.value = UiState.Success(it) },
                onFailure = { _state.value = UiState.Error(it.message ?: "Fehler beim Laden") }
            )
        }
    }
}
