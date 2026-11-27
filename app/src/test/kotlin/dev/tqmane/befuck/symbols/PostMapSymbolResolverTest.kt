package dev.tqmane.befuck.symbols

import android.graphics.Bitmap
import dev.tqmane.befuck.posting.LocationData
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PostMapSymbolResolverTest {
    // Deliberately reverse constructor/field ordering. Labels, not positions or
    // obfuscated names, are the contract the resolver must prove.
    class Coordinate(latitude: Double, longitude: Double) {
        @JvmField val zz = longitude
        @JvmField val aa = latitude
        override fun toString() = "lat/lng: ($aa,$zz)"
    }
    class Ready(first: Coordinate, second: Coordinate, approximate: Boolean) {
        @JvmField val q = second
        @JvmField val a = first
        @JvmField val renamed = approximate
        override fun toString() = "Ready(markerLocation=$q, userLocation=$a, showCircle=$renamed)"
    }
    class Photos(first: Bitmap, second: Bitmap) {
        @JvmField val x = second
        @JvmField val b = first
        override fun toString() = "BitmapReady(lowBitmap=$x, highBitmap=$b)"
    }
    class WrongReady(first: Coordinate, second: Coordinate, approximate: Boolean) {
        @JvmField val q = first
        @JvmField val a = second
        @JvmField val renamed = approximate
        override fun toString() = "Ready(markerLocation=$q, userLocation=$q, showCircle=$renamed)"
    }
    class Cell(private var value: Any?) {
        var reads = 0
        fun getValue(): Any? { reads++; return value }
        fun setValue(value: Any?) { this.value = value }
    }
    class Navigation { fun close() {} }
    class Owner {
        @JvmField val reorderedPhoto = Cell(null)
        @JvmField val reorderedPermission = Cell(false)
        @JvmField val reorderedCurrent = Cell(null)
        @JvmField val reorderedReady = Cell(null)
        @JvmField val selectedPhoto = Cell(false)
        @JvmField val nav = Navigation()
        fun invoke(a: Any?, b: Any?, c: Any?): Any = Unit
    }
    private fun probe() = PostMapSymbolResolver.probeDisplayModels(Ready::class.java, Photos::class.java, Coordinate::class.java)
    private fun binding(): PostMapSymbols {
        val fields = probe().toMutableMap()
        fields["toggle"] = Owner::class.java.getField("selectedPhoto")
        fields["navigation"] = Owner::class.java.getField("nav")
        val render = Owner::class.java.getMethod("invoke", Any::class.java, Any::class.java, Any::class.java)
        return PostMapSymbols(render, render, "unused", Unit, Runnable::class.java,
            Cell::class.java.getMethod("getValue"), Cell::class.java.getMethod("setValue", Any::class.java),
            Navigation::class.java.getMethod("close"), fields, {},
            Owner::class.java.fields.filter { it.type == Cell::class.java }, { "synthetic-post-id" }, false)
    }

    @Test fun labelsProveReorderedCoordinatesAndPhotos() {
        val fields = probe()
        assertEquals("aa", fields.getValue("latitude").name)
        assertEquals("zz", fields.getValue("longitude").name)
        assertEquals("q", fields.getValue("post").name)
        assertEquals("a", fields.getValue("user").name)
        assertEquals("x", fields.getValue("low").name)
        assertEquals("b", fields.getValue("high").name)
    }

    @Test fun duplicatedRolesFailClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            PostMapSymbolResolver.probeDisplayModels(WrongReady::class.java, Photos::class.java, Coordinate::class.java)
        }
    }

    @Test fun loadingStatesSubscribeThenBecomeIdentifiableWithoutFieldOrder() {
        val symbols = binding()
        val owner = Owner()
        val loading = symbols.snapshot(owner)
        assertNull(loading.post)
        assertNull(loading.user)
        assertNull(loading.bitmap)
        assertFalse(loading.canRequestLocation)
        assertEquals("synthetic-post-id", loading.postId)
        for (field in Owner::class.java.fields.filter { it.type == Cell::class.java }) {
            assertEquals(1, (field.get(owner) as Cell).reads)
        }
        owner.reorderedReady.setValue(Ready(Coordinate(1.0, 2.0), Coordinate(12.0, 24.0), true))
        owner.reorderedPermission.setValue(true)
        owner.reorderedCurrent.setValue(Coordinate(5.0, 6.0))
        val snapshot = symbols.snapshot(owner)
        assertEquals(LocationData(12.0, 24.0), snapshot.post)
        assertEquals(LocationData(5.0, 6.0), snapshot.user)
        assertTrue(snapshot.approximate)
        assertTrue(snapshot.locationAllowed)
    }

    @Test fun ambiguousReadyAndPermissionValuesDoNotPickTheFirstField() {
        val symbols = binding()
        val owner = Owner()
        val ready = Ready(Coordinate(1.0, 2.0), Coordinate(12.0, 24.0), true)
        owner.reorderedReady.setValue(ready)
        owner.reorderedPhoto.setValue(ready)
        assertNull(symbols.snapshot(owner).post)
        owner.reorderedPhoto.setValue(true)
        owner.reorderedPermission.setValue(true)
        assertFalse(symbols.snapshot(owner).locationAllowed)
    }

    @Test fun resolveFillMaxSizeModifierSelectsFillElementRegardlessOfSizeKtMethodName() {
        val compiler = javax.tools.ToolProvider.getSystemJavaCompiler() ?: return
        val dir = kotlin.io.path.createTempDirectory("sizekt-fixture").toFile()
        try {
            val modifierSrc = dir.resolve("androidx/compose/ui/Modifier.java").apply { parentFile?.mkdirs() }
            modifierSrc.writeText(
                """
                package androidx.compose.ui;
                public interface Modifier {
                    final class Companion implements Modifier {
                        public static final Companion a = new Companion();
                    }
                }
                """.trimIndent()
            )
            val fillSrc = dir.resolve("androidx/compose/foundation/layout/FillElement.java").apply { parentFile?.mkdirs() }
            fillSrc.writeText(
                """
                package androidx.compose.foundation.layout;
                import androidx.compose.ui.Modifier;
                public final class FillElement implements Modifier {
                    public final String kind;
                    public FillElement(String kind) { this.kind = kind; }
                    @Override public boolean equals(Object o) { return o instanceof FillElement && kind.equals(((FillElement) o).kind); }
                    @Override public int hashCode() { return kind.hashCode(); }
                    @Override public String toString() { return "FillElement(" + kind + ")"; }
                }
                """.trimIndent()
            )
            val sizeKtSrc = dir.resolve("androidx/compose/foundation/layout/SizeKt.java").apply { parentFile?.mkdirs() }
            sizeKtSrc.writeText(
                """
                package androidx.compose.foundation.layout;
                import androidx.compose.ui.Modifier;
                public final class SizeKt {
                    // Mimics BeReal 3.100.0/3.101.1 where SizeKt.f is fillMaxWidth and e is fillMaxSize.
                    public static Modifier f(Modifier m, float f) { return new FillElement("fillMaxWidth"); }
                    public static Modifier b(Modifier m, float f) { return new FillElement("fillMaxHeight"); }
                    public static Modifier e(Modifier m, float f) { return new FillElement("fillMaxSize"); }
                }
                """.trimIndent()
            )
            val rc = compiler.run(null, null, null, "-d", dir.absolutePath,
                modifierSrc.absolutePath, fillSrc.absolutePath, sizeKtSrc.absolutePath)
            assertEquals(0, rc)
            val loader = java.net.URLClassLoader(arrayOf(dir.toURI().toURL()), null)
            val resolved = PostMapSymbolResolver.resolveFillMaxSizeModifier(loader)
            assertEquals("FillElement(fillMaxSize)", resolved.toString())
        } finally {
            dir.deleteRecursively()
        }
    }
}
