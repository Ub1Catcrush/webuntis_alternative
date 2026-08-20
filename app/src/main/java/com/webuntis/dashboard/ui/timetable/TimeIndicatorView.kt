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
     * Derives the Y pixel position for [timeMin] by interpolating over the rendered lesson
     * cards.
     *
     * Real, measured bounds are used wherever a card is currently attached/laid out — this is
     * what keeps the line accurate even when a card grows beyond its minimum height (long
     * subjects, many teachers, notes, etc.). For any group that ISN'T currently rendered (e.g.
     * scrolled just outside RecyclerView's small attached/cached window), its bounds are
     * estimated using the same proportional per-minute scale the adapter uses to size cards
     * (see GroupViewHolder.bind()), chained from the nearest rendered neighbour. Without this,
     * an off-screen lesson would be silently skipped and the line would interpolate straight
     * across it as if it were empty space between two unrelated cards — making it appear ahead
     * of where the still-current lesson actually ends.
     */
    private fun interpolateY(timeMin: Int): Float? {
        val rv      = recyclerView ?: return null
        val adapter = rv.adapter as? LessonAdapter ?: return null
        val groups  = adapter.currentList
        if (groups.isEmpty()) return null

        val rvLoc = IntArray(2).also { rv.getLocationOnScreen(it) }
        val myLoc = IntArray(2).also { getLocationOnScreen(it) }
        val offsetY = rvLoc[1] - myLoc[1]

        // Real, measured top/bottom for whatever is currently attached & laid out, by position.
        val bounds = Rect()
        val realByPos = HashMap<Int, FloatArray>()
        for (i in 0 until rv.childCount) {
            val child = rv.getChildAt(i) ?: continue
            val pos   = rv.getChildAdapterPosition(child)
            if (pos == RecyclerView.NO_POSITION) continue
            rv.getDecoratedBoundsWithMargins(child, bounds)
            realByPos[pos] = floatArrayOf((bounds.top + offsetY).toFloat(), (bounds.bottom + offsetY).toFloat())
        }
        if (realByPos.isEmpty()) return null

        // Same proportional scale used to size cards — only used to ESTIMATE the position of
        // groups that aren't currently rendered.
        val pxPerMin = 48f * density / 45f
        fun minutesOf(group: LessonGroup): Pair<Int, Int> {
            val s = (group.startTime / 100) * 60 + (group.startTime % 100)
            val e = (group.endTime   / 100) * 60 + (group.endTime   % 100)
            return s to e
        }

        val firstRenderedPos = groups.indices.firstOrNull { realByPos.containsKey(it) } ?: return null

        val items = ArrayList<ItemBounds>(groups.size)
        var lastBottom: Float
        run {
            val (sMin, eMin) = minutesOf(groups[firstRenderedPos])
            val (top, bottom) = realByPos.getValue(firstRenderedPos)
            items.add(ItemBounds(sMin, eMin, top.toInt(), bottom.toInt()))
            lastBottom = bottom
        }
        // Forward: fill in anything after the first rendered position (real bounds where
        // available, estimated where not — chained from the previous item's bottom).
        for (pos in (firstRenderedPos + 1) until groups.size) {
            val (sMin, eMin) = minutesOf(groups[pos])
            val real = realByPos[pos]
            val top = real?.get(0) ?: lastBottom
            val bottom = real?.get(1) ?: (top + (eMin - sMin).coerceAtLeast(1) * pxPerMin)
            items.add(ItemBounds(sMin, eMin, top.toInt(), bottom.toInt()))
            lastBottom = bottom
        }
        // Backward: fill in anything before the first rendered position, chained from its top.
        var firstTop = items.first().topPx.toFloat()
        for (pos in (firstRenderedPos - 1) downTo 0) {
            val (sMin, eMin) = minutesOf(groups[pos])
            val bottom = firstTop
            val top = bottom - (eMin - sMin).coerceAtLeast(1) * pxPerMin
            items.add(0, ItemBounds(sMin, eMin, top.toInt(), bottom.toInt()))
            firstTop = top
        }

        if (items.isEmpty()) return null

        // Before first / after last item overall
        if (timeMin <= items.first().startMin) return items.first().topPx.toFloat()
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
