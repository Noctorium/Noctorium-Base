package app.noctorium.bandcamp

/**
 * The address Noctorium keeps for a Bandcamp track: its page, with the ids that find its stream after it.
 *
 * The page is what opens in a browser and what a share sends. The ids ride along after a `#`, which a
 * browser never sends to Bandcamp, because a page is three hundred kilobytes to read for two numbers --
 * and the player is handed nothing but this address, by the same route as every other service's track.
 * A song listed without a page of its own (the one a discover card offers, say) gets its album's page,
 * and the ids are then what keep it apart from the album's other songs.
 */
data class BandcampSource(val page: String, val trackId: Long?, val bandId: Long?) {
    companion object {
        private const val TRACK = "bandcamp-track"
        private const val BAND = "band"

        fun of(page: String?, trackId: Long, bandId: Long?): String {
            val base = (page ?: "https://bandcamp.com").substringBefore('#')
            return "$base#$TRACK=$trackId" + (bandId?.takeIf { it > 0 }?.let { "&$BAND=$it" } ?: "")
        }

        /**
         * A Bandcamp track's address read back, or null for anything else.
         *
         * Also takes a song's bare page -- `artist.bandcamp.com/track/name` -- which has no ids and has to be
         * read to find them.
         */
        fun parse(url: String): BandcampSource? {
            val page = url.substringBefore('#')
            val fragment = url.substringAfter('#', "")
            if (fragment.startsWith("$TRACK=")) {
                val fields = fragment.split('&').associate { it.substringBefore('=') to it.substringAfter('=', "") }
                val trackId = fields[TRACK]?.toLongOrNull() ?: return null
                return BandcampSource(page, trackId, fields[BAND]?.toLongOrNull())
            }
            val host = HOST.find(page)?.groupValues?.get(1)?.lowercase() ?: return null
            val path = page.substringAfter(host, "").substringBefore('?')
            return if (host.endsWith(".bandcamp.com") && path.startsWith("/track/")) BandcampSource(page, null, null) else null
        }

        /** The page alone, for showing or sharing: the ids mean nothing to anyone but Noctorium. */
        fun page(url: String): String = if (parse(url) != null) url.substringBefore('#') else url

        private val HOST = Regex("""^https?://([^/?#]+)""")
    }
}
