package dev.tqmane.befuck.ui

import dev.tqmane.befuck.download.FeedPostMedia
import dev.tqmane.befuck.download.PostMediaMetadata
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** Calendar dates are device-local posting dates, not UTC dates or cache capture timestamps. */
internal class SavedCalendarModel(today: LocalDate = LocalDate.now()) {
    enum class Mode { CALENDAR, ALL }
    data class Entry(val post: FeedPostMedia, val day: LocalDate?, val timestamp: Long?)
    var selectedDate: LocalDate = today
        private set
    var displayedMonth: YearMonth = YearMonth.from(today)
        private set
    var mode = Mode.CALENDAR
        private set
    var entries: List<Entry> = emptyList()
        private set
    var query: String = ""
    val matching: List<Entry> get() {
        val needle = query.trim()
        return entries.filter { needle.isEmpty() || it.post.username.orEmpty().contains(needle, ignoreCase = true) }
    }
    val counts: Map<LocalDate, Int> get() = matching.mapNotNull { it.day }.groupingBy { it }.eachCount()
    val visible: List<FeedPostMedia> get() = matching.filter { mode == Mode.ALL || it.day == selectedDate }.map { it.post }

    fun replace(posts: List<FeedPostMedia>, zone: ZoneId) {
        entries = posts.distinctBy { it.postId }.map { post ->
            val millis = PostMediaMetadata.timestamp(post)
            val date = millis?.let { runCatching { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }.getOrNull() }
            Entry(post, date, millis)
        }.sortedWith(compareByDescending<Entry> { it.timestamp ?: Long.MIN_VALUE }.thenBy { it.post.postId })
    }
    fun select(date: LocalDate) {
        require(date.year in 1..9999)
        selectedDate = date
        displayedMonth = YearMonth.from(date)
        mode = Mode.CALENDAR
    }
    fun showAll() { mode = Mode.ALL }
    fun showCalendar() { mode = Mode.CALENDAR }
    fun showMonth(month: YearMonth) {
        require(month.year in 1..9999)
        displayedMonth = month
    }
    fun moveMonth(delta: Long) {
        runCatching { displayedMonth.plusMonths(delta) }.getOrNull()?.takeIf { it.year in 1..9999 }?.let(::showMonth)
    }
    companion object {
        /** Full weeks with non-interactive leading/trailing blanks, including six-week months. */
        fun cells(month: YearMonth, firstDay: DayOfWeek = DayOfWeek.MONDAY): List<LocalDate?> {
            val lead = Math.floorMod(month.atDay(1).dayOfWeek.value - firstDay.value, 7)
            val length = ((lead + month.lengthOfMonth() + 6) / 7) * 7
            return List(length) { index -> (index - lead + 1).takeIf { it in 1..month.lengthOfMonth() }?.let(month::atDay) }
        }
    }
}
