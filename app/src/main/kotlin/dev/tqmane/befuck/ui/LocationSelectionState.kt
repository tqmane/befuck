package dev.tqmane.befuck.ui

import dev.tqmane.befuck.posting.LocationData

/** Main-thread transaction gate: edits and newer requests invalidate every older proposal. */
internal class LocationSelectionState(initial: LocationData? = null) {
    var selection: LocationData? = initial?.takeIf(::isValid)
        private set
    private var generation = 0L
    private var closed = false
    fun begin(): Long = ++generation
    fun accepts(ticket: Long): Boolean = !closed && ticket == generation
    fun invalidate() { ++generation }
    fun select(location: LocationData?): Boolean {
        if (closed || location != null && !isValid(location)) return false
        invalidate(); selection = location; return true
    }
    fun apply(ticket: Long, location: LocationData): Boolean {
        if (!accepts(ticket)) return false
        return select(location)
    }
    fun close() { closed = true; invalidate() }
    companion object {
        fun isValid(location: LocationData) = location.latitude.isFinite() && location.longitude.isFinite() && location.latitude in -90.0..90.0 && location.longitude in -180.0..180.0
    }
}
