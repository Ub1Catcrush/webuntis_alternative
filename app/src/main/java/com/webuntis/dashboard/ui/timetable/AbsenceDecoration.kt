package com.webuntis.dashboard.ui.timetable

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.recyclerview.widget.RecyclerView
import com.webuntis.dashboard.model.Absence

/**
 * RecyclerView ItemDecoration that draws a semi-transparent grey overlay
 * over lesson groups that are covered by an absence.
 *
 * Consecutive lesson rows covered by the same absence are merged into a single overlay block
 * (one border, one centered label, one diagonal line) spanning all of them, rather than each
 * row getting its own separate overlay — visually this reads as "these hours together are one
 * absence" instead of a repeated stack of identical little boxes.
 *
 * Uses the actual View positions (via RecyclerView.getChildAt) so the overlay aligns
 * pixel-perfectly with the lesson cards regardless of scroll position.
 */
class AbsenceDecoration : RecyclerView.ItemDecoration() {

    var absences: List<Absence> = emptyList()
        set(value) { field = value }

    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(80, 80, 80, 80)
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(200, 255, 255, 255)
        textSize = 0f  // set dynamically from density
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
        setShadowLayer(2f, 0f, 1f, android.graphics.Color.argb(120, 0, 0, 0))
    }

    private val strikeLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(160, 180, 60, 60)
        strokeWidth = 0f  // set dynamically
        style = Paint.Style.STROKE
    }

    private data class CoveredRow(val position: Int, val rect: Rect, val absences: List<Absence>)

    override fun onDrawOver(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        if (absences.isEmpty()) return
        val adapter = parent.adapter as? LessonAdapter ?: return
        val density = parent.resources.displayMetrics.density

        textPaint.textSize  = 11f * density
        strikeLinePaint.strokeWidth = 2f * density

        // 1) Find every currently visible row covered by an absence.
        val covered = mutableListOf<CoveredRow>()
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i) ?: continue
            val pos   = parent.getChildAdapterPosition(child)
            if (pos == RecyclerView.NO_POSITION) continue
            val group = adapter.currentList.getOrNull(pos) ?: continue

            val lessonStartMin = (group.startTime / 100) * 60 + (group.startTime % 100)
            val lessonEndMin   = (group.endTime   / 100) * 60 + (group.endTime   % 100)

            val matching = absences.filter { abs ->
                val absStart = abs.startTime ?: 0
                val absEnd   = abs.endTime   ?: 2359
                val absStartMin = (absStart / 100) * 60 + (absStart % 100)
                val absEndMin   = (absEnd   / 100) * 60 + (absEnd   % 100)
                // Overlap: absence starts before lesson ends AND ends after lesson starts
                absStartMin < lessonEndMin && absEndMin > lessonStartMin
            }
            if (matching.isEmpty()) continue

            val rect = Rect()
            parent.getDecoratedBoundsWithMargins(child, rect)
            covered.add(CoveredRow(pos, rect, matching))
        }
        if (covered.isEmpty()) return
        covered.sortBy { it.position }

        // 2) Merge consecutive adapter positions (i.e. consecutive, back-to-back time slots)
        // into runs, and draw ONE overlay block per run instead of one per row.
        var runStart = 0
        for (i in 1..covered.size) {
            val runEnds = i == covered.size || covered[i].position != covered[i - 1].position + 1
            if (runEnds) {
                drawRunOverlay(canvas, covered.subList(runStart, i))
                runStart = i
            }
        }
    }

    private fun drawRunOverlay(canvas: Canvas, run: List<CoveredRow>) {
        val top    = run.minOf { it.rect.top }
        val bottom = run.maxOf { it.rect.bottom }
        val left   = run.minOf { it.rect.left }
        val right  = run.maxOf { it.rect.right }

        canvas.drawRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), overlayPaint)

        val label = run.first().absences.firstOrNull()?.let { abs ->
            abs.reason?.takeIf { it.isNotBlank() }
                ?: abs.text?.takeIf { it.isNotBlank() }
                ?: "Abwesenheit"
        } ?: "Abwesenheit"

        val cx = (left + right) / 2f
        val cy = (top + bottom) / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(label, cx, cy, textPaint)

        // One diagonal spanning the whole merged block, not one per row.
        canvas.drawLine(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), strikeLinePaint)
    }
}
