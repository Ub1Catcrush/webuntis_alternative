package com.webuntis.dashboard.ui.messages

import android.content.ContentValues
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.*
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
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
import com.webuntis.dashboard.databinding.FragmentMessagesBinding
import com.webuntis.dashboard.databinding.ItemMessageBinding
import com.webuntis.dashboard.model.Attachment
import com.webuntis.dashboard.model.Message
import com.webuntis.dashboard.model.UiState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.io.OutputStream

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
            expandedProvider = { viewModel.expanded.value }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
        binding.swipeRefresh.setOnRefreshListener { viewModel.load() }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
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
                                binding.emptyView.text = "Keine Nachrichten"
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
            }
        }

        // Re-render expanded state changes (attachment loaded)
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.expanded.collect {
                    adapter.notifyDataSetChanged()
                }
            }
        }

        // Handle download results
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.downloadState.collect { state ->
                    when (state) {
                        is DownloadState.Ready -> {
                            saveFile(state.filename, state.bytes)
                            viewModel.clearDownload()
                        }
                        is DownloadState.Error -> {
                            Toast.makeText(requireContext(), "Download fehlgeschlagen: ${state.message}", Toast.LENGTH_LONG).show()
                            viewModel.clearDownload()
                        }
                        else -> {}
                    }
                }
            }
        }
    }

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
                val uri = ctx.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv
                ) ?: throw Exception("MediaStore insert fehlgeschlagen")

                ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: throw Exception("OutputStream konnte nicht geöffnet werden")

                cv.clear()
                cv.put(MediaStore.Downloads.IS_PENDING, 0)
                ctx.contentResolver.update(uri, cv, null, null)

                Toast.makeText(ctx, "✓ Gespeichert: $filename", Toast.LENGTH_SHORT).show()

                // Open file so user can see it immediately
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mimeType(filename))
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                try { startActivity(intent) } catch (_: Exception) {}
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                dir.mkdirs()
                val file = java.io.File(dir, filename)
                file.writeBytes(bytes)
                Toast.makeText(ctx, "✓ Gespeichert: ${file.absolutePath}", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(requireContext(),
                "Speichern fehlgeschlagen: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun mimeType(filename: String) = when {
        filename.endsWith(".pdf", true)  -> "application/pdf"
        filename.endsWith(".png", true)  -> "image/png"
        filename.endsWith(".jpg", true) || filename.endsWith(".jpeg", true) -> "image/jpeg"
        filename.endsWith(".docx", true) -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        filename.endsWith(".xlsx", true) -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        else -> "application/octet-stream"
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}

class MessageAdapter(
    private val onToggleExpand: (Message) -> Unit,
    private val onDownload: (attachmentId: String, name: String, msg: Message) -> Unit,
    private val expandedProvider: () -> Map<Int, Message>
) : ListAdapter<Message, MessageAdapter.VH>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) =
        holder.bind(getItem(position), expandedProvider())

    inner class VH(private val b: ItemMessageBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(msg: Message, expanded: Map<Int, Message>) {
            b.textSender.text  = msg.sender?.displayName ?: "–"
            b.textSubject.text = msg.subject ?: "(Kein Betreff)"
            b.textPreview.text = msg.contentPreview ?: ""
            b.textDate.text    = msg.sentDateFormatted
            b.iconUnread.isVisible     = msg.isMessageRead == false
            b.iconAttachment.isVisible = msg.hasAttachments == true

            // Account label chip
            if (!msg.label.isNullOrBlank()) {
                b.chipAccount.isVisible = true
                b.chipAccount.text = msg.label ?: ""
            } else {
                b.chipAccount.isVisible = false
            }

            val expandedMsg = expanded[msg.id]
            val isExpanded  = expandedMsg != null

            b.textPreview.maxLines = if (isExpanded) Int.MAX_VALUE else 2
            b.textPreview.ellipsize = if (isExpanded) null else android.text.TextUtils.TruncateAt.END
            b.layoutExpanded.isVisible = isExpanded

            if (isExpanded) {
                b.textFullPreview.text = msg.contentPreview ?: ""

                // Attachments
                val atts = expandedMsg.attachments
                if (msg.hasAttachments == true) {
                    if (atts.isEmpty()) {
                        // Still loading
                        b.attachmentsProgress.isVisible = true
                        b.layoutAttachments.isVisible   = false
                    } else {
                        b.attachmentsProgress.isVisible = false
                        b.layoutAttachments.isVisible   = true
                        buildAttachmentRows(b.layoutAttachments, atts, msg)
                    }
                } else {
                    b.attachmentsProgress.isVisible = false
                    b.layoutAttachments.isVisible   = false
                }
            }

            b.root.setOnClickListener { onToggleExpand(msg) }
        }

        private fun buildAttachmentRows(container: LinearLayout, atts: List<Attachment>, msg: Message) {
            container.removeAllViews()
            atts.forEach { att ->
                val row = LayoutInflater.from(container.context)
                    .inflate(com.webuntis.dashboard.R.layout.item_attachment, container, false)
                row.findViewById<TextView>(com.webuntis.dashboard.R.id.text_filename).text =
                    att.name ?: "Anhang"
                row.setOnClickListener {
                    val attachmentId = att.id ?: return@setOnClickListener
                    val name         = att.name ?: "anhang"
                    val progress     = row.findViewById<ProgressBar>(com.webuntis.dashboard.R.id.download_progress)
                    progress.isVisible = true
                    onDownload(attachmentId, name, msg)
                }
                container.addView(row)
            }
        }
    }

    object Diff : DiffUtil.ItemCallback<Message>() {
        override fun areItemsTheSame(a: Message, b: Message) = a.id == b.id
        override fun areContentsTheSame(a: Message, b: Message) = a == b
    }
}
