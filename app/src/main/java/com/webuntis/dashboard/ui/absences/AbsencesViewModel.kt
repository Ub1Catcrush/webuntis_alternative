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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class AbsenceFilter { ALL, EXCUSED, UNEXCUSED, PENDING }

@HiltViewModel
class AbsencesViewModel @Inject constructor(
    private val repository: WebUntisRepository
) : ViewModel() {

    // Raw list from server (unfiltered)
    private val _allAbsences = MutableStateFlow<UiState<List<Absence>>>(UiState.Loading)

    private val _meta = MutableStateFlow<AbsencesMetaData?>(null)
    val meta: StateFlow<AbsencesMetaData?> = _meta

    private val _filter = MutableStateFlow(AbsenceFilter.ALL)
    val filter: StateFlow<AbsenceFilter> = _filter

    // Derived: filtered view exposed to the UI
    private val _state = MutableStateFlow<UiState<List<Absence>>>(UiState.Loading)
    val state: StateFlow<UiState<List<Absence>>> = _state

    val isParent: Boolean get() = repository.sessionManager.session?.isParent == true
    val studentId: Int get() = repository.sessionManager.studentId

    init {
        // Re-apply filter whenever raw data or filter changes
        viewModelScope.launch {
            combine(_allAbsences, _filter) { raw, f ->
                when (raw) {
                    is UiState.Success -> UiState.Success(applyFilter(raw.data, f))
                    else -> raw
                }
            }.collect { _state.value = it }
        }
        load(forceRefresh = false)
        loadMeta()
    }

    fun load(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            if (forceRefresh || _allAbsences.value !is UiState.Success) {
                _allAbsences.value = UiState.Loading
            }
            // Always fetch all absences; filtering is done client-side
            repository.getAbsences(forceRefresh).fold(
                onSuccess = { _allAbsences.value = UiState.Success(it) },
                onFailure = { _allAbsences.value = UiState.Error(it.message ?: "Fehler beim Laden") }
            )
        }
    }

    private fun loadMeta() {
        viewModelScope.launch {
            repository.getAbsencesMeta().onSuccess { _meta.value = it }
        }
    }

    fun setFilter(f: AbsenceFilter) {
        _filter.value = f
    }

    private fun applyFilter(list: List<Absence>, f: AbsenceFilter): List<Absence> = when (f) {
        AbsenceFilter.ALL      -> list
        AbsenceFilter.EXCUSED  -> list.filter { it.isExcused == true }
        AbsenceFilter.UNEXCUSED-> list.filter { it.isExcused == false }
        AbsenceFilter.PENDING  -> list.filter { it.isExcused == null || isPending(it.excuseStatus) }
    }

    private fun isPending(status: String?): Boolean {
        if (status == null) return false
        val s = status.lowercase()
        return s.contains("ausstehend") || s.contains("pending") || s.contains("offen") || s.contains("open")
    }

    /** Count of unexcused absences in the full (unfiltered) list. */
    fun unexcusedCount(): Int =
        (_allAbsences.value as? UiState.Success)?.data?.count { it.isExcused == false } ?: 0

    fun createAbsence(req: CreateAbsenceRequest, onResult: (Result<Absence>) -> Unit) {
        viewModelScope.launch {
            val res = repository.createAbsence(req)
            if (res.isSuccess) { repository.clearAllCaches(); load(forceRefresh = true) }
            onResult(res)
        }
    }

    fun updateAbsence(id: Int, req: CreateAbsenceRequest, onResult: (Result<Absence>) -> Unit) {
        viewModelScope.launch {
            val res = repository.updateAbsence(id, req)
            if (res.isSuccess) { repository.clearAllCaches(); load(forceRefresh = true) }
            onResult(res)
        }
    }

    fun deleteAbsence(id: Int, onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            val res = repository.deleteAbsence(id)
            if (res.isSuccess) { repository.clearAllCaches(); load(forceRefresh = true) }
            onResult(res)
        }
    }
}
