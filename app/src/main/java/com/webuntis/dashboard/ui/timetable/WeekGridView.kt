package com.webuntis.dashboard.ui.timetable

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import com.webuntis.dashboard.R
import com.webuntis.dashboard.databinding.ItemWeekLessonBinding
import com.webuntis.dashboard.databinding.ViewWeekGridBinding
import com.webuntis.dashboard.model.Lesson
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * A real time-grid week view: every day is a column, every row corresponds to an actual
 * clock-time offset shared by all columns, so lessons at the same time of day line up across
 * days, and lessons automatically get a taller tile when they span more time (e.g. Doppelstunden)
 * because tile height is derived directly from (endTime - startTime), not from a fixed row count.
 *
 * Day columns always stretch to fill the full available width (split evenly across the visible
 * days). Each tile shows two lines — the short subject name (large) and the long subject name
 * (small) — both sized once per grid so every tile uses the exact same, fully-fitting font size.
 */
class WeekGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val binding: ViewWeekGridBinding

    var onLessonClick: ((Lesson) -> Unit)? = null

    init {
        orientation = VERTICAL
        binding = ViewWeekGridBinding.inflate(LayoutInflater.from(context), this)
    }

    private val density get() = resources.displayMetrics.density

    companion object {
        // Vertical scale: same ratio as the day view (48dp per 45 minutes) so both views feel consistent.
        private const val DP_PER_MIN = 48f / 45f
        private const val GUTTER_WIDTH_DP = 42f
        private const val HEADER_HEIGHT_DP = 32f
        private const val MIN_TILE_HEIGHT_DP = 36f
        private const val MIN_COL_WIDTH_DP = 56f
        private const val TILE_GAP_DP = 2f
        private const val TILE_H_PADDING_DP = 8f // horizontal padding+margins to keep clear of, per tile
        private const val DEFAULT_START_MIN = 8 * 60   // 08:00 fallback when no lessons at all
        private const val DEFAULT_END_MIN = 16 * 60    // 16:00 fallback

        private const val SHORT_MAX_SP = 20f
        private const val SHORT_MIN_SP = 9f
        private const val LONG_MAX_SP = 12f
        private const val LONG_MIN_SP = 7f
    }

    private fun minutesOf(hhmm: Int): Int = (hhmm / 100) * 60 + (hhmm % 100)

    /** Cached args so a re-layout (e.g. orientation change) can rebuild with the same data. */
    private var pendingDays: List<SchoolDay> = emptyList()
    private var pendingShowLongSubjects = false
    private var pendingShowShortSubjectInParens = false

    fun submit(
        days: List<SchoolDay>,
        showLongSubjects: Boolean,
        showShortSubjectInParens: Boolean
    ) {
        pendingDays = days
        pendingShowLongSubjects = showLongSubjects
        pendingShowShortSubjectInParens = showShortSubjectInParens
        // Defer until we actually know our width, so day columns can fill the full screen width.
        doOnLayout {
            buildGrid(pendingDays, pendingShowLongSubjects, pendingShowShortSubjectInParens)
        }
    }

    private fun buildGrid(
        days: List<SchoolDay>,
        showLongSubjects: Boolean,
        showShortSubjectInParens: Boolean
    ) {
        binding.weekDayColumns.removeAllViews()
        binding.weekGutterLabels.removeAllViews()
        if (days.isEmpty()) return

        val allLessons = days.flatMap { it.lessons }
        val startMin = (allLessons.minOfOrNull { minutesOf(it.startTime) } ?: DEFAULT_START_MIN)
            .let { (it / 60) * 60 } // floor to the hour
        val endMinRaw = allLessons.maxOfOrNull { minutesOf(it.endTime) } ?: DEFAULT_END_MIN
        val endMin = (((endMinRaw + 59) / 60) * 60).coerceAtLeast(startMin + 60) // ceil to the hour

        val pxPerMin = DP_PER_MIN * density
        val gridHeightPx = ((endMin - startMin) * pxPerMin).roundToInt()
        val headerHeightPx = (HEADER_HEIGHT_DP * density).roundToInt()
        val minTileHeightPx = (MIN_TILE_HEIGHT_DP * density).roundToInt()
        val tileGapPx = (TILE_GAP_DP * density).roundToInt()

        // Use the full available width (this view's width minus the fixed time gutter),
        // split evenly across every visible day, so the grid always fills the screen.
        val gutterWidthPx = (GUTTER_WIDTH_DP * density).roundToInt()
        val availableWidthPx = (width - gutterWidthPx).coerceAtLeast(1)
        val minColWidthPx = (MIN_COL_WIDTH_DP * density).roundToInt()
        val dayColWidthPx = (availableWidthPx / days.size).coerceAtLeast(minColWidthPx)

        buildGutter(startMin, endMin, gridHeightPx, pxPerMin)

        // ── Compute ONE uniform font size for all short names and ONE for all long names,
        // so every tile across the whole week renders text at exactly the same size. ──
        val tilePaddingPx = (TILE_H_PADDING_DP * density).roundToInt()
        val textAvailableWidthPx = (dayColWidthPx - tilePaddingPx).coerceAtLeast(1).toFloat()
        val shortNames = allLessons.map { it.subjectName }.distinct()
        val longNames = allLessons.map { it.subjectLongName }.distinct()
        val shortSizeSp = computeUniformTextSizeSp(shortNames, textAvailableWidthPx, SHORT_MAX_SP, SHORT_MIN_SP, bold = true)
        val longSizeSp = computeUniformTextSizeSp(longNames, textAvailableWidthPx, LONG_MAX_SP, LONG_MIN_SP, bold = false)

        val today = LocalDate.now()
        val nowMin = TimeIndicatorView.currentTimeMinutes()

        for (day in days) {
            val isToday = day.date == today
            val column = buildDayColumn(
                day = day,
                isToday = isToday,
                // Only today's column should dim lessons based on the current clock time;
                // other days must rely purely on isPastDay (comparing dates), not time-of-day.
                nowMin = if (isToday) nowMin else -1,
                startMin = startMin,
                gridHeightPx = gridHeightPx,
                headerHeightPx = headerHeightPx,
                dayColWidthPx = dayColWidthPx,
                minTileHeightPx = minTileHeightPx,
                tileGapPx = tileGapPx,
                pxPerMin = pxPerMin,
                showLongSubjects = showLongSubjects,
                showShortSubjectInParens = showShortSubjectInParens,
                shortSizeSp = shortSizeSp,
                longSizeSp = longSizeSp
            )
            binding.weekDayColumns.addView(
                column,
                LinearLayout.LayoutParams(dayColWidthPx, LinearLayout.LayoutParams.WRAP_CONTENT)
            )
        }
    }

    /**
     * Finds the largest font size (within [minSp, maxSp], stepping down by 0.5sp) at which every
     * string in [texts] fits on a single line within [availableWidthPx]. Falls back to [minSp]
     * if even the smallest size doesn't fit everything.
     */
    private fun computeUniformTextSizeSp(
        texts: List<String>,
        availableWidthPx: Float,
        maxSp: Float,
        minSp: Float,
        bold: Boolean
    ): Float {
        if (texts.isEmpty()) return maxSp
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        var size = maxSp
        while (size > minSp) {
            paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, size, resources.displayMetrics)
            val fitsAll = texts.all { paint.measureText(it) <= availableWidthPx }
            if (fitsAll) return size
            size -= 0.5f
        }
        return minSp
    }

    private fun buildGutter(startMin: Int, endMin: Int, gridHeightPx: Int, pxPerMin: Float) {
        binding.weekGutterLabels.layoutParams = binding.weekGutterLabels.layoutParams.apply {
            height = gridHeightPx
        }
        var h = (startMin / 60) * 60
        while (h <= endMin) {
            if (h >= startMin) {
                val top = ((h - startMin) * pxPerMin).roundToInt()
                val label = TextView(context).apply {
                    text = String.format("%02d:00", (h / 60) % 24)
                    textSize = 10f
                    setTextColor(ContextCompat.getColor(context, R.color.on_surface_variant))
                }
                val lp = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
                )
                lp.topMargin = (top - 7 * resources.displayMetrics.density).roundToInt().coerceAtLeast(0)
                lp.gravity = Gravity.TOP or Gravity.END
                lp.marginEnd = (4 * density).roundToInt()
                binding.weekGutterLabels.addView(label, lp)
            }
            h += 60
        }
    }

    private fun buildDayColumn(
        day: SchoolDay,
        isToday: Boolean,
        nowMin: Int,
        startMin: Int,
        gridHeightPx: Int,
        headerHeightPx: Int,
        dayColWidthPx: Int,
        minTileHeightPx: Int,
        tileGapPx: Int,
        pxPerMin: Float,
        showLongSubjects: Boolean,
        showShortSubjectInParens: Boolean,
        shortSizeSp: Float,
        longSizeSp: Float
    ): View {
        val columnLayout = LinearLayout(context).apply { orientation = VERTICAL }

        val header = TextView(context).apply {
            text = day.tabLabel
            gravity = Gravity.CENTER
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(
                ContextCompat.getColor(
                    context,
                    if (isToday) R.color.today_tab else R.color.on_surface_variant
                )
            )
            setBackgroundResource(R.drawable.bg_compact_header)
        }
        columnLayout.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, headerHeightPx))

        val lessonsContainer = FrameLayout(context)
        columnLayout.addView(
            lessonsContainer,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, gridHeightPx).apply {
                topMargin = (2 * density).roundToInt()
            }
        )

        val isPastDay = day.date.isBefore(LocalDate.now())

        for (group in day.groupedLessons) {
            val groupStartMin = minutesOf(group.startTime)
            val groupEndMin = minutesOf(group.endTime)
            val top = ((groupStartMin - startMin) * pxPerMin).roundToInt()
            val height = (((groupEndMin - groupStartMin) * pxPerMin).roundToInt())
                .coerceAtLeast(minTileHeightPx)
            val isGroupPast = isPastDay || (nowMin >= 0 && nowMin > groupEndMin)

            val n = group.lessons.size.coerceAtLeast(1)
            val cellWidth = ((dayColWidthPx - tileGapPx * (n - 1)) / n).coerceAtLeast(1)

            group.lessons.forEachIndexed { index, lesson ->
                val tileBinding = ItemWeekLessonBinding.inflate(LayoutInflater.from(context), lessonsContainer, false)
                bindTile(tileBinding, lesson, isGroupPast, shortSizeSp, longSizeSp)
                tileBinding.root.setOnClickListener { onLessonClick?.invoke(lesson) }

                val lp = FrameLayout.LayoutParams(cellWidth - tileGapPx, height - tileGapPx)
                lp.topMargin = top
                lp.leftMargin = index * (cellWidth + tileGapPx)
                lessonsContainer.addView(tileBinding.root, lp)
            }
        }

        return columnLayout
    }

    private fun bindTile(
        b: ItemWeekLessonBinding,
        lesson: Lesson,
        isPast: Boolean,
        shortSizeSp: Float,
        longSizeSp: Float
    ) {
        val ctx = b.root.context
        val normalTextColor = com.google.android.material.color.MaterialColors.getColor(
            b.root, com.google.android.material.R.attr.colorOnSurface
        )
        val mutedTextColor = com.google.android.material.color.MaterialColors.getColor(
            b.root, com.google.android.material.R.attr.colorOnSurfaceVariant
        )

        b.textSubjectShort.text = lesson.subjectName
        b.textSubjectLong.text = lesson.subjectLongName
        b.textSubjectShort.textSize = shortSizeSp
        b.textSubjectLong.textSize = longSizeSp

        b.textSubjectShort.paintFlags = b.textSubjectShort.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
        b.textSubjectLong.paintFlags = b.textSubjectLong.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
        b.textSubjectShort.setTextColor(normalTextColor)
        b.textSubjectLong.setTextColor(mutedTextColor)

        b.root.alpha = 1f
        b.root.strokeColor = com.google.android.material.color.MaterialColors.getColor(
            b.root, com.google.android.material.R.attr.colorOutlineVariant
        )
        b.root.strokeWidth = (1 * density).roundToInt()
        b.root.setCardBackgroundColor(ContextCompat.getColor(ctx, android.R.color.transparent))

        when {
            lesson.isCancelled -> {
                b.root.setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.red_container))
                b.textSubjectShort.setTextColor(ContextCompat.getColor(ctx, R.color.red))
                b.textSubjectLong.setTextColor(ContextCompat.getColor(ctx, R.color.red))
                b.textSubjectShort.paintFlags = b.textSubjectShort.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                b.textSubjectLong.paintFlags = b.textSubjectLong.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            }
            lesson.isSubstitution -> {
                b.root.setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.yellow_container))
            }
            lesson.isExtra -> {
                b.root.setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.green_container))
            }
        }

        if (lesson.isFromClassPlan) {
            b.root.strokeColor = ContextCompat.getColor(ctx, R.color.primary_variant)
            b.root.strokeWidth = (1.5f * density).roundToInt()
        }

        if (isPast) {
            b.root.alpha = 0.55f
        }
    }
}
