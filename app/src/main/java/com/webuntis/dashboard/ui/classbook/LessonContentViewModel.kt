package com.webuntis.dashboard.ui.classbook

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.webuntis.dashboard.api.SessionManager
import com.webuntis.dashboard.api.WebUntisRepository
import com.webuntis.dashboard.model.Lesson
import com.webuntis.dashboard.model.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject

/**
 * One section's "Unterrichtsinhalt" entries — i.e. the per-lesson Content field shown in the
 * timetable (Lesson.teachingContent) — grouped either by subject or by day depending on
 * [LessonContentViewModel.groupMode]. [header] is the subject name or the formatted day,
 * respectively.
 */
data class ContentGroup(val header: String, val entries: List<Lesson>)

@HiltViewModel
class LessonContentViewModel @Inject constructor(
    private val repository: WebUntisRepository
) : ViewModel() {

    companion object {
        /** How many more days "Weitere Tage laden" adds on each tap. */
        const val LOAD_MORE_INCREMENT = 14
        /** Safety cap on how far "Weitere Tage laden" can widen the window, to bound the number
         *  of per-lesson detail calls a single tab can trigger. */
        const val MAX_WINDOW_DAYS = 180
    }

    private val _state = MutableStateFlow<UiState<List<ContentGroup>>>(UiState.Loading)
    val state: StateFlow<UiState<List<ContentGroup>>> = _state

    private val _windowDays = MutableStateFlow(repository.sessionManager.lessonContentDefaultDays.coerceAtLeast(1))
    val windowDays: StateFlow<Int> = _windowDays

    private val _canLoadMore = MutableStateFlow(true)
    val canLoadMore: StateFlow<Boolean> = _canLoadMore

    private val _groupMode = MutableStateFlow(repository.sessionManager.lessonContentGroupMode)
    val groupMode: StateFlow<SessionManager.LessonContentGroupMode> = _groupMode

    /** Entry count from the previous fetch, to detect when widening the window stopped turning
     *  up anything new (e.g. the start of the school year was reached). */
    private var lastEntryCount = -1

    /** The lessons from the most recent fetch, kept around so switching group mode is a pure
     *  re-grouping of already-loaded data — no network call needed. */
    private var lastLessons: List<Lesson> = emptyList()

    init {
        load()
    }

    fun load(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            if (forceRefresh || _state.value !is UiState.Success) {
                _state.value = UiState.Loading
            }
            fetchAndApply(_windowDays.value, forceRefresh)
        }
    }

    /** Widens the visible window by [LOAD_MORE_INCREMENT] more days and re-fetches — only the
     *  newly-uncovered (older) slice actually hits the network, see
     *  WebUntisRepository.getTeachingContentEntries(). */
    fun loadMoreDays() {
        if (_windowDays.value >= MAX_WINDOW_DAYS) return
        viewModelScope.launch {
            _windowDays.value = (_windowDays.value + LOAD_MORE_INCREMENT).coerceAtMost(MAX_WINDOW_DAYS)
            fetchAndApply(_windowDays.value, forceRefresh = false)
        }
    }

    /** Switches between grouping by subject and by day, persists the choice as the new default,
     *  and immediately re-renders from the already-loaded lessons (no re-fetch). */
    fun toggleGroupMode() {
        val next = if (_groupMode.value == SessionManager.LessonContentGroupMode.BY_DAY)
            SessionManager.LessonContentGroupMode.BY_SUBJECT
        else
            SessionManager.LessonContentGroupMode.BY_DAY
        repository.sessionManager.lessonContentGroupMode = next
        _groupMode.value = next
        if (lastLessons.isNotEmpty() || _state.value is UiState.Success) {
            _state.value = UiState.Success(group(lastLessons, next))
        }
    }

    private suspend fun fetchAndApply(days: Int, forceRefresh: Boolean) {
        repository.getTeachingContentEntries(days, forceRefresh).fold(
            onSuccess = { lessons -> applyResult(lessons, days) },
            onFailure = { _state.value = UiState.Error(it.message ?: "Fehler beim Laden") }
        )
    }

    private fun applyResult(lessons: List<Lesson>, days: Int) {
        _canLoadMore.value = days < MAX_WINDOW_DAYS &&
            (lastEntryCount == -1 || lessons.size > lastEntryCount)
        lastEntryCount = lessons.size
        lastLessons = lessons

        _state.value = UiState.Success(group(lessons, _groupMode.value))
    }

    private fun group(lessons: List<Lesson>, mode: SessionManager.LessonContentGroupMode): List<ContentGroup> {
        return if (mode == SessionManager.LessonContentGroupMode.BY_SUBJECT) {
            lessons
                .groupBy { lesson -> lesson.subjectLongName.takeIf { it != "–" } ?: lesson.subjectName }
                .toSortedMap(compareBy { it.lowercase() })
                .map { (subject, entries) -> ContentGroup(subject, entries.sortedByDescending { it.date }) }
        } else {
            lessons
                .groupBy { it.date }
                .toSortedMap(compareByDescending { it })
                .map { (date, entries) ->
                    val local = entries.firstOrNull()?.localDate
                    val weekday = local?.dayOfWeek?.getDisplayName(TextStyle.FULL, Locale.GERMAN)
                    val formatted = entries.firstOrNull()?.dateFormatted ?: date.toString()
                    val header = if (weekday != null) "$weekday, $formatted" else formatted
                    ContentGroup(header, entries.sortedBy { it.startTime })
                }
        }
    }
}
