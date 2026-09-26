package com.webuntis.dashboard.ui.absences

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.webuntis.dashboard.api.CreateAbsenceRequest
import com.webuntis.dashboard.api.WebUntisRepository
import com.webuntis.dashboard.model.Absence
import com.webuntis.dashboard.model.AbsenceListEntry
import com.webuntis.dashboard.model.AbsenceTime
import com.webuntis.dashboard.model.AbsencesMetaData
import com.webuntis.dashboard.model.UiState
import com.webuntis.dashboard.model.groupIntoAbsenceEntries
import dagger.hilt.android.lifecycle.HiltViewModel
import com.webuntis.dashboard.model.TimegridRow
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class AbsenceFilter { ALL, EXCUSED, UNEXCUSED, PENDING }

/** Which of the two absences views is currently shown — see [AbsencesFragment]'s toggle. */
enum class AbsencesViewMode { MESSAGES, LIST }

@HiltViewModel
class AbsencesViewModel @Inject constructor(
    private val repository: WebUntisRepository,
    private val appForegroundEvents: com.webuntis.dashboard.api.AppForegroundEvents,
    val activeAccountManager: com.webuntis.dashboard.api.ActiveAccountManager
) : ViewModel() {

    // Raw list from server (unfiltered)
    private val _allAbsences = MutableStateFlow<UiState<List<Absence>>>(UiState.Loading)

    // Per-lesson breakdown backing the day-grouped "Liste der Abwesenheiten" view.
    private val _allAbsenceTimes = MutableStateFlow<UiState<List<AbsenceTime>>>(UiState.Loading)
    private val _dayGroups = MutableStateFlow<UiState<List<AbsenceListEntry>>>(UiState.Loading)
    val dayGroups: StateFlow<UiState<List<AbsenceListEntry>>> = _dayGroups

    private val _viewMode = MutableStateFlow(AbsencesViewMode.MESSAGES)
    val viewMode: StateFlow<AbsencesViewMode> = _viewMode

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
        // Re-group into days whenever the raw per-lesson breakdown changes
        viewModelScope.launch {
            _allAbsenceTimes.collect { raw ->
                _dayGroups.value = when (raw) {
                    is UiState.Success -> UiState.Success(raw.data.groupIntoAbsenceEntries())
                    is UiState.Loading -> UiState.Loading
                    is UiState.Error   -> UiState.Error(raw.message)
                }
            }
        }
        load(forceRefresh = false)
        loadMeta()
        viewModelScope.launch {
            appForegroundEvents.onForegroundResume.collect { load(forceRefresh = true); loadMeta() }
        }
        viewModelScope.launch {
            activeAccountManager.current.drop(1).collect { load(forceRefresh = true); loadMeta() }
        }
    }

    fun setViewMode(mode: AbsencesViewMode) {
        _viewMode.value = mode
    }

    fun load(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            if (forceRefresh || _allAbsences.value !is UiState.Success) {
                _allAbsences.value = UiState.Loading
                _allAbsenceTimes.value = UiState.Loading
            }
            // Both lists come from the same underlying request (see
            // WebUntisRepository.fetchAbsencesAndTimes) — fetching them back-to-back only
            // hits the network once, the second call is served from the shared cache entry
            // the first one just (re-)populated.
            repository.getAbsences(forceRefresh).fold(
                onSuccess = { _allAbsences.value = UiState.Success(it) },
                onFailure = { _allAbsences.value = UiState.Error(it.message ?: "Fehler beim Laden") }
            )
            repository.getAbsenceTimes(forceRefresh = false).fold(
                onSuccess = { _allAbsenceTimes.value = UiState.Success(it) },
                onFailure = { _allAbsenceTimes.value = UiState.Error(it.message ?: "Fehler beim Laden") }
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
            if (res.isSuccess) { repository.clearDataCachesOnly(); load(forceRefresh = true) }
            onResult(res)
        }
    }

    fun updateAbsence(id: Int, req: CreateAbsenceRequest, onResult: (Result<Absence>) -> Unit) {
        viewModelScope.launch {
            val res = repository.updateAbsence(id, req)
            if (res.isSuccess) { repository.clearDataCachesOnly(); load(forceRefresh = true) }
            onResult(res)
        }
    }

    fun deleteAbsence(id: Int, onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            val res = repository.deleteAbsence(id)
            if (res.isSuccess) { repository.clearDataCachesOnly(); load(forceRefresh = true) }
            onResult(res)
        }
    }

    /** Exposes the timegrid rows for the dialog's period chips. */
    suspend fun getTimegrid(): List<TimegridRow> =
        repository.getTimegrid(forceRefresh = false).getOrNull() ?: emptyList()

    /**
     * Returns (startTime, endTime) using the school's timegrid:
     * - startTime = start of the first period (period 1)
     * - endTime   = end of the last period that has lessons on [date]'s weekday
     *
     * Falls back to timetable data, then to (08:00, 16:00).
     */
    suspend fun getTimetableTimesForDate(date: LocalDate): Pair<LocalTime, LocalTime> {
        // Primary: use the timegrid API (fastest, no per-day network call)
        val rows = repository.getTimegrid(forceRefresh = false).getOrNull()
        if (!rows.isNullOrEmpty()) {
            val firstStart = rows.minOf { it.startTime }
            val lastEnd    = rows.maxOf { it.endTime }
            return Pair(
                LocalTime.of(firstStart / 100, firstStart % 100),
                LocalTime.of(lastEnd   / 100, lastEnd   % 100)
            )
        }
        // Fallback: load timetable for that specific day
        val day = repository.getSchoolDaysFrom(date, 1, forceRefresh = false)
            .getOrNull()?.firstOrNull { it.date == date }
        val lessons = day?.lessons ?: emptyList()
        if (lessons.isNotEmpty()) {
            val firstStart = lessons.minOf { it.startTime }
            val lastEnd    = lessons.maxOf { it.endTime }
            return Pair(
                LocalTime.of(firstStart / 100, firstStart % 100),
                LocalTime.of(lastEnd   / 100, lastEnd   % 100)
            )
        }
        return Pair(LocalTime.of(8, 0), LocalTime.of(16, 0))
    }
}
