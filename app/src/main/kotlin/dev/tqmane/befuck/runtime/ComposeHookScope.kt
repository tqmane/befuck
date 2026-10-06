package dev.tqmane.befuck.runtime

/** Emit hook content inside the host's own restart group, never after a paused/skipped body. */
object ComposeHookScope {
    class Frame internal constructor(internal val composer: Any, internal val content: Runnable) {
        internal var depth = 0
        internal var executed: Boolean? = null
        internal var emitted = false
    }

    private val frames = ThreadLocal<MutableList<Frame>>()

    @JvmStatic
    fun push(composer: Any, content: Runnable): Frame {
        val stack = frames.get() ?: mutableListOf<Frame>().also(frames::set)
        return Frame(composer, content).also(stack::add)
    }

    @JvmStatic
    fun pop(frame: Frame) {
        val stack = checkNotNull(frames.get())
        check(stack.last() === frame) { "Compose hook frames must close in order" }
        stack.removeAt(stack.lastIndex)
        if (stack.isEmpty()) frames.remove()
    }

    @JvmStatic
    fun started(composer: Any) {
        frames.get()?.forEach { if (it.composer === composer) it.depth++ }
    }

    @JvmStatic
    fun executed(composer: Any, execute: Boolean) {
        frames.get()?.forEach {
            if (it.composer === composer && it.depth == 1 && it.executed == null) it.executed = execute
        }
    }

    @JvmStatic
    fun ending(composer: Any) {
        frames.get()?.asReversed()?.forEach {
            if (it.composer === composer && it.depth == 1 && it.executed == true && !it.emitted) {
                it.emitted = true // Nested groups created by the callback must not re-enter it.
                it.content.run()
            }
        }
    }

    @JvmStatic
    fun ended(composer: Any) {
        frames.get()?.forEach {
            if (it.composer === composer) {
                check(it.depth > 0) { "Unbalanced host restart group" }
                it.depth--
            }
        }
    }
}
