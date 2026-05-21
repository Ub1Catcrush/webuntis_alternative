package com.webuntis.dashboard.ui.homework

import android.os.Bundle
import android.view.*
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.webuntis.dashboard.R
import com.webuntis.dashboard.databinding.FragmentHomeworkBinding
import com.webuntis.dashboard.model.UiState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class HomeworkFragment : Fragment() {

    private var _binding: FragmentHomeworkBinding? = null
    private val binding get() = _binding!!
    private val viewModel: HomeworkViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeworkBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val adapter = HomeworkAdapter { id -> viewModel.toggleDone(id) }
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
        binding.swipeRefresh.setOnRefreshListener { viewModel.load() }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    binding.swipeRefresh.isRefreshing = false
                    when (state) {
                        is UiState.Loading -> {
                            binding.progressBar.isVisible = true
                            binding.recyclerView.isVisible = false
                            binding.emptyView.isVisible = false
                        }
                        is UiState.Success -> {
                            binding.progressBar.isVisible = false
                            val open = state.data.count { !it.isDone }
                            binding.toolbar.subtitle =
                                if (open > 0) getString(R.string.label_homework_open, open) else getString(R.string.label_homework_all_done)
                            if (state.data.isEmpty()) {
                                binding.recyclerView.isVisible = false
                                binding.emptyView.isVisible = true
                                binding.emptyView.text = getString(R.string.label_no_homework_short)
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
