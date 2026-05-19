package com.webuntis.dashboard.ui.events

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.webuntis.dashboard.R
import com.webuntis.dashboard.databinding.ItemEventBinding
import com.webuntis.dashboard.model.SchoolEvent

class EventsAdapter : ListAdapter<SchoolEvent, EventsAdapter.VH>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemEventBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) =
        holder.bind(getItem(position))

    class VH(private val b: ItemEventBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(event: SchoolEvent) {
            b.textTitle.text = event.displayTitle
            b.textDate.text = event.dateLabel
            b.textTime.text = event.timeLabel.ifBlank { "" }
            if (event.displayText.isNotBlank()) {
                b.textDescription.text = event.displayText
                b.textDescription.visibility = android.view.View.VISIBLE
            } else {
                b.textDescription.visibility = android.view.View.GONE
            }

            // Color the left accent bar by event type
            val accentColor = when {
                event.isExam -> ContextCompat.getColor(b.root.context, R.color.red)
                event.eventType == "HOLIDAYS" ->
                    ContextCompat.getColor(b.root.context, R.color.green)
                else -> ContextCompat.getColor(b.root.context, R.color.blue)
            }
            b.accentBar.setBackgroundColor(accentColor)

            // Type chip
            b.typeChip.text = when {
                event.isExam -> b.root.context.getString(R.string.event_type_exam)
                event.eventType == "HOLIDAYS" ->
                    b.root.context.getString(R.string.event_type_holiday)
                else -> b.root.context.getString(R.string.event_type_event)
            }
        }
    }

    object Diff : DiffUtil.ItemCallback<SchoolEvent>() {
        override fun areItemsTheSame(a: SchoolEvent, b: SchoolEvent) = a.id == b.id
        override fun areContentsTheSame(a: SchoolEvent, b: SchoolEvent) = a == b
    }
}
