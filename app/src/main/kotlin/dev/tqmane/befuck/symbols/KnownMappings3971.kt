package dev.tqmane.befuck.symbols

/** Reviewed against the original 3.97.1 DEX; 3.97.0 labels identify shared hook roles. */
internal object KnownMappings3971 {
    const val VERSION_NAME = "3.97.1"
    const val VERSION_CODE = 3599414L

    @JvmStatic
    fun isKnownVersion(name: String?, code: Long) = name == VERSION_NAME && code == VERSION_CODE

    private val classes = mapOf(
        "a7i" to "t7i",
        "ai6" to "dh6",
        "an9" to "om9",
        "ax6" to "ew6",
        "b8e" to "k7e",
        "b99" to "k89",
        "bhn" to "whn",
        "bi1" to "bh1",
        "bi6" to "eh6",
        "c8e" to "l7e",
        "d42" to "d32",
        "ddi" to "wdi",
        "edi" to "xdi",
        "eki" to "yki",
        "el7" to "hk7",
        "eu" to "dt",
        "f3i" to "y3i",
        "fj6" to "ii6",
        "fk9" to "tj9",
        "fl7" to "ik7",
        "g34" to "f24",
        "gmg" to "zmg",
        "gsh" to "zsh",
        "h45" to "j35",
        "hkm" to "alm",
        "huh" to "avh",
        "hun" to "cvn",
        "ie5" to "kd5",
        "isk" to "ctk",
        "jkg" to "clg",
        "jpg" to "cqg",
        "js8" to "rr8",
        "kxa" to "wwa",
        "lia" to "xha",
        "lkg" to "elg",
        "mdi" to "fei",
        "mfg" to "egg",
        "mi6" to "ph6",
        "n57" to "p47",
        "ndi" to "gei",
        "ns8" to "vr8",
        "oh6" to "rg6",
        "okg" to "hlg",
        "om7" to "rl7",
        "pm7" to "sl7",
        "po1" to "pn1",
        "ps8" to "xr8",
        "qfb" to "afb",
        "qm7" to "tl7",
        "r0l" to "j1l",
        "r1g" to "k2g",
        "r68" to "u58",
        "ra7" to "t97",
        "re7" to "ud7",
        "rm7" to "ul7",
        "s55" to "u45",
        "sdi" to "lei",
        "t28" to "w18",
        "t4e" to "c4e",
        "tam" to "mbm",
        "tin" to "ojn",
        "tm7" to "wl7",
        "u2i" to "o3i",
        "u4a" to "h4a",
        "uak" to "nbk",
        "uo2" to "un2",
        "up4" to "wo4",
        "vx4" to "xw4",
        "w19" to "e19",
        "wi1" to "wh1",
        "wl7" to "zk7",
        "wrh" to "psh",
        "wx4" to "yw4",
        "x3j" to "q4j",
        "x6n" to "q7n",
        "y2a" to "k2a",
        "y6n" to "r7n",
        "yci" to "rdi",
        "z4e" to "i4e",
        "z89" to "i89",
        "zh6" to "ch6",
        "zxl" to "syl",
    )

    private val methods = mapOf(
        "ie5.z" to "N", // Immutable list copy.
        "kxa.A" to "B", // Koin application.
        "uak.x" to "s", // Flow.first, not collect or firstOrNull.
        "x6n.N" to "O",
        "y6n.S" to "T", // Camera origin from Bundle.
    )

    fun className(name: String): String = classes[name] ?: name
    fun methodName(owner: String, name: String): String = methods["$owner.$name"] ?: name
}
