package com.webuntis.dashboard.ui.classbook

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.webuntis.dashboard.api.WebUntisRepository
import com.webuntis.dashboard.model.Lesson
import com.webuntis.dashboard.model.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * One subject's "Unterrichtsinhalt" entries — i.e. the per-lesson Content field shown in the
 * timetable (Lesson.teachingContent) — most recent first.
 */
data class SubjectContentGroup(val subject: String, val entries: List<Lesson>)

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

    private val _state = MutableStateFlow<UiState<List<SubjectContentGroup>>>(UiState.Loading)
    val state: StateFlow<UiState<List<SubjectContentGroup>>> = _state

    private val _windowDays = MutableStateFlow(repository.sessionManager.lessonContentDefaultDays.coerceAtLeast(1))
    val windowDays: StateFlow<Int> = _windowDays

    private val _canLoadMore = MutableStateFlow(true)
    val canLoadMore: StateFlow<Boolean> = _canLoadMore

    /** Entry count from the previous fetch, to detect when widening the window stopped turning
     *  up anything new (e.g. the start of the school year was reached). */
    private var lastEntryCount = -1

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

        val grouped = lessons
            .groupBy { lesson -> lesson.subjectLongName.takeIf { it != "–" } ?: lesson.subjectName }
            .toSortedMap(compareBy { it.lowercase() })
            .map { (subject, entries) ->
                SubjectContentGroup(subject, entries.sortedByDescending { it.date })
            }

        _state.value = UiState.Success(grouped)
    }
}
