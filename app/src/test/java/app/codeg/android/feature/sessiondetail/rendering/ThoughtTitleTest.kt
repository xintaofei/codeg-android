package app.codeg.android.feature.sessiondetail.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ported from the web's `src/lib/activity/thought.test.ts`. */
class ThoughtTitleTest {

    private val summary = listOf(
        "**Summarizing log status**",
        "",
        "I need to gather the logs.",
        "",
        "**Checking the Makefile**",
        "",
        "The docs target is missing.",
    ).joinToString("\n")

    @Test
    fun `uses the first heading once the thought is done`() {
        assertEquals("Summarizing log status", ThoughtTitle.title(summary, streaming = false))
    }

    @Test
    fun `uses the newest heading while it is still being written`() {
        assertEquals("Checking the Makefile", ThoughtTitle.title(summary, streaming = true))
    }

    @Test
    fun `accepts ATX headings and ignores fenced code`() {
        assertEquals("Real title", ThoughtTitle.title("```\n# not a title\n```\n## Real title\nbody", false))
    }

    @Test
    fun `falls back to the first sentence`() {
        assertEquals("Let me look at the parser first", ThoughtTitle.title("Let me look at the parser first. Then the tests.", false))
        assertEquals("foo is broken", ThoughtTitle.title("- `foo` is broken\nmore", false))
    }

    @Test
    fun `clips long titles`() {
        val title = ThoughtTitle.title("x".repeat(80), false)!!
        assertEquals(60, title.length)
        assertTrue(title.endsWith("…"))
    }

    @Test
    fun `is null for an empty thought`() {
        assertNull(ThoughtTitle.title("  \n ", false))
    }

    @Test
    fun `drops the heading the row already shows`() {
        assertEquals(
            summary.split("\n").drop(2).joinToString("\n"),
            ThoughtTitle.bodyWithoutTitle(summary, "Summarizing log status"),
        )
    }

    @Test
    fun `keeps the body when the title came from elsewhere`() {
        val content = "Plain reasoning text."
        assertEquals(content, ThoughtTitle.bodyWithoutTitle(content, "Plain reasoning text"))
    }

    // Beyond the web's cases: the JavaScript semantics the regexes spell out.

    @Test
    fun `a heading ends at the line, a CRLF's CR included`() {
        assertEquals("Planning the fix", ThoughtTitle.title("**Planning the fix**\r\nbody", false))
        assertEquals("Next steps", ThoughtTitle.title("__Next steps__:\nbody", false))
        // CJK sentence ends count where space or the end follows them, as on the web; a `.`
        // mid-word does not.
        assertEquals("先看解析器", ThoughtTitle.title("先看解析器。 然后是测试。", false))
        assertEquals("先看解析器。然后是测试", ThoughtTitle.title("先看解析器。然后是测试。", false))
        assertEquals("Read v1.2 notes", ThoughtTitle.title("Read v1.2 notes", false))
    }

    @Test
    fun `a clip never splits an astral character`() {
        // The cut falls between the emoji's two halves: it goes whole.
        assertEquals("x".repeat(58) + "…", ThoughtTitle.title("x".repeat(58) + "😀" + "y".repeat(10), false))
        // One unit earlier it fits, whole.
        assertEquals("x".repeat(57) + "😀…", ThoughtTitle.title("x".repeat(57) + "😀" + "y".repeat(10), false))
    }
}
