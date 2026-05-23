package com.webuntis.dashboard.ui.timetable

import android.os.Bundle
import android.view.*
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.tabs.TabLayoutMediator
import com.webuntis.dashboard.R
import com.webuntis.dashboard.databinding.FragmentTimetableBinding
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

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.days.collect { state ->
                    when (state) {
                        is UiState.Loading -> {
                            binding.swipeRefresh.isRefreshing = true
                        }
                        is UiState.Success -> {
                            binding.swipeRefresh.isRefreshing = false
                            val days = state.data
                            val count = days.size.coerceAtLeast(1)

                            // Only rebuild the pager adapter when the day count actually changes.
                            // Re-building it every time the StateFlow emits (e.g. on tab switch back)
                            // destroys and recreates all DayFragments unnecessarily — even when the
                            // data is identical and served from cache with zero network calls.
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
                                // Adapter exists with correct count — just refresh tab labels
                                // (they may have changed if locale changed etc.)
                                for (i in 0 until binding.tabLayout.tabCount) {
                                    binding.tabLayout.getTabAt(i)?.text =
                                        days.getOrNull(i)?.tabLabel
                                            ?: getString(R.string.label_day_fallback, i + 1)
                                }
                            }
                        }
                        is UiState.Error -> {
                            binding.swipeRefresh.isRefreshing = false
                            // DayFragment shows the error message
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        tabMediator?.detach()
        tabMediator = null
        super.onDestroyView()
        _binding = null
    }
}
