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
}
