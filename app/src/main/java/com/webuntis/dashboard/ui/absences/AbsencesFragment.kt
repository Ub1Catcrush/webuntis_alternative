package com.webuntis.dashboard.ui.absences

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.view.*
import android.widget.ArrayAdapter
import android.widget.Toast
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
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.webuntis.dashboard.model.AbsencesMetaData
import com.webuntis.dashboard.model.AbsenceReason
import com.webuntis.dashboard.model.TimegridRow
import com.webuntis.dashboard.R
import com.webuntis.dashboard.api.CreateAbsenceRequest
import com.webuntis.dashboard.databinding.DialogEditAbsenceBinding
import com.webuntis.dashboard.databinding.FragmentAbsencesBinding
import com.webuntis.dashboard.databinding.ItemAbsenceBinding
import com.webuntis.dashboard.model.Absence
import com.webuntis.dashboard.model.UiState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
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

        val adapter = AbsenceAdapter { absence ->
            if (absence.canEdit == true) {
                showEditAbsenceDialog(absence)
            } else {
                Toast.makeText(requireContext(), getString(R.string.absence_cannot_edit), Toast.LENGTH_SHORT).show()
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
                                if (state.data.isEmpty()) {
                                    binding.recyclerView.isVisible = false
                                    binding.emptyView.isVisible = true
                                    binding.emptyView.text = getString(R.string.label_no_absences_short)
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
                launch {
                    viewModel.filter.collect { activeFilter ->
                        updateSubtitle(activeFilter)
                    }
                }
            }
        }

        // Build fixed 4-chip filter bar (done once, client-side filtering)
        buildFilterChips()
        binding.filterScroll.isVisible = true
    }

    private fun buildFilterChips() {
        val group = binding.chipGroupFilter
        group.removeAllViews()

        val filters = listOf(
            AbsenceFilter.ALL       to getString(R.string.absence_filter_all),
            AbsenceFilter.UNEXCUSED to getString(R.string.label_absence_unexcused),
            AbsenceFilter.EXCUSED   to getString(R.string.label_absence_excused),
            AbsenceFilter.PENDING   to getString(R.string.label_absence_pending)
        )

        filters.forEach { (filter, label) ->
            val chip = Chip(requireContext()).apply {
                text = label
                isCheckable = true
                isChecked = viewModel.filter.value == filter
                tag = filter
            }
            group.addView(chip)
        }

        group.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
            val chip = group.findViewById<Chip>(checkedIds[0])
            (chip?.tag as? AbsenceFilter)?.let { viewModel.setFilter(it) }
        }
    }

    private fun updateSubtitle(activeFilter: AbsenceFilter) {
        val unexcusedCount = viewModel.unexcusedCount()
        binding.toolbar.subtitle = when {
            activeFilter != AbsenceFilter.ALL ->
                getString(R.string.absence_filter_active,
                    when (activeFilter) {
                        AbsenceFilter.EXCUSED   -> getString(R.string.label_absence_excused)
                        AbsenceFilter.UNEXCUSED -> getString(R.string.label_absence_unexcused)
                        AbsenceFilter.PENDING   -> getString(R.string.label_absence_pending)
                        AbsenceFilter.ALL       -> ""
                    })
            unexcusedCount > 0 -> getString(R.string.absence_unexcused_count, unexcusedCount)
            else -> ""
        }
    }

    private fun showEditAbsenceDialog(absence: Absence?) {
        // For new absences, we'll override the default times with timetable data asynchronously
        val context = requireContext()
        val meta = viewModel.meta.value ?: return
        val reasons = meta.absenceReasons ?: emptyList()

        val dialogBinding = DialogEditAbsenceBinding.inflate(LayoutInflater.from(context))

        var start = absence?.let { untisIntToDate(it.startDate ?: 0) }
                    ?: LocalDate.now()
        var end   = absence?.let { untisIntToDate(it.endDate ?: 0) }
                    ?: LocalDate.now()
        var startTime = absence?.startTime?.let { untisIntToTime(it) }
                        ?: LocalTime.of(8, 0)
        var endTime   = absence?.endTime?.let { untisIntToTime(it) }
                        ?: LocalTime.of(16, 0)
        var selectedReasonId = absence?.reasonId ?: meta.defaultAbsenceReason ?: -1

        val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

        val updateButtons = {
            dialogBinding.btnStartDate.text = start.format(dateFmt)
            dialogBinding.btnEndDate.text   = end.format(dateFmt)
            dialogBinding.btnStartTime.text = startTime.format(timeFmt)
            dialogBinding.btnEndTime.text   = endTime.format(timeFmt)
        }
        updateButtons()

        // ── Period quick-select chips ─────────────────────────────────────────
        // Two chip rows: one for start time, one for end time. Each chip shows HH:mm.
        viewLifecycleOwner.lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) {
                viewModel.getTimegrid()
            }
            if (rows.isEmpty()) return@launch

            val chipGroup = dialogBinding.chipGroupPeriods
            chipGroup.removeAllViews()

            // Helper: build one row label + chips
            fun addTimechips(
                labelText: String,
                isStart: Boolean,
                initialTime: LocalTime
            ) {
                // Section label
                val label = android.widget.TextView(context).apply {
                    text = labelText
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelSmall)
                    setPadding(0, 8, 0, 2)
                }
                chipGroup.addView(label)

                // Horizontal scroll for the chips in this row
                val hscroll = android.widget.HorizontalScrollView(context).apply {
                    isHorizontalScrollBarEnabled = false
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }
                val row = com.google.android.material.chip.ChipGroup(context).apply {
                    isSingleSelection = true
                    chipSpacingHorizontal = 4
                }
                hscroll.addView(row)
                chipGroup.addView(hscroll)

                rows.forEach { r ->
                    val time = if (isStart)
                        LocalTime.of(r.startTime / 100, r.startTime % 100)
                    else
                        LocalTime.of(r.endTime / 100, r.endTime % 100)
                    val chip = Chip(context).apply {
                        text = time.format(timeFmt)
                        isCheckable = true
                        tag = time
                        isChecked = time == initialTime
                    }
                    chip.setOnClickListener {
                        if (chip.isChecked) {
                            if (isStart) startTime = time else endTime = time
                            updateButtons()
                        }
                    }
                    row.addView(chip)
                }
            }

            addTimechips(getString(R.string.dialog_absence_section_start), isStart = true,  initialTime = startTime)
            addTimechips(getString(R.string.dialog_absence_section_end),   isStart = false, initialTime = endTime)

            // For new absences: set initial start/end from timegrid
            if (absence == null) {
                startTime = LocalTime.of(rows.first().startTime / 100, rows.first().startTime % 100)
                endTime   = LocalTime.of(rows.last().endTime   / 100, rows.last().endTime   % 100)
                updateButtons()
            }
        }

        // ── Date & time pickers ───────────────────────────────────────────────
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
                // Deselect start-time chips since user manually picked a time
                clearChipGroupRow(dialogBinding.chipGroupPeriods, rowIndex = 1)
                updateButtons()
            }, startTime.hour, startTime.minute, true).show()
        }
        dialogBinding.btnEndTime.setOnClickListener {
            TimePickerDialog(context, { _, h, m ->
                endTime = LocalTime.of(h, m)
                clearChipGroupRow(dialogBinding.chipGroupPeriods, rowIndex = 3)
                updateButtons()
            }, endTime.hour, endTime.minute, true).show()
        }

        // ── Reason spinner ────────────────────────────────────────────────────
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
        dialogBinding.textTitle.text = if (absence == null)
            getString(R.string.absence_dialog_title_add)
        else
            getString(R.string.absence_dialog_title_edit)

        if (absence != null) dialogBinding.btnDelete.isVisible = true

        val dialog = MaterialAlertDialogBuilder(context)
            .setView(dialogBinding.root)
            .create()

        dialogBinding.btnCancel.setOnClickListener { dialog.dismiss() }

        dialogBinding.btnSave.setOnClickListener {
            val req = CreateAbsenceRequest(
                startDate  = dateToUntis(start),
                startTime  = timeToUntis(startTime),
                endDate    = dateToUntis(end),
                endTime    = timeToUntis(endTime),
                text       = dialogBinding.editText.text.toString(),
                reasonId   = selectedReasonId,
                studentId  = viewModel.studentId
            )
            if (absence == null) {
                viewModel.createAbsence(req) { res ->
                    res.onSuccess { dialog.dismiss() }
                       .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
                }
            } else {
                viewModel.updateAbsence(absence.id, req) { res ->
                    res.onSuccess { dialog.dismiss() }
                       .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
                }
            }
        }

        dialogBinding.btnDelete.setOnClickListener {
            MaterialAlertDialogBuilder(context)
                .setTitle(getString(R.string.absence_delete_title))
                .setMessage(getString(R.string.absence_delete_message))
                .setNegativeButton(getString(R.string.btn_cancel), null)
                .setPositiveButton(getString(R.string.absence_delete_confirm)) { _, _ ->
                    viewModel.deleteAbsence(absence!!.id) { res ->
                        res.onSuccess { dialog.dismiss() }
                           .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
                    }
                }
                .show()
        }

        dialog.show()
    }

        /**
         * Clears chip selection inside a nested ChipGroup.
         * Container layout: [TextView, HScrollView(ChipGroup), TextView, HScrollView(ChipGroup)]
         * rowIndex 1 = start chips (index 1), rowIndex 3 = end chips (index 3)
         */
        private fun clearChipGroupRow(container: android.widget.LinearLayout, rowIndex: Int) {
            val hscroll = container.getChildAt(rowIndex) as? android.widget.HorizontalScrollView ?: return
            val cg = hscroll.getChildAt(0) as? com.google.android.material.chip.ChipGroup ?: return
            for (i in 0 until cg.childCount) (cg.getChildAt(i) as? Chip)?.isChecked = false
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
            b.textTime.text   = if (a.isFullDay) ctx.getString(R.string.label_absence_fullday) else a.timeLabel
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
            b.root.alpha = if (a.canEdit == true) 1.0f else 0.85f
        }
    }

    object Diff : DiffUtil.ItemCallback<Absence>() {
        override fun areItemsTheSame(a: Absence, b: Absence) = a.id == b.id
        override fun areContentsTheSame(a: Absence, b: Absence) = a == b
    }
}
