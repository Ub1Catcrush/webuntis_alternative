package com.webuntis.dashboard.ui.classbook

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.webuntis.dashboard.R
import com.webuntis.dashboard.api.SessionManager
import com.webuntis.dashboard.databinding.FragmentLessonContentBinding
import com.webuntis.dashboard.databinding.ItemLessonContentEntryBinding
import com.webuntis.dashboard.databinding.ItemLessonContentHeaderBinding
import com.webuntis.dashboard.model.Lesson
import com.webuntis.dashboard.model.UiState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class LessonContentFragment : Fragment() {

    private var _binding: FragmentLessonContentBinding? = null
    private val binding get() = _binding!!
    private val viewModel: LessonContentViewModel by viewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLessonContentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val adapter = LessonContentAdapter()
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
        binding.swipeRefresh.setOnRefreshListener { viewModel.load(forceRefresh = true) }
        binding.btnLoadMore.setOnClickListener { viewModel.loadMoreDays() }
        binding.btnToggleGroupMode.setOnClickListener { viewModel.toggleGroupMode() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.collect { state ->
                binding.swipeRefresh.isRefreshing = false
                when (state) {
                    is UiState.Loading -> {
                        binding.progressBar.isVisible = true
                        binding.recyclerView.isVisible = false
                        binding.emptyView.isVisible = false
                        binding.btnLoadMore.isVisible = false
                    }
                    is UiState.Success -> {
                        binding.progressBar.isVisible = false
                        if (state.data.isEmpty()) {
                            binding.recyclerView.isVisible = false
                            binding.emptyView.isVisible = true
                            binding.emptyView.text = getString(R.string.lesson_content_empty)
                            binding.btnLoadMore.isVisible = viewModel.canLoadMore.value
                        } else {
                            binding.recyclerView.isVisible = true
                            binding.emptyView.isVisible = false
                            adapter.submitGroups(state.data, viewModel.groupMode.value)
                            binding.btnLoadMore.isVisible = viewModel.canLoadMore.value
                        }
                    }
                    is UiState.Error -> {
                        binding.progressBar.isVisible = false
                        binding.recyclerView.isVisible = false
                        binding.emptyView.isVisible = true
                        binding.emptyView.text = state.message
                        binding.btnLoadMore.isVisible = false
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.windowDays.collect { days ->
                binding.toolbar.subtitle = resources.getQuantityString(
                    R.plurals.lesson_content_subtitle_days, days, days
                )
                binding.btnLoadMore.isVisible = viewModel.canLoadMore.value &&
                    (viewModel.state.value is UiState.Success)
            }
        }

        // Button always shows the CURRENTLY active mode; tapping switches to the other one —
        // same convention as the Day/Week toggle on the timetable tab.
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.groupMode.collect { mode ->
                binding.btnToggleGroupMode.text = getString(
                    if (mode == SessionManager.LessonContentGroupMode.BY_DAY)
                        R.string.lesson_content_group_by_day
                    else
                        R.string.lesson_content_group_by_subject
                )
                // Re-render immediately when the mode changes so the per-entry label
                // (date vs. subject) updates too, not just the section headers.
                (viewModel.state.value as? UiState.Success)?.let { adapter.submitGroups(it.data, mode) }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

// ─── Adapter ─────────────────────────────────────────────────────────────────

sealed class ContentRow {
    data class Header(val label: String, val count: Int) : ContentRow()
    data class Entry(val entry: Lesson, val groupMode: SessionManager.LessonContentGroupMode) : ContentRow()
}

class LessonContentAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val rows = mutableListOf<ContentRow>()

    fun submitGroups(groups: List<ContentGroup>, groupMode: SessionManager.LessonContentGroupMode) {
        rows.clear()
        groups.forEach { group ->
            rows.add(ContentRow.Header(group.header, group.entries.size))
            group.entries.forEach { rows.add(ContentRow.Entry(it, groupMode)) }
        }
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int) = when (rows[position]) {
        is ContentRow.Header -> 0
        is ContentRow.Entry  -> 1
    }

    override fun getItemCount() = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == 0) {
            HeaderVH(ItemLessonContentHeaderBinding.inflate(inflater, parent, false))
        } else {
            EntryVH(ItemLessonContentEntryBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is ContentRow.Header -> (holder as HeaderVH).bind(row)
            is ContentRow.Entry  -> (holder as EntryVH).bind(row.entry, row.groupMode)
        }
    }

    class HeaderVH(private val b: ItemLessonContentHeaderBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(header: ContentRow.Header) {
            b.textSubject.text = header.label
            b.textCount.text = b.root.resources.getQuantityString(
                R.plurals.lesson_content_entry_count, header.count, header.count
            )
        }
    }

    class EntryVH(private val b: ItemLessonContentEntryBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(entry: Lesson, groupMode: SessionManager.LessonContentGroupMode) {
            // Whichever of "date" / "subject" is ALREADY the section header is redundant here —
            // show the other one as the per-entry leading label instead.
            b.textLeadingLabel.text = if (groupMode == SessionManager.LessonContentGroupMode.BY_DAY) {
                entry.subjectLongName.takeIf { it != "–" } ?: entry.subjectName
            } else {
                entry.dateFormatted
            }
            b.textContent.text = entry.teachingContent ?: ""
            val teacher = entry.teacherNames
            b.textTeacher.text = teacher
            b.textTeacher.isVisible = teacher.isNotBlank()
            b.colorStripe.setBackgroundColor(
                entry.resolvedColor()
                    ?: com.webuntis.dashboard.ui.timetable.subjectColor(entry.subjectName, b.root.context)
            )
        }
    }
}
