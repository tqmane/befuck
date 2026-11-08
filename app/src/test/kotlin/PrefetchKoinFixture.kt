/** Matches the host's static Koin entry point; contains no application or network code. */
object fcb {
    var root: Any? = null
    @JvmStatic fun B(): Any? = root
}
