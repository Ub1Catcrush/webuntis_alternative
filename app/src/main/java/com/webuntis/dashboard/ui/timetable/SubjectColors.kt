package com.webuntis.dashboard.ui.timetable

import android.content.Context
import androidx.core.content.ContextCompat
import com.webuntis.dashboard.R

/**
 * Local fallback heuristic used when the WebUntis API doesn't provide a per-lesson
 * color (see [com.webuntis.dashboard.model.Lesson.resolvedColor]). Guesses a color
 * from common German/English subject-name abbreviations. Shared by the Tag
 * ([LessonAdapter]) and Woche ([WeekGridView]) views so both fall back consistently.
 */
fun subjectColor(name: String, ctx: Context): Int {
    val n = name.lowercase()
    val res = when {
        n.startsWith("m_") || n.contains("math") -> R.color.subject_math
        (n == "d" || n.contains("deu") || n.contains("deutsch")) || n.startsWith("d_") -> R.color.subject_german
        n.contains("eng") || n.startsWith("e_") -> R.color.subject_english
        n.contains("phy") || n.startsWith("ph") -> R.color.subject_physics
        n.contains("che") || n.startsWith("ch") -> R.color.subject_chemistry
        n.contains("geo") || n.contains("gesch") || n == "gl" || n.startsWith("gl") -> R.color.subject_history
        n.contains("bio") -> R.color.subject_bio
        n.contains("sport") || n == "sp" || n.startsWith("sp") -> R.color.subject_sport
        n.contains("kunst") || n.contains("musik") || n == "mu" || n == "ku" -> R.color.subject_art
        else -> R.color.subject_default
    }
    return ContextCompat.getColor(ctx, res)
}
