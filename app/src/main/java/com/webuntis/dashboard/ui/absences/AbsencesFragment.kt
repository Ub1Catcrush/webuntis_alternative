package com.webuntis.dashboard.ui.absences

import android.os.Bundle
import android.view.*
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.webuntis.dashboard.R
import com.webuntis.dashboard.databinding.FragmentAbsencesBinding
import com.webuntis.dashboard.databinding.ItemAbsenceBinding
import com.webuntis.dashboard.model.Absence
import com.webuntis.dashboard.model.UiState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class AbsencesFragment : Fragment() {

    private var _binding: FragmentAbsencesBinding? = null
    private val binding get() = _binding!!
    private val viewModel: AbsencesViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAbsencesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val adapter = AbsenceAdapter()
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
        binding.swipeRefresh.setOnRefreshListener { viewModel.load(forceRefresh = true) }

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
                            val unexcused = state.data.count {
                                it.isExcused == false && it.excuseStatus != "APP Mitteilung"
                            }
                            binding.toolbar.subtitle =
                                if (unexcused > 0) "$unexcused nicht entschuldigt" else ""
                            if (state.data.isEmpty()) {
                                binding.recyclerView.isVisible = false
                                binding.emptyView.isVisible = true
                                binding.emptyView.text = "Keine Abwesenheiten"
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

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}

class AbsenceAdapter : ListAdapter<Absence, AbsenceAdapter.VH>(Diff) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemAbsenceBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    class VH(private val b: ItemAbsenceBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(a: Absence) {
            val ctx = b.root.context
            b.textDate.text   = a.dateLabel
            b.textTime.text   = if (a.isFullDay) "Ganztägig" else a.timeLabel
            b.textReason.text = a.reason?.takeIf { it.isNotBlank() }
                ?: a.text?.takeIf { it.isNotBlank() } ?: "–"
            val noteText = a.text?.takeIf { it.isNotBlank() && it != a.reason }
            b.textNote.text = noteText
            b.textNote.isVisible = !noteText.isNullOrBlank()
            b.textStatus.text = a.excuseStatus ?: "–"

            val isApp    = a.excuseStatus == "APP Mitteilung"
            val excused  = a.isExcused == true
            val bgRes    = when { isApp -> R.color.yellow_container; excused -> R.color.green_container; else -> R.color.red_container }
            val textRes  = when { isApp -> R.color.yellow; excused -> R.color.green; else -> R.color.red }
            b.textStatus.setBackgroundColor(ContextCompat.getColor(ctx, bgRes))
            b.textStatus.setTextColor(ContextCompat.getColor(ctx, textRes))
        }
    }

    object Diff : DiffUtil.ItemCallback<Absence>() {
        override fun areItemsTheSame(a: Absence, b: Absence) = a.id == b.id
        override fun areContentsTheSame(a: Absence, b: Absence) = a == b
    }
}
