package com.webuntis.dashboard.ui.timetable

import android.graphics.Paint
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.webuntis.dashboard.R
import com.webuntis.dashboard.databinding.ItemLessonBinding
import com.webuntis.dashboard.model.Lesson

class LessonAdapter : ListAdapter<Lesson, LessonAdapter.LessonViewHolder>(LessonDiff) {

    /** Set by the Fragment from SessionManager.showLongNames; triggers redraw when changed. */
    var showLongSubjects: Boolean = false
        set(value) { field = value; notifyDataSetChanged() }
    var showLongTeachers: Boolean = false
        set(value) { field = value; notifyDataSetChanged() }
    var showLongRooms: Boolean = false
        set(value) { field = value; notifyDataSetChanged() }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LessonViewHolder {
        val binding = ItemLessonBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return LessonViewHolder(binding)
    }

    override fun onBindViewHolder(holder: LessonViewHolder, position: Int) {
        holder.bind(getItem(position), showLongSubjects, showLongTeachers, showLongRooms)
    }

    class LessonViewHolder(private val b: ItemLessonBinding) : RecyclerView.ViewHolder(b.root) {

        fun bind(lesson: Lesson, showLongSubjects: Boolean, showLongTeachers: Boolean, showLongRooms: Boolean) {
            val ctx = b.root.context

            // Time
            b.textTime.text = "${lesson.startTimeFormatted}\n${lesson.endTimeFormatted}"

            // ── Proportional height based on actual duration ──────────────────
            val startMin    = (lesson.startTime / 100) * 60 + (lesson.startTime % 100)
            val endMin      = (lesson.endTime   / 100) * 60 + (lesson.endTime   % 100)
            val durationMin = (endMin - startMin).coerceAtLeast(1)
            val BASE_DP     = 80f
            val BASE_MIN    = 45f
            val heightDp    = BASE_DP * (durationMin / BASE_MIN)
            val minHeightPx = (heightDp * ctx.resources.displayMetrics.density).toInt()
            b.root.minimumHeight = minHeightPx
            // When info text is visible let the card grow freely; otherwise pin to exact height
            b.root.layoutParams = b.root.layoutParams.also { lp ->
                lp.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            }

            // Subject
            b.textSubject.text = lesson.displaySubject(showLongSubjects)

            // ── Teacher row ──────────────────────────────────────────────────
            // Active teachers (not REMOVED); strikethrough for removed ones
            // Uses detail-enriched removedTeachers when available, else orgname fallback.
            val activeTeachers = lesson.displayTeachers(showLongTeachers)
            val removedNames   = lesson.removedTeachers
                ?: lesson.te?.mapNotNull { it.orgname }?.filter { it.isNotEmpty() }
                    ?.takeIf { lesson.isSubstitution }

            if (activeTeachers.isNotEmpty() || !removedNames.isNullOrEmpty()) {
                b.teacherRow.isVisible = true
                b.textTeacher.text = activeTeachers.ifEmpty { "–" }
                if (!removedNames.isNullOrEmpty()) {
                    b.textTeacherOriginal.isVisible = true
                    b.textTeacherOriginal.text = removedNames.joinToString(", ")
                    b.textTeacherOriginal.paintFlags =
                        b.textTeacherOriginal.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                } else {
                    b.textTeacherOriginal.isVisible = false
                }
            } else {
                b.teacherRow.isVisible = false
                b.textTeacherOriginal.isVisible = false
            }

            // ── Info / substitution / teaching content ───────────────────────
            val infoText = listOfNotNull(
                lesson.substText?.takeIf { it.isNotBlank() },
                lesson.info?.takeIf { it.isNotBlank() },
                lesson.teachingContent?.takeIf { it.isNotBlank() }
                    ?.let { "📖 $it" }
            ).joinToString(" · ")
            b.textInfo.isVisible = infoText.isNotEmpty()
            b.textInfo.text = infoText

            // ── Notes for all (from v1 gridEntry notesAll / NOTES_FOR_ALL text) ──
            // Shown as a separate row with a 📌 prefix so it's clearly distinct
            // from lesson info and substitution text.
            val notes = lesson.notesForAll?.takeIf { it.isNotBlank() }
            b.textNotesForAll.isVisible = notes != null
            if (notes != null) {
                b.textNotesForAll.text = "📌 $notes"
                // Make URLs clickable so teachers' links (wikihow, youtube, etc.) open directly
                android.text.util.Linkify.addLinks(b.textNotesForAll, android.text.util.Linkify.WEB_URLS)
                b.textNotesForAll.movementMethod = android.text.method.LinkMovementMethod.getInstance()
            }

            // Room
            b.textRoom.text = lesson.displayRooms(showLongRooms)
            b.textRoom.isVisible = lesson.displayRooms(showLongRooms).isNotEmpty()

            // Dot color
            // Keep space even when cancelled so layout stays stable (INVISIBLE not GONE)
            b.colorDot.visibility = if (lesson.isCancelled) android.view.View.INVISIBLE else android.view.View.VISIBLE
            b.colorDot.setColorFilter(subjectColor(lesson.subjectName, ctx))

            // Status styling
            // Reset first
            b.textSubject.paintFlags = b.textSubject.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            b.root.alpha = 1f
            b.badgeChip.isVisible = false
            b.cardRoot.setCardBackgroundColor(ContextCompat.getColor(ctx, android.R.color.transparent))

            when {
                lesson.isCancelled -> {
                    b.textSubject.setTextColor(ContextCompat.getColor(ctx, R.color.red))
                    b.textSubject.typeface = Typeface.DEFAULT_BOLD
                    b.textSubject.paintFlags = b.textSubject.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                    b.root.alpha = 0.7f
                    b.cardRoot.setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.red_container))
                    b.badgeChip.isVisible = true
                    b.badgeChip.text = b.root.context.getString(R.string.badge_cancelled)
                    b.badgeChip.setChipBackgroundColorResource(R.color.red_container)
                    b.badgeChip.setTextColor(ContextCompat.getColor(ctx, R.color.red))
                }
                lesson.isSubstitution -> {
                    b.textSubject.setTextColor(ContextCompat.getColor(ctx, android.R.color.tab_indicator_text))
                    b.textSubject.typeface = Typeface.DEFAULT_BOLD
                    b.cardRoot.setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.yellow_container))
                    b.badgeChip.isVisible = true
                    b.badgeChip.text = b.root.context.getString(R.string.badge_substitution)
                    b.badgeChip.setChipBackgroundColorResource(R.color.yellow_container)
                    b.badgeChip.setTextColor(ContextCompat.getColor(ctx, R.color.yellow))
                }
                lesson.isExtra -> {
                    b.textSubject.setTextColor(ContextCompat.getColor(ctx, android.R.color.tab_indicator_text))
                    b.textSubject.typeface = Typeface.DEFAULT_BOLD
                    b.cardRoot.setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.green_container))
                    b.badgeChip.isVisible = true
                    b.badgeChip.text = b.root.context.getString(R.string.badge_extra)
                    b.badgeChip.setChipBackgroundColorResource(R.color.green_container)
                    b.badgeChip.setTextColor(ContextCompat.getColor(ctx, R.color.green))
                }
                else -> {
                    b.textSubject.setTextColor(ContextCompat.getColor(ctx, android.R.color.tab_indicator_text))
                    b.textSubject.typeface = Typeface.DEFAULT_BOLD
                }
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

    object LessonDiff : DiffUtil.ItemCallback<Lesson>() {
        override fun areItemsTheSame(a: Lesson, b: Lesson) = a.id == b.id
        override fun areContentsTheSame(a: Lesson, b: Lesson) = a == b
    }
}
