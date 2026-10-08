package dev.tqmane.befuck.runtime

import android.content.Context
import android.content.res.Resources
import android.os.Build
import android.os.Process
import java.io.File
import java.security.MessageDigest

/** Unmodified upstream native code; host UniFFI contract/checksum checks still run. */
object PreludeNativeLibrary {
    @JvmStatic
    @Synchronized
    fun extract(context: Context, resources: Resources): String {
        val abis = if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS
        val bytes = abis.firstNotNullOfOrNull { abi ->
            try {
                resources.assets.open("befuck/prelude/0.4.1/jni/$abi/libprelude.so").use { it.readBytes() }
            } catch (_: java.io.FileNotFoundException) { null }
        } ?: error("No Prelude native implementation for the current process ABI")
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val directory = File(context.codeCacheDir, "befuck/prelude").apply {
            check(isDirectory || mkdirs()) { "Cannot create Prelude code cache" }
        }
        val output = File(directory, "libprelude-$digest.so")
        if (output.isFile && output.readBytes().contentEquals(bytes)) return output.absolutePath
        val temporary = File.createTempFile("prelude-", ".so", directory)
        try {
            temporary.outputStream().use { it.write(bytes) }
            check(temporary.setReadOnly()) { "Cannot protect Prelude code cache" }
            check(temporary.renameTo(output)) { "Cannot publish Prelude code cache" }
        } finally {
            temporary.delete()
        }
        return output.absolutePath
    }
}
