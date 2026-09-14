package com.webuntis.dashboard.ui.changes

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.Gson
import com.webuntis.dashboard.R
import com.webuntis.dashboard.api.ChangeLogEntry
import com.webuntis.dashboard.api.ChangeSnapshot
import com.webuntis.dashboard.api.SessionManager
import com.webuntis.dashboard.databinding.DialogRecentChangesBinding
import com.webuntis.dashboard.databinding.ItemRecentChangeBinding
import dagger.hilt.android.AndroidEntryPoint
import java.text.DateFormat
import javax.inject.Inject

/**
 * "Was ist neu" dialog opened from any of the change-check notifications (see
 * NotificationHelper / PlanChangeCheckWorker) — shows the actual list of recent changes
 * (schedule changes, new messages, homework, classbook entries) from the last 7 days, instead
 * of leaving the user with just a bare "45 Stundenplanänderungen" count.
 *
 * Purely a read-only view over [ChangeSnapshot.recentChanges]; it doesn't affect the
 * background worker's own 7-day dedup ledger. It only updates
 * [SessionManager.changesLastViewedAt], which controls the "NEU" dot shown per entry.
 */
@AndroidEntryPoint
class RecentChangesDialogFragment : DialogFragment() {

    @Inject lateinit var sessionManager: SessionManager

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = DialogRecentChangesBinding.inflate(LayoutInflater.from(requireContext()))

        val gson = Gson()
        val snapshot = sessionManager.lastNotifiedSnapshot?.let { json ->
            try { gson.fromJson(json, ChangeSnapshot::class.java) } catch (e: Exception) { null }
        }
        val cutoff = System.currentTimeMillis() - ChangeSnapshot.NOTIFIED_TTL_MS
        val entries = snapshot?.recentChanges
            ?.filter { it.timestampMs >= cutoff }
            ?.sortedByDescending { it.timestampMs }
            ?: emptyList()

        // Read BEFORE overwriting below, so entries seen for the first time in this dialog
        // still show their "NEU" dot while it's open.
        val previouslyViewedAt = sessionManager.changesLastViewedAt

        binding.textEmpty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        binding.recyclerChanges.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
        binding.recyclerChanges.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerChanges.adapter = ChangesAdapter(entries, previouslyViewedAt)

        sessionManager.changesLastViewedAt = System.currentTimeMillis()

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.changes_dialog_title)
            .setView(binding.root)
            .setPositiveButton(R.string.changes_dialog_close, null)
            .create()
    }

    private class ChangesAdapter(
        private val items: List<ChangeLogEntry>,
        private val previouslyViewedAt: Long
    ) : RecyclerView.Adapter<ChangesAdapter.ViewHolder>() {

        inner class ViewHolder(val binding: ItemRecentChangeBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemRecentChangeBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return ViewHolder(binding)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val entry = items[position]
            val binding = holder.binding
            binding.textTitle.text = entry.title
            binding.textSubtitle.text = buildString {
                append(formatTimestamp(entry.timestampMs))
                if (entry.text.isNotBlank()) append(" · ").append(entry.text)
            }
            binding.iconCategory.setImageResource(
                when (entry.category) {
                    "timetable" -> R.drawable.ic_calendar
                    "messages"  -> R.drawable.ic_message
                    "homework"  -> R.drawable.ic_homework
                    "classbook" -> R.drawable.ic_book
                    else        -> R.drawable.ic_calendar
                }
            )
            binding.dotUnread.visibility =
                if (entry.timestampMs > previouslyViewedAt) View.VISIBLE else View.GONE
        }

        private fun formatTimestamp(ms: Long): String =
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(ms)
    }
}
