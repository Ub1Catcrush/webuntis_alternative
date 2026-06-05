package com.webuntis.dashboard.ui.events

import android.os.Bundle
import android.view.*
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.tabs.TabLayout
import com.webuntis.dashboard.R
import com.webuntis.dashboard.databinding.FragmentEventsBinding
import com.webuntis.dashboard.model.UiState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class EventsFragment : Fragment() {

    private var _binding: FragmentEventsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: EventsViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentEventsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val adapter = EventsAdapter()
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

        binding.swipeRefresh.setOnRefreshListener { viewModel.load(forceRefresh = true) }

        // Sync tab to current ViewModel state (e.g. after rotation)
        binding.tabLayout.getTabAt(if (viewModel.showPast.value) 1 else 0)?.select()

        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                viewModel.setShowPast(tab?.position == 1)
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    binding.swipeRefresh.isRefreshing = state is UiState.Loading
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
                                binding.emptyView.text = if (viewModel.showPast.value)
                                    getString(R.string.label_no_events_including_past)
                                else
                                    getString(R.string.label_no_events)
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
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
