package com.webuntis.dashboard.ui.timetable

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.webuntis.dashboard.api.SessionManager
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

    /**
     * Each lesson's horizontal slice of this row as (lesson, startFraction, widthFraction),
     * all in 0..1. Uses the API's own layoutStartPosition/layoutWidth when every lesson in the
     * group has them — this is authoritative (it's what the official WebUntis web client uses
     * too) and correctly handles uneven splits, not just an equal division. Falls back to an
     * even split only when that layout data isn't available at all.
     */
    fun layoutSlots(): List<Triple<Lesson, Float, Float>> {
        val hasLayout = lessons.isNotEmpty() && lessons.all { it.layoutWidth != null && it.layoutStartPosition != null }
        if (hasLayout) {
            val totalWidth = lessons.maxOf { (it.layoutStartPosition ?: 0) + (it.layoutWidth ?: 0) }.coerceAtLeast(1)
            return lessons.map {
                val start = (it.layoutStartPosition ?: 0).toFloat() / totalWidth
                val width = (it.layoutWidth ?: totalWidth).toFloat() / totalWidth
                Triple(it, start, width)
            }
        }
        val n = lessons.size.coerceAtLeast(1)
        return lessons.mapIndexed { i, l -> Triple(l, i.toFloat() / n, 1f / n) }
    }
}

/** UI model for the timetable tab — wraps TimetableDay with a display label */
data class SchoolDay(val day: TimetableDay) {
    val date: LocalDate get() = day.date
    val lessons: List<Lesson> get() = day.lessons
    val tabLabel: String get() = day.label

    /**
     * Groups lessons that should be displayed side-by-side.
     *
     * Preferred approach: the WebUntis API already tells us exactly which lessons at the same
     * time belong together via `layoutGroup` (same value = same row, shown side-by-side using
     * `layoutStartPosition`/`layoutWidth`) — this is authoritative straight from the server and
     * correctly handles cases a local heuristic can't, e.g. 3 active parallel course offerings
     * plus a 4th, independently cancelled one, all sharing the same time slot: naively assuming
     * "any cancelled + any active at the same time = one is a stand-in for the other" (the
     * Ersatzstunden case) would wrongly stack all 4 vertically instead of side-by-side.
     *
     * Fallback (only used when the server didn't provide layoutGroup for a given time slot):
     * a lesson that's a substitution/replacement for a specific cancelled one stays separate
     * (vertical), while multiple simultaneous ACTIVE lessons are grouped side-by-side.
     */
    val groupedLessons: List<LessonGroup> by lazy {
        val result = mutableListOf<LessonGroup>()
        val timeGroups = day.lessons.groupBy { it.startTime to it.endTime }
        val sortedTimes = timeGroups.keys.sortedWith(compareBy({ it.first }, { it.second }))

        for (time in sortedTimes) {
            val list = timeGroups[time]!!

            if (list.any { it.layoutGroup != null }) {
                // Server-provided layout — trust it. Lessons without a layoutGroup of their own
                // (shouldn't normally happen when others in the same slot have one) each get
                // their own singleton row so nothing is silently dropped.
                val byLayoutGroup = list.groupBy { it.layoutGroup }
                for ((layoutGroup, groupLessons) in byLayoutGroup) {
                    if (layoutGroup == null) {
                        groupLessons.forEach { result.add(LessonGroup(listOf(it))) }
                    } else {
                        result.add(LessonGroup(groupLessons.sortedBy { it.layoutStartPosition ?: 0 }))
                    }
                }
            } else {
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
    val showShortSubjectInParens: Boolean get() = repository.sessionManager.showShortSubjectInParens
    val showShortTeacherInParens: Boolean get() = repository.sessionManager.showShortTeacherInParens
    val showShortRoomInParens:    Boolean get() = repository.sessionManager.showShortRoomInParens
    val useCompactWeekView: Boolean get() = repository.sessionManager.useCompactWeekView

    val weekViewSecondLine: com.webuntis.dashboard.api.SessionManager.WeekViewSecondLine
        get() = repository.sessionManager.weekViewSecondLine

    /** Switches between the day and the week grid directly from the timetable screen. */
    fun toggleUseWeekView() {
        repository.sessionManager.useCompactWeekView = !repository.sessionManager.useCompactWeekView
    }

    // ── Personal / Class timetable switch ───────────────────────────────────────

    val timetableViewMode: SessionManager.TimetableViewMode get() = repository.sessionManager.timetableViewMode
    /** True once a class element id is known, i.e. the class timetable can actually be requested. */
    val canShowClassTimetable: Boolean get() = repository.sessionManager.canShowClassTimetable

    /** Switches between the personal and the class timetable and reloads. */
    fun setTimetableViewMode(mode: SessionManager.TimetableViewMode) {
        if (repository.sessionManager.timetableViewMode == mode) return
        repository.setTimetableViewMode(mode)
        loadAll(forceRefresh = true)
    }

    fun toggleTimetableViewMode() {
        val next = when (timetableViewMode) {
            SessionManager.TimetableViewMode.PERSONAL -> SessionManager.TimetableViewMode.CLASS
            SessionManager.TimetableViewMode.CLASS     -> SessionManager.TimetableViewMode.COMBINED
            SessionManager.TimetableViewMode.COMBINED  -> SessionManager.TimetableViewMode.PERSONAL
        }
        setTimetableViewMode(next)
    }

    // ── Combined view: which class-plan subjects fill gaps in the personal plan ────────────────

    val combinedOverlaySubjects: Set<String> get() = repository.sessionManager.combinedOverlaySubjects

    /** Saves the chosen subjects and reloads. Switches to COMBINED mode if not already active. */
    fun setCombinedOverlaySubjects(subjects: Set<String>) {
        repository.setCombinedOverlaySubjects(subjects)
        if (timetableViewMode != SessionManager.TimetableViewMode.COMBINED) {
            repository.setTimetableViewMode(SessionManager.TimetableViewMode.COMBINED)
        }
        loadAll(forceRefresh = true)
    }

    /** Distinct subjects currently available in the class plan, for the picker dialog. */
    suspend fun loadAvailableClassSubjects(): List<com.webuntis.dashboard.model.ClassSubjectOption> =
        repository.getAvailableClassSubjects()

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
