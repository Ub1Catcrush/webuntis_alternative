package com.webuntis.dashboard.ui.timetable

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.webuntis.dashboard.R
import java.util.Calendar

/**
 * Overlay view that draws a horizontal "current time" line across the timetable.
 *
 * Y position is derived from the actual RecyclerView child bounds so it stays
 * accurate even when lesson cards grow beyond their minimum height (long subjects,
 * many teachers, notes, etc.).
 */
class TimeIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
    }

    // Set by DayFragment
    var dayStartMin: Int = -1
    var dayEndMin:   Int = -1

    /** The RecyclerView whose children we use for Y interpolation */
    var recyclerView: RecyclerView? = null

    // Semi-transparent red
    private val lineColor = ContextCompat.getColor(context, R.color.red).let {
        android.graphics.Color.argb(180,
            android.graphics.Color.red(it),
            android.graphics.Color.green(it),
            android.graphics.Color.blue(it))
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = lineColor
        strokeWidth = 2f * density
        style = Paint.Style.STROKE
    }
    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = lineColor
        style = Paint.Style.FILL
    }
    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = lineColor
        style = Paint.Style.FILL
    }
    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, android.R.color.white)
        textSize = 10f * density
        textAlign = Paint.Align.LEFT
        typeface = android.graphics.Typeface.DEFAULT
    }

    var currentTimeMin: Int = currentTimeMinutes()
        set(value) { field = value; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (dayStartMin < 0 || dayEndMin < 0 || dayStartMin >= dayEndMin) return
        if (currentTimeMin !in dayStartMin..dayEndMin) return

        val y = interpolateY(currentTimeMin) ?: return

        val circleRadius = 5f * density
        val labelMargin  = 4f * density
        val labelPadH    = 4f * density
        val labelPadV    = 2f * density
        val lineStartX   = circleRadius * 2 + labelMargin + 30f * density

        canvas.drawLine(lineStartX, y, width.toFloat(), y, linePaint)
        canvas.drawCircle(lineStartX, y, circleRadius, circlePaint)

        val timeText = "%02d:%02d".format(currentTimeMin / 60, currentTimeMin % 60)
        val textW  = labelTextPaint.measureText(timeText)
        val badgeW = textW + labelPadH * 2
        val badgeH = labelTextPaint.textSize + labelPadV * 2
        val badgeLeft = labelMargin
        val badgeTop  = y - badgeH / 2f
        canvas.drawRoundRect(
            RectF(badgeLeft, badgeTop, badgeLeft + badgeW, badgeTop + badgeH),
            4f * density, 4f * density, labelBgPaint
        )
        canvas.drawText(timeText, badgeLeft + labelPadH,
            badgeTop + badgeH / 2f - (labelTextPaint.descent() + labelTextPaint.ascent()) / 2f,
            labelTextPaint)
    }

    /**
     * Derives the Y pixel position for [timeMin] by interpolating over the
     * actual rendered bounds of the visible lesson cards.
     *
     * Within a card: linear interpolation between card top and bottom.
     * Between cards: linear interpolation in the gap.
     * Before first / after last visible card: clamp to card edge.
     */
    private fun interpolateY(timeMin: Int): Float? {
        val rv      = recyclerView ?: return null
        val adapter = rv.adapter as? LessonAdapter ?: return null
        val groups  = adapter.currentList
        if (groups.isEmpty()) return null

        val rvLoc = IntArray(2).also { rv.getLocationOnScreen(it) }
        val myLoc = IntArray(2).also { getLocationOnScreen(it) }
        val offsetY = rvLoc[1] - myLoc[1]

        val bounds = Rect()
        val items = (0 until rv.childCount).mapNotNull { i ->
            val child = rv.getChildAt(i) ?: return@mapNotNull null
            val pos   = rv.getChildAdapterPosition(child)
            if (pos == RecyclerView.NO_POSITION) return@mapNotNull null
            val group = groups.getOrNull(pos) ?: return@mapNotNull null
            rv.getDecoratedBoundsWithMargins(child, bounds)
            val sMin = (group.startTime / 100) * 60 + (group.startTime % 100)
            val eMin = (group.endTime   / 100) * 60 + (group.endTime   % 100)
            ItemBounds(sMin, eMin, bounds.top + offsetY, bounds.bottom + offsetY)
        }.sortedBy { it.startMin }

        if (items.isEmpty()) return null

        // Before first visible card
        if (timeMin <= items.first().startMin) return items.first().topPx.toFloat()
        // After last visible card
        if (timeMin >= items.last().endMin)    return items.last().bottomPx.toFloat()

        // Within a card
        for (item in items) {
            if (timeMin in item.startMin..item.endMin) {
                val fraction = (timeMin - item.startMin).toFloat() /
                               (item.endMin - item.startMin).coerceAtLeast(1).toFloat()
                return item.topPx + fraction * (item.bottomPx - item.topPx)
            }
        }
        // Between two adjacent cards (gap / break)
        for (i in 0 until items.size - 1) {
            val cur  = items[i]
            val next = items[i + 1]
            if (timeMin in cur.endMin..next.startMin) {
                val range = (next.startMin - cur.endMin).coerceAtLeast(1).toFloat()
                val fraction = (timeMin - cur.endMin).toFloat() / range
                return cur.bottomPx + fraction * (next.topPx - cur.bottomPx)
            }
        }
        return null
    }

    private data class ItemBounds(
        val startMin: Int, val endMin: Int,
        val topPx: Int,   val bottomPx: Int
    )

    // Touch events pass through so RecyclerView remains scrollable
    override fun onTouchEvent(event: android.view.MotionEvent) = false

    companion object {
        fun currentTimeMinutes(): Int {
            val cal = Calendar.getInstance()
            return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        }
    }
}
