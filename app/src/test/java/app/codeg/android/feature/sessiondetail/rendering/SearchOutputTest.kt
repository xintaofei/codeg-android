package app.codeg.android.feature.sessiondetail.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Grouping search stdout, and — more importantly — refusing to.
 *
 * Search output is agent- and flag-dependent (`rg -c` counts, `--heading`
 * groups, `--json`, "Found N files" preambles), and a half-parsed result reads
 * far worse than the raw text. Every "returns null" case below is a shape the
 * card must keep showing verbatim.
 */
class SearchOutputTest {

    @Test
    fun `grep line matches group per file`() {
        val parsed = requireNotNull(
            SearchOutput.parse(
                """
                src/a.kt:12:  val x = 1
                src/a.kt:40:  val y = 2
                src/b.kt:7:fun main()
                """.trimIndent(),
            ),
        )

        assertEquals(SearchOutput.Mode.MATCHES, parsed.mode)
        assertEquals(3, parsed.matchCount)
        assertEquals(2, parsed.fileCount)
        assertEquals(listOf("src/a.kt", "src/b.kt"), parsed.groups.map { it.path })
        assertEquals(listOf(12, 40), parsed.groups[0].lines.map { it.line })
        assertEquals("fun main()", parsed.groups[1].lines[0].text)
    }

    @Test
    fun `a bare path list is the files shape`() {
        val parsed = requireNotNull(SearchOutput.parse("src/a.kt\nsrc/b.kt\nREADME.md"))

        assertEquals(SearchOutput.Mode.FILES, parsed.mode)
        assertEquals(3, parsed.fileCount)
        assertEquals(0, parsed.matchCount)
        assertEquals(listOf("src/a.kt", "src/b.kt", "README.md"), parsed.groups.map { it.path })
    }

    @Test
    fun `a path must look like a path, not a number`() {
        // Timestamps and `line:col` diagnostics have the same `:<digits>:` shape
        // as a grep hit and are the reason this is not just the regex.
        assertNull(SearchOutput.parse("12:34:56 done\n13:00:01 done"))
        assertNull(SearchOutput.parse("1.2.3\n4.5.6"))
        assertNull(SearchOutput.parse("""{"type":"match"}""" + "\n" + """{"type":"end"}"""))
    }

    @Test
    fun `output that mostly does not fit is left as text`() {
        // `rg --count` has no content column, so it is neither shape.
        assertNull(SearchOutput.parse("a.ts:3\nb.ts:11"))
        // `rg --heading` puts the path on its own line and drops it from the rest.
        assertNull(SearchOutput.parse("src/a.ts\n12:  const x = 1\n40:  y"))
        assertNull(SearchOutput.parse("Searching…\nnothing here\nstill nothing"))
    }

    @Test
    fun `a counted path WITH a directory is grouped, as it is on the web`() {
        // Known shared limitation, pinned rather than silently diverged from:
        // `rg --count src/` prints `src/a.kt:3`, whose slash satisfies the
        // bare-path rule the extension rule would have rejected, so the counts
        // are shown as if they were file names. Fixing it belongs upstream, in
        // the logic both clients read from.
        val parsed = requireNotNull(SearchOutput.parse("src/a.kt:3\nsrc/b.kt:1"))

        assertEquals(SearchOutput.Mode.FILES, parsed.mode)
        assertEquals(listOf("src/a.kt:3", "src/b.kt:1"), parsed.groups.map { it.path })
    }

    @Test
    fun `a stray warning rides along as a note`() {
        val parsed = requireNotNull(
            SearchOutput.parse(
                """
                src/a.kt:1:hit
                src/a.kt:2:hit
                src/a.kt:3:hit
                src/a.kt:4:hit
                rg: nope: No such file or directory
                """.trimIndent(),
            ),
        )

        assertEquals(4, parsed.matchCount)
        assertEquals(listOf("rg: nope: No such file or directory"), parsed.notes)
    }

    @Test
    fun `ripgrep context separators are skipped, not counted`() {
        val parsed = requireNotNull(SearchOutput.parse("src/a.kt:1:hit\n--\nsrc/a.kt:9:hit"))

        assertEquals(2, parsed.matchCount)
        assertEquals(emptyList<String>(), parsed.notes)
    }

    @Test
    fun `rows are capped and the remainder counted`() {
        val text = (1..10).joinToString("\n") { "src/a.kt:$it:hit" }
        val parsed = requireNotNull(SearchOutput.parse(text, maxRows = 4))

        assertEquals(4, parsed.groups.sumOf { it.lines.size })
        assertEquals(6, parsed.hiddenCount)
        // The counts stay authoritative: they describe the whole output.
        assertEquals(10, parsed.matchCount)
    }

    @Test
    fun `notes are capped too, and what is dropped is counted`() {
        // A search over a tree with unreadable directories pairs real matches
        // with one warning per directory. The ratio gate admits a fifth of
        // them, and the card composes every note eagerly in one transcript
        // item — so an uncapped list is thousands of live Text nodes.
        val text = (1..400).joinToString("\n") { "src/a.kt:$it:hit" } +
            "\n" + (1..90).joinToString("\n") { "rg: dir$it: Permission denied" }
        val parsed = requireNotNull(SearchOutput.parse(text))

        assertEquals(20, parsed.notes.size)
        assertEquals("rg: dir1: Permission denied", parsed.notes.first())
        // 200 rows past the cap, plus 70 notes past theirs.
        assertEquals(270, parsed.hiddenCount)
        assertEquals(400, parsed.matchCount)
    }

    @Test
    fun `blank output is not a result set`() {
        assertNull(SearchOutput.parse(""))
        assertNull(SearchOutput.parse("   \n\n "))
    }
}
