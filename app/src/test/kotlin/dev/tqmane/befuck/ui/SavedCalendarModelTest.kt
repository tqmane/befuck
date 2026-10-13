package dev.tqmane.befuck.ui

import dev.tqmane.befuck.download.FeedPostMedia
import org.junit.Assert.*
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

class SavedCalendarModelTest {
    private val today = LocalDate.of(2026, 10, 13)
    private fun post(id: String, at: String?, user: String = "Alice", taken: String? = null) = FeedPostMedia(id,taken,null,null,null,capturedAtMillis=0,username=user,postedAt=at)
    @Test fun leapYearFebruaryAndSixWeekMonthsHaveCompleteWeekRows() {
        assertEquals(29,SavedCalendarModel.cells(YearMonth.of(2024,2)).count { it != null })
        assertEquals(28,SavedCalendarModel.cells(YearMonth.of(2025,2)).count { it != null })
        assertEquals(42,SavedCalendarModel.cells(YearMonth.of(2026,8)).size)
        assertEquals(42,SavedCalendarModel.cells(YearMonth.of(2026,11)).size)
    }
    @Test fun everyMonthFrom2000Through2100HasOneCellPerDateAtCorrectWeekday() {
        for (year in 2000..2100) for (month in 1..12) {
            val value=YearMonth.of(year,month); val cells=SavedCalendarModel.cells(value)
            assertEquals(0,cells.size%7)
            assertEquals((1..value.lengthOfMonth()).map(value::atDay),cells.filterNotNull())
            cells.forEachIndexed { index, day -> if (day != null) assertEquals(day.dayOfWeek.value-1,index%7) }
        }
    }
    @Test fun sundayStartOffsetsDifferWithoutDroppingDates() {
        val month=YearMonth.of(2026,11)
        assertEquals(month.atDay(1),SavedCalendarModel.cells(month,DayOfWeek.SUNDAY).first())
        assertNull(SavedCalendarModel.cells(month).first())
    }
    @Test fun monthBrowsingPreservesSelectedDayAndCrossesYearBoundary() {
        val state=SavedCalendarModel(LocalDate.of(2026,12,31)); state.moveMonth(1)
        assertEquals(YearMonth.of(2027,1),state.displayedMonth)
        assertEquals(LocalDate.of(2026,12,31),state.selectedDate)
        state.select(LocalDate.of(2027,1,4)); assertEquals(LocalDate.of(2027,1,4),state.selectedDate)
        state.moveMonth(-1); assertEquals(YearMonth.of(2026,12),state.displayedMonth)
    }
    @Test fun localMidnightDeterminesPostingDayNotUtcOrCacheTime() {
        val post=post("boundary","2026-10-12T15:30:00Z")
        val state=SavedCalendarModel(today)
        state.replace(listOf(post),ZoneId.of("Asia/Tokyo")); assertEquals(1,state.visible.size)
        state.replace(listOf(post),ZoneId.of("UTC")); assertTrue(state.visible.isEmpty())
        state.select(today.minusDays(1)); assertEquals(1,state.visible.size)
    }
    @Test fun publishedTimestampWinsAndTakenTimestampIsFallback() {
        val state=SavedCalendarModel(today)
        state.replace(listOf(post("published","2026-10-13T01:00:00Z",taken="2026-10-12T01:00:00Z"),post("fallback",null,taken="2026-10-13T02:00:00Z")),ZoneId.of("UTC"))
        assertEquals(listOf("fallback","published"),state.visible.map { it.postId })
    }
    @Test fun unknownDatesAreKeptUnderAllDatesButNeverAssignedToToday() {
        val state=SavedCalendarModel(today)
        state.replace(listOf(post("unknown","invalid"),post("valid","2026-10-13T01:00:00Z")),ZoneId.of("UTC"))
        assertEquals(listOf("valid"),state.visible.map { it.postId })
        assertEquals(mapOf(today to 1),state.counts)
        state.showAll(); assertEquals(listOf("valid","unknown"),state.visible.map { it.postId })
        state.showCalendar(); assertEquals(1,state.visible.size)
    }
    @Test fun usernameSearchFiltersDotsAndPostsUsingSamePredicate() {
        val state=SavedCalendarModel(today)
        state.replace(listOf(post("a","2026-10-13T01:00:00Z"),post("b","2026-10-13T02:00:00Z","Bob")),ZoneId.of("UTC"))
        state.query=" aLiCe "; assertEquals(1,state.counts[today]); assertEquals(listOf("a"),state.visible.map { it.postId })
        state.query="missing"; assertTrue(state.counts.isEmpty()); assertTrue(state.visible.isEmpty())
    }
    @Test fun refreshAndEmptyRefreshPreserveSelectionAndBrowsingMonth() {
        val state=SavedCalendarModel(today)
        state.select(LocalDate.of(2024,2,29)); state.moveMonth(1)
        state.replace(emptyList(),ZoneId.of("UTC"))
        assertEquals(LocalDate.of(2024,2,29),state.selectedDate); assertEquals(YearMonth.of(2024,3),state.displayedMonth)
    }
    @Test fun duplicateSnapshotsDoNotDoubleDayCounts() {
        val state=SavedCalendarModel(today); val post=post("same","2026-10-13T01:00:00Z")
        state.replace(listOf(post,post),ZoneId.of("UTC")); assertEquals(1,state.counts[today])
    }
    @Test fun boundsCannotOverflowCalendarNavigation() {
        val state=SavedCalendarModel(today)
        state.showMonth(YearMonth.of(1,1)); state.moveMonth(-1); assertEquals(YearMonth.of(1,1),state.displayedMonth)
        state.showMonth(YearMonth.of(9999,12)); state.moveMonth(1); assertEquals(YearMonth.of(9999,12),state.displayedMonth)
        state.moveMonth(Long.MAX_VALUE); assertEquals(YearMonth.of(9999,12),state.displayedMonth)
    }
}
