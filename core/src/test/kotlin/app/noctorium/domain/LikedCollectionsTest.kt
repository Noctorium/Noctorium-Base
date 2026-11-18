package app.noctorium.domain

import app.noctorium.core.ArtistDetails
import app.noctorium.core.compactFollowers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Each service's liked songs, which the library heads; and followers as an artist's page says them. */
class LikedCollectionsTest {

    @Test
    fun `each service's own liked collection is known by its id`() {
        assertTrue(Playlist("LM", "Liked Music", ProviderType.YOUTUBE_MUSIC).isLikedCollection())
        assertTrue(Playlist("likes", "Liked tracks", ProviderType.SOUNDCLOUD).isLikedCollection())
        assertTrue(Playlist("liked-songs", "Liked Songs", ProviderType.SPOTIFY).isLikedCollection())
        assertTrue(Playlist("loved:42", "Loved tracks", ProviderType.DEEZER).isLikedCollection())
        assertFalse(Playlist("PL123", "Road trip", ProviderType.YOUTUBE_MUSIC).isLikedCollection())
        assertFalse(Playlist("LM", "Not YouTube's", ProviderType.BANDCAMP).isLikedCollection())
    }

    @Test
    fun `liked collections come first and the rest keep their order`() {
        val a = Playlist("a", "A", ProviderType.SOUNDCLOUD)
        val b = Playlist("b", "B", ProviderType.YOUTUBE_MUSIC)
        val liked = Playlist("LM", "Liked Music", ProviderType.YOUTUBE_MUSIC)
        assertEquals(listOf(liked, a, b), listOf(a, liked, b).likedFirst())
    }

    @Test
    fun `followers read as a page says them, from a number or the service's own words`() {
        assertEquals("950", compactFollowers(950))
        assertEquals("1.5K", compactFollowers(1_500))
        assertEquals("12K", compactFollowers(12_345))
        assertEquals("2.2M", compactFollowers(2_200_000))
        assertEquals("3M", compactFollowers(3_000_000))
        assertEquals("2.2M followers", ArtistDetails("Flume", ProviderType.SOUNDCLOUD, followers = 2_200_000).followersLine)
        assertEquals("7.22M followers", ArtistDetails("Daft Punk", ProviderType.YOUTUBE_MUSIC, followersText = "7.22M").followersLine)
        assertNull(ArtistDetails("Nobody", ProviderType.BANDCAMP).followersLine)
    }
}
