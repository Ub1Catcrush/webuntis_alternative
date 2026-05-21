package com.webuntis.dashboard.ui.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.webuntis.dashboard.api.WebUntisRepository
import com.webuntis.dashboard.model.Attachment
import com.webuntis.dashboard.model.Message
import com.webuntis.dashboard.model.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MessagesViewModel @Inject constructor(
    private val repository: WebUntisRepository
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<List<Message>>>(UiState.Loading)
    val state: StateFlow<UiState<List<Message>>> = _state

    // Expanded message id → Message with attachments loaded
    private val _expanded = MutableStateFlow<Map<Int, Message>>(emptyMap())
    val expanded: StateFlow<Map<Int, Message>> = _expanded

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.value = UiState.Loading
            repository.getMessages().fold(
                onSuccess = { _state.value = UiState.Success(it) },
                onFailure = { _state.value = UiState.Error(it.message ?: context.getString(R.string.error_generic)) }
            )
        }
    }

    fun toggleExpand(msg: Message) {
        viewModelScope.launch {
            val current = _expanded.value
            if (current.containsKey(msg.id)) {
                // Collapse
                _expanded.value = current - msg.id
            } else {
                // Expand — fetch attachments if needed
                val withAtts = if (msg.hasAttachments == true) {
                    repository.getMessageWithAttachments(msg).getOrDefault(msg)
                } else msg
                _expanded.value = current + (msg.id to withAtts)
            }
        }
    }

    fun download(attachmentId: String, filename: String, msg: Message) {
        viewModelScope.launch {
            _downloadState.value = DownloadState.Loading(filename)
            repository.downloadAttachment(attachmentId, msg).fold(
                onSuccess = { bytes -> _downloadState.value = DownloadState.Ready(filename, bytes) },
                onFailure = { _downloadState.value = DownloadState.Error(it.message ?: context.getString(R.string.error_generic)) }
            )
        }
    }

    fun clearDownload() { _downloadState.value = DownloadState.Idle }
}

sealed class DownloadState {
    object Idle : DownloadState()
    data class Loading(val filename: String) : DownloadState()
    data class Ready(val filename: String, val bytes: ByteArray) : DownloadState()
    data class Error(val message: String) : DownloadState()
}
