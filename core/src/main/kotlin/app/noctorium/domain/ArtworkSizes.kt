package app.noctorium.domain

/**
 * The address of the same cover at [px] pixels on its longest side, from services that can provide it.
 *
 * Every cover used to be fetched at whatever size the listing named, and a YouTube Music listing names
 * 120 by 120. The now playing screen draws that across four hundred pixels and more -- three and a half
 * times up -- which is the blur. The images themselves are not small: the same server makes any size on
 * request, and 120 pixels was simply what was asked for.
 *
 * So a cover is asked for at the size it will be drawn. Not at the largest the server will make: a list
 * of covers at 1200 pixels each is a quarter of a megabyte a row, where the thumbnail it becomes is eight
 * kilobytes. Only sizes each service is known to have are asked for, because a size that does not exist
 * is not a slightly worse picture, it is no picture.
 *
 * Anything this does not recognise comes back unchanged.
 */
fun artworkAt(url: String, px: Int): String {
    if (px <= 0) return url
    return googleSized(url, px) ?: youTubeThumbnailSized(url, px) ?: soundCloudSized(url, px) ?: url
}

/**
 * Google's image servers -- YouTube Music covers, channel pictures -- which carry the size in the address,
 * after its last `=`: `=w120-h120-l90-rj`, `=s176-c-k-c0x00ffffff-no-rj`.
 *
 * Only the size tokens are changed. The rest are instructions -- crop to a square, a quality, a background
 * colour -- and dropping them turns a round channel picture into a letterboxed one.
 */
private fun googleSized(url: String, px: Int): String? {
    val host = hostOf(url) ?: return null
    if (!host.endsWith("googleusercontent.com") && !host.endsWith("ggpht.com")) return null
    // A query string means the `=` is not the size marker, and guessing which part is would be guessing.
    if ('?' in url) return null
    val cut = url.lastIndexOf('=')
    if (cut < 0) return "$url=w$px-h$px-l90-rj"
    val params = url.substring(cut + 1)
    if (!GOOGLE_PARAMS.matches(params)) return null
    // One size token or another: an address sized by `s` has no `w` and `h`, and the reverse.
    val resized = params.split('-').joinToString("-") { token ->
        when {
            GOOGLE_WIDTH.matches(token) -> "w$px"
            GOOGLE_HEIGHT.matches(token) -> "h$px"
            GOOGLE_SQUARE.matches(token) -> "s$px"
            else -> token
        }
    }
    return url.substring(0, cut + 1) + resized
}

private val GOOGLE_PARAMS = Regex("""[a-z0-9-]+""")
private val GOOGLE_WIDTH = Regex("""w\d+""")
private val GOOGLE_HEIGHT = Regex("""h\d+""")
private val GOOGLE_SQUARE = Regex("""s\d+""")

/**
 * YouTube's video stills, which come in fixed names rather than sizes.
 *
 * `default` is 120 wide and `mqdefault` 320, and a listing often names one of those. Moved up only as far
 * as `hqdefault`, which every video has. `maxresdefault` is sharper and missing for a great many videos --
 * anything uploaded before high definition, and plenty since -- and a missing still is a blank square
 * where a slightly softer picture would have been. The signed query some stills carry belongs to the name
 * it came with, and goes with it.
 */
private fun youTubeThumbnailSized(url: String, px: Int): String? {
    val host = hostOf(url) ?: return null
    if (!host.endsWith("ytimg.com")) return null
    val match = YOUTUBE_STILL.find(url) ?: return null
    val (prefix, name, extension) = match.destructured
    val wanted = when {
        name == "default" && px > 120 -> "hqdefault"
        name == "mqdefault" && px > 320 -> "hqdefault"
        else -> return url
    }
    return "$prefix$wanted.$extension"
}

private val YOUTUBE_STILL = Regex("""^(https?://[^/]+/vi(?:_webp)?/[^/]+/)(default|mqdefault|hqdefault|sddefault|maxresdefault)\.(jpg|webp)""")

/**
 * SoundCloud's artwork, named by a size suffix: `-large` is 100 by 100, the size every listing gives.
 *
 * Up to `-t500x500` and no further. `-original` is whatever was uploaded, and that is sometimes a
 * three-thousand-pixel photograph of several megabytes for a square drawn at four hundred.
 */
private fun soundCloudSized(url: String, px: Int): String? {
    val host = hostOf(url) ?: return null
    if (!host.endsWith("sndcdn.com")) return null
    val match = SOUNDCLOUD_SIZE.find(url) ?: return null
    val wanted = when {
        px <= 100 -> return url
        px <= 300 -> "t300x300"
        else -> "t500x500"
    }
    return url.replaceRange(match.groups[1]!!.range, wanted)
}

private val SOUNDCLOUD_SIZE = Regex("""-(large|t\d+x\d+|small|badge|tiny|mini|crop)\.(jpg|png|jpeg)""")

/**
 * A drawn size rounded up to one of a few, so that covers a handful of pixels apart share one fetch and
 * one cache entry rather than each asking the server for its own.
 */
fun artworkBucket(px: Int): Int = when {
    px <= 0 -> 0
    px <= 64 -> 64
    px <= 128 -> 128
    px <= 256 -> 256
    px <= 512 -> 512
    else -> 1024
}

private fun hostOf(url: String): String? =
    Regex("""^https?://([^/?#]+)""").find(url)?.groupValues?.get(1)?.lowercase()
