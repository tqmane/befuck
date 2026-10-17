package dev.tqmane.befuck.download

/** A complete coordinate already safe to display for this post, never an unfiltered host DTO. */
data class PostLocation(val latitude: Double, val longitude: Double, val approximate: Boolean) {
    init {
        require(valid(latitude, longitude)) { "Invalid post location" }
    }

    // (0,0) is also the host's default placeholder for posts without a shared location.
    // Keep it readable in old backups, but never treat it as an observed map/GPS position.
    val isMappable: Boolean get() = latitude != 0.0 || longitude != 0.0

    // Keep the coordinate and its privacy state in one observed value. Independent field
    // merges could otherwise combine latitude, longitude and precision from different captures.
    internal fun encode(): String = "$latitude,$longitude,${if (approximate) 1 else 0}"

    companion object {
        fun create(latitude: Double?, longitude: Double?, approximate: Boolean): PostLocation? =
            if (latitude != null && longitude != null && valid(latitude, longitude) &&
                (latitude != 0.0 || longitude != 0.0)) {
                PostLocation(latitude, longitude, approximate)
            } else null

        internal fun decode(value: String?): PostLocation? {
            val parts = value?.split(',') ?: return null
            if (parts.size != 3 || parts[2] !in setOf("0", "1")) return null
            // Legacy archives may contain the old 0,0 placeholder. Decode it for
            // non-destructive backup/schema validation; callers filter isMappable.
            val latitude = parts[0].toDoubleOrNull() ?: return null
            val longitude = parts[1].toDoubleOrNull() ?: return null
            if (!valid(latitude, longitude)) return null
            val location = PostLocation(latitude, longitude, parts[2] == "1")
            return location.takeIf { it.encode() == value }
        }

        private fun valid(latitude: Double, longitude: Double) = latitude.isFinite() && longitude.isFinite() &&
            latitude in -90.0..90.0 && longitude in -180.0..180.0
    }
}
