package app.noctorium.update

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * When an update is allowed to interrupt somebody, and when it has to wait to be asked.
 *
 * The rule exists because the check runs at every launch. Anything that pops up on a found release and
 * nothing else would reappear every single time it was declined, which is the behaviour people learn to
 * click away without reading -- and then the one release that matters gets clicked away too.
 */
class UpdatePromptTest {

    private fun version(text: String) = Version.parse(text)!!

    @Test
    fun `a new release nobody has turned down interrupts`() {
        assertTrue(shouldPromptAbout(version("0.4.9"), quietly = true, dismissedVersion = ""))
    }

    @Test
    fun `the version that was turned down does not come back`() {
        assertFalse(shouldPromptAbout(version("0.4.9"), quietly = true, dismissedVersion = "0.4.9"))
    }

    /** Declining one release is not a standing refusal to hear about any of them. */
    @Test
    fun `the next release after a refused one asks again`() {
        assertTrue(shouldPromptAbout(version("0.5.0"), quietly = true, dismissedVersion = "0.4.9"))
    }

    /**
     * Pressing "Check now" in settings is already a question with an answer on screen. A dialog on top
     * of that is Noctorium telling somebody what they just asked it.
     */
    @Test
    fun `a check somebody asked for is answered where they asked it`() {
        assertFalse(shouldPromptAbout(version("0.4.9"), quietly = false, dismissedVersion = ""))
    }
}
