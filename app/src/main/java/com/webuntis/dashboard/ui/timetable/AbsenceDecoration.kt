package com.webuntis.dashboard.ui.timetable

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.webuntis.dashboard.model.Absence

/**
 * RecyclerView ItemDecoration that draws a semi-transparent grey overlay
 * over lesson groups that are covered by an absence.
 *
 * Uses the actual View positions (via RecyclerView.getChildAt) so the overlay
 * aligns pixel-perfectly with the lesson cards regardless of scroll position.
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

    override fun onDrawOver(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        if (absences.isEmpty()) return
        val adapter = parent.adapter as? LessonAdapter ?: return
        val density = parent.resources.displayMetrics.density

        textPaint.textSize  = 11f * density
        strikeLinePaint.strokeWidth = 2f * density

        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i) ?: continue
            val pos   = parent.getChildAdapterPosition(child)
            if (pos == RecyclerView.NO_POSITION) continue
            val group = adapter.currentList.getOrNull(pos) ?: continue

            val lessonStartMin = (group.startTime / 100) * 60 + (group.startTime % 100)
            val lessonEndMin   = (group.endTime   / 100) * 60 + (group.endTime   % 100)

            // Find all absences that overlap this lesson group
            val matching = absences.filter { abs ->
                val absStart = abs.startTime ?: 0
                val absEnd   = abs.endTime   ?: 2359
                val absStartMin = (absStart / 100) * 60 + (absStart % 100)
                val absEndMin   = (absEnd   / 100) * 60 + (absEnd   % 100)
                // Overlap: absence starts before lesson ends AND ends after lesson starts
                absStartMin < lessonEndMin && absEndMin > lessonStartMin
            }
            if (matching.isEmpty()) continue

            // Get child bounds in RecyclerView coordinates
            val rect = Rect()
            parent.getDecoratedBoundsWithMargins(child, rect)

            // Draw grey overlay over entire lesson card
            canvas.drawRect(rect.left.toFloat(), rect.top.toFloat(),
                rect.right.toFloat(), rect.bottom.toFloat(), overlayPaint)

            // Draw label (reason or "Abwesenheit") centered in the card
            val label = matching.firstOrNull()?.let { abs ->
                abs.reason?.takeIf { it.isNotBlank() }
                    ?: abs.text?.takeIf { it.isNotBlank() }
                    ?: "Abwesenheit"
            } ?: "Abwesenheit"

            val cx = (rect.left + rect.right) / 2f
            val cy = (rect.top + rect.bottom) / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(label, cx, cy, textPaint)

            // Draw a subtle diagonal line to make absence visually distinct
            canvas.drawLine(rect.left.toFloat(), rect.top.toFloat(),
                rect.right.toFloat(), rect.bottom.toFloat(), strikeLinePaint)
        }
    }
}
