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
import javax.inject.Inject

/** A group of lessons occurring at the same time, displayed side-by-side if not Ersatzstunden. */
data class LessonGroup(val lessons: List<Lesson>) {
    val id: String = lessons.joinToString("-") { it.id.toString() }
    val startTime: Int = lessons.firstOrNull()?.startTime ?: 0
    val endTime: Int = lessons.firstOrNull()?.endTime ?: 0
}

/** UI model for the timetable tab — wraps TimetableDay with a display label */
data class SchoolDay(val day: TimetableDay) {
    val date: LocalDate get() = day.date
    val lessons: List<Lesson> get() = day.lessons
    val tabLabel: String get() = day.label

    /**
     * Groups lessons that should be displayed side-by-side.
     * Parallel active lessons are grouped. If a lesson is a substitution for a cancelled one,
     * they remain separate (vertical) as per the "keine Ersatzstunden" requirement.
     */
    val groupedLessons: List<LessonGroup> by lazy {
        val result = mutableListOf<LessonGroup>()
        val groups = day.lessons.groupBy { it.startTime to it.endTime }
        val sortedTimes = groups.keys.sortedWith(compareBy({ it.first }, { it.second }))

        for (time in sortedTimes) {
            val list = groups[time]!!
            val cancelled = list.filter { it.isCancelled }
            val active = list.filter { !it.isCancelled }

            if (cancelled.isNotEmpty() && active.isNotEmpty()) {
                // Mix of cancelled and active: show vertically (Ersatzstunden-Fall)
                cancelled.forEach { result.add(LessonGroup(listOf(it))) }
                active.forEach { result.add(LessonGroup(listOf(it))) }
            } else if (active.size > 1) {
                // Multiple active lessons without cancellations: side-by-side
                result.add(LessonGroup(active))
            } else {
                // Single lesson or only cancelled ones: vertical
                list.forEach { result.add(LessonGroup(listOf(it))) }
            }
        }
        result
    }
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
    val useCompactWeekView: Boolean get() = repository.sessionManager.useCompactWeekView

    init { loadAll() }

    fun loadAll(forceRefresh: Boolean = false) {
        viewModelScope.launch {
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
