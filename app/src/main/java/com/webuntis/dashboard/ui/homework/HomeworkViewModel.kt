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

    fun load(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            _state.value = UiState.Loading
            repository.getHomework(forceRefresh).fold(
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
                onFailure = { _state.value = UiState.Error(it.message ?: "Fehler beim Laden") }
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

    fun downloadAttachment(
        hw: com.webuntis.dashboard.model.Homework,
        att: com.webuntis.dashboard.model.HomeworkAttachment,
        context: android.content.Context
    ) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val result = repository.downloadHomeworkAttachment(hw.id, att)
            result.fold(
                onSuccess = { (bytes, filename) ->
                    try {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                            // API 29+ : MediaStore.Downloads (no WRITE_EXTERNAL_STORAGE needed)
                            val values = android.content.ContentValues().apply {
                                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, filename)
                                put(android.provider.MediaStore.Downloads.MIME_TYPE,
                                    att.contentType ?: "application/octet-stream")
                                put(android.provider.MediaStore.Downloads.RELATIVE_PATH,
                                    android.os.Environment.DIRECTORY_DOWNLOADS)
                                put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
                            }
                            val uri = context.contentResolver.insert(
                                android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                                ?: return@launch
                            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                            values.clear()
                            values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
                            context.contentResolver.update(uri, values, null, null)

                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                    setDataAndType(uri, att.contentType ?: "*/*")
                                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                runCatching { context.startActivity(intent) }
                            }
                        } else {
                            // API 26-28: legacy external storage (requires WRITE_EXTERNAL_STORAGE)
                            @Suppress("DEPRECATION")
                            val dir = android.os.Environment.getExternalStoragePublicDirectory(
                                android.os.Environment.DIRECTORY_DOWNLOADS)
                            dir.mkdirs()
                            val file = java.io.File(dir, filename)
                            file.writeBytes(bytes)
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                val uri = androidx.core.content.FileProvider.getUriForFile(
                                    context, context.packageName + ".fileprovider", file)
                                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                    setDataAndType(uri, att.contentType ?: "*/*")
                                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                runCatching { context.startActivity(intent) }
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("Homework", "Download save failed", e)
                    }
                },
                onFailure = { e ->
                    android.util.Log.e("Homework", "Download failed: ${e.message}")
                }
            )
        }
    }
}
