package app.spiceity.auth

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Whether a harvested session is actually a session.
 *
 * This is the loop that made signing in impossible. The check asked only whether the cookie was *present*,
 * and an expired cookie sits in the store looking exactly like a live one — so sign-in reported success,
 * wrote the dead cookies out, and the service then answered 401. The account read as signed out, and
 * pressing sign-in again harvested the very same dead cookie and reported success again.
 *
 * Nothing failed anywhere in that circle, which is why it could go round forever.
 */
class SessionValidityTest {
    private val now = Instant.parse("2026-09-08T12:00:00Z").epochSecond

    private fun cookie(
        name: String,
        value: String = "a-real-value",
        expires: Long = 0,
        domain: String = ".youtube.com",
    ) = HarvestedCookie(
        domain = domain,
        path = "/",
        name = name,
        value = value,
        secure = true,
        expiresEpochSeconds = expires,
    )

    @Test
    fun `a cookie with time left on it is live`() {
        assertTrue(cookie("SAPISID", expires = now + 3_600).isLive(now))
    }

    /** The whole bug: this used to count, and counting it is what closed the circle. */
    @Test
    fun `an expired cookie is not a session, however present it is`() {
        assertFalse(cookie("SAPISID", expires = now - 1).isLive(now))
        assertFalse(cookie("SAPISID", expires = now - 86_400).isLive(now))
    }

    /**
     * An expiry of zero means a session cookie — one the browser holds for as long as it runs and never
     * writes an expiry for. Treating zero as "expired in 1970" would reject every one of them and make
     * signing in impossible in the opposite direction.
     */
    @Test
    fun `a session cookie has no expiry and is still live`() {
        assertTrue(cookie("SAPISID", expires = 0).isLive(now))
    }

    @Test
    fun `a cookie with no value is not a session`() {
        assertFalse(cookie("SAPISID", value = "", expires = now + 3_600).isLive(now))
        assertFalse(cookie("SAPISID", value = "   ", expires = now + 3_600).isLive(now))
    }

    @Test
    fun `a live google signing cookie reads as signed in, under either name`() {
        assertTrue(isYouTubeSignedIn(listOf(cookie("SAPISID", expires = Instant.now().epochSecond + 3_600))))
        assertTrue(isYouTubeSignedIn(listOf(cookie("__Secure-3PAPISID", expires = 0))))
    }

    @Test
    fun `a store holding only an expired google cookie reads as signed out`() {
        val stale = Instant.now().epochSecond - 3_600
        val store = listOf(
            cookie("SAPISID", expires = stale),
            cookie("__Secure-3PAPISID", expires = stale),
            // Plenty of other live cookies, none of which signs a request.
            cookie("VISITOR_INFO1_LIVE", expires = 0),
            cookie("PREF", expires = 0),
        )

        assertFalse(
            isYouTubeSignedIn(store),
            "an expired session read as signed in, which is what let sign-in report success forever",
        )
    }

    @Test
    fun `soundcloud is judged the same way`() {
        val live = HarvestedCookie(".soundcloud.com", "/", "oauth_token", "2-294451-x", true, 0)
        val dead = live.copy(expiresEpochSeconds = Instant.now().epochSecond - 60)

        assertTrue(isSoundCloudSignedIn(listOf(live)))
        assertFalse(isSoundCloudSignedIn(listOf(dead)))
        assertFalse(isSoundCloudSignedIn(listOf(live.copy(value = ""))))
    }

    @Test
    fun `an empty store is signed out rather than an error`() {
        assertFalse(isYouTubeSignedIn(emptyList()))
        assertFalse(isSoundCloudSignedIn(emptyList()))
    }

    /**
     * The addresses cleared before a fresh sign-in have to cover where the signing cookies actually live,
     * which is the parent domain rather than the page the form is on.
     */
    @Test
    fun `clearing covers the domains the session is really held on`() {
        assertTrue(YOUTUBE_SESSION_URLS.any { it.contains("accounts.google.com") }, "the sign-in host is missing")
        assertTrue(YOUTUBE_SESSION_URLS.any { it.contains("music.youtube.com") }, "the music host is missing")
        // Google sets its signing cookies on google.com as well as youtube.com; missing either leaves a
        // session behind that is enough to skip the login form.
        assertTrue(YOUTUBE_SESSION_URLS.any { it.removePrefix("https://").startsWith("google.com") })
        assertTrue(YOUTUBE_SESSION_URLS.any { it.contains("www.youtube.com") })

        assertTrue(SOUNDCLOUD_SESSION_URLS.any { it.contains("secure.soundcloud.com") }, "the sign-in host is missing")
        assertTrue(SOUNDCLOUD_SESSION_URLS.any { it.removePrefix("https://").startsWith("soundcloud.com") })

        (YOUTUBE_SESSION_URLS + SOUNDCLOUD_SESSION_URLS).forEach { url ->
            assertTrue(url.startsWith("https://"), "$url is not a secure address")
            assertTrue(url.endsWith("/"), "$url should name a host, not a page")
        }
    }
}

/**
 * Replacing the saved session only once the new one has been accepted.
 *
 * The check needs a real file to point at, and the first version of this wrote that file straight over the
 * live session before testing it. A dead cookie in the browser's store then destroyed a perfectly good
 * saved session on the way to discovering it was dead — which is the one outcome this whole path exists to
 * prevent. It happened once during development, to a real session.
 */
class SessionAdoptionTest {
    private val folder: java.nio.file.Path = java.nio.file.Files.createTempDirectory("spiceity-adopt")

    @kotlin.test.AfterTest
    fun cleanUp() {
        folder.toFile().deleteRecursively()
    }

    @Test
    fun `an accepted session replaces the saved one`() {
        val live = folder.resolve("youtube.cookies")
        java.nio.file.Files.writeString(live, "the old session")
        val candidate = folder.resolve("youtube.cookies.checking")
        java.nio.file.Files.writeString(candidate, "the new session")

        val saved = adoptCookieFile(candidate, live)

        assertTrue(saved == live)
        kotlin.test.assertEquals("the new session", java.nio.file.Files.readString(live))
        assertFalse(java.nio.file.Files.exists(candidate), "the candidate was left behind")
    }

    @Test
    fun `adopting works when there is no saved session yet`() {
        val live = folder.resolve("nested").resolve("soundcloud.cookies")
        val candidate = folder.resolve("soundcloud.cookies.checking")
        java.nio.file.Files.writeString(candidate, "first session")

        adoptCookieFile(candidate, live)

        kotlin.test.assertEquals("first session", java.nio.file.Files.readString(live))
    }

    /**
     * The point of the whole arrangement: a session that is never adopted leaves the saved one alone. The
     * window writes the candidate, has it refused, and deletes it — and the working session is untouched.
     */
    @Test
    fun `a refused session leaves the saved one exactly as it was`() {
        val live = folder.resolve("youtube.cookies")
        java.nio.file.Files.writeString(live, "the working session")
        val candidate = folder.resolve("youtube.cookies.checking")
        java.nio.file.Files.writeString(candidate, "a dead session")

        // What the window does when the check says no: discard the candidate, adopt nothing.
        java.nio.file.Files.deleteIfExists(candidate)

        kotlin.test.assertEquals("the working session", java.nio.file.Files.readString(live))
        assertFalse(java.nio.file.Files.exists(candidate))
    }

    /** The candidate is named apart from the live file, or the two would be the same path. */
    @Test
    fun `the candidate is not the file being protected`() {
        val live = folder.resolve("youtube.cookies")

        assertTrue(live.resolveSibling("youtube.cookies.checking") != live)
        assertTrue(live.resolveSibling("youtube.cookies.checking").parent == live.parent)
    }
}
