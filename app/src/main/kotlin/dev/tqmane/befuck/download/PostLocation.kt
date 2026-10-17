package dev.tqmane.befuck.download

/** A complete coordinate already safe to display for this post, never an unfiltered host DTO. */
data class PostLocation(val latitude: Double, val longitude: Double, val approximate: Boolean) {
    init {
        require(valid(latitude, longitude)) { "Invalid post location" }
    }

    // Keep the coordinate and its privacy state in one observed value. Independent field
    // merges could otherwise combine latitude, longitude and precision from different captures.
    internal fun encode(): String = "$latitude,$longitude,${if (approximate) 1 else 0}"

    companion object {
        fun create(latitude: Double?, longitude: Double?, approximate: Boolean): PostLocation? =
            if (latitude != null && longitude != null && valid(latitude, longitude)) {
                PostLocation(latitude, longitude, approximate)
            } else null

        internal fun decode(value: String?): PostLocation? {
            val parts = value?.split(',') ?: return null
            if (parts.size != 3 || parts[2] !in setOf("0", "1")) return null
            val location = create(parts[0].toDoubleOrNull(), parts[1].toDoubleOrNull(), parts[2] == "1")
                ?: return null
            return location.takeIf { it.encode() == value }
        }

        private fun valid(latitude: Double, longitude: Double) = latitude.isFinite() && longitude.isFinite() &&
            latitude in -90.0..90.0 && longitude in -180.0..180.0
    }
}
