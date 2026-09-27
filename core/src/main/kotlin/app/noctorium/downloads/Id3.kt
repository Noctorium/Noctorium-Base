package app.noctorium.downloads

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** What goes on a saved MP3 so a phone, a car or a file manager shows the song rather than a file name. */
class AudioTags(
    val title: String,
    val artist: String,
    val album: String? = null,
    /** JPEG or PNG. Anything else is left off, because a player that cannot decode a picture shows none. */
    val cover: ByteArray? = null,
)

/**
 * Writes the tag at the front of an MP3: title, artist, album and the cover.
 *
 * This is what ffmpeg was still needed for. mpv makes the MP3 itself and can write a title and an artist
 * while it encodes, but it will not embed a picture, and a phone showing a grey square for every saved song
 * is what made the cover worth having. The format is small and fixed: a ten-byte header, then frames of a
 * four-letter name, a length and the value. Writing one is less code than downloading ffmpeg was.
 *
 * Version 2.3, not the newer 2.4 mpv writes. 2.3 is the one every player reads, and 2.4's differences --
 * UTF-8 text, differently encoded frame lengths -- are exactly the parts older car stereos and Windows
 * Explorer get wrong.
 */
object Id3 {
    private const val HEADER_LENGTH = 10

    /** Room left after the frames, so a tag editor can add to the tag without rewriting the whole file. */
    private const val PADDING = 1024

    /** Replaces whatever tag [file] starts with by one carrying [tags]. The audio is not touched. */
    fun retag(file: Path, tags: AudioTags) {
        val original = Files.readAllBytes(file)
        val audio = original.copyOfRange(existingTagLength(original).coerceAtMost(original.size), original.size)
        // Next to the file and then moved over it, so a failure halfway leaves the file as it was.
        val working = file.resolveSibling(file.fileName.toString() + ".tagging")
        try {
            Files.newOutputStream(working).use { out ->
                out.write(tagFor(tags))
                out.write(audio)
            }
            Files.move(working, file, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(working)
        }
    }

    /** The whole tag, header included, ready to go in front of the audio. */
    fun tagFor(tags: AudioTags): ByteArray {
        val frames = ByteArrayOutputStream()
        tags.title.takeIf(String::isNotBlank)?.let { frames.write(textFrame("TIT2", it)) }
        tags.artist.takeIf(String::isNotBlank)?.let { frames.write(textFrame("TPE1", it)) }
        tags.album?.takeIf(String::isNotBlank)?.let { frames.write(textFrame("TALB", it)) }
        tags.cover?.let { picture -> pictureMime(picture)?.let { frames.write(pictureFrame(it, picture)) } }
        frames.write(ByteArray(PADDING))
        val body = frames.toByteArray()
        return byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0) +
            syncsafe(body.size) + body
    }

    /**
     * How many bytes at the front of [file] are an ID3v2 tag, or zero if it does not start with one.
     *
     * The length in the header does not count the header, or the footer a 2.4 tag may carry, so both are
     * added back. Getting this wrong by even one byte leaves a scrap of the old tag in front of the audio,
     * which most players skip and some play as a click.
     */
    fun existingTagLength(file: ByteArray): Int {
        if (file.size < HEADER_LENGTH) return 0
        if (file[0] != 'I'.code.toByte() || file[1] != 'D'.code.toByte() || file[2] != '3'.code.toByte()) return 0
        val sizeBytes = file.copyOfRange(6, 10)
        // Each byte carries seven bits. A high bit set means this is not a tag header at all.
        if (sizeBytes.any { it.toInt() and 0x80 != 0 }) return 0
        val size = sizeBytes.fold(0) { total, byte -> (total shl 7) or (byte.toInt() and 0x7f) }
        val footer = if (file[5].toInt() and 0x10 != 0) HEADER_LENGTH else 0
        return HEADER_LENGTH + size + footer
    }

    /** JPEG or PNG, told by their first bytes, which cannot be mistaken. Null for anything else. */
    fun pictureMime(picture: ByteArray): String? = when {
        picture.size > 3 && picture[0] == 0xFF.toByte() && picture[1] == 0xD8.toByte() && picture[2] == 0xFF.toByte() ->
            "image/jpeg"
        picture.size > 8 && picture.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE) -> "image/png"
        else -> null
    }

    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)

    /**
     * A text frame in UTF-16 with a byte order mark: 2.3 has no UTF-8, and Latin-1, the other choice,
     * cannot write most of the world's artist names.
     */
    private fun textFrame(id: String, value: String): ByteArray {
        val text = value.trim().take(MAX_TEXT)
        val data = byteArrayOf(1, 0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE) + byteArrayOf(0, 0)
        return frame(id, data)
    }

    /** The front cover. Type 3 is what every player looks for; the description is left empty. */
    private fun pictureFrame(mime: String, picture: ByteArray): ByteArray {
        val data = ByteArrayOutputStream().apply {
            write(0)
            write(mime.toByteArray(Charsets.ISO_8859_1))
            write(0)
            write(3)
            write(0)
            write(picture)
        }.toByteArray()
        return frame("APIC", data)
    }

    /** In 2.3 a frame's length is an ordinary 32-bit number; only the tag's own length is seven bits a byte. */
    private fun frame(id: String, data: ByteArray): ByteArray =
        id.toByteArray(Charsets.ISO_8859_1) + int32(data.size) + byteArrayOf(0, 0) + data

    private fun int32(value: Int) = byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())

    private fun syncsafe(value: Int) = byteArrayOf(
        ((value ushr 21) and 0x7f).toByte(),
        ((value ushr 14) and 0x7f).toByte(),
        ((value ushr 7) and 0x7f).toByte(),
        (value and 0x7f).toByte(),
    )

    /** Far longer than any real title; a limit only so a runaway description cannot make the tag huge. */
    private const val MAX_TEXT = 500
}
