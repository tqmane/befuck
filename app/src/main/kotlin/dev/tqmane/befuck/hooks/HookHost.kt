package dev.tqmane.befuck.hooks

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.Resources
import android.util.Log
import dev.tqmane.befuck.runtime.RuntimeKnowledge
import dev.tqmane.befuck.symbols.HostMappings
import dev.tqmane.befuck.symbols.ResolvedSymbols
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap

/** Shared libxposed host wrapper, once-logging, and runtime state shared across hook installers. */
internal class HookHost(
    val xposed: XposedInterface,
    val targetPackage: String = "com.bereal.ft",
) {
    companion object {
        const val TAG = "BeFuck"
    }

    private val loggedKeys = ConcurrentHashMap.newKeySet<String>()

    @Volatile var targetClassLoader: ClassLoader? = null
    @Volatile var targetApplicationInfo: ApplicationInfo? = null
    @Volatile var applicationContext: Context? = null
    @Volatile var moduleResources: Resources? = null
    @Volatile var mappings: HostMappings? = null
    @Volatile var resolvedSymbols: ResolvedSymbols? = null
    @Volatile var smsAuthCompatibility: Boolean = false
    @Volatile var composeInjectionReady: Boolean = false

    val moduleApplicationInfo: ApplicationInfo
        get() = xposed.moduleApplicationInfo

    fun info(message: String) {
        Log.i(TAG, message)
        xposed.log(Log.INFO, TAG, message)
    }

    fun warn(message: String) {
        Log.w(TAG, message)
        xposed.log(Log.WARN, TAG, message)
    }

    fun error(message: String, throwable: Throwable) {
        RuntimeKnowledge.recordFailure(throwable)
        Log.e(TAG, message, throwable)
        xposed.log(Log.ERROR, TAG, message, throwable)
    }

    fun logOnce(key: String, message: String) {
        if (loggedKeys.add(key)) info(message)
    }

    fun markOnce(key: String): Boolean = loggedKeys.add(key)

    fun hook(executable: Executable): XposedInterface.HookBuilder = xposed.hook(executable)

    fun hookClassInitializer(type: Class<*>): XposedInterface.HookBuilder = xposed.hookClassInitializer(type)

    fun restoreStaticStringIfNull(field: Field, symbol: String, value: String) {
        try {
            field.isAccessible = true
            RuntimeKnowledge.rememberRepair(field, value, symbol)
            if (field.get(null) == null) {
                field.set(null, value)
                info("Restored missing static string $symbol via resolved Field ${field.declaringClass.name}.${field.name}")
            }
        } catch (failure: Throwable) {
            error("Could not restore resolved string field $symbol", failure)
        }
    }

    fun createZeroSizeComposeModifier(classLoader: ClassLoader): Any {
        val modifierClass = Class.forName("androidx.compose.ui.Modifier", false, classLoader)
        val sizeElementClass = Class.forName("androidx.compose.foundation.layout.SizeElement", false, classLoader)
        val sizeConstructor = sizeElementClass.declaredConstructors.firstOrNull { candidate ->
            val p = candidate.parameterTypes
            p.size == 5 &&
                p[0] == Float::class.javaPrimitiveType &&
                p[1] == Float::class.javaPrimitiveType &&
                p[2] == Float::class.javaPrimitiveType &&
                p[3] == Float::class.javaPrimitiveType &&
                p[4] == Boolean::class.javaPrimitiveType
        } ?: throw NoSuchMethodException("Compose SizeElement(float x4, boolean)")
        sizeConstructor.isAccessible = true
        val element = sizeConstructor.newInstance(0f, 0f, 0f, 0f, false)

        val companionClass = Class.forName("androidx.compose.ui.Modifier\$Companion", false, classLoader)
        val singletonField = companionClass.declaredFields.firstOrNull { field ->
            java.lang.reflect.Modifier.isStatic(field.modifiers) && modifierClass.isAssignableFrom(field.type)
        } ?: throw NoSuchFieldException("Modifier.Companion singleton")
        singletonField.isAccessible = true
        val modifier = singletonField.get(null)

        val append = modifierClass.methods.firstOrNull { candidate ->
            candidate.parameterTypes.contentEquals(arrayOf(modifierClass)) && candidate.returnType == modifierClass
        } ?: throw NoSuchMethodException("Modifier.then(Modifier)")
        val sized = append.invoke(modifier, element)

        // Compose children can draw outside a zero-size layout unless its bounds are clipped.
        val clipClass = Class.forName("androidx.compose.ui.draw.ClipKt", false, classLoader)
        val clipMethod = clipClass.declaredMethods.firstOrNull { candidate ->
            java.lang.reflect.Modifier.isStatic(candidate.modifiers) &&
                candidate.returnType == modifierClass &&
                candidate.parameterTypes.contentEquals(arrayOf(modifierClass))
        } ?: throw NoSuchMethodException("Compose clipToBounds(Modifier)")
        clipMethod.isAccessible = true
        return requireNotNull(clipMethod.invoke(null, sized))
    }
}
