package dev.tqmane.befuck.symbols

/** 3.101.1's screen exposes a privacy-filtered Precise/Approximate marker, not Ready/BitmapReady. */
internal object PostMapMappings31011 {
    private val config = PostMapSymbolResolver.SheetPostMapConfig(
        display = "nkh",
        marker = "mkh",
        approximate = "kkh",
        precise = "lkh",
        renderOwner = "jkh",
        function0 = "jc9",
        function1 = "nc9",
        closeOwner = "xdh",
        unitOwner = "zmo",
        versionLabel = "3.101.1",
    )

    fun resolve(loader: ClassLoader): PostMapSymbols? = PostMapSymbolResolver.resolveSheetPostMap(loader, config)
}
