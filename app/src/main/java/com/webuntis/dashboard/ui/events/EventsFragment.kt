package com.webuntis.dashboard.ui.events

import android.os.Bundle
import android.view.*
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
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
        setupMenu()

        val adapter = EventsAdapter()
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

        binding.swipeRefresh.setOnRefreshListener { viewModel.load(forceRefresh = true) }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.state.collect { state ->
                        binding.swipeRefresh.isRefreshing = state is UiState.Loading
                        when (state) {
                            is UiState.Loading -> {
                                binding.recyclerView.isVisible = false
                                binding.emptyView.isVisible = false
                            }
                            is UiState.Success -> {
                                if (state.data.isEmpty()) {
                                    binding.recyclerView.isVisible = false
                                    binding.emptyView.isVisible = true
                                    binding.emptyView.text = getString(R.string.label_no_events)
                                } else {
                                    binding.recyclerView.isVisible = true
                                    binding.emptyView.isVisible = false
                                    adapter.submitList(state.data)
                                }
                            }
                            is UiState.Error -> {
                                binding.recyclerView.isVisible = false
                                binding.emptyView.isVisible = true
                                binding.emptyView.text = state.message
                            }
                        }
                    }
                }
                launch {
                    viewModel.showPast.collect { showPast ->
                        binding.toolbar.subtitle = if (showPast) getString(R.string.events_subtitle_including_past) else ""
                        requireActivity().invalidateOptionsMenu()
                    }
                }
            }
        }
    }

    private fun setupMenu() {
        val menuHost: MenuHost = requireActivity()
        menuHost.addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.menu_events, menu)
                val item = menu.findItem(R.id.action_toggle_past)
                item?.isChecked = viewModel.showPast.value
                item?.title = if (viewModel.showPast.value)
                    getString(R.string.action_hide_past_events)
                else
                    getString(R.string.action_show_past_events)
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                if (menuItem.itemId == R.id.action_toggle_past) {
                    viewModel.toggleShowPast()
                    return true
                }
                return false
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
