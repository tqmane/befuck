package dev.tqmane.befuck.runtime

/** Repair only a single missing analytics key supported by the class's own labels. */
object RepairInference {
    private val binderDescriptor = Regex("(?:[A-Za-z_$][A-Za-z0-9_$]*\\.)+I[A-Za-z_$][A-Za-z0-9_$]*")
    fun isBinderDescriptor(value: String): Boolean = binderDescriptor.matches(value)
    private val label = Regex("(?:[(,]|^)[ ]*([A-Za-z][A-Za-z0-9_]*)=")

    fun uniqueMissingKey(constructorStrings: List<String>, descriptionStrings: List<String>, nullFields: Int): String? {
        if (nullFields != 1) return null
        val names = descriptionStrings.flatMap { text -> label.findAll(text).map { it.groupValues[1] }.toList() }.toSet()
        return (names - constructorStrings.toSet()).singleOrNull()
    }
}
