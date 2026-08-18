package com.webuntis.dashboard.ui.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.webuntis.dashboard.api.SessionManager
import com.webuntis.dashboard.api.WebUntisRepository
import com.webuntis.dashboard.model.Message
import com.webuntis.dashboard.model.RecipientPerson
import com.webuntis.dashboard.model.Teacher
import com.webuntis.dashboard.model.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class MessagesTab { INBOX, SENT, DRAFTS }

@HiltViewModel
class MessagesViewModel @Inject constructor(
    private val repository: WebUntisRepository,
    val sessionManager: SessionManager
) : ViewModel() {

    // ── Tab ────────────────────────────────────────────────────────────────────
    private val _activeTab = MutableStateFlow(MessagesTab.INBOX)
    val activeTab: StateFlow<MessagesTab> = _activeTab

    // ── Lists ──────────────────────────────────────────────────────────────────
    private val _inboxState  = MutableStateFlow<UiState<List<Message>>>(UiState.Loading)
    val inboxState: StateFlow<UiState<List<Message>>> = _inboxState

    private val _sentState   = MutableStateFlow<UiState<List<Message>>>(UiState.Loading)
    val sentState: StateFlow<UiState<List<Message>>> = _sentState

    private val _draftsState = MutableStateFlow<UiState<List<Message>>>(UiState.Loading)
    val draftsState: StateFlow<UiState<List<Message>>> = _draftsState

    // ── Unread count ───────────────────────────────────────────────────────────
    private val _unreadCount = MutableStateFlow(0)
    val unreadCount: StateFlow<Int> = _unreadCount

    // ── Expanded (thread) ──────────────────────────────────────────────────────
    private val _expanded = MutableStateFlow<Map<Int, Message>>(emptyMap())
    val expanded: StateFlow<Map<Int, Message>> = _expanded

    // ── Teachers ───────────────────────────────────────────────────────────────
    private val _teachers = MutableStateFlow<List<RecipientPerson>>(emptyList())
    val teachers: StateFlow<List<RecipientPerson>> = _teachers

    // ── Short↔long name lookup, for showing sender/recipient as "Langform (Kürzel)" ────────────
    private val _nameCatalog = MutableStateFlow(com.webuntis.dashboard.model.NameCatalog())
    val nameCatalog: StateFlow<com.webuntis.dashboard.model.NameCatalog> = _nameCatalog

    // ── Compose ────────────────────────────────────────────────────────────────
    private val _composeState = MutableStateFlow<ComposeState>(ComposeState.Closed)
    val composeState: StateFlow<ComposeState> = _composeState

    // ── Pending attachments (for compose / draft) ─────────────────────────────
    // New files to upload: filename → bytes
    private val _pendingAttachments = MutableStateFlow<List<Pair<String, ByteArray>>>(emptyList())
    val pendingAttachments: StateFlow<List<Pair<String, ByteArray>>> = _pendingAttachments
    // Existing server-side attachments to keep: id → name
    private val _existingAttachments = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val existingAttachments: StateFlow<List<Pair<String, String>>> = _existingAttachments

    fun addAttachment(filename: String, bytes: ByteArray) {
        _pendingAttachments.value = _pendingAttachments.value + (filename to bytes)
    }
    fun removeAttachment(filename: String) {
        _pendingAttachments.value = _pendingAttachments.value.filterNot { it.first == filename }
    }
    fun addExistingAttachment(id: String, name: String) {
        if (_existingAttachments.value.none { it.first == id })
            _existingAttachments.value = _existingAttachments.value + (id to name)
    }
    fun removeExistingAttachment(id: String) {
        _existingAttachments.value = _existingAttachments.value.filterNot { it.first == id }
    }
    fun clearAttachments() {
        _pendingAttachments.value = emptyList()
        _existingAttachments.value = emptyList()
    }
    /** IDs that were present on load but have since been removed — sent as attachmentIdsToDelete */
    private var _originalAttachmentIds: Set<String> = emptySet()
    fun setOriginalAttachmentIds(ids: Set<String>) { _originalAttachmentIds = ids }
    val removedAttachmentIds: List<String>
        get() = _originalAttachmentIds.filterNot { id -> _existingAttachments.value.any { it.first == id } }

    // ── Download ───────────────────────────────────────────────────────────────
    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState

    init {
        loadInbox()
        refreshUnreadCount()
        loadNameCatalog()
    }

    private fun loadNameCatalog() {
        viewModelScope.launch { _nameCatalog.value = repository.getNameCatalog() }
    }

    // ── Tab switching ──────────────────────────────────────────────────────────

    fun switchTab(tab: MessagesTab) {
        _activeTab.value = tab
        when (tab) {
            MessagesTab.INBOX  -> { if (_inboxState.value  !is UiState.Success) loadInbox() }
            MessagesTab.SENT   -> loadSent()
            MessagesTab.DRAFTS -> loadDrafts()
        }
    }

    fun refresh() = when (_activeTab.value) {
        MessagesTab.INBOX  -> loadInbox(true)
        MessagesTab.SENT   -> loadSent(true)
        MessagesTab.DRAFTS -> loadDrafts(true)
    }

    // ── Load ───────────────────────────────────────────────────────────────────

    fun loadInbox(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            if (forceRefresh || _inboxState.value !is UiState.Success) _inboxState.value = UiState.Loading
            repository.getMessages(forceRefresh).fold(
                onSuccess = { _inboxState.value = UiState.Success(it); refreshUnreadCount() },
                onFailure = { _inboxState.value = UiState.Error(it.message ?: "Fehler") }
            )
        }
    }

    // Keep legacy name so nothing else breaks
    fun load(forceRefresh: Boolean = false) = loadInbox(forceRefresh)

    fun loadSent(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            _sentState.value = UiState.Loading
            repository.getSentMessages(forceRefresh).fold(
                onSuccess = { _sentState.value = UiState.Success(it) },
                onFailure = { _sentState.value = UiState.Error(it.message ?: "Fehler") }
            )
        }
    }

    fun loadDrafts(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            _draftsState.value = UiState.Loading
            repository.getDrafts(forceRefresh).fold(
                onSuccess = { _draftsState.value = UiState.Success(it) },
                onFailure = { _draftsState.value = UiState.Error(it.message ?: "Fehler") }
            )
        }
    }

    fun refreshUnreadCount() {
        viewModelScope.launch { _unreadCount.value = repository.getUnreadMessageCount() }
    }

    // ── Expand / Thread ────────────────────────────────────────────────────────

    fun toggleExpand(msg: Message) {
        viewModelScope.launch {
            val current = _expanded.value
            if (current.containsKey(msg.id)) {
                _expanded.value = current - msg.id
            } else {
                val withDetail = if (msg.hasAttachments == true || msg.replyHistory == null) {
                    repository.getMessageWithAttachments(msg).getOrDefault(msg)
                } else msg
                _expanded.value = current + (msg.id to withDetail)
            }
        }
    }

    // ── Compose ────────────────────────────────────────────────────────────────

    fun openCompose(draft: Message? = null, replyTo: Message? = null) {
        viewModelScope.launch {
            if (_teachers.value.isEmpty()) {
                repository.getTeachers().onSuccess { _teachers.value = it }
            }
            // For drafts: fetch full detail so existing attachments are visible in the dialog
            val resolvedDraft = if (draft != null) {
                repository.getMessageWithAttachments(draft).getOrDefault(draft)
            } else null
            _composeState.value = ComposeState.Open(draft = resolvedDraft, replyTo = replyTo)
        }
    }

    fun closeCompose() { _composeState.value = ComposeState.Closed; clearAttachments(); _originalAttachmentIds = emptySet() }

    fun sendMessage(
        subject: String,
        content: String,
        recipientIds: List<Int>,
        fromSecondAccount: Boolean,
        replyToMsgId: Int? = null
    ) {
        viewModelScope.launch {
            _composeState.value = ComposeState.Sending
            repository.sendMessage(subject, content, recipientIds,
                allowReply = true, replyToMsgId = replyToMsgId,
                fromSecondAccount = fromSecondAccount
            ).fold(
                onSuccess = {
                    _composeState.value = ComposeState.Sent
                    loadInbox(true); loadSent(true)
                },
                onFailure = { _composeState.value = ComposeState.Error(it.message ?: "Senden fehlgeschlagen") }
            )
        }
    }

    fun saveDraft(
        subject: String,
        content: String,
        fromSecondAccount: Boolean,
        draftId: Int? = null
    ) {
        viewModelScope.launch {
            _composeState.value = ComposeState.Saving
            repository.saveDraft(
                subject = subject,
                content = content,
                draftId = draftId,
                fromSecondAccount = fromSecondAccount,
                attachments = _pendingAttachments.value.filter { it.second.isNotEmpty() },
                removedAttachmentIds = removedAttachmentIds
            ).fold(
                onSuccess = {
                    _composeState.value = ComposeState.Saved
                    clearAttachments()
                    loadDrafts(true)
                },
                onFailure = { _composeState.value = ComposeState.Error(it.message ?: "Speichern fehlgeschlagen") }
            )
        }
    }

    // ── Delete ─────────────────────────────────────────────────────────────────

    fun deleteMessage(msg: Message) {
        viewModelScope.launch {
            repository.deleteMessage(msg).onSuccess {
                when {
                    msg.isDraft -> loadDrafts(true)
                    msg.isSent  -> loadSent(true)
                    else        -> loadInbox(true)
                }
            }
        }
    }

    // ── Download ───────────────────────────────────────────────────────────────

    fun download(attachmentId: String, filename: String, msg: Message) {
        viewModelScope.launch {
            _downloadState.value = DownloadState.Loading(filename)
            repository.downloadAttachment(attachmentId, msg).fold(
                onSuccess = { bytes -> _downloadState.value = DownloadState.Ready(filename, bytes) },
                onFailure = { _downloadState.value = DownloadState.Error(it.message ?: "Fehler") }
            )
        }
    }

    fun clearDownload() { _downloadState.value = DownloadState.Idle }
}

// ── Sealed states ──────────────────────────────────────────────────────────────

sealed class ComposeState {
    object Closed  : ComposeState()
    data class Open(val draft: Message? = null, val replyTo: Message? = null) : ComposeState()
    object Sending : ComposeState()
    object Sent    : ComposeState()
    object Saving  : ComposeState()
    object Saved   : ComposeState()
    data class Error(val message: String) : ComposeState()
}

sealed class DownloadState {
    object Idle : DownloadState()
    data class Loading(val filename: String) : DownloadState()
    data class Ready(val filename: String, val bytes: ByteArray) : DownloadState()
    data class Error(val message: String) : DownloadState()
}
