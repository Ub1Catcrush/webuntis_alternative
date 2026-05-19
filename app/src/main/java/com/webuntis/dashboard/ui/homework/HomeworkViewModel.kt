package com.webuntis.dashboard.ui.homework

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.webuntis.dashboard.api.WebUntisRepository
import com.webuntis.dashboard.model.Homework
import com.webuntis.dashboard.model.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject

data class HomeworkUiItem(
    val homework: Homework,
    val subjectName: String,
    var isDone: Boolean = false
)

@HiltViewModel
class HomeworkViewModel @Inject constructor(
    private val repository: WebUntisRepository
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<List<HomeworkUiItem>>>(UiState.Loading)
    val state: StateFlow<UiState<List<HomeworkUiItem>>> = _state

    private val doneIds = mutableSetOf<Int>()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.value = UiState.Loading
            repository.getHomework().fold(
                onSuccess = { (homeworks, subjectMap) ->
                    val todayInt = LocalDate.now()
                        .format(DateTimeFormatter.ofPattern("yyyyMMdd")).toInt()
                    val items = homeworks
                        .filter { hw -> (hw.dueDate ?: Int.MAX_VALUE) >= todayInt }
                        .sortedBy { hw -> hw.dueDate ?: Int.MAX_VALUE }
                        .map { hw ->
                            val subject = subjectMap[hw.lessonId?.toString()]
                                ?: hw.subject
                                ?: "Aufgabe"
                            HomeworkUiItem(hw, subject, hw.id in doneIds)
                        }
                    _state.value = UiState.Success(items)
                },
                onFailure = { _state.value = UiState.Error(it.message ?: "Fehler") }
            )
        }
    }

    fun toggleDone(id: Int) {
        if (id in doneIds) doneIds.remove(id) else doneIds.add(id)
        val current = (_state.value as? UiState.Success)?.data ?: return
        _state.value = UiState.Success(current.map { item ->
            if (item.homework.id == id) item.copy(isDone = id in doneIds) else item
        })
    }

    suspend fun getUnreadCount(): Int = repository.getUnreadMessageCount()
}
