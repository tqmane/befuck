package dev.tqmane.befuck.ui

import android.content.Context
import android.content.res.Resources
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import androidx.core.widget.TextViewCompat
import dev.tqmane.befuck.R
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Embedded calendar, not a date dialog: selecting a day changes the list below it. */
internal class PostCalendarView(context: Context, private val labels: Resources,
    private val model: SavedCalendarModel, private val onDateSelected: () -> Unit,
    private val today: () -> LocalDate = { LocalDate.now() }) : LinearLayout(context) {
    private val materialContext = ModuleUiContext(context, labels)
    private val locale: Locale get() = labels.configuration.locales[0] ?: Locale.getDefault()
    private var choosingMonth = false
    private var year = model.displayedMonth.year
    private val title = Button(context)
    private val grid = LinearLayout(context).apply { orientation = VERTICAL; tag = "calendar-grid" }
    private val previous: View
    private val next: View
    init {
        orientation = VERTICAL
        tag = "post-calendar"
        background = ExpressiveUi.shape(context, ExpressiveUi.SURFACE, 28f, 0xFF303030.toInt())
        setPadding(dp(8), dp(10), dp(8), dp(12))
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        previous = ExpressiveUi.iconButton(context, TablerIcon.CHEVRON_LEFT, labels.getString(R.string.befuck_calendar_previous_month)) {
            if (choosingMonth) year = (year - 1).coerceAtLeast(1) else model.moveMonth(-1)
            render()
        }.apply { tag = "calendar-previous" }
        next = ExpressiveUi.iconButton(context, TablerIcon.CHEVRON_RIGHT, labels.getString(R.string.befuck_calendar_next_month)) {
            if (choosingMonth) year = (year + 1).coerceAtMost(9999) else model.moveMonth(1)
            render()
        }.apply { tag = "calendar-next" }
        header.addView(previous, LayoutParams(dp(48), dp(48)))
        title.apply {
            tag = "calendar-month-title"; isAllCaps = false; textSize = 19f
            maxLines = 1; setHorizontallyScrolling(false)
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this, 10, 19, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
            setTextColor(Color.WHITE); typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = ExpressiveUi.ripple(context, ExpressiveUi.SURFACE, 20f)
            minWidth = 0; minimumWidth = 0; minHeight = dp(56)
            setPadding(dp(4), dp(8), dp(4), dp(8))
            setOnClickListener { choosingMonth = !choosingMonth; year = model.displayedMonth.year; render() }
        }
        header.addView(title, LayoutParams(0, -2, 1f))
        header.addView(next, LayoutParams(dp(48), dp(48)))
        addView(header)
        addView(grid)
        render()
    }
    fun returnToDays() { choosingMonth = false; render() }
    fun render() {
        grid.removeAllViews()
        val counts = model.counts
        val monthPattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "yMMMM")
        title.text = if (choosingMonth) year.toString() else model.displayedMonth.format(DateTimeFormatter.ofPattern(monthPattern, locale))
        title.contentDescription = labels.getString(if (choosingMonth) R.string.befuck_calendar_return_month else R.string.befuck_calendar_choose_month, title.text)
        previous.contentDescription = labels.getString(if (choosingMonth) R.string.befuck_calendar_previous_year else R.string.befuck_calendar_previous_month)
        next.contentDescription = labels.getString(if (choosingMonth) R.string.befuck_calendar_next_year else R.string.befuck_calendar_next_month)
        previous.isEnabled = if (choosingMonth) year > 1 else model.displayedMonth > YearMonth.of(1, 1)
        next.isEnabled = if (choosingMonth) year < 9999 else model.displayedMonth < YearMonth.of(9999, 12)
        if (choosingMonth) {
            val columns = if (resources.configuration.fontScale >= 1.5f) 2 else 3
            (1..12).chunked(columns).forEach { months ->
                val row = LinearLayout(context)
                months.forEach { number ->
                    val month = YearMonth.of(year, number)
                    val count = counts.entries.filter { YearMonth.from(it.key) == month }.sumOf { it.value }
                    val button = ExpressiveUi.button(context, java.time.Month.of(number).getDisplayName(TextStyle.SHORT_STANDALONE, locale)) {
                        model.showMonth(month); choosingMonth = false; render()
                    }.apply {
                        tag = "calendar-month-$month"
                        isSelected = month == model.displayedMonth
                        background = ExpressiveUi.ripple(context, if (isSelected) Color.WHITE else ExpressiveUi.CONTAINER, 18f)
                        setTextColor(if (isSelected) Color.BLACK else Color.WHITE)
                        contentDescription = labels.getString(R.string.befuck_calendar_day_posts, month.toString(), count)
                        minimumHeight = dp(64); minHeight = dp(64)
                        setPadding(dp(4), dp(10), dp(4), dp(10))
                    }
                    row.addView(button, LayoutParams(0, -2, 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
                }
                grid.addView(row)
            }
            return
        }
        val weekdays = LinearLayout(context).apply { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
        for (weekday in DayOfWeek.values()) weekdays.addView(ExpressiveUi.text(context,
            weekday.getDisplayName(TextStyle.NARROW_STANDALONE, locale), 12f, ExpressiveUi.SECONDARY).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(8))
            }, LayoutParams(0, -2, 1f))
        grid.addView(weekdays)
        SavedCalendarModel.cells(model.displayedMonth).chunked(7).forEach { week ->
            val row = LinearLayout(context)
            week.forEach { day ->
                val cell: View = if (day == null) View(context).apply { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
                else CalendarDayCell(materialContext, day.dayOfMonth.toString(), day == model.selectedDate, day == today(), counts.getOrDefault(day, 0) > 0).apply {
                    tag = "calendar-day-$day"
                    contentDescription = labels.getString(R.string.befuck_calendar_day_posts,
                        day.format(DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.FULL).withLocale(locale)), counts.getOrDefault(day, 0))
                    if (day == today()) contentDescription = "$contentDescription · ${labels.getString(R.string.befuck_calendar_today)}"
                    setOnClickListener {
                        val restoreFocus = hasFocus()
                        model.select(day); render(); onDateSelected()
                        if (restoreFocus) grid.findViewWithTag<View>("calendar-day-$day")?.requestFocus()
                    }
                }
                row.addView(cell, LayoutParams(0, dp(if (resources.configuration.fontScale >= 1.5f) 64 else 52), 1f))
            }
            grid.addView(row)
        }
    }
    private fun dp(value: Int) = ExpressiveUi.dp(context, value)
}
