package com.webuntis.dashboard.ui.homework

import android.graphics.Paint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.webuntis.dashboard.R
import com.webuntis.dashboard.databinding.ItemHomeworkBinding
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class HomeworkAdapter(
    private val onToggle: (Int) -> Unit
) : ListAdapter<HomeworkUiItem, HomeworkAdapter.VH>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemHomeworkBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(private val b: ItemHomeworkBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: HomeworkUiItem) {
            val hw = item.homework
            b.subjectChip.text = item.subjectName
            b.textContent.text = hw.displayText

            // Due date with color
            val due = hw.dueDateFormatted
            if (due != null) {
                b.textDue.text = b.root.context.getString(R.string.label_homework_due, due)
                val dueNum = hw.dueDate ?: 0
                val todayNum = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")).toInt()
                b.textDue.setTextColor(ContextCompat.getColor(b.root.context, when {
                    dueNum < todayNum -> R.color.red
                    dueNum <= todayNum + 2 -> R.color.yellow
                    else -> R.color.on_surface_variant
                }))
            } else {
                b.textDue.text = ""
            }

            // Done state
            b.checkBox.isChecked = item.isDone
            if (item.isDone) {
                b.textContent.paintFlags = b.textContent.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                b.root.alpha = 0.5f
            } else {
                b.textContent.paintFlags = b.textContent.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
                b.root.alpha = 1f
            }

            b.checkBox.setOnClickListener { onToggle(hw.id) }

            // Subject chip color
            val colorRes = subjectColorRes(item.subjectName)
            b.subjectChip.backgroundTintList =
                android.content.res.ColorStateList.valueOf(
                    androidx.core.content.ContextCompat.getColor(b.root.context, colorRes)
                )
        }

        private fun subjectColorRes(name: String): Int {
            val n = name.lowercase()
            return when {
                n.contains("math") -> R.color.subject_math_container
                n.contains("deu") || n.contains("deutsch") -> R.color.subject_german_container
                n.contains("eng") -> R.color.subject_english_container
                n.contains("phy") -> R.color.subject_physics_container
                n.contains("che") -> R.color.subject_chemistry_container
                n.contains("geo") || n.contains("gesch") || n.contains("hist") -> R.color.subject_history_container
                n.contains("bio") -> R.color.subject_bio_container
                n.contains("sport") -> R.color.subject_sport_container
                else -> R.color.subject_default_container
            }
        }
    }

    object Diff : DiffUtil.ItemCallback<HomeworkUiItem>() {
        override fun areItemsTheSame(a: HomeworkUiItem, b: HomeworkUiItem) = a.homework.id == b.homework.id
        override fun areContentsTheSame(a: HomeworkUiItem, b: HomeworkUiItem) = a == b
    }
}
