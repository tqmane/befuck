package dev.tqmane.befuck.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostReflectionTest {
    private enum class SampleEnum { ALPHA, BETA }

    private class ObfuscatedLikeEnum private constructor(private val label: String) {
        override fun toString(): String = label

        companion object {
            @JvmField val FIRST = ObfuscatedLikeEnum("FIRST")
            @JvmField val SECOND = ObfuscatedLikeEnum("SECOND")

            @JvmStatic
            fun valuesArray(): Array<ObfuscatedLikeEnum> = arrayOf(FIRST, SECOND)
        }
    }

    private open class BaseHost {
        private val baseOnly = 1
        protected open fun onBaseEvent(code: Int): Int = baseOnly + code
    }

    private class DerivedHost : BaseHost() {
        private val derivedOnly = 2
        fun derivedAction(): Int = derivedOnly
    }

    @Test
    fun namedEnumValueResolvesStandardAndObfuscatedEnums() {
        assertEquals(SampleEnum.BETA, HostReflection.namedEnumValue(SampleEnum::class.java, "BETA"))
        assertEquals(ObfuscatedLikeEnum.SECOND, HostReflection.namedEnumValue(ObfuscatedLikeEnum::class.java, "SECOND"))
    }

    @Test(expected = IllegalStateException::class)
    fun namedEnumValueRejectsUnknownConstant() {
        HostReflection.namedEnumValue(SampleEnum::class.java, "MISSING")
    }

    @Test
    fun findHierarchyMethodWalksWithinBound() {
        val found = HostReflection.findHierarchyMethod(
            DerivedHost::class.java,
            BaseHost::class.java,
            "onBaseEvent",
            Int::class.javaPrimitiveType!!,
        )
        assertNotNull(found)
        assertEquals(5, found!!.invoke(DerivedHost(), 4))

        val outsideBound = HostReflection.findHierarchyMethod(
            DerivedHost::class.java,
            DerivedHost::class.java,
            "onBaseEvent",
            Int::class.javaPrimitiveType!!,
        )
        assertNull(outsideBound)
    }

    @Test
    fun allMethodsAndFieldsCollectInheritedMembersWithoutDuplicates() {
        val fieldNames = HostReflection.allFields(DerivedHost::class.java).map { it.name }
        assertTrue(fieldNames.containsAll(listOf("baseOnly", "derivedOnly")))
        val methodNames = HostReflection.allMethods(DerivedHost::class.java).map { it.name }
        assertTrue(methodNames.containsAll(listOf("onBaseEvent", "derivedAction")))
    }
}
