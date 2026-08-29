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

        // Personal / Class / Combined timetable switch — only shown once a class id is known
        binding.btnToggleViewMode.isVisible = viewModel.canShowClassTimetable
        binding.btnEditCombinedSubjects.isVisible = viewModel.canShowClassTimetable &&
            viewModel.timetableViewMode == com.webuntis.dashboard.api.SessionManager.TimetableViewMode.COMBINED
        updateViewModeButtonLabel()
        binding.btnToggleViewMode.setOnClickListener {
            viewModel.toggleTimetableViewMode()
            updateViewModeButtonLabel()
            binding.btnEditCombinedSubjects.isVisible = viewModel.canShowClassTimetable &&
                viewModel.timetableViewMode == com.webuntis.dashboard.api.SessionManager.TimetableViewMode.COMBINED
        }
        binding.btnEditCombinedSubjects.setOnClickListener { showCombinedSubjectsDialog() }

        // Day / Week switch — directly in the timetable, not buried in settings.
        updateDayWeekButtonLabel()
        binding.btnToggleDayWeek.setOnClickListener {
            viewModel.toggleUseWeekView()
            updateDayWeekButtonLabel()
            renderCurrentDays()
        }

        // Show/hide the entire today-row (not just the button)
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.anchorDate.collect {
                    binding.rowToday.isVisible = !viewModel.isAtDefault
                }
            }
        }

        binding.weekGridView.onLessonClick = { lesson -> showLessonDetail(lesson) }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.days.collect { state ->
                    when (state) {
                        is UiState.Loading -> {
                            binding.swipeRefresh.isRefreshing = true
                            binding.holidayBanner.isVisible = false
                            // Keep tabLayout/viewPager/weekGridView visible as-is during reload
                        }
                        is UiState.Success -> {
                            binding.swipeRefresh.isRefreshing = false
                            lastDays = state.data

                            // Re-check on every successful load — defensive, in case the class id
                            // was only just resolved (e.g. as a fallback from lesson details).
                            binding.btnToggleViewMode.isVisible = viewModel.canShowClassTimetable
                            binding.btnEditCombinedSubjects.isVisible = viewModel.canShowClassTimetable &&
                                viewModel.timetableViewMode == com.webuntis.dashboard.api.SessionManager.TimetableViewMode.COMBINED

                            renderCurrentDays()
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

    private var lastDays: List<SchoolDay>? = null

    /** Re-draws the currently loaded days using whichever mode (day/week) is active. */
    private fun renderCurrentDays() {
        val days = lastDays ?: return
        val isWeek = viewModel.useCompactWeekView

        // Show holiday banner ONLY when there are no school days at all to display
        val showHoliday = days.isEmpty()
        binding.holidayBanner.isVisible = showHoliday
        binding.tabLayout.isVisible = !isWeek && !showHoliday
        binding.viewPager.isVisible = !isWeek && !showHoliday
        binding.weekGridView.isVisible = isWeek && !showHoliday

        if (!showHoliday) {
            if (isWeek) {
                binding.weekGridView.submit(
                    days,
                    secondLineMode = viewModel.weekViewSecondLine
                )
            } else {
                setupViewPager(days)
            }
        }
    }

    /** Shows the currently active mode; tapping switches Tag ↔ Woche. */
    private fun updateDayWeekButtonLabel() {
        binding.btnToggleDayWeek.text = getString(
            if (viewModel.useCompactWeekView) R.string.timetable_view_week else R.string.timetable_view_day
        )
    }

    /** Shows the currently active mode; tapping cycles Ich → Klasse → Kombiniert → Ich. */
    private fun updateViewModeButtonLabel() {
        val labelRes = when (viewModel.timetableViewMode) {
            com.webuntis.dashboard.api.SessionManager.TimetableViewMode.PERSONAL -> R.string.timetable_view_mode_personal
            com.webuntis.dashboard.api.SessionManager.TimetableViewMode.CLASS    -> R.string.timetable_view_mode_class
            com.webuntis.dashboard.api.SessionManager.TimetableViewMode.COMBINED -> R.string.timetable_view_mode_combined
        }
        binding.btnToggleViewMode.text = getString(labelRes)
    }

    /** Multi-choice picker for which class-plan subjects should fill gaps in the personal plan. */
    private fun showCombinedSubjectsDialog() {
        binding.btnEditCombinedSubjects.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            val available = viewModel.loadAvailableClassSubjects()
            binding.btnEditCombinedSubjects.isEnabled = true

            if (available.isEmpty()) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.timetable_combined_dialog_title)
                    .setMessage(R.string.timetable_combined_dialog_empty)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                return@launch
            }

            val selected = viewModel.combinedOverlaySubjects
            // Stored/compared by shortName (abbreviation) — matches Lesson.subjectName elsewhere —
            // but shown as "Langname (Kürzel)" since abbreviations alone (e.g. "Sp", "D_G") can be
            // ambiguous to the user.
            val checked = BooleanArray(available.size) { available[it].shortName in selected }
            val labels = available.map { it.displayLabel }.toTypedArray()

            // IMPORTANT: never combine setMessage() with setMultiChoiceItems()/setItems() on the
            // same AlertDialog.Builder. AlertController only shows ONE of message-or-list — if a
            // message is set, the list view never gets attached to the dialog at all, so it would
            // silently render as an empty picker even though `available` is non-empty. The
            // explanatory text is shown as a subtitle line under the title instead.
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(
                    getString(R.string.timetable_combined_dialog_title) + "\n" +
                    getString(R.string.timetable_combined_dialog_message)
                )
                .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                    checked[which] = isChecked
                }
                .setPositiveButton(R.string.timetable_combined_dialog_apply) { _, _ ->
                    val chosen = available.filterIndexed { index, _ -> checked[index] }
                        .map { it.shortName }.toSet()
                    viewModel.setCombinedOverlaySubjects(chosen)
                }
                .setNegativeButton(R.string.timetable_combined_dialog_cancel, null)
                .show()
        }
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
            textSubject.text = lesson.displaySubject(viewModel.showLongSubjects, viewModel.showShortSubjectInParens)
            
            // Time range from strings.xml
            textTime.text = getString(R.string.timetable_time_range, 
                lesson.startTimeFormatted, lesson.endTimeFormatted)

            // Teacher logic with strikethrough for removed ones
            val activeTeachers = lesson.displayTeachers(viewModel.showLongTeachers, viewModel.showShortTeacherInParens)
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

            textRoom.text = lesson.displayRooms(viewModel.showLongRooms, viewModel.showShortRoomInParens).ifEmpty { "–" }
            
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
