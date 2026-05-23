package com.webuntis.dashboard.ui.timetable

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.webuntis.dashboard.api.TimetableDay
import com.webuntis.dashboard.api.WebUntisRepository
import com.webuntis.dashboard.model.Lesson
import com.webuntis.dashboard.model.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject

/** UI model for the timetable tab — wraps TimetableDay with a display label */
data class SchoolDay(val day: TimetableDay) {
    val date: LocalDate get() = day.date
    val lessons: List<Lesson> get() = day.lessons
    val tabLabel: String get() = day.label
}

@HiltViewModel
class TimetableViewModel @Inject constructor(
    private val repository: WebUntisRepository
) : ViewModel() {

    private val _days = MutableStateFlow<UiState<List<SchoolDay>>>(UiState.Loading)
    val days: StateFlow<UiState<List<SchoolDay>>> = _days

    val showLongSubjects: Boolean get() = repository.sessionManager.showLongSubjects
    val showLongTeachers: Boolean get() = repository.sessionManager.showLongTeachers
    val showLongRooms:    Boolean get() = repository.sessionManager.showLongRooms

    init { loadAll() }

    fun loadAll(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            // Skip entirely if we already have fresh cached data and no force-refresh.
            // This prevents redundant state emissions when returning from another tab —
            // the StateFlow already holds the correct Success value and nothing has changed.
            if (!forceRefresh && repository.isTimetableCacheFresh()) {
                // Cache is fresh — nothing to do. StateFlow already has the right data.
                // Don't emit Loading (would cause flicker) and don't re-fetch.
                return@launch
            }
            // Only show loading spinner when there is no data to display yet.
            if (forceRefresh || _days.value !is UiState.Success) {
                _days.value = UiState.Loading
            }
            _days.value = repository.getTwoSchoolDays(forceRefresh).fold(
                onSuccess = { list -> UiState.Success(list.map { SchoolDay(it) }) },
                onFailure = { UiState.Error(it.message ?: "Fehler beim Laden") }
            )
        }
    }
}
