package dev.tqmane.befuck.symbols

/** 3.98.0's screen exposes a privacy-filtered Precise/Approximate marker, not Ready/BitmapReady. */
internal object PostMapMappings3980 {
    private val config = PostMapSymbolResolver.SheetPostMapConfig(
        display = "ffg",
        marker = "efg",
        approximate = "cfg",
        precise = "dfg",
        renderOwner = "bfg",
        function0 = "uo8",
        function1 = "yo8",
        closeOwner = "l9e",
        unitOwner = "c8n",
        versionLabel = "3.98.0",
    )

    fun resolve(loader: ClassLoader): PostMapSymbols? = PostMapSymbolResolver.resolveSheetPostMap(loader, config)
}
