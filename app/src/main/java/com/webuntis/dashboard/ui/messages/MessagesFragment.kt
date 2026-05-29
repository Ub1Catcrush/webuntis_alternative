package com.webuntis.dashboard.ui.messages

import android.content.ContentValues
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.*
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.tabs.TabLayout
import com.webuntis.dashboard.R
import com.webuntis.dashboard.databinding.FragmentMessagesBinding
import com.webuntis.dashboard.databinding.ItemMessageBinding
import com.webuntis.dashboard.model.Attachment
import com.webuntis.dashboard.model.Message
import com.webuntis.dashboard.model.ReplyMessage
import com.webuntis.dashboard.model.Teacher
import com.webuntis.dashboard.model.UiState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MessagesFragment : Fragment() {

    private var _binding: FragmentMessagesBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MessagesViewModel by viewModels()
    private lateinit var adapter: MessageAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMessagesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = MessageAdapter(
            onToggleExpand   = { msg -> viewModel.toggleExpand(msg) },
            onDownload       = { id, name, msg -> viewModel.download(id, name, msg) },
            onReply          = { msg -> viewModel.openCompose(replyTo = msg) },
            onEditDraft      = { msg -> viewModel.openCompose(draft = msg) },
            onDelete         = { msg -> confirmDelete(msg) },
            expandedProvider = { viewModel.expanded.value }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
        binding.swipeRefresh.setOnRefreshListener { viewModel.refresh() }

        // ── Tabs ──────────────────────────────────────────────────────────────
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("Eingang"))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("Gesendet"))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("Entwürfe"))

        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                viewModel.switchTab(when (tab?.position) {
                    1    -> MessagesTab.SENT
                    2    -> MessagesTab.DRAFTS
                    else -> MessagesTab.INBOX
                })
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) { viewModel.refresh() }
        })

        // ── FAB ───────────────────────────────────────────────────────────────
        binding.fabCompose.setOnClickListener { viewModel.openCompose() }

        observeStates()
    }

    private fun observeStates() {
        // Sync tab position → active tab
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.activeTab.collect { tab ->
                    val idx = when (tab) { MessagesTab.INBOX -> 0; MessagesTab.SENT -> 1; MessagesTab.DRAFTS -> 2 }
                    if (binding.tabLayout.selectedTabPosition != idx)
                        binding.tabLayout.getTabAt(idx)?.select()
                }
            }
        }

        // Render active tab's state
        fun renderActive() {
            val state = when (viewModel.activeTab.value) {
                MessagesTab.INBOX  -> viewModel.inboxState.value
                MessagesTab.SENT   -> viewModel.sentState.value
                MessagesTab.DRAFTS -> viewModel.draftsState.value
            }
            renderState(state)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.activeTab.collect  { renderActive() } }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.inboxState.collect  { if (viewModel.activeTab.value == MessagesTab.INBOX)  renderState(it) } }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.sentState.collect   { if (viewModel.activeTab.value == MessagesTab.SENT)   renderState(it) } }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.draftsState.collect { if (viewModel.activeTab.value == MessagesTab.DRAFTS) renderState(it) } }
        }

        // Expanded state changes → re-render list
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.expanded.collect { adapter.notifyDataSetChanged() } }
        }

        // Downloads
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.downloadState.collect { state ->
                    when (state) {
                        is DownloadState.Ready -> { saveFile(state.filename, state.bytes); viewModel.clearDownload() }
                        is DownloadState.Error -> { Toast.makeText(requireContext(), "Download fehlgeschlagen: ${state.message}", Toast.LENGTH_LONG).show(); viewModel.clearDownload() }
                        else -> {}
                    }
                }
            }
        }

        // Compose state
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.composeState.collect { state ->
                    when (state) {
                        is ComposeState.Open  -> showComposeDialog(state.draft, state.replyTo)
                        is ComposeState.Sent  -> { Toast.makeText(requireContext(), "✓ Gesendet", Toast.LENGTH_SHORT).show(); viewModel.closeCompose() }
                        is ComposeState.Error -> Toast.makeText(requireContext(), state.message, Toast.LENGTH_LONG).show()
                        else -> {}
                    }
                }
            }
        }
    }

    private fun renderState(state: UiState<List<Message>>) {
        binding.swipeRefresh.isRefreshing = false
        when (state) {
            is UiState.Loading -> {
                binding.progressBar.isVisible = true
                binding.recyclerView.isVisible = false
                binding.emptyView.isVisible = false
            }
            is UiState.Success -> {
                binding.progressBar.isVisible = false
                if (state.data.isEmpty()) {
                    binding.recyclerView.isVisible = false
                    binding.emptyView.isVisible = true
                    binding.emptyView.text = when (viewModel.activeTab.value) {
                        MessagesTab.INBOX  -> "Keine Nachrichten"
                        MessagesTab.SENT   -> "Keine gesendeten Nachrichten"
                        MessagesTab.DRAFTS -> "Keine Entwürfe"
                    }
                } else {
                    binding.recyclerView.isVisible = true
                    binding.emptyView.isVisible = false
                    adapter.submitList(state.data)
                }
            }
            is UiState.Error -> {
                binding.progressBar.isVisible = false
                binding.recyclerView.isVisible = false
                binding.emptyView.isVisible = true
                binding.emptyView.text = state.message
            }
        }
    }

    // ── Compose Dialog ─────────────────────────────────────────────────────────

    private var composeOpen = false

    private fun showComposeDialog(draft: Message?, replyTo: Message?) {
        if (composeOpen) return
        composeOpen = true

        val ctx = requireContext()
        val scroll = ScrollView(ctx)
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 16)
        }
        scroll.addView(layout)

        // Account spinner (only shown when 2nd account exists)
        val hasSecond   = viewModel.sessionManager.secondAccount != null
        val primaryName = viewModel.sessionManager.session?.personName ?: "Hauptaccount"
        val secondName  = viewModel.sessionManager.secondAccount?.label
            ?.takeIf { it.isNotBlank() }
            ?: viewModel.sessionManager.secondAccount?.personName
            ?: "2. Account"
        val accountSpinner = Spinner(ctx)
        if (hasSecond) {
            accountSpinner.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item,
                listOf("Von: $primaryName", "Von: $secondName"))
            layout.addView(accountSpinner)
        }

        // Subject
        val etSubject = EditText(ctx).apply {
            hint = "Betreff"
            setText(when {
                replyTo != null -> replyTo.subject?.let { if (it.startsWith("Re:")) it else "Re: $it" } ?: ""
                draft   != null -> draft.subject ?: ""
                else            -> ""
            })
            isEnabled = replyTo == null
        }
        layout.addView(etSubject)

        // Recipient chips
        layout.addView(TextView(ctx).apply { text = "Empfänger:" })
        val selectedRecipients = mutableListOf<Teacher>()
        val chipGroup = ChipGroup(ctx).apply { isSingleLine = false }
        layout.addView(chipGroup)

        fun addChip(teacher: Teacher) {
            if (selectedRecipients.any { it.id == teacher.id }) return
            selectedRecipients.add(teacher)
            chipGroup.addView(Chip(ctx).apply {
                text = teacher.displayName
                isCloseIconVisible = true
                setOnCloseIconClickListener { selectedRecipients.remove(teacher); chipGroup.removeView(this) }
            })
        }

        // Pre-fill from replyTo sender or draft recipients
        if (replyTo != null) {
            replyTo.sender?.userId?.let { uid ->
                addChip(Teacher(uid, replyTo.sender.displayName, replyTo.sender.displayName))
            }
        }
        draft?.recipientPersons?.forEach { r ->
            r.personId?.let { addChip(Teacher(it, r.displayName, r.displayName)) }
        }

        val acTeacher = AutoCompleteTextView(ctx).apply { hint = "Lehrer suchen…"; threshold = 1 }
        layout.addView(acTeacher)

        // Populate autocomplete when teachers are loaded
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.teachers.collect { teachers ->
                if (teachers.isNotEmpty()) {
                    val adapter = ArrayAdapter(ctx, android.R.layout.simple_dropdown_item_1line,
                        teachers.map { it.displayName })
                    acTeacher.setAdapter(adapter)
                    acTeacher.setOnItemClickListener { _, _, pos, _ ->
                        val name = adapter.getItem(pos)?.toString() ?: return@setOnItemClickListener
                        teachers.firstOrNull { it.displayName == name }?.let { addChip(it) }
                        acTeacher.text.clear()
                    }
                }
            }
        }

        // Body
        val etBody = EditText(ctx).apply {
            hint = "Nachricht"
            minLines = 5
            setText(draft?.contentPreview ?: "")
        }
        layout.addView(etBody)

        AlertDialog.Builder(ctx)
            .setTitle(when { draft != null -> "Entwurf bearbeiten"; replyTo != null -> "Antworten"; else -> "Neue Nachricht" })
            .setView(scroll)
            .setPositiveButton("Senden") { _, _ ->
                val subject = etSubject.text.toString().trim()
                val body    = etBody.text.toString().trim()
                val ids     = selectedRecipients.map { it.id }
                if (subject.isBlank() || body.isBlank() || ids.isEmpty()) {
                    Toast.makeText(ctx, "Bitte Betreff, Empfänger und Nachricht ausfüllen.", Toast.LENGTH_LONG).show()
                    composeOpen = false; return@setPositiveButton
                }
                val fromSecond = hasSecond && accountSpinner.selectedItemPosition == 1
                viewModel.sendMessage(subject, body, ids, fromSecond, replyTo?.id)
                composeOpen = false
            }
            .setNegativeButton("Abbrechen") { _, _ -> viewModel.closeCompose(); composeOpen = false }
            .setOnDismissListener { composeOpen = false }
            .show()
    }

    private fun confirmDelete(msg: Message) {
        AlertDialog.Builder(requireContext())
            .setTitle(if (msg.isDraft) "Entwurf löschen?" else "Nachricht löschen?")
            .setMessage(msg.subject ?: "(Kein Betreff)")
            .setPositiveButton("Löschen") { _, _ -> viewModel.deleteMessage(msg) }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    // ── File saving ────────────────────────────────────────────────────────────

    private fun saveFile(filename: String, bytes: ByteArray) {
        try {
            val ctx = requireContext()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val cv = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, filename)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType(filename))
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                    ?: throw Exception("MediaStore insert fehlgeschlagen")
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: throw Exception("OutputStream konnte nicht geöffnet werden")
                cv.clear(); cv.put(MediaStore.Downloads.IS_PENDING, 0)
                ctx.contentResolver.update(uri, cv, null, null)
                Toast.makeText(ctx, "✓ Gespeichert: $filename", Toast.LENGTH_SHORT).show()
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mimeType(filename))
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                try { startActivity(intent) } catch (_: Exception) {}
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                dir.mkdirs()
                java.io.File(dir, filename).writeBytes(bytes)
                Toast.makeText(ctx, "✓ Gespeichert: $filename", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Speichern fehlgeschlagen: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun mimeType(filename: String) = when {
        filename.endsWith(".pdf",  true) -> "application/pdf"
        filename.endsWith(".png",  true) -> "image/png"
        filename.endsWith(".jpg",  true) || filename.endsWith(".jpeg", true) -> "image/jpeg"
        filename.endsWith(".docx", true) -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        filename.endsWith(".xlsx", true) -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        else -> "application/octet-stream"
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}

// ── MessageAdapter ─────────────────────────────────────────────────────────────

class MessageAdapter(
    private val onToggleExpand:   (Message) -> Unit,
    private val onDownload:       (attachmentId: String, name: String, msg: Message) -> Unit,
    private val onReply:          (Message) -> Unit,
    private val onEditDraft:      (Message) -> Unit,
    private val onDelete:         (Message) -> Unit,
    private val expandedProvider: () -> Map<Int, Message>
) : ListAdapter<Message, MessageAdapter.VH>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) =
        holder.bind(getItem(position), expandedProvider())

    inner class VH(private val b: ItemMessageBinding) : RecyclerView.ViewHolder(b.root) {

        fun bind(msg: Message, expanded: Map<Int, Message>) {
            // Sender line: for sent/draft show recipients instead
            b.textSender.text = when {
                msg.isSent || msg.isDraft -> {
                    val r = msg.recipientPersons?.mapNotNull { it.displayName }?.joinToString(", ")
                    if (!r.isNullOrBlank()) "An: $r" else msg.sender?.displayName ?: "–"
                }
                else -> msg.sender?.displayName ?: "–"
            }
            b.textSubject.text = msg.subject ?: "(Kein Betreff)"
            b.textPreview.text = msg.contentPreview ?: ""
            b.textDate.text    = msg.sentDateFormatted
            b.iconUnread.isVisible     = msg.isMessageRead == false && !msg.isSent && !msg.isDraft
            b.iconAttachment.isVisible = msg.hasAttachments == true

            b.chipAccount.isVisible = !msg.label.isNullOrBlank()
            if (!msg.label.isNullOrBlank()) b.chipAccount.text = msg.label

            val expandedMsg = expanded[msg.id]
            val isExpanded  = expandedMsg != null

            b.layoutExpanded.isVisible = isExpanded
            b.textPreview.maxLines  = if (isExpanded) Int.MAX_VALUE else 2
            b.textPreview.ellipsize = if (isExpanded) null else android.text.TextUtils.TruncateAt.END
            b.textPreview.setTextColor(
                android.util.TypedValue().let { tv ->
                    val attr = if (isExpanded) com.google.android.material.R.attr.colorOnSurface
                               else           com.google.android.material.R.attr.colorOnSurfaceVariant
                    b.root.context.theme.resolveAttribute(attr, tv, true)
                    tv.data
                }
            )

            if (isExpanded) {
                renderExpanded(expandedMsg, msg)
            }

            b.root.setOnClickListener {
                if (msg.isDraft) onEditDraft(msg) else onToggleExpand(msg)
            }
            b.root.setOnLongClickListener { onDelete(msg); true }
        }

        private fun renderExpanded(expandedMsg: Message, original: Message) {
            // Attachments
            val atts = expandedMsg.attachments
            if (original.hasAttachments == true) {
                if (atts.isEmpty()) {
                    b.attachmentsProgress.isVisible = true
                    b.layoutAttachments.isVisible   = false
                } else {
                    b.attachmentsProgress.isVisible = false
                    b.layoutAttachments.isVisible   = true
                    buildAttachmentRows(b.layoutAttachments, atts, original)
                }
            } else {
                b.attachmentsProgress.isVisible = false
                b.layoutAttachments.isVisible   = false
            }

            // Reply history (thread) — oldest first
            val history = expandedMsg.replyHistory
            if (!history.isNullOrEmpty()) {
                b.layoutReplyHistory.isVisible = true
                b.layoutReplyHistory.removeAllViews()
                history.reversed().forEach { reply -> buildReplyBubble(b.layoutReplyHistory, reply) }
            } else {
                b.layoutReplyHistory.isVisible = false
            }

            // Reply button — only in inbox if reply is allowed
            val showReplyBtn = expandedMsg.isReplyAllowed == true && !original.isSent && !original.isDraft
            // Add reply button dynamically if not yet present
            var replyBtn = b.layoutExpanded.findViewWithTag<com.google.android.material.button.MaterialButton>("reply_btn")
            if (showReplyBtn) {
                if (replyBtn == null) {
                    replyBtn = com.google.android.material.button.MaterialButton(
                        b.root.context,
                        null,
                        com.google.android.material.R.attr.borderlessButtonStyle
                    ).apply {
                        tag  = "reply_btn"
                        text = "Antworten"
                        setOnClickListener { onReply(original) }
                    }
                    b.layoutExpanded.addView(replyBtn, 0)
                }
                replyBtn.isVisible = true
            } else {
                replyBtn?.isVisible = false
            }
        }

        private fun buildReplyBubble(container: LinearLayout, reply: ReplyMessage) {
            val ctx = container.context
            val bubble = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                val bgColor = android.util.TypedValue().let { tv ->
                    ctx.theme.resolveAttribute(com.google.android.material.R.attr.colorSurfaceContainerHigh, tv, true)
                    tv.data
                }
                setBackgroundColor(bgColor)
                val dp8  = (8  * ctx.resources.displayMetrics.density).toInt()
                val dp12 = (12 * ctx.resources.displayMetrics.density).toInt()
                setPadding(dp12, dp8, dp12, dp8)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp8 }
                background = androidx.core.content.ContextCompat.getDrawable(
                    ctx, R.drawable.bg_notes_for_all)
            }

            val header = TextView(ctx).apply {
                text = buildString {
                    append(reply.sender?.displayName ?: "–")
                    reply.sentDateFormatted?.let { append("  ·  $it") }
                }
                setTypeface(null, android.graphics.Typeface.BOLD)
                textSize = 12f
                setTextColor(androidx.core.content.ContextCompat.getColor(ctx, android.R.color.darker_gray))
            }

            val dp4 = (4 * ctx.resources.displayMetrics.density).toInt()
            val content = TextView(ctx).apply {
                text = reply.content ?: ""
                textSize = 13f
                setLineSpacing(0f, 1.3f)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp4 }
            }

            bubble.addView(header)
            bubble.addView(content)
            container.addView(bubble)
        }

        private fun buildAttachmentRows(container: LinearLayout, atts: List<Attachment>, msg: Message) {
            container.removeAllViews()
            atts.forEach { att ->
                val row = LayoutInflater.from(container.context)
                    .inflate(R.layout.item_attachment, container, false)
                row.findViewById<TextView>(R.id.text_filename).text = att.name ?: "Anhang"
                row.setOnClickListener {
                    val id   = att.id   ?: return@setOnClickListener
                    val name = att.name ?: "anhang"
                    row.findViewById<ProgressBar>(R.id.download_progress).isVisible = true
                    onDownload(id, name, msg)
                }
                container.addView(row)
            }
        }
    }

    object Diff : DiffUtil.ItemCallback<Message>() {
        override fun areItemsTheSame(a: Message, b: Message) = a.id == b.id && a.storedIn == b.storedIn
        override fun areContentsTheSame(a: Message, b: Message) = a == b
    }
}
