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

    init { loadAll() }

    fun loadAll() {
        viewModelScope.launch {
            _days.value = UiState.Loading
            _days.value = repository.getTwoSchoolDays().fold(
                onSuccess = { list -> UiState.Success(list.map { SchoolDay(it) }) },
                onFailure = { UiState.Error(it.message ?: context.getString(R.string.error_generic)) }
            )
        }
    }
}
