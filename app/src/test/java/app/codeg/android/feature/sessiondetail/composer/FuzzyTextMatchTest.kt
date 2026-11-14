package app.codeg.android.feature.sessiondetail.composer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Web `src/lib/fuzzy-text-match.test.ts`, ported case for case (via iOS). */
class FuzzyTextMatchTest {
    private data class Item(val id: Int = 0, val name: String, val tag: String? = null)

    private val commands = listOf(
        Item(name = "bmad-help", tag = "Show BMAD help"),
        Item(name = "bmad-create-story", tag = "Create a story"),
        Item(name = "clear", tag = "Clear the chat"),
    )

    @Test
    fun `subsequence matches contiguous and gapped`() {
        assertEquals(0, FuzzyTextMatch.subsequenceFirstIndex("bmadhelp", "bmad-help"))
        assertEquals(0, FuzzyTextMatch.subsequenceFirstIndex("bmhp", "bmad-help"))
        assertEquals(5, FuzzyTextMatch.subsequenceFirstIndex("help", "bmad-help"))
        assertEquals(-1, FuzzyTextMatch.subsequenceFirstIndex("bmadx", "bmad-help"))
    }

    @Test
    fun `tiers order exact, prefix, substring, subsequence`() {
        val exact = req(FuzzyTextMatch.score("help", "help"))
        val prefix = req(FuzzyTextMatch.score("help", "help-me"))
        val substring = req(FuzzyTextMatch.score("help", "bmad-help"))
        val subsequence = req(FuzzyTextMatch.score("bmhp", "bmad-help"))
        assertTrue(exact > prefix)
        assertTrue(prefix > substring)
        val gapped = req(FuzzyTextMatch.score("bmadh", "bmad-help"))
        val contiguous = req(FuzzyTextMatch.score("bmad-", "bmad-help"))
        assertTrue(gapped < contiguous)
        assertTrue(subsequence > 0)
        assertNull(FuzzyTextMatch.score("zzz", "bmad-help"))
    }

    @Test
    fun `an empty query keeps everything in order`() {
        assertEquals(commands, FuzzyTextMatch.rank("", commands, { it.name }))
        assertEquals(commands, FuzzyTextMatch.rank("   ", commands, { it.name }))
    }

    @Test
    fun `hyphenated names match by subsequence`() {
        assertEquals(listOf("bmad-help"), FuzzyTextMatch.rank("bmadhelp", commands, { it.name }).map { it.name })
        assertTrue("bmad-help" in FuzzyTextMatch.rank("bmhp", commands, { it.name }).map { it.name })
    }

    @Test
    fun `exact beats prefix beats subsequence, and shorter wins ties`() {
        val items = listOf(Item(name = "batch"), Item(name = "bh-tool"), Item(name = "bh"), Item(name = "bmad-help"))
        assertEquals(listOf("bh", "bh-tool", "batch", "bmad-help"), FuzzyTextMatch.rank("bh", items, { it.name }).map { it.name })
    }

    @Test
    fun `any primary match outranks any secondary match`() {
        val items = listOf(Item(name = "zzz", tag = "bh"), Item(name = "batch", tag = "zzz"))
        assertEquals(listOf("batch", "zzz"), FuzzyTextMatch.rank("bh", items, { it.name }, { it.tag }).map { it.name })
    }

    @Test
    fun `equal scores keep their input order`() {
        val items = listOf(Item(1, "abc"), Item(2, "abc"), Item(3, "abc"))
        assertEquals(listOf(1, 2, 3), FuzzyTextMatch.rank("abc", items, { it.name }).map { it.id })
    }

    @Test
    fun `an empty primary never matches`() {
        val items = listOf(Item(name = ""), Item(name = "bmad-help"))
        assertEquals(listOf("bmad-help"), FuzzyTextMatch.rank("bmad", items, { it.name }).map { it.name })
    }

    @Test
    fun `the secondary field falls in below primary hits`() {
        val items = listOf(Item(name = "clear", tag = "bmad helpers"), Item(name = "bmad-help", tag = "other"))
        val ranked = FuzzyTextMatch.rank("bmad", items, { it.name }, { it.tag })
        assertEquals("bmad-help", ranked.first().name)
        assertTrue("clear" in ranked.map { it.name })
    }

    @Test
    fun `matching is case-insensitive`() {
        assertEquals(listOf("bmad-help"), FuzzyTextMatch.rank("BMADHELP", commands, { it.name }).map { it.name })
    }

    @Test
    fun `positions count UTF-16 units`() {
        // An emoji is two units: a substring after it starts at 2, like JS.
        val after = req(FuzzyTextMatch.score("ab", "😀ab"))
        val atStart = req(FuzzyTextMatch.score("ab", "xab"))
        assertTrue("two units in beats one unit in", after < atStart)
    }

    private fun req(score: Long?): Long {
        assertNotNull(score)
        return score!!
    }
}
