package dev.tqmane.befuck.runtime

import android.content.Context
import android.content.pm.ApplicationInfo
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [33])
class RuntimeSymbolCacheTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val settings get() = app.getSharedPreferences("symbol-cache-test", Context.MODE_PRIVATE)

    @Before fun reset() { settings.edit().clear().commit() }

    private fun installed(name: String, apk: File) = ApplicationInfo().apply {
        packageName = name
        sourceDir = apk.absolutePath
    }

    private fun apk(name: String, contents: String) = File(app.cacheDir, name).apply { writeText(contents) }

    private fun identity(host: ApplicationInfo, module: ApplicationInfo, version: String = "3.97.1", code: Long = 3599414L) =
        RuntimeSymbolCache.identity(host, module, version, code)

    @Test fun legacyCacheIsMigratedWithoutRemovingSettingsRepairsOrSavedPosts() {
        settings.edit().putString("symbol_json", "old-null-signatures")
            .putString("reader:old#method", "old-fields").putBoolean("force_scan", true)
            .putBoolean("prefer_preset", false).putBoolean("auto_repair", false)
            .putString("repair:field", "manual-value").putBoolean("manual:field", true)
            .putString("repair_info:field", "manual-provenance").putString("archive_account", "owner").commit()
        app.openOrCreateDatabase("symbol-update-posts-test.db", Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("CREATE TABLE IF NOT EXISTS saved_posts (id TEXT PRIMARY KEY)")
            db.execSQL("INSERT OR REPLACE INTO saved_posts VALUES ('saved-post')")
            assertTrue(RuntimeSymbolCache.invalidateIfChanged(settings, "installed-apps"))
            assertFalse(settings.contains("symbol_json"))
            assertFalse(settings.contains("reader:old#method"))
            assertFalse(settings.contains("force_scan"))
            assertFalse(settings.getBoolean("prefer_preset", true))
            assertFalse(settings.getBoolean("auto_repair", true))
            assertEquals("manual-value", settings.getString("repair:field", null))
            assertTrue(settings.getBoolean("manual:field", false))
            assertEquals("manual-provenance", settings.getString("repair_info:field", null))
            assertEquals("owner", settings.getString("archive_account", null))
            db.rawQuery("SELECT id FROM saved_posts", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("saved-post", cursor.getString(0))
            }
        }
    }

    @Test fun unchangedInstalledAppsReuseCacheAndPreserveExplicitManualScan() {
        val host = installed("com.bereal.ft", apk("unchanged-host.apk", "host"))
        val module = installed("dev.tqmane.befuck", apk("unchanged-module.apk", "module"))
        assertTrue(RuntimeSymbolCache.invalidateIfChanged(settings, identity(host, module)))
        settings.edit().putString("symbol_json", "resolved-signatures").putString("reader:field", "resolved")
            .putBoolean("force_scan", true).commit()
        assertFalse(RuntimeSymbolCache.invalidateIfChanged(settings, identity(host, module)))
        assertEquals("resolved-signatures", settings.getString("symbol_json", null))
        assertEquals("resolved", settings.getString("reader:field", null))
        assertTrue(settings.getBoolean("force_scan", false))
    }

    @Test fun moduleRebuildInvalidatesCachedMissingSymbolsEvenWithSameHostVersion() {
        val host = installed("com.bereal.ft", apk("module-update-host.apk", "host"))
        val moduleFile = apk("module-update.apk", "old-module")
        val module = installed("dev.tqmane.befuck", moduleFile)
        RuntimeSymbolCache.invalidateIfChanged(settings, identity(host, module))
        settings.edit().putString("symbol_json", "cached-null-grid-symbol").commit()
        moduleFile.writeText("updated-module-with-new-mappings")
        assertTrue(RuntimeSymbolCache.invalidateIfChanged(settings, identity(host, module)))
        assertFalse(settings.contains("symbol_json"))
        assertFalse(RuntimeSymbolCache.invalidateIfChanged(settings, identity(host, module)))
    }

    @Test fun hostVersionAndSameVersionReplacementBothInvalidateSignatures() {
        val hostFile = apk("host-update.apk", "host")
        val host = installed("com.bereal.ft", hostFile)
        val module = installed("dev.tqmane.befuck", apk("host-update-module.apk", "module"))
        RuntimeSymbolCache.invalidateIfChanged(settings, identity(host, module))
        settings.edit().putString("symbol_json", "cached-signatures").commit()
        assertTrue(RuntimeSymbolCache.invalidateIfChanged(settings, identity(host, module, "3.97.2", 3599415L)))
        assertFalse(settings.contains("symbol_json"))
        settings.edit().putString("symbol_json", "cached-signatures").commit()
        hostFile.writeText("replaced-host-with-same-version")
        assertTrue(RuntimeSymbolCache.invalidateIfChanged(settings, identity(host, module, "3.97.2", 3599415L)))
        assertFalse(settings.contains("symbol_json"))
    }
}
