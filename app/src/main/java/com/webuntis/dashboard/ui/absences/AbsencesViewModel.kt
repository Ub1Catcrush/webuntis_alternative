package com.webuntis.dashboard.ui.absences

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.webuntis.dashboard.api.CreateAbsenceRequest
import com.webuntis.dashboard.api.WebUntisRepository
import com.webuntis.dashboard.model.Absence
import com.webuntis.dashboard.model.AbsencesMetaData
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

    private val _meta = MutableStateFlow<AbsencesMetaData?>(null)
    val meta: StateFlow<AbsencesMetaData?> = _meta

    private var currentFilterId: Int = -1

    val isParent: Boolean get() = repository.sessionManager.session?.isParent == true
    val studentId: Int get() = repository.sessionManager.studentId

    init {
        load(forceRefresh = false)
        loadMeta()
    }

    fun load(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            if (forceRefresh || _state.value !is UiState.Success) {
                _state.value = UiState.Loading
            }
            repository.getAbsences(forceRefresh, currentFilterId).fold(
                onSuccess = { _state.value = UiState.Success(it) },
                onFailure = { _state.value = UiState.Error(it.message ?: "Fehler beim Laden") }
            )
        }
    }

    private fun loadMeta() {
        viewModelScope.launch {
            repository.getAbsencesMeta().onSuccess {
                _meta.value = it
            }
        }
    }

    fun setFilter(statusId: Int) {
        if (currentFilterId == statusId) return
        currentFilterId = statusId
        load(forceRefresh = true)
    }

    fun createAbsence(req: CreateAbsenceRequest, onResult: (Result<Absence>) -> Unit) {
        viewModelScope.launch {
            val res = repository.createAbsence(req)
            if (res.isSuccess) {
                repository.clearAllCaches() // Invalidate cache to see new item
                load(forceRefresh = true)
            }
            onResult(res)
        }
    }

    fun updateAbsence(id: Int, req: CreateAbsenceRequest, onResult: (Result<Absence>) -> Unit) {
        viewModelScope.launch {
            val res = repository.updateAbsence(id, req)
            if (res.isSuccess) {
                repository.clearAllCaches()
                load(forceRefresh = true)
            }
            onResult(res)
        }
    }

    fun deleteAbsence(id: Int, onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            val res = repository.deleteAbsence(id)
            if (res.isSuccess) {
                repository.clearAllCaches()
                load(forceRefresh = true)
            }
            onResult(res)
        }
    }
}
