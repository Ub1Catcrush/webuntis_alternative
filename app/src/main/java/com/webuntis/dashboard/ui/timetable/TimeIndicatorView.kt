package com.webuntis.dashboard.ui.timetable

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.webuntis.dashboard.R
import java.util.Calendar

/**
 * Overlay view that draws a horizontal "current time" line across the timetable.
 *
 * Coordinate mapping mirrors LessonAdapter exactly:
 *   - Each lesson's height = BASE_DP * (durationMin / BASE_MIN)
 *   - Total scroll height  = sum of all lesson heights + padding
 *
 * The view is transparent everywhere except the time line itself,
 * so it can sit on top of the RecyclerView without blocking touch events.
 */
class TimeIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // Same constants as LessonAdapter
    private val BASE_DP  = 48f
    private val BASE_MIN = 45f
    private val PADDING_DP = 12f  // RecyclerView padding from fragment_day.xml

    private val density = resources.displayMetrics.density

    init {
        // Software layer so ARGB alpha composites correctly over RecyclerView
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
    }

    // Set by DayFragment after lessons are bound
    var dayStartMin: Int = -1   // earliest lesson start in minutes from midnight
    var dayEndMin:   Int = -1   // latest lesson end in minutes from midnight
    /** Total scrollable height of the RecyclerView content in px (set after layout) */
    var contentHeightPx: Int = 0

    // Semi-transparent red (alpha ~180/255 ≈ 70%)
    private val lineColor  = ContextCompat.getColor(context, R.color.red).let {
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
        typeface = android.graphics.Typeface.DEFAULT  // not bold
    }

    /** Current time in minutes from midnight, updated externally */
    var currentTimeMin: Int = currentTimeMinutes()
        set(value) { field = value; invalidate() }

    override fun onDraw(canvas: Canvas) {
        if (dayStartMin < 0 || dayEndMin < 0 || dayStartMin >= dayEndMin) return
        if (currentTimeMin < dayStartMin || currentTimeMin > dayEndMin) return

        val paddingPx = PADDING_DP * density
        val y = minuteToY(currentTimeMin, paddingPx)

        val circleRadius = 5f * density
        val labelMargin  = 4f * density
        val labelPadH    = 4f * density
        val labelPadV    = 2f * density

        // Draw the horizontal line
        canvas.drawLine(circleRadius * 2 + labelMargin + 30f * density, y,
            width.toFloat(), y, linePaint)

        // Draw the left dot
        canvas.drawCircle(circleRadius * 2 + labelMargin + 30f * density, y,
            circleRadius, circlePaint)

        // Draw the time label badge
        val timeText = "%02d:%02d".format(currentTimeMin / 60, currentTimeMin % 60)
        val textW = labelTextPaint.measureText(timeText)
        val badgeW = textW + labelPadH * 2
        val badgeH = labelTextPaint.textSize + labelPadV * 2
        val badgeLeft = labelMargin
        val badgeTop  = y - badgeH / 2
        canvas.drawRoundRect(
            RectF(badgeLeft, badgeTop, badgeLeft + badgeW, badgeTop + badgeH),
            4f * density, 4f * density, labelBgPaint
        )
        canvas.drawText(timeText, badgeLeft + labelPadH,
            badgeTop + badgeH / 2 - (labelTextPaint.descent() + labelTextPaint.ascent()) / 2,
            labelTextPaint)
    }

    /**
     * Convert a time in minutes-from-midnight to a Y pixel coordinate,
     * using the same proportional layout as LessonAdapter.
     */
    private fun minuteToY(timeMin: Int, paddingPx: Float): Float {
        val baseHeightPx = BASE_DP * density * ((timeMin - dayStartMin) / BASE_MIN)
        return paddingPx + baseHeightPx
    }

    // Touch events pass through so RecyclerView remains scrollable
    override fun onTouchEvent(event: android.view.MotionEvent) = false

    companion object {
        fun currentTimeMinutes(): Int {
            val cal = Calendar.getInstance()
            return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        }
    }
}
