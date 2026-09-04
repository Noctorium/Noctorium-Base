package app.spiceity.core

import app.spiceity.domain.Artist
import app.spiceity.domain.ProviderType
import app.spiceity.domain.Track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which services a like can be written to is this state's decision alone.
 *
 * The heart button used to ask the question itself, with a hardcoded test for SoundCloud, and so stayed
 * dead on YouTube Music for as long as YouTube liking had been working underneath it.
 */
class LikeSupportTest {
    private fun track(provider: ProviderType, id: String = "v1") = Track(
        provider = provider,
        id = id,
        title = "Antarctica",
        artists = listOf(Artist("a", "\$uicideboy\$", provider)),
        sourceUrl = "https://example.test/$id",
    )

    @Test
    fun `a signed-in youtube account can be liked to`() {
        val state = LikeState(youTubeReady = true)

        assertTrue(state.supports(track(ProviderType.YOUTUBE_MUSIC)))
        assertTrue(state.supports(track(ProviderType.YOUTUBE_VIDEO)))
    }

    @Test
    fun `each service is judged on its own session`() {
        val youTubeOnly = LikeState(youTubeReady = true, soundCloudReady = false)
        assertTrue(youTubeOnly.supports(track(ProviderType.YOUTUBE_MUSIC)))
        assertFalse(youTubeOnly.supports(track(ProviderType.SOUNDCLOUD)))

        val soundCloudOnly = LikeState(youTubeReady = false, soundCloudReady = true)
        assertFalse(soundCloudOnly.supports(track(ProviderType.YOUTUBE_MUSIC)))
        assertTrue(soundCloudOnly.supports(track(ProviderType.SOUNDCLOUD)))
    }

    @Test
    fun `a local file is never liked to a service`() {
        assertFalse(LikeState(youTubeReady = true, soundCloudReady = true).supports(track(ProviderType.LOCAL)))
    }

    /** YouTube Music and plain YouTube share a video id, so a like on either reads as liked on both. */
    @Test
    fun `a youtube like is recognised across both youtube surfaces`() {
        val state = LikeState(youTubeReady = true, likedKeys = setOf("yt:7tLGGiNjp_U"))

        assertTrue(state.isLiked(track(ProviderType.YOUTUBE_MUSIC, "7tLGGiNjp_U")))
        assertTrue(state.isLiked(track(ProviderType.YOUTUBE_VIDEO, "7tLGGiNjp_U")))
        assertFalse(state.isLiked(track(ProviderType.YOUTUBE_MUSIC, "other")))
    }

    @Test
    fun `likes are scoped to their own service`() {
        assertEquals("yt:v1", likeKey(track(ProviderType.YOUTUBE_MUSIC)))
        assertEquals("sc:v1", likeKey(track(ProviderType.SOUNDCLOUD)))
        // The same id on two services must not be one like.
        assertFalse(
            LikeState(likedKeys = setOf("sc:v1")).isLiked(track(ProviderType.YOUTUBE_MUSIC)),
        )
    }
}
