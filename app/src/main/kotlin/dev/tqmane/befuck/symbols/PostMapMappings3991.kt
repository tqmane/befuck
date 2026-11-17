package dev.tqmane.befuck.symbols

/** 3.99.1's screen exposes a privacy-filtered Precise/Approximate marker, not Ready/BitmapReady. */
internal object PostMapMappings3991 {
    private val config = PostMapSymbolResolver.SheetPostMapConfig(
        display = "cah",
        marker = "bah",
        approximate = "z9h",
        precise = "aah",
        renderOwner = "y9h",
        function0 = "a49",
        function1 = "e49",
        closeOwner = "fve",
        unitOwner = "fao",
        versionLabel = "3.99.1",
    )

    fun resolve(loader: ClassLoader): PostMapSymbols? = PostMapSymbolResolver.resolveSheetPostMap(loader, config)
}
