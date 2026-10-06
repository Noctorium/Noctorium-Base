package app.noctorium.core

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What the hearts become after a service's likes are read: the listing, except where this device has just
 * changed something the listing does not show yet, and never less than was there on the strength of a part.
 */
class MergeLikesTest {
    private val youTube = setOf("yt:abc")

    @Test
    fun `a complete listing is the account's likes, and leaves the other service alone`() {
        val merged = mergeLikes(youTube + setOf("sc:1", "sc:2"), "sc:", setOf("sc:2", "sc:3"), complete = true, recent = emptyMap())
        assertEquals(setOf("yt:abc", "sc:2", "sc:3"), merged)
    }

    @Test
    fun `a like just made survives a listing that has not caught up with it`() {
        // Exactly the report: liked on the phone, SoundCloud kept it, and the next read came back without it.
        val merged = mergeLikes(
            current = setOf("sc:tycho/awake", "sc:1"),
            prefix = "sc:",
            listed = setOf("sc:1"),
            complete = true,
            recent = mapOf("sc:tycho/awake" to true),
        )
        assertEquals(setOf("sc:tycho/awake", "sc:1"), merged)
    }

    @Test
    fun `an unlike just made is not undone by a listing that still has it`() {
        val merged = mergeLikes(setOf("sc:1"), "sc:", setOf("sc:1", "sc:2"), complete = true, recent = mapOf("sc:1" to false))
        assertEquals(setOf("sc:2"), merged)
    }

    @Test
    fun `a listing cut short only ever adds`() {
        // A page failed after the first: the likes on the pages never read keep their hearts.
        val merged = mergeLikes(setOf("sc:old-1", "sc:old-2"), "sc:", setOf("sc:new"), complete = false, recent = emptyMap())
        assertEquals(setOf("sc:old-1", "sc:old-2", "sc:new"), merged)
    }

    @Test
    fun `a recent write for one service says nothing about the other`() {
        val merged = mergeLikes(setOf("yt:abc"), "yt:", setOf("yt:abc"), complete = true, recent = mapOf("sc:1" to true))
        assertEquals(setOf("yt:abc"), merged)
    }
}
