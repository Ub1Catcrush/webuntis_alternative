package com.webuntis.dashboard.ui.timetable

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.webuntis.dashboard.R
import com.webuntis.dashboard.databinding.ItemCompactDayBinding
import com.webuntis.dashboard.databinding.ItemCompactLessonBinding
import com.webuntis.dashboard.model.Lesson
import java.time.LocalDate

class CompactWeekAdapter : ListAdapter<SchoolDay, CompactWeekAdapter.DayViewHolder>(DayDiff) {

    var showLongSubjects: Boolean = false
        set(value) { field = value; notifyDataSetChanged() }
    var showLongRooms:    Boolean = false
        set(value) { field = value; notifyDataSetChanged() }
    
    var onLessonClick: ((Lesson) -> Unit)? = null
    var isPastDay: Boolean = false
        set(value) { field = value; notifyDataSetChanged() }
    var currentTimeMin: Int = -1
        set(value) { field = value; notifyDataSetChanged() }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DayViewHolder {
        val b = ItemCompactDayBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return DayViewHolder(b)
    }

    override fun onBindViewHolder(holder: DayViewHolder, position: Int) {
        holder.bind(getItem(position), showLongSubjects, showLongRooms, onLessonClick, isPastDay, currentTimeMin)
    }

    class DayViewHolder(private val b: ItemCompactDayBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(day: SchoolDay, longSubjects: Boolean, longRooms: Boolean, onClick: ((Lesson) -> Unit)?, isPastDay: Boolean = false, currentTimeMin: Int = -1) {
            val today = LocalDate.now()
            val isPastDay = day.date.isBefore(today)
            val isToday   = day.date == today
            b.textDayLabel.text = day.tabLabel
            b.rvLessons.layoutManager = LinearLayoutManager(b.root.context)
            val adapter = CompactLessonAdapter()
            adapter.showLongSubjects = longSubjects
            adapter.showLongRooms    = longRooms
            adapter.onLessonClick    = onClick
            adapter.isPastDay        = isPastDay
            adapter.currentTimeMin   = if (isToday) com.webuntis.dashboard.ui.timetable.TimeIndicatorView.currentTimeMinutes() else -1
            b.rvLessons.adapter = adapter
            adapter.submitList(day.lessons)
        }
    }

    object DayDiff : DiffUtil.ItemCallback<SchoolDay>() {
        override fun areItemsTheSame(a: SchoolDay, b: SchoolDay) = a.date == b.date
        override fun areContentsTheSame(a: SchoolDay, b: SchoolDay) = a == b
    }
}

class CompactLessonAdapter : ListAdapter<Lesson, CompactLessonAdapter.LessonViewHolder>(LessonDiff) {

    var showLongSubjects: Boolean = false
    var showLongRooms:    Boolean = false
    var onLessonClick: ((Lesson) -> Unit)? = null
    var isPastDay: Boolean = false
    var currentTimeMin: Int = -1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LessonViewHolder {
        val b = ItemCompactLessonBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return LessonViewHolder(b)
    }

    override fun onBindViewHolder(holder: LessonViewHolder, position: Int) {
        holder.bind(getItem(position), showLongSubjects, showLongRooms, onLessonClick, isPastDay, currentTimeMin)
    }

    class LessonViewHolder(private val b: ItemCompactLessonBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(lesson: Lesson, longSubjects: Boolean, longRooms: Boolean, onClick: ((Lesson) -> Unit)?, isPastDay: Boolean = false, currentTimeMin: Int = -1) {
            val ctx = b.root.context
            b.textSubject.text = lesson.displaySubject(longSubjects)
            
            // Use localized string resource instead of String.format
            b.textTime.text = ctx.getString(R.string.timetable_time_range, lesson.startTimeFormatted, lesson.endTimeFormatted)
            
            val room = lesson.displayRooms(longRooms)
            b.textRoom.text = room
            b.textRoom.visibility = if (room.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
            
            b.root.setOnClickListener { onClick?.invoke(lesson) }

            // Basic status coloring
            when {
                lesson.isCancelled -> {
                    b.cardRoot.setCardBackgroundColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.red_container))
                    b.textSubject.paintFlags = b.textSubject.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
                    b.root.alpha = 0.7f
                }
                lesson.isSubstitution -> {
                    b.cardRoot.setCardBackgroundColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.yellow_container))
                    b.textSubject.paintFlags = b.textSubject.paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG.inv()
                    b.root.alpha = 1.0f
                }
                else -> {
                    b.cardRoot.setCardBackgroundColor(androidx.core.content.ContextCompat.getColor(ctx, android.R.color.transparent))
                    b.textSubject.paintFlags = b.textSubject.paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG.inv()
                    b.root.alpha = 1.0f
                }
            }
            // Dim past lessons (past days or today's finished lessons)
            val lessonEndMin = (lesson.endTime / 100) * 60 + (lesson.endTime % 100)
            val isLessonPast = isPastDay || (currentTimeMin >= 0 && currentTimeMin > lessonEndMin)
            if (isLessonPast) {
                b.root.alpha = (b.root.alpha * 0.45f).coerceAtLeast(0.35f)
                b.cardRoot.alpha = 0.55f
            } else {
                b.cardRoot.alpha = 1f
            }
        }
    }

    object LessonDiff : DiffUtil.ItemCallback<Lesson>() {
        override fun areItemsTheSame(a: Lesson, b: Lesson) = a.id == b.id
        override fun areContentsTheSame(a: Lesson, b: Lesson) = a == b
    }
}
