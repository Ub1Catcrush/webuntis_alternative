package com.webuntis.dashboard.ui.timetable

import android.graphics.Paint
import android.graphics.Typeface
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.webuntis.dashboard.R
import com.webuntis.dashboard.databinding.ItemLessonBinding
import com.webuntis.dashboard.databinding.ItemLessonGroupBinding
import com.webuntis.dashboard.model.Lesson

class LessonAdapter : ListAdapter<LessonGroup, LessonAdapter.GroupViewHolder>(GroupDiff) {

    var showLongSubjects: Boolean = false
        set(value) { field = value; notifyDataSetChanged() }
    var showLongTeachers: Boolean = false
        set(value) { field = value; notifyDataSetChanged() }
    var showLongRooms: Boolean = false
        set(value) { field = value; notifyDataSetChanged() }
    var showShortSubjectInParens: Boolean = false
        set(value) { field = value; notifyDataSetChanged() }
    var showShortTeacherInParens: Boolean = false
        set(value) { field = value; notifyDataSetChanged() }
    var showShortRoomInParens: Boolean = false
        set(value) { field = value; notifyDataSetChanged() }

    var isPast: Boolean = false
        set(value) { field = value; notifyDataSetChanged() }
    /** -1 = not today; otherwise = current time in minutes for per-lesson past detection */
    var currentTimeMin: Int = -1
        set(value) { field = value; notifyDataSetChanged() }

    var onLessonClick: ((Lesson) -> Unit)? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupViewHolder {
        val binding = ItemLessonGroupBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return GroupViewHolder(binding)
    }

    override fun onBindViewHolder(holder: GroupViewHolder, position: Int) {
        holder.bind(
            getItem(position),
            showLongSubjects, showLongTeachers, showLongRooms,
            showShortSubjectInParens, showShortTeacherInParens, showShortRoomInParens,
            onLessonClick, isPast, currentTimeMin
        )
    }

    class GroupViewHolder(private val b: ItemLessonGroupBinding) : RecyclerView.ViewHolder(b.root) {

        fun bind(
            group: LessonGroup, showLongSubjects: Boolean, showLongTeachers: Boolean, showLongRooms: Boolean,
            showShortSubjectInParens: Boolean, showShortTeacherInParens: Boolean, showShortRoomInParens: Boolean,
            onClick: ((Lesson) -> Unit)?, isPast: Boolean = false, currentTimeMin: Int = -1
        ) {
            val ctx = b.root.context
            val first = group.lessons.firstOrNull() ?: return
            // Per-group past detection for today (currentTimeMin >= 0)
            val groupEndMin = (group.endTime / 100) * 60 + (group.endTime % 100)
            val isGroupPast = isPast || (currentTimeMin >= 0 && currentTimeMin > groupEndMin)
            
            b.textTime.text = "${first.startTimeFormatted}\n${first.endTimeFormatted}"
            
            // ── Ultra-compact density calculation ──────────────────────────
            val startMin    = (first.startTime / 100) * 60 + (first.startTime % 100)
            val endMin      = (first.endTime   / 100) * 60 + (first.endTime   % 100)
            val durationMin = (endMin - startMin).coerceAtLeast(1)
            val BASE_DP     = 48f // Reduced to fit ~10-12 hours per screen
            val BASE_MIN    = 45f
            val heightDp    = BASE_DP * (durationMin / BASE_MIN)
            val minHeightPx = (heightDp * ctx.resources.displayMetrics.density).toInt()

            b.root.minimumHeight = minHeightPx
            b.lessonsContainer.removeAllViews()

            group.layoutSlots().forEachIndexed { index, (lesson, _, widthFraction) ->
                val lessonBinding = ItemLessonBinding.inflate(LayoutInflater.from(ctx), b.lessonsContainer, false)
                lessonBinding.root.minimumHeight = minHeightPx
                bindLesson(
                    lessonBinding, lesson, showLongSubjects, showLongTeachers, showLongRooms,
                    showShortSubjectInParens, showShortTeacherInParens, showShortRoomInParens,
                    onClick, isGroupPast
                )

                // Use the server's own proportional widths (from layoutStartPosition/layoutWidth)
                // when available, instead of always splitting evenly — matches the official
                // WebUntis layout exactly, including uneven splits.
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, widthFraction)
                if (index > 0) lp.marginStart = (4 * ctx.resources.displayMetrics.density).toInt()

                b.lessonsContainer.addView(lessonBinding.root, lp)
            }
        }

        private fun bindLesson(
            b: ItemLessonBinding, lesson: Lesson, showLongSubjects: Boolean, showLongTeachers: Boolean, showLongRooms: Boolean,
            showShortSubjectInParens: Boolean, showShortTeacherInParens: Boolean, showShortRoomInParens: Boolean,
            onClick: ((Lesson) -> Unit)?, isPast: Boolean = false
        ) {
            val ctx = b.root.context

            b.textSubject.text = lesson.displaySubject(showLongSubjects, showShortSubjectInParens)
            b.root.setOnClickListener { onClick?.invoke(lesson) }

            // Teacher Row
            val activeTeachers = lesson.displayTeachers(showLongTeachers, showShortTeacherInParens)
            val removedNames   = lesson.removedTeachers
                ?: lesson.te?.mapNotNull { it.orgname }?.filter { it.isNotEmpty() }
                    ?.takeIf { lesson.isSubstitution }

            if (activeTeachers.isNotEmpty() || !removedNames.isNullOrEmpty()) {
                b.teacherRow.isVisible = true
                b.textTeacher.text = activeTeachers.ifEmpty { "–" }
                if (!removedNames.isNullOrEmpty()) {
                    b.textTeacherOriginal.isVisible = true
                    b.textTeacherOriginal.text = "(${removedNames.joinToString(", ")})"
                    b.textTeacherOriginal.paintFlags =
                        b.textTeacherOriginal.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                } else {
                    b.textTeacherOriginal.isVisible = false
                }
            } else {
                b.teacherRow.isVisible = false
            }

            // Info text (Now below teacher in Column 1)
            val replacedText = lesson.replacedSubject?.let { ctx.getString(R.string.timetable_replaced_subject, it) }
            val infoText = listOfNotNull(
                replacedText,
                lesson.substText?.takeIf { it.isNotBlank() },
                lesson.info?.takeIf { it.isNotBlank() },
                lesson.teachingContent?.takeIf { it.isNotBlank() }?.let { "📖 $it" }
            ).joinToString(" · ")
            
            b.textInfo.isVisible = infoText.isNotEmpty()
            if (infoText.isNotEmpty()) {
                b.textInfo.text = infoText
            }

            // Room
            val roomText = lesson.displayRooms(showLongRooms, showShortRoomInParens)
            b.textRoom.text = roomText
            b.textRoom.isVisible = roomText.isNotEmpty()

            // Notes (pinned)
            val notes = lesson.notesForAll?.takeIf { it.isNotBlank() }
            b.textNotesForAll.isVisible = notes != null
            if (notes != null) {
                b.textNotesForAll.text = ctx.getString(R.string.timetable_notes_pin, notes)
            }

            // Status Colors
            b.colorDot.isVisible = !lesson.isCancelled
            b.colorDot.setColorFilter(subjectColor(lesson.subjectName, ctx))
            b.textSubject.paintFlags = b.textSubject.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            b.root.alpha = 1f
            b.badgeChip.isVisible = false
            b.cardRoot.setCardBackgroundColor(ContextCompat.getColor(ctx, android.R.color.transparent))

            when {
                lesson.isCancelled -> {
                    b.textSubject.setTextColor(ContextCompat.getColor(ctx, R.color.red))
                    b.textSubject.paintFlags = b.textSubject.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                    b.root.alpha = 0.7f
                    b.cardRoot.setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.red_container))
                    b.badgeChip.isVisible = true
                    b.badgeChip.text = ctx.getString(R.string.badge_cancelled)
                    b.badgeChip.setChipBackgroundColorResource(R.color.red_container)
                    b.badgeChip.setTextColor(ContextCompat.getColor(ctx, R.color.red))
                }
                lesson.isSubstitution -> {
                    b.cardRoot.setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.yellow_container))
                    b.badgeChip.isVisible = true
                    b.badgeChip.text = ctx.getString(R.string.badge_substitution)
                    b.badgeChip.setChipBackgroundColorResource(R.color.yellow_container)
                    b.badgeChip.setTextColor(ContextCompat.getColor(ctx, R.color.yellow))
                }
                lesson.isExtra -> {
                    b.cardRoot.setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.green_container))
                    b.badgeChip.isVisible = true
                    b.badgeChip.text = ctx.getString(R.string.badge_extra)
                    b.badgeChip.setChipBackgroundColorResource(R.color.green_container)
                    b.badgeChip.setTextColor(ContextCompat.getColor(ctx, R.color.green))
                }
            }
            // Dim past lessons with a slight grey overlay
            if (isPast) {
                val currentAlpha = b.root.alpha
                b.root.alpha = (currentAlpha * 0.45f).coerceAtLeast(0.35f)
                b.cardRoot.alpha = 0.55f
            } else {
                b.cardRoot.alpha = 1f
            }

            // Combined view: mark entries filled in from the class plan (not part of the
            // personal timetable) with a distinct border + badge, in addition to any status badge.
            if (lesson.isFromClassPlan) {
                b.cardRoot.strokeColor = ContextCompat.getColor(ctx, R.color.primary_variant)
                b.cardRoot.strokeWidth = (1.5f * ctx.resources.displayMetrics.density).toInt()
                if (!b.badgeChip.isVisible) {
                    b.badgeChip.isVisible = true
                    b.badgeChip.text = ctx.getString(R.string.timetable_combined_label)
                    b.badgeChip.chipBackgroundColor = android.content.res.ColorStateList.valueOf(
                        ContextCompat.getColor(ctx, R.color.primary_variant)
                    )
                    b.badgeChip.setTextColor(ContextCompat.getColor(ctx, R.color.white))
                }
            } else {
                b.cardRoot.strokeColor = com.google.android.material.color.MaterialColors.getColor(
                    b.root, com.google.android.material.R.attr.colorOutlineVariant
                )
                b.cardRoot.strokeWidth = (1 * ctx.resources.displayMetrics.density).toInt()
            }
        }

        private fun subjectColor(name: String, ctx: android.content.Context): Int {
            val n = name.lowercase()
            val res = when {
                n.startsWith("m_") || n.contains("math") -> R.color.subject_math
                (n == "d" || n.contains("deu") || n.contains("deutsch")) -> R.color.subject_german
                n.contains("eng") || n.startsWith("e_") -> R.color.subject_english
                n.contains("phy") -> R.color.subject_physics
                n.contains("che") -> R.color.subject_chemistry
                n.contains("geo") || n.contains("gesch") || n == "gl" || n.startsWith("gl") -> R.color.subject_history
                n.contains("bio") -> R.color.subject_bio
                n.contains("sport") || n == "sp" || n.startsWith("sp") -> R.color.subject_sport
                n.contains("kunst") || n.contains("musik") || n == "mu" || n == "ku" -> R.color.subject_art
                else -> R.color.subject_default
            }
            return ContextCompat.getColor(ctx, res)
        }
    }

    object GroupDiff : DiffUtil.ItemCallback<LessonGroup>() {
        override fun areItemsTheSame(a: LessonGroup, b: LessonGroup) = a.id == b.id
        override fun areContentsTheSame(a: LessonGroup, b: LessonGroup) = a == b
    }
}
