package dev.tqmane.befuck.runtime

import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import java.io.File
import java.security.MessageDigest

/** Signatures belong to the installed host and module APKs, including same-version rebuilds. */
internal object RuntimeSymbolCache {
    private const val IDENTITY_KEY = "symbol_cache_identity"

    fun identity(host: ApplicationInfo, module: ApplicationInfo, name: String?, code: Long): String {
        fun apkFiles(app: ApplicationInfo): String =
            (listOfNotNull(app.sourceDir) + app.splitSourceDirs.orEmpty()).joinToString("|") { path ->
                val file = File(path)
                "${file.absolutePath}:${file.length()}:${file.lastModified()}"
            }
        // The prefix migrates caches created before module APK identity was tracked.
        val source = "1|${host.packageName}|$name|$code|${apkFiles(host)}|${apkFiles(module)}"
        return MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun invalidateIfChanged(settings: SharedPreferences, identity: String): Boolean {
        if (settings.getString(IDENTITY_KEY, null) == identity) return false
        val edit = settings.edit().remove("symbol_json").remove("force_scan")
        settings.all.keys.filter { it.startsWith("reader:") }.forEach(edit::remove)
        edit.putString(IDENTITY_KEY, identity).commit()
        return true
    }
}
