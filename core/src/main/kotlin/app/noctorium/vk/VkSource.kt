package app.noctorium.vk

/**
 * The address Noctorium keeps for a VK song: its page on vk.ru, with the key VK wants for it after a `#`.
 *
 * `vk.ru/audio<owner>_<id>` opens the song on VK, and the owner and id are what VK's interface asks for to
 * hand over a fresh address. Many songs also need an access key, which belongs to the listing they came from
 * and is not part of any address VK shows -- so it rides after the `#`, where a browser never sends it and a
 * share or a copied link leaves it off.
 */
data class VkSource(val ownerId: Long, val audioId: Long, val accessKey: String?) {
    /** How VK's `audio.getById` names this song. */
    val requestId: String get() = listOfNotNull("${ownerId}_$audioId", accessKey?.takeIf(String::isNotBlank)).joinToString("_")

    val page: String get() = "https://vk.ru/audio${ownerId}_$audioId"

    override fun toString(): String = page + (accessKey?.takeIf(String::isNotBlank)?.let { "#$ACCESS=$it" } ?: "")

    companion object {
        private const val ACCESS = "vk-access"
        private val SONG = Regex("""^https?://(?:m\.)?vk\.(?:ru|com)/audio(-?\d+)_(\d+)""")

        fun of(audio: VkAudio): String = VkSource(audio.ownerId, audio.id, audio.accessKey).toString()

        /** A VK song's address read back, or null for anything else. */
        fun parse(url: String): VkSource? {
            val match = SONG.find(url.substringBefore('#')) ?: return null
            val key = url.substringAfter('#', "").split('&')
                .firstOrNull { it.startsWith("$ACCESS=") }?.substringAfter('=')?.takeIf(String::isNotBlank)
            return VkSource(match.groupValues[1].toLong(), match.groupValues[2].toLong(), key)
        }

        /** The page alone, for showing or sharing. */
        fun page(url: String): String = parse(url)?.page ?: url
    }
}
