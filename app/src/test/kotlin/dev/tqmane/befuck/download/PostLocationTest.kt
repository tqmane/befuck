package dev.tqmane.befuck.download

import org.junit.Assert.*
import org.junit.Test

class PostLocationTest {
    @Test fun absentOrInvalidComponentsNeverBecomeAnOriginCoordinate() {
        assertNull(PostLocation.create(null, 0.0, false))
        assertNull(PostLocation.create(0.0, null, false))
        for ((lat, lon) in listOf(Double.NaN to 0.0, 0.0 to Double.POSITIVE_INFINITY,
            90.0001 to 0.0, -90.0001 to 0.0, 0.0 to 180.0001, 0.0 to -180.0001)) {
            assertNull(PostLocation.create(lat, lon, false))
            assertThrows(IllegalArgumentException::class.java) { PostLocation(lat, lon, false) }
        }
    }

    @Test fun explicitOriginAndBoundaryCoordinatesAreValid() {
        for ((lat, lon) in listOf(0.0 to 0.0, 90.0 to 180.0, -90.0 to -180.0, -33.8 to 151.2)) {
            assertEquals(PostLocation(lat, lon, false), PostLocation.create(lat, lon, false))
        }
    }

    @Test fun coordinateAndPrivacyFlagRoundTripAsOneCanonicalValue() {
        for (location in listOf(PostLocation(35.68, 139.76, false), PostLocation(-33.8, 151.2, true))) {
            assertEquals(location, PostLocation.decode(location.encode()))
        }
        for (value in listOf("35.0", "35.0,139.0", "35.0,139.0,unknown", "35.0,139.0,1,extra",
            "NaN,139.0,0", "35.0,181.0,0", "35,139,0")) {
            assertNull(PostLocation.decode(value))
        }
    }
}
