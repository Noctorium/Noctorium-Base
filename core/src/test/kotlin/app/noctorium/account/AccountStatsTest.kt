package app.noctorium.account

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AccountStatsTest {

    @Test
    fun `the service's answer is read whole, and what this version does not know is passed over`() {
        val stats = AccountStats.parse(
            """
            {"range":"30d","from":null,"streams":3,"uniqueTracks":2,"artists":2,"msPlayed":600000,"hours":0.2,
             "byProvider":[{"provider":"YOUTUBE_MUSIC","streams":1,"hours":0.1},{"provider":"YOUTUBE_VIDEO","streams":1,"hours":0},
                           {"provider":"SOUNDCLOUD","streams":1,"hours":0.1}],
             "topTracks":[{"title":"A","artist":"B","provider":"SOUNDCLOUD","streams":2,"minutes":7}],
             "topArtists":[{"artist":"B","streams":2,"minutes":7,"tracks":1}],
             "timeline":{"bucket":"day","points":[{"start":"2026-11-27","streams":3,"minutes":10}]},
             "hourly":[{"hour":9,"streams":3,"minutes":10}],"weekdays":[{"day":5,"streams":3,"minutes":10}],
             "recent":[{"title":"A","artist":"B","provider":"SOUNDCLOUD","playedAt":"2026-11-27T09:00:00.000Z","msPlayed":200000}],
             "streak":{"current":4,"longest":9},"somethingNew":true}
            """,
        )!!
        assertEquals(3, stats.streams)
        assertEquals(AccountStats.Streak(4, 9), stats.streak)
        assertEquals("2026-11-27", stats.timeline.points.single().start)
        // The two YouTubes count as one.
        assertEquals(listOf("YouTube Music" to 2, "SoundCloud" to 1), stats.services)
    }

    @Test
    fun `an answer that is not statistics is none`() {
        assertNull(AccountStats.parse("not json"))
    }
}
