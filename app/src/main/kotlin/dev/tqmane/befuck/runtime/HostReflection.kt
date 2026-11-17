package dev.tqmane.befuck.runtime

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Shared host reflection helpers for R8-obfuscated AndroidX/Kotlin types. */
internal object HostReflection {
    fun allMethods(type: Class<*>): List<Method> {
        val methods = type.methods.toMutableList()
        var current: Class<*>? = type.superclass
        while (current != null) {
            methods += current.declaredMethods
            current = current.superclass
        }
        return methods.distinctBy { it.name + it.parameterTypes.joinToString { p -> p.name } + it.returnType.name }
    }

    fun allFields(type: Class<*>): List<Field> {
        val fields = mutableListOf<Field>()
        var current: Class<*>? = type
        while (current != null) {
            fields += current.declaredFields
            current = current.superclass
        }
        return fields.distinctBy { it.name + it.type.name }
    }

    fun namedEnumValue(type: Class<*>, expectedName: String): Any {
        if (type.isEnum) {
            val values = type.enumConstants ?: error("${type.name} has no enum values")
            return values.firstOrNull { (it as Enum<*>).name == expectedName }
                ?: error("${type.name} has no enum constant $expectedName")
        }
        val arrayMethod = allMethods(type).firstOrNull { method ->
            Modifier.isStatic(method.modifiers) && method.parameterCount == 0 &&
                method.returnType.isArray && method.returnType.componentType == type
        }
        if (arrayMethod != null) {
            arrayMethod.isAccessible = true
            val values = arrayMethod.invoke(null) as? Array<*>
            values?.firstOrNull { value -> value != null && value.toString() == expectedName }?.let { return it }
        }
        allFields(type).firstOrNull { field ->
            Modifier.isStatic(field.modifiers) && type.isAssignableFrom(field.type)
        }?.let { field ->
            field.isAccessible = true
            field.get(null)?.takeIf { it.toString() == expectedName }?.let { return it }
        }
        error("${type.name} has no value named $expectedName")
    }

    @JvmStatic
    fun findHierarchyMethod(
        start: Class<*>,
        rootBound: Class<*>,
        name: String,
        vararg parameterTypes: Class<*>,
    ): Method? {
        var current: Class<*>? = start
        while (current != null && rootBound.isAssignableFrom(current)) {
            try {
                return current.getDeclaredMethod(name, *parameterTypes).apply { isAccessible = true }
            } catch (_: NoSuchMethodException) {
                current = current.superclass
            }
        }
        return null
    }
}
