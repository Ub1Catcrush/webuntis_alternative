package com.webuntis.dashboard.ui.homework

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.webuntis.dashboard.api.WebUntisRepository
import com.webuntis.dashboard.model.Homework
import com.webuntis.dashboard.model.HomeworkAttachment
import com.webuntis.dashboard.model.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
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

    private val _showPast = MutableStateFlow(false)
    val showPast: StateFlow<Boolean> = _showPast

    private val doneIds = mutableSetOf<Int>()

    init { load() }

    fun setShowPast(show: Boolean) {
        if (_showPast.value == show) return
        _showPast.value = show
        load(forceRefresh = false)
    }

    fun load(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            if (forceRefresh || _state.value !is UiState.Success) {
                _state.value = UiState.Loading
            }
            val nameCatalog = repository.getNameCatalog()
            repository.getHomework(forceRefresh).fold(
                onSuccess = { data: Pair<List<Homework>, Map<String, String>> ->
                    val homeworks = data.first
                    val subjectMap = data.second
                    val todayInt = LocalDate.now()
                        .format(DateTimeFormatter.ofPattern("yyyyMMdd")).toInt()
                    
                    val items = homeworks
                        .filter { hw -> 
                            if (_showPast.value) {
                                (hw.dueDate ?: 0) < todayInt
                            } else {
                                (hw.dueDate ?: Int.MAX_VALUE) >= todayInt
                            }
                        }
                        .sortedBy { hw -> 
                            if (_showPast.value) -(hw.dueDate ?: 0) else (hw.dueDate ?: Int.MAX_VALUE)
                        }
                        .map { hw ->
                            val shortSubject = subjectMap[hw.lessonId?.toString()]
                                ?: hw.subject
                                ?: "Aufgabe"
                            val subject = nameCatalog.subjectDisplay(shortSubject)
                            HomeworkUiItem(hw, subject, hw.id in doneIds)
                        }
                    _state.value = UiState.Success(items)
                },
                onFailure = { e: Throwable ->
                    _state.value = UiState.Error(e.message ?: "Fehler beim Laden")
                }
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
        hw: Homework,
        att: HomeworkAttachment,
        context: Context
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.downloadHomeworkAttachment(hw.id, att).fold(
                onSuccess = { res: Pair<ByteArray, String> ->
                    val bytes = res.first
                    val filename = res.second
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            val values = ContentValues().apply {
                                put(MediaStore.Downloads.DISPLAY_NAME, filename)
                                put(MediaStore.Downloads.MIME_TYPE,
                                    att.contentType ?: "application/octet-stream")
                                put(MediaStore.Downloads.RELATIVE_PATH,
                                    Environment.DIRECTORY_DOWNLOADS)
                                put(MediaStore.Downloads.IS_PENDING, 1)
                            }
                            val uri: Uri = context.contentResolver.insert(
                                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                                ?: return@fold
                            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                            values.clear()
                            values.put(MediaStore.Downloads.IS_PENDING, 0)
                            context.contentResolver.update(uri, values, null, null)

                            withContext(Dispatchers.Main) {
                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(uri, att.contentType ?: "*/*")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                runCatching { context.startActivity(intent) }
                            }
                        } else {
                            @Suppress("DEPRECATION")
                            val dir = Environment.getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_DOWNLOADS)
                            dir.mkdirs()
                            val file = File(dir, filename)
                            file.writeBytes(bytes)
                            withContext(Dispatchers.Main) {
                                val uri = FileProvider.getUriForFile(
                                    context, context.packageName + ".fileprovider", file)
                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(uri, att.contentType ?: "*/*")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                runCatching { context.startActivity(intent) }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("Homework", "Download save failed", e)
                    }
                },
                onFailure = { e: Throwable ->
                    Log.e("Homework", "Download failed: ${e.message}")
                }
            )
        }
    }
}
