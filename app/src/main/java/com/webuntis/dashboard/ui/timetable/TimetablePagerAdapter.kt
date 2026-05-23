package com.webuntis.dashboard.ui.timetable

import com.webuntis.dashboard.R
import android.os.Bundle
import android.view.*
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.webuntis.dashboard.databinding.DialogLessonDetailBinding
import com.webuntis.dashboard.databinding.FragmentDayBinding
import com.webuntis.dashboard.model.Lesson
import com.webuntis.dashboard.model.UiState
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.graphics.Paint
import kotlinx.coroutines.launch

class TimetablePagerAdapter(
    fragment: Fragment,
    private val dayCount: Int
) : FragmentStateAdapter(fragment) {
    override fun getItemCount() = dayCount
    override fun createFragment(position: Int): Fragment =
        DayFragment.newInstance(position)
}

class DayFragment : Fragment() {

    private var _binding: FragmentDayBinding? = null
    private val binding get() = _binding!!
    private val viewModel: TimetableViewModel by viewModels({ requireParentFragment() })
    private var dayIndex = 0

    companion object {
        fun newInstance(day: Int) = DayFragment().apply {
            arguments = Bundle().apply { putInt("day", day) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dayIndex = arguments?.getInt("day") ?: 0
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDayBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val adapter = LessonAdapter()
        
        adapter.showLongSubjects = viewModel.showLongSubjects
        adapter.showLongTeachers = viewModel.showLongTeachers
        adapter.showLongRooms    = viewModel.showLongRooms
        
        adapter.onLessonClick = { lesson -> showLessonDetail(lesson) }

        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.days.collect { state ->
                    when (state) {
                        is UiState.Loading -> {
                            binding.progressBar.isVisible = true
                            binding.recyclerView.isVisible = false
                            binding.emptyView.isVisible = false
                        }
                        is UiState.Success -> {
                            binding.progressBar.isVisible = false
                            val day = state.data.getOrNull(dayIndex)
                            if (day == null || day.lessons.isEmpty()) {
                                binding.recyclerView.isVisible = false
                                binding.emptyView.isVisible = true
                                binding.emptyView.text = getString(R.string.label_no_lessons_today)
                            } else {
                                binding.recyclerView.isVisible = true
                                binding.emptyView.isVisible = false
                                adapter.submitList(day.groupedLessons)
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
    }

    private fun showLessonDetail(lesson: Lesson) {
        val context = requireContext()
        val dialogBinding = DialogLessonDetailBinding.inflate(LayoutInflater.from(context))
        
        with(dialogBinding) {
            textSubject.text = lesson.displaySubject(viewModel.showLongSubjects)
            textTime.text = getString(R.string.timetable_time_range, 
                lesson.startTimeFormatted, lesson.endTimeFormatted)

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
        super.onDestroyView()
        _binding = null
    }
}
