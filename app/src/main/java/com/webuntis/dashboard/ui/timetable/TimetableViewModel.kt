package com.webuntis.dashboard.ui.timetable

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.webuntis.dashboard.api.TimetableDay
import com.webuntis.dashboard.api.WebUntisRepository
import com.webuntis.dashboard.model.Lesson
import com.webuntis.dashboard.model.Absence
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

    private val _absences = MutableStateFlow<List<Absence>>(emptyList())
    val absences: StateFlow<List<Absence>> = _absences

    val showLongSubjects: Boolean get() = repository.sessionManager.showLongSubjects
    val showLongTeachers: Boolean get() = repository.sessionManager.showLongTeachers
    val showLongRooms:    Boolean get() = repository.sessionManager.showLongRooms
    val useCompactWeekView: Boolean get() = repository.sessionManager.useCompactWeekView

    // ── Date navigation ───────────────────────────────────────────────────────

    /** The first date of the currently displayed window. null = today (default). */
    private val _anchorDate = MutableStateFlow<LocalDate?>(null)
    val anchorDate: StateFlow<LocalDate?> = _anchorDate

    val isAtDefault: Boolean get() = _anchorDate.value == null ||
        _anchorDate.value == LocalDate.now()

    init { loadAll() }

    fun loadAll(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            if (forceRefresh || _days.value !is UiState.Success) {
                _days.value = UiState.Loading
            }
            val anchor = _anchorDate.value
            _days.value = if (anchor == null || anchor == LocalDate.now()) {
                repository.getTwoSchoolDays(forceRefresh).fold(
                    onSuccess = { list -> UiState.Success(list.map { SchoolDay(it) }) },
                    onFailure = { UiState.Error(it.message ?: "Fehler beim Laden") }
                )
            } else {
                repository.getSchoolDaysFrom(anchor, repository.sessionManager.timetableDays, forceRefresh).fold(
                    onSuccess = { list -> UiState.Success(list.map { SchoolDay(it) }) },
                    onFailure = { UiState.Error(it.message ?: "Fehler beim Laden") }
                )
            }
            repository.getAbsences(forceRefresh).onSuccess { _absences.value = it }
        }
    }

    /**
     * Extends the displayed window by [schoolDays] real school days
     * (positive = append more future days, negative = prepend more past days).
     * The existing days stay visible; new ones are appended/prepended.
     */
    fun shiftDays(schoolDays: Int) {
        viewModelScope.launch {
            val currentDays = (_days.value as? UiState.Success)?.data ?: emptyList()
            val numDays = repository.sessionManager.timetableDays

            if (schoolDays > 0) {
                // Append: load the next [numDays] days after the last currently shown day
                val lastDate = currentDays.lastOrNull()?.date ?: LocalDate.now()
                val newAnchor = lastDate.plusDays(1)
                val extra = repository.getSchoolDaysFrom(newAnchor, numDays)
                    .getOrNull()?.map { SchoolDay(it) } ?: emptyList()
                if (extra.isNotEmpty()) {
                    _days.value = UiState.Success(currentDays + extra)
                }
            } else {
                // Prepend: load [numDays] days before the first currently shown day
                val firstDate = currentDays.firstOrNull()?.date ?: LocalDate.now()
                val searchFrom = firstDate.minusDays(1)
                val newAnchor = findSchoolDayOffset(searchFrom, -numDays)
                val extra = repository.getSchoolDaysFrom(newAnchor, numDays)
                    .getOrNull()
                    ?.map { SchoolDay(it) }
                    ?.filter { it.date.isBefore(firstDate) }
                    ?: emptyList()
                if (extra.isNotEmpty()) {
                    _anchorDate.value = extra.first().date
                    _days.value = UiState.Success(extra + currentDays)
                }
            }
        }
    }

    fun resetToToday() {
        _anchorDate.value = null
        loadAll(forceRefresh = true)
    }

    /**
     * Walks backwards from [from] counting only actual school days
     * until [offset] (negative) is reached.
     */
    private suspend fun findSchoolDayOffset(from: LocalDate, offset: Int): LocalDate {
        // Load a wide window backwards and pick the right position
        val steps = -offset  // positive count
        val windowStart = from.minusDays((steps * 3).toLong().coerceAtLeast(30))
        val rangeResult = repository.getSchoolDaysFrom(windowStart,
            (steps * 3).coerceAtLeast(60))
        val allDays = (rangeResult.getOrNull() ?: emptyList())
            .filter { !it.date.isAfter(from) }
            .sortedByDescending { it.date }
        // Take [steps] days back from 'from'
        return allDays.getOrNull(steps - 1)?.date
            ?: allDays.lastOrNull()?.date
            ?: from.minusDays(steps.toLong())
    }
}
