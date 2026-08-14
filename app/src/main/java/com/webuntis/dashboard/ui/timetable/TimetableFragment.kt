package com.webuntis.dashboard.ui.timetable

import android.graphics.Paint
import android.os.Bundle
import android.view.*
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayoutMediator
import com.webuntis.dashboard.R
import com.webuntis.dashboard.databinding.DialogLessonDetailBinding
import com.webuntis.dashboard.databinding.FragmentTimetableBinding
import com.webuntis.dashboard.model.Lesson
import com.webuntis.dashboard.model.UiState
import dagger.hilt.android.AndroidEntryPoint
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import androidx.core.content.ContextCompat
import java.time.LocalDate
import kotlinx.coroutines.launch

@AndroidEntryPoint
class TimetableFragment : Fragment() {

    private var _binding: FragmentTimetableBinding? = null
    private val binding get() = _binding!!
    private val viewModel: TimetableViewModel by viewModels()
    private var tabMediator: TabLayoutMediator? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTimetableBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.swipeRefresh.setOnRefreshListener { viewModel.loadAll(forceRefresh = true) }

        binding.btnPrevDays.setOnClickListener { viewModel.shiftDays(-5) }
        binding.btnNextDays.setOnClickListener { viewModel.shiftDays(+5) }
        binding.btnToday.setOnClickListener    { viewModel.resetToToday() }

        // Personal / Class timetable switch — only shown once a class id is known
        binding.btnToggleViewMode.isVisible = viewModel.canShowClassTimetable
        updateViewModeButtonLabel()
        binding.btnToggleViewMode.setOnClickListener {
            viewModel.toggleTimetableViewMode()
            updateViewModeButtonLabel()
        }

        // Show/hide the entire today-row (not just the button)
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.anchorDate.collect {
                    binding.rowToday.isVisible = !viewModel.isAtDefault
                }
            }
        }

        // Setup Compact RecyclerView
        val compactAdapter = CompactWeekAdapter()
        compactAdapter.onLessonClick = { lesson -> showLessonDetail(lesson) }
        
        binding.compactRecyclerView.layoutManager = 
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.compactRecyclerView.adapter = compactAdapter

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.days.collect { state ->
                    val isCompact = viewModel.useCompactWeekView
                    
                    // Note: tabLayout/viewPager/compactRecyclerView visibility is managed
                    // in the Success branch below (also respects holidayBanner)

                    when (state) {
                        is UiState.Loading -> {
                            binding.swipeRefresh.isRefreshing = true
                            binding.holidayBanner.isVisible = false
                            // Keep tabLayout/viewPager/compact visible as-is during reload
                        }
                        is UiState.Success -> {
                            binding.swipeRefresh.isRefreshing = false
                            val days = state.data

                            // Show holiday banner ONLY when there are no school days at all to display
                            val showHoliday = days.isEmpty()
                            binding.holidayBanner.isVisible = showHoliday
                            binding.tabLayout.isVisible = !isCompact && !showHoliday
                            binding.viewPager.isVisible = !isCompact && !showHoliday
                            binding.compactRecyclerView.isVisible = isCompact && !showHoliday

                            if (!showHoliday) {
                                if (isCompact) {
                                    compactAdapter.showLongSubjects = viewModel.showLongSubjects
                                    compactAdapter.showLongRooms = viewModel.showLongRooms
                                    compactAdapter.submitList(days)
                                } else {
                                    setupViewPager(days)
                                }
                            }
                        }
                        is UiState.Error -> {
                            binding.swipeRefresh.isRefreshing = false
                            binding.holidayBanner.isVisible = false
                        }
                    }
                }
            }
        }
    }

    /** Label always shows the view the button switches TO, not the currently active one. */
    private fun updateViewModeButtonLabel() {
        val showsClass = viewModel.timetableViewMode == com.webuntis.dashboard.api.SessionManager.TimetableViewMode.CLASS
        binding.btnToggleViewMode.text = getString(
            if (showsClass) R.string.timetable_view_mode_personal else R.string.timetable_view_mode_class
        )
    }

    private fun setupViewPager(days: List<SchoolDay>) {
        val count = days.size.coerceAtLeast(1)
        val existingAdapter = binding.viewPager.adapter
        if (existingAdapter == null ||
            (existingAdapter as? TimetablePagerAdapter)?.itemCount != count) {
            tabMediator?.detach()
            binding.viewPager.adapter =
                TimetablePagerAdapter(this@TimetableFragment, count)
            tabMediator = TabLayoutMediator(
                binding.tabLayout, binding.viewPager
            ) { tab, position ->
                val day   = days.getOrNull(position)
                val label = day?.tabLabel ?: getString(R.string.label_day_fallback, position + 1)
                val isToday = day?.date == LocalDate.now()
                if (isToday) {
                    val spannable = SpannableString(label)
                    spannable.setSpan(
                        ForegroundColorSpan(ContextCompat.getColor(requireContext(), R.color.today_tab)),
                        0, label.length,
                        android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    tab.text = spannable
                } else {
                    tab.text = label
                }
            }.also { it.attach() }
        } else {
            for (i in 0 until binding.tabLayout.tabCount) {
                val day   = days.getOrNull(i)
                val label = day?.tabLabel ?: getString(R.string.label_day_fallback, i + 1)
                val isToday = day?.date == LocalDate.now()
                binding.tabLayout.getTabAt(i)?.text = if (isToday) {
                    SpannableString(label).also { ss ->
                        ss.setSpan(ForegroundColorSpan(ContextCompat.getColor(requireContext(), R.color.today_tab)),
                            0, label.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                } else label
            }
        }
    }

    private fun showLessonDetail(lesson: Lesson) {
        val context = requireContext()
        val dialogBinding = DialogLessonDetailBinding.inflate(LayoutInflater.from(context))
        
        with(dialogBinding) {
            textSubject.text = lesson.displaySubject(viewModel.showLongSubjects)
            
            // Time range from strings.xml
            textTime.text = getString(R.string.timetable_time_range, 
                lesson.startTimeFormatted, lesson.endTimeFormatted)

            // Teacher logic with strikethrough for removed ones
            val activeTeachers = lesson.displayTeachers(viewModel.showLongTeachers)
            val removedNames   = lesson.removedTeachers
                ?: lesson.te?.mapNotNull { it.orgname }?.filter { it.isNotEmpty() }
                    ?.takeIf { lesson.isSubstitution }
            
            textTeacher.text = activeTeachers.ifEmpty { "–" }
            if (!removedNames.isNullOrEmpty()) {
                textTeacherOriginal.isVisible = true
                textTeacherOriginal.text = removedNames.joinToString(", ")
                textTeacherOriginal.paintFlags = textTeacherOriginal.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            } else {
                textTeacherOriginal.isVisible = false
            }

            textRoom.text = lesson.displayRooms(viewModel.showLongRooms).ifEmpty { "–" }
            
            val info = listOfNotNull(
                lesson.replacedSubject?.let { getString(R.string.timetable_replaced_subject, it) },
                lesson.substText?.takeIf { it.isNotBlank() },
                lesson.info?.takeIf { it.isNotBlank() }
            ).joinToString(" · ")
            
            if (info.isNotBlank()) {
                rowInfo.isVisible = true
                textInfo.text = info
            }
            
            if (!lesson.teachingContent.isNullOrBlank()) {
                rowContent.isVisible = true
                textContent.text = lesson.teachingContent
            }
            
            if (!lesson.notesForAll.isNullOrBlank()) {
                rowNotes.isVisible = true
                textNotes.text = lesson.notesForAll
            }
        }

        val dialog = MaterialAlertDialogBuilder(context)
            .setView(dialogBinding.root)
            .create()

        dialogBinding.btnClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    override fun onDestroyView() {
        tabMediator?.detach()
        tabMediator = null
        super.onDestroyView()
        _binding = null
    }
}
