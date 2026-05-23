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
                    
                    // Toggle visibility based on mode
                    binding.appBar.isVisible = !isCompact
                    binding.viewPager.isVisible = !isCompact
                    binding.compactRecyclerView.isVisible = isCompact

                    when (state) {
                        is UiState.Loading -> {
                            binding.swipeRefresh.isRefreshing = true
                        }
                        is UiState.Success -> {
                            binding.swipeRefresh.isRefreshing = false
                            val days = state.data
                            
                            if (isCompact) {
                                compactAdapter.showLongSubjects = viewModel.showLongSubjects
                                compactAdapter.showLongRooms = viewModel.showLongRooms
                                compactAdapter.submitList(days)
                            } else {
                                setupViewPager(days)
                            }
                        }
                        is UiState.Error -> {
                            binding.swipeRefresh.isRefreshing = false
                        }
                    }
                }
            }
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
                tab.text = days.getOrNull(position)?.tabLabel
                    ?: getString(R.string.label_day_fallback, position + 1)
            }.also { it.attach() }
        } else {
            for (i in 0 until binding.tabLayout.tabCount) {
                binding.tabLayout.getTabAt(i)?.text =
                    days.getOrNull(i)?.tabLabel
                        ?: getString(R.string.label_day_fallback, i + 1)
            }
        }
    }

    private fun showLessonDetail(lesson: Lesson) {
        val context = requireContext()
        val dialogBinding = DialogLessonDetailBinding.inflate(LayoutInflater.from(context))
        
        with(dialogBinding) {
            textSubject.text = lesson.displaySubject(viewModel.showLongSubjects)
            
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
