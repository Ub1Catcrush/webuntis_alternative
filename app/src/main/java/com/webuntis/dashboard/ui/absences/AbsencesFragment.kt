package com.webuntis.dashboard.ui.absences

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.view.*
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.webuntis.dashboard.R
import com.webuntis.dashboard.api.CreateAbsenceRequest
import com.webuntis.dashboard.databinding.DialogEditAbsenceBinding
import com.webuntis.dashboard.databinding.FragmentAbsencesBinding
import com.webuntis.dashboard.databinding.ItemAbsenceBinding
import com.webuntis.dashboard.model.Absence
import com.webuntis.dashboard.model.UiState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@AndroidEntryPoint
class AbsencesFragment : Fragment() {

    private var _binding: FragmentAbsencesBinding? = null
    private val binding get() = _binding!!
    private val viewModel: AbsencesViewModel by viewModels()
    private val dateFmt = DateTimeFormatter.ofPattern("dd.MM.yyyy")

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAbsencesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupMenu()

        val adapter = AbsenceAdapter { absence ->
            if (absence.canEdit == true) {
                showEditAbsenceDialog(absence)
            } else {
                Toast.makeText(requireContext(), "Diese Abwesenheit kann nicht bearbeitet werden.", Toast.LENGTH_SHORT).show()
            }
        }
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
        binding.swipeRefresh.setOnRefreshListener { viewModel.load(forceRefresh = true) }

        binding.fabAdd.setOnClickListener { showEditAbsenceDialog(null) }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
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
                                    it.isExcused == false && it.excuseStatus?.contains("entschuldigt", ignoreCase = true) == false
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
                launch {
                    viewModel.meta.collect { meta ->
                        binding.fabAdd.isVisible = viewModel.isParent && meta?.canReportAbsence == true
                    }
                }
            }
        }
    }

    private fun setupMenu() {
        val menuHost: MenuHost = requireActivity()
        menuHost.addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.menu_absences, menu)
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                if (menuItem.itemId == R.id.action_filter) {
                    showFilterDialog()
                    return true
                }
                return false
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    private fun showFilterDialog() {
        val meta = viewModel.meta.value ?: return
        val statuses = meta.excuseStatuses ?: return
        val items = statuses.map { it.label }.toTypedArray()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Filtern nach")
            .setItems(items) { _, which ->
                val selected = statuses[which]
                viewModel.setFilter(selected.id.toIntOrNull() ?: -1)
            }
            .show()
    }

    private fun showEditAbsenceDialog(absence: Absence?) {
        val context = requireContext()
        val meta = viewModel.meta.value ?: return
        val reasons = meta.absenceReasons ?: emptyList()
        
        val dialogBinding = DialogEditAbsenceBinding.inflate(LayoutInflater.from(context))
        
        // Initial state from meta defaults or existing absence
        var start = absence?.let { untisIntToDate(it.startDate ?: 0) } 
                    ?: meta.defaultDate?.let { untisIntToDate(it) } 
                    ?: LocalDate.now()
        var end = absence?.let { untisIntToDate(it.endDate ?: 0) } 
                  ?: meta.defaultDate?.let { untisIntToDate(it) } 
                  ?: LocalDate.now()
        var startTime = absence?.startTime?.let { untisIntToTime(it) } 
                        ?: meta.defaultStartTime?.let { untisIntToTime(it) } 
                        ?: LocalTime.of(8, 0)
        var endTime = absence?.endTime?.let { untisIntToTime(it) } 
                      ?: meta.defaultEndTime?.let { untisIntToTime(it) } 
                      ?: LocalTime.of(16, 0)
        var selectedReasonId = absence?.reasonId ?: meta.defaultAbsenceReason ?: -1

        val updateButtons = {
            dialogBinding.btnStartDate.text = start.format(dateFmt)
            dialogBinding.btnEndDate.text = end.format(dateFmt)
            dialogBinding.btnStartTime.text = startTime.toString()
            dialogBinding.btnEndTime.text = endTime.toString()
        }
        updateButtons()

        // Date/Time Pickers
        dialogBinding.btnStartDate.setOnClickListener {
            DatePickerDialog(context, { _, y, m, d ->
                start = LocalDate.of(y, m + 1, d)
                if (end.isBefore(start)) end = start
                updateButtons()
            }, start.year, start.monthValue - 1, start.dayOfMonth).show()
        }
        dialogBinding.btnEndDate.setOnClickListener {
            DatePickerDialog(context, { _, y, m, d ->
                end = LocalDate.of(y, m + 1, d)
                updateButtons()
            }, end.year, end.monthValue - 1, end.dayOfMonth).show()
        }
        dialogBinding.btnStartTime.setOnClickListener {
            TimePickerDialog(context, { _, h, m ->
                startTime = LocalTime.of(h, m)
                updateButtons()
            }, startTime.hour, startTime.minute, true).show()
        }
        dialogBinding.btnEndTime.setOnClickListener {
            TimePickerDialog(context, { _, h, m ->
                endTime = LocalTime.of(h, m)
                updateButtons()
            }, endTime.hour, endTime.minute, true).show()
        }

        // Reason Spinner (Material Exposed Dropdown)
        val reasonNames = reasons.map { it.name }
        val spinnerAdapter = ArrayAdapter(context, android.R.layout.simple_dropdown_item_1line, reasonNames)
        dialogBinding.spinnerReason.setAdapter(spinnerAdapter)
        
        reasons.indexOfFirst { it.id == selectedReasonId }.takeIf { it >= 0 }?.let {
            dialogBinding.spinnerReason.setText(reasons[it].name, false)
        }

        dialogBinding.spinnerReason.setOnItemClickListener { _, _, position, _ ->
            selectedReasonId = reasons[position].id
        }

        dialogBinding.editText.setText(absence?.text ?: "")
        dialogBinding.textTitle.text = if (absence == null) "Abwesenheit melden" else "Abwesenheit bearbeiten"
        
        if (absence != null) {
            dialogBinding.btnDelete.isVisible = true
        }

        val dialog = MaterialAlertDialogBuilder(context)
            .setView(dialogBinding.root)
            .create()

        dialogBinding.btnCancel.setOnClickListener { dialog.dismiss() }
        
        dialogBinding.btnSave.setOnClickListener {
            val req = CreateAbsenceRequest(
                startDate = dateToUntis(start),
                startTime = timeToUntis(startTime),
                endDate = dateToUntis(end),
                endTime = timeToUntis(endTime),
                text = dialogBinding.editText.text.toString(),
                reasonId = selectedReasonId,
                studentId = viewModel.studentId
            )
            
            if (absence == null) {
                viewModel.createAbsence(req) { res ->
                    res.onSuccess { dialog.dismiss() }.onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
                }
            } else {
                viewModel.updateAbsence(absence.id, req) { res ->
                    res.onSuccess { dialog.dismiss() }.onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
                }
            }
        }

        dialogBinding.btnDelete.setOnClickListener {
            MaterialAlertDialogBuilder(context)
                .setTitle("Abwesenheit löschen?")
                .setMessage("Möchten Sie diese Abwesenheit wirklich löschen?")
                .setNegativeButton("Abbrechen", null)
                .setPositiveButton("Löschen") { _, _ ->
                    viewModel.deleteAbsence(absence!!.id) { res ->
                        res.onSuccess { dialog.dismiss() }.onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
                    }
                }
                .show()
        }

        dialog.show()
    }

    private fun untisIntToDate(d: Int): LocalDate {
        val s = d.toString()
        if (s.length != 8) return LocalDate.now()
        return LocalDate.of(s.substring(0, 4).toInt(), s.substring(4, 6).toInt(), s.substring(6, 8).toInt())
    }

    private fun untisIntToTime(t: Int): LocalTime {
        val s = t.toString().padStart(4, '0')
        return LocalTime.of(s.substring(0, 2).toInt(), s.substring(2, 4).toInt())
    }

    private fun dateToUntis(d: LocalDate): Int = d.format(DateTimeFormatter.ofPattern("yyyyMMdd")).toInt()
    private fun timeToUntis(t: LocalTime): Int = t.format(DateTimeFormatter.ofPattern("HHmm")).toInt()

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}

class AbsenceAdapter(private val onClick: (Absence) -> Unit) : ListAdapter<Absence, AbsenceAdapter.VH>(Diff) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemAbsenceBinding.inflate(LayoutInflater.from(parent.context), parent, false), onClick)

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    class VH(private val b: ItemAbsenceBinding, private val onClick: (Absence) -> Unit) : RecyclerView.ViewHolder(b.root) {
        fun bind(a: Absence) {
            val ctx = b.root.context
            b.root.setOnClickListener { onClick(a) }
            b.textDate.text   = a.dateLabel
            b.textTime.text   = if (a.isFullDay) "Ganztägig" else a.timeLabel
            b.textReason.text = a.reason?.takeIf { it.isNotBlank() }
                ?: a.text?.takeIf { it.isNotBlank() } ?: "–"
            val noteText = a.text?.takeIf { it.isNotBlank() && it != a.reason }
            b.textNote.text = noteText
            b.textNote.isVisible = !noteText.isNullOrBlank()
            b.textStatus.text = a.excuseStatus ?: "–"

            val excused  = a.isExcused == true
            val isApp    = a.excuseStatus?.contains("APP", ignoreCase = true) == true
            val bgRes    = when { isApp -> R.color.yellow_container; excused -> R.color.green_container; else -> R.color.red_container }
            val textRes  = when { isApp -> R.color.yellow; excused -> R.color.green; else -> R.color.red }
            b.textStatus.setBackgroundResource(bgRes)
            b.textStatus.setTextColor(ContextCompat.getColor(ctx, textRes))
            
            // Visual hint for editability: editable ones are full opacity, others slightly faded
            b.root.alpha = if (a.canEdit == true) 1.0f else 0.85f
        }
    }

    object Diff : DiffUtil.ItemCallback<Absence>() {
        override fun areItemsTheSame(a: Absence, b: Absence) = a.id == b.id
        override fun areContentsTheSame(a: Absence, b: Absence) = a == b
    }
}
