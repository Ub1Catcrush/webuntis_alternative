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

    private val _unreadCount = MutableStateFlow(0)
    val unreadCount: StateFlow<Int> = _unreadCount

    // Expanded message id → Message with attachments loaded
    private val _expanded = MutableStateFlow<Map<Int, Message>>(emptyMap())
    val expanded: StateFlow<Map<Int, Message>> = _expanded

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState

    init {
        load()
        refreshUnreadCount()
    }

    fun load(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            // Guard removed: repository handles cache internally.
            // Local StateFlow must be updated even if data comes from cache.
            if (forceRefresh || _state.value !is UiState.Success) {
                _state.value = UiState.Loading
            }
            repository.getMessages(forceRefresh).fold(
                onSuccess = {
                    _state.value = UiState.Success(it)
                    // Also refresh unread count whenever we load messages
                    refreshUnreadCount()
                },
                onFailure = { _state.value = UiState.Error(it.message ?: "Fehler beim Laden") }
            )
        }
    }

    fun refreshUnreadCount() {
        viewModelScope.launch {
            _unreadCount.value = repository.getUnreadMessageCount()
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
                onFailure = { _downloadState.value = DownloadState.Error(it.message ?: "Fehler beim Laden") }
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
