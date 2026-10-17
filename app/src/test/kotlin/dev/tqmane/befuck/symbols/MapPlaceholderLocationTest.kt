package dev.tqmane.befuck.symbols

import dev.tqmane.befuck.download.PostLocation
import org.junit.Assert.*
import org.junit.Test

class MapPlaceholderLocationTest {
    private class Placeholder(val postId: String, val isFriend: Boolean, val latitude: Double, val longitude: Double)
    private fun field(name: String) = Placeholder::class.java.getDeclaredField(name).apply { isAccessible = true }
    private val symbols = MapPlaceholderLocationSymbols(Placeholder::class.java.declaredConstructors.toList(),
        field("postId"), field("isFriend"), field("latitude"), field("longitude"))

    @Test fun exactCaptureRequiresTheHostFriendFlagAndKeepsTheSamePostIdentity() {
        assertEquals("post" to PostLocation(35.0, 139.0, false),
            symbols.snapshot(Placeholder("post", true, 35.0, 139.0)) { it == "post" })
        assertNull(symbols.snapshot(Placeholder("post", false, 35.0, 139.0)) { true })
        assertNull(symbols.snapshot(Placeholder("post", true, 35.0, 139.0)) { false })
        assertNull(symbols.snapshot(Any()) { true })
    }

    @Test fun invalidCoordinatesAndMissingIdentityNeverProduceLocations() {
        assertNull(symbols.snapshot(Placeholder("", true, 0.0, 0.0)) { true })
        assertNull(symbols.snapshot(Placeholder("post", true, Double.NaN, 0.0)) { true })
        assertNull(symbols.snapshot(Placeholder("post", true, 0.0, 181.0)) { true })
        assertNull(symbols.snapshot(Placeholder("origin", true, 0.0, 0.0)) { true })
    }
}
