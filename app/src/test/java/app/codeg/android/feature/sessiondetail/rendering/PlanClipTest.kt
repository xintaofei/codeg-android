package app.codeg.android.feature.sessiondetail.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a collapsed plan card shows. The caller reads "there is something to
 * expand" off the returned prefix being shorter than its input, so a clip that
 * gives back the whole string is also the signal that no toggle belongs there.
 */
class PlanClipTest {

    @Test
    fun `a plan that already fits comes back whole`() {
        val short = (1..5).joinToString("\n") { "- step $it" }
        assertEquals(short, clipPlanSource(short))
    }

    @Test
    fun `a long list is cut on a line boundary`() {
        val long = (1..60).joinToString("\n") { "- step $it" }
        val clipped = clipPlanSource(long)

        assertEquals(24, clipped.split("\n").size)
        assertEquals("- step 24", clipped.split("\n").last())
        assertTrue(clipped.length < long.length)
    }

    @Test
    fun `a one-paragraph plan is cut by characters, not left whole`() {
        // The case source lines alone miss: no newline anywhere, so the line
        // budget never trips, and the card renders a screenful with no toggle.
        val paragraph = "We will refactor the parser. ".repeat(200)
        val clipped = clipPlanSource(paragraph)

        assertTrue("clipped=${clipped.length}", clipped.length <= 1500)
        assertTrue(clipped.length < paragraph.length)
        assertTrue(paragraph.startsWith(clipped))
    }

    @Test
    fun `a few very long lines are cut by characters at the last break`() {
        val lines = (1..5).joinToString("\n") { "- " + "detail ".repeat(80) }
        val clipped = clipPlanSource(lines)

        assertTrue(clipped.length <= 1500)
        // Still whole lines: the character budget backs up to a line break.
        assertTrue(clipped.split("\n").all { it.startsWith("- detail") })
    }

    @Test
    fun `trailing blank lines do not invent a toggle`() {
        val padded = "- one\n- two" + "\n".repeat(40)
        assertEquals(padded.trimEnd(), clipPlanSource(padded.trimEnd()))
    }

    @Test
    fun `the character budget never cuts an emoji in half`() {
        // The budget counts UTF-16 units and an emoji is two of them; landing
        // between the pair leaves a lone surrogate, drawn as the replacement
        // glyph.
        val padded = "a".repeat(1499) + "\uD83D\uDE00rest"
        val clipped = clipPlanSource(padded)

        assertTrue(clipped.isNotEmpty())
        assertFalse(clipped.last().isHighSurrogate())
        assertEquals(clipped, String(clipped.toCharArray()))
    }
}
