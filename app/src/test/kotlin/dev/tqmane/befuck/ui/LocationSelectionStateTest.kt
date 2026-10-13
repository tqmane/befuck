package dev.tqmane.befuck.ui

import dev.tqmane.befuck.posting.LocationData
import org.junit.Assert.*
import org.junit.Test

class LocationSelectionStateTest {
    @Test fun newerSearchRejectsEarlierResponse() {
        val state = LocationSelectionState()
        val old = state.begin(); val current = state.begin()
        assertFalse(state.apply(old, LocationData(1.0, 2.0)))
        assertTrue(state.apply(current, LocationData(3.0, 4.0)))
        assertEquals(3.0, state.selection!!.latitude, 0.0)
    }
    @Test fun manualPinInvalidatesGpsAndSearch() {
        val state = LocationSelectionState(); val ticket = state.begin()
        assertTrue(state.select(LocationData(35.0, 139.0)))
        assertFalse(state.apply(ticket, LocationData(0.0, 0.0)))
        assertEquals(35.0, state.selection!!.latitude, 0.0)
    }
    @Test fun clearCannotBeUndoneByLateResult() {
        val state = LocationSelectionState(LocationData(1.0, 1.0)); val ticket = state.begin()
        state.select(null)
        assertFalse(state.apply(ticket, LocationData(2.0, 2.0))); assertNull(state.selection)
    }
    @Test fun editedQueryAndDismissInvalidateCallbacks() {
        val state = LocationSelectionState(); val ticket = state.begin(); state.invalidate()
        assertFalse(state.accepts(ticket)); val next = state.begin(); state.close()
        assertFalse(state.accepts(next)); assertFalse(state.select(LocationData(1.0, 1.0)))
    }
    @Test fun nonFiniteAndOutOfRangeCoordinatesNeverBecomeSelection() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, -91.0, 91.0).forEach {
            assertFalse(LocationSelectionState().select(LocationData(it, 0.0)))
        }
        listOf(Double.NaN, Double.NEGATIVE_INFINITY, -181.0, 181.0).forEach {
            assertFalse(LocationSelectionState().select(LocationData(0.0, it)))
        }
        assertNull(LocationSelectionState(LocationData(Double.NaN, 1.0)).selection)
        assertTrue(LocationSelectionState().select(LocationData(90.0, -180.0)))
    }
}
