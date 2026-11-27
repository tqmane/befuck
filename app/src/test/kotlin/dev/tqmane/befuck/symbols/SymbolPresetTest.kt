package dev.tqmane.befuck.symbols

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SymbolPresetTest {
    @Test fun bundledPresetUsesCurrentSchemaAndIncludesEveryReaderSlot() {
        val preset = RuntimeEnvironment.getApplication().assets.open("befuck/symbols-3600852.json")
            .bufferedReader().use { JSONObject(it.readText()) }
        assertEquals(SymbolCacheCodec.SCHEMA, preset.getInt("schema"))
        val values = preset.getJSONObject("symbols").getJSONObject("values")
        assertNotNull(KnownMappings.find(values.getString("versionName"), preset.getLong("version_code")))
        val feed = values.getJSONObject("feedMediaSymbols").getJSONObject("values")
        for (reader in listOf("corePostLocationSymbols", "remotePostLocationSymbols", "mapPlaceholderLocationSymbols", "friendGraphSymbols")) {
            assertFalse("Preset disables $reader", feed.isNull(reader))
        }
        for ((key, record) in listOf("rootKeys" to values,
                "feedKeys" to feed)) {
            val field = SymbolCacheCodec::class.java.getDeclaredField(key).apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST") val names = field.get(SymbolCacheCodec) as List<String>
            names.forEach { assertTrue("Preset missing $it", record.has(it)) }
        }
    }

    @Test fun preset31011MatchesCodecSchemaAndGridClickMappings() {
        val preset = RuntimeEnvironment.getApplication().assets.open("befuck/symbols-3606462.json")
            .bufferedReader().use { JSONObject(it.readText()) }
        assertEquals(SymbolCacheCodec.SCHEMA, preset.getInt("schema"))
        assertEquals(3606462L, preset.getLong("version_code"))
        val values = preset.getJSONObject("symbols").getJSONObject("values")
        val mapping = KnownMappings.find(values.getString("versionName"), preset.getLong("version_code"))
        assertNotNull(mapping)
        assertNull(mapping!!.gridCardClickReplacement)
        val feed = values.getJSONObject("feedMediaSymbols").getJSONObject("values")
        for (reader in listOf("corePostLocationSymbols", "mapPlaceholderLocationSymbols", "friendGraphSymbols")) {
            assertFalse("Preset missing $reader", feed.isNull(reader))
        }
        for ((key, record) in listOf("rootKeys" to values, "feedKeys" to feed)) {
            val field = SymbolCacheCodec::class.java.getDeclaredField(key).apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST") val names = field.get(SymbolCacheCodec) as List<String>
            names.forEach { assertTrue("Preset 3.101.1 missing $it", record.has(it)) }
        }
        for ((ver, code, expected) in listOf(
            Triple("3.97.0", 3597523L, 8 to 7),
            Triple("3.97.1", 3599414L, 8 to 7),
            Triple("3.98.0", 3600852L, null),
            Triple("3.99.1", 3603560L, null),
            Triple("3.100.0", 3604882L, null),
            Triple("3.101.1", 3606462L, null),
        )) {
            assertEquals("gridCardClickReplacement mismatch for $ver", expected, KnownMappings.find(ver, code)!!.gridCardClickReplacement)
        }
    }

    @Test fun mappings31011UseDomainBlurStateClassesAndRewriter() {
        val mapping = KnownMappings.find("3.101.1", 3606462L)!!
        assertEquals("a48", mapping.className(HostClass.BlurredPostState))
        assertEquals("b48", mapping.className(HostClass.RegularPostState))
        assertEquals("c48", mapping.className(HostClass.PostViewState))
        assertEquals("d48", mapping.className(HostClass.HomeFeedPost))
        val rewriterMethod = mapping.javaClass.getMethod("feedBlurModelRewriter", ClassLoader::class.java)
        assertEquals(HostMappings::class.java, rewriterMethod.declaringClass)
        val blurSwitchMethod = mapping.javaClass.getMethod("feedPostBlurSwitchInvoke", ClassLoader::class.java)
        assertEquals(KnownMappings31011::class.java, blurSwitchMethod.declaringClass)
    }
}
