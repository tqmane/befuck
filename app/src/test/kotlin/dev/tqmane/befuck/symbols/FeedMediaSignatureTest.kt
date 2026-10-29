package dev.tqmane.befuck.symbols

import org.junit.Assert.*
import org.junit.Test

class FeedMediaSignatureTest {
    @Test fun acceptsPostIdBeforeComposerWithoutAcceptingArbitraryExtraArguments() {
        val legacy = mutableListOf("androidx.compose.ui.Modifier", "dual", "boolean")
        repeat(13) { legacy += "state-or-callback" }
        legacy += listOf("androidx.compose.runtime.Composer", "int")
        assertTrue(FeedMediaSymbolResolver.isBlurredMediaParameters(legacy, "dual"))
        val current = legacy.toMutableList().apply { add(16, "java.lang.String") }
        assertTrue(FeedMediaSymbolResolver.isBlurredMediaParameters(current, "dual"))
        assertFalse(FeedMediaSymbolResolver.isBlurredMediaParameters(current, "another-model"))
        assertFalse(FeedMediaSymbolResolver.isBlurredMediaParameters(current.toMutableList().apply { this[16] = "boolean" }, "dual"))
        assertFalse(FeedMediaSymbolResolver.isBlurredMediaParameters(current + "int", "dual"))
    }
}
