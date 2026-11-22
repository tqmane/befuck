package dev.tqmane.befuck.symbols

/** 3.100.0's screen exposes a privacy-filtered Precise/Approximate marker, not Ready/BitmapReady. */
internal object PostMapMappings31000 {
    private val config = PostMapSymbolResolver.SheetPostMapConfig(
        display = "peh",
        marker = "oeh",
        approximate = "meh",
        precise = "neh",
        renderOwner = "leh",
        function0 = "p79",
        function1 = "t79",
        closeOwner = "c8h",
        unitOwner = "sfo",
        versionLabel = "3.100.0",
    )

    fun resolve(loader: ClassLoader): PostMapSymbols? = PostMapSymbolResolver.resolveSheetPostMap(loader, config)
}
