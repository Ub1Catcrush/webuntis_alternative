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
import com.webuntis.dashboard.databinding.FragmentDayBinding
import com.webuntis.dashboard.model.UiState
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
        // Read the setting from SessionManager via the parent fragment's ViewModel
        val vm = androidx.lifecycle.ViewModelProvider(requireParentFragment())
            .get(TimetableViewModel::class.java)
        adapter.showLongSubjects = vm.showLongSubjects
        adapter.showLongTeachers = vm.showLongTeachers
        adapter.showLongRooms    = vm.showLongRooms
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
                                adapter.submitList(day.lessons)
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
