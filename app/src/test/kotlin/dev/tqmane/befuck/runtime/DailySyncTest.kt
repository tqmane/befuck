package dev.tqmane.befuck.runtime

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [33])
class DailySyncTest {
    private val context get() = RuntimeEnvironment.getApplication() as Context

    @Before fun reset() {
        context.getSharedPreferences("befuck_daily_sync", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun disabledByDefaultAndIntervalCanBeChangedWithoutSchedulingHostWork() {
        assertFalse(DailySync.enabled(context))
        assertEquals(3L, DailySync.intervalHours(context))
        for (hours in DailySync.INTERVALS) {
            assertTrue(DailySync.setIntervalHours(context, hours))
            assertEquals(hours, DailySync.intervalHours(context))
            assertFalse(DailySync.enabled(context))
        }
    }

    @Test fun invalidIntervalsCannotChangeSavedSettings() {
        for (hours in listOf(0L, -1L, 4L, 25L, Long.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { DailySync.setIntervalHours(context, hours) }
        }
        assertEquals(3L, DailySync.intervalHours(context))
        context.getSharedPreferences("befuck_daily_sync", Context.MODE_PRIVATE)
            .edit().putLong("interval_hours", -1L).commit()
        assertEquals(3L, DailySync.intervalHours(context))
    }

    @Test fun missingHostSchedulerFailsClosedAndKeepsInterval() {
        DailySync.setIntervalHours(context, 6L)
        assertFalse(DailySync.configure(context, true))
        assertFalse(DailySync.enabled(context))
        assertEquals(6L, DailySync.intervalHours(context))
        assertEquals(0L, DailySync.lastSuccess(context))
        assertTrue(DailySync.configure(context, false))
    }

    @Test fun unmarkedObjectsCannotRunBackgroundFeedRequests() {
        assertFalse(DailySync.isMarkedWork(Any()))
        assertFalse(DailySync.run(context, null, Any()))
        assertEquals(0L, DailySync.lastSuccess(context))
    }
}
