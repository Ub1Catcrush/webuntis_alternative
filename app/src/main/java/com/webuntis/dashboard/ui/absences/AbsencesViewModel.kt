package com.webuntis.dashboard.ui.absences

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.webuntis.dashboard.api.WebUntisRepository
import com.webuntis.dashboard.model.Absence
import com.webuntis.dashboard.model.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AbsencesViewModel @Inject constructor(
    private val repository: WebUntisRepository
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<List<Absence>>>(UiState.Loading)
    val state: StateFlow<UiState<List<Absence>>> = _state

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.value = UiState.Loading
            repository.getAbsences().fold(
                onSuccess = { _state.value = UiState.Success(it) },
                onFailure = { _state.value = UiState.Error(it.message ?: context.getString(R.string.error_generic)) }
            )
        }
    }
}
