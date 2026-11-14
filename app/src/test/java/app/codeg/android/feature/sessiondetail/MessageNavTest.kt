package app.codeg.android.feature.sessiondetail

import app.codeg.android.core.model.ContentBlock
import app.codeg.android.core.model.ConversationOutline
import app.codeg.android.core.model.MessageTurn
import app.codeg.android.core.model.TurnRole
import app.codeg.android.core.model.TurnWindowState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class MessageNavTest {

    private fun user(id: String, text: String?, secs: Long = 0) =
        MessageTurn(id, TurnRole.USER, listOfNotNull(text?.let { ContentBlock.Text(it) }), Instant.ofEpochSecond(secs))

    private fun reply(id: String, secs: Long = 0) = MessageTurn(id, TurnRole.ASSISTANT, emptyList(), Instant.ofEpochSecond(secs))

    private val history = listOf(user("turn-0", "first", 1), reply("turn-1", 2), user("turn-2", "second", 3), reply("turn-3", 4))

    private fun window(offset: Int, turns: List<MessageTurn> = history) =
        TurnWindowState(offset = offset, total = 10, prefixHash = extendPrefixFingerprint(FNV_SEED_HEX, turns.take(offset))!!)

    @Test
    fun `an outline stands for the window's prefix only when it fingerprints to it`() {
        val exact = ConversationOutline(history, 10, extendPrefixFingerprint(FNV_SEED_HEX, history)!!)
        assertEquals(4, MessageNav.outlinePrefix(exact, window(4))?.size)
        // It reaches further than the window now does (pages came in since): the head is checked.
        assertEquals(2, MessageNav.outlinePrefix(exact, window(2))?.size)
        // It falls short of the window, or the history was rewritten under it.
        assertNull(MessageNav.outlinePrefix(exact.copy(turns = history.take(1)), window(2)))
        val rewritten = window(2, listOf(user("turn-0", "first", 9), reply("turn-1", 2)))
        assertNull(MessageNav.outlinePrefix(exact, rewritten))
        assertNull(MessageNav.outlinePrefix(exact.copy(prefixHash = "0000000000000000"), window(4)))
    }

    @Test
    fun `one entry per message, numbered across the conversation, the ones above the window placed`() {
        val loaded = listOf(user("turn-2", "second"), reply("turn-3"), user("p1", "  a   new\\nline  "))
        val entries = MessageNav.entries(above = history.take(2), loaded = loaded)
        assertEquals(listOf("turn-0", "turn-2", "p1"), entries.map { it.turnId })
        assertEquals(listOf(1, 2, 3), entries.map { it.ordinal })
        assertEquals(listOf(0, null, null), entries.map { it.turnIndex })
        assertNotNull(entries.last().label)
    }

    @Test
    fun `a label is the first text cut to eighty, and a message with none has none`() {
        assertEquals("x".repeat(77) + "...", MessageNav.label(user("u", "x".repeat(81))))
        assertEquals("x".repeat(80), MessageNav.label(user("u", "x".repeat(80))))
        assertEquals("two words", MessageNav.label(user("u", " two\n words ")))
        assertNull(MessageNav.label(user("u", null)))
    }

    @Test
    fun `a turn in both lists counts once`() {
        val entries = MessageNav.entries(above = history, loaded = history)
        assertEquals(2, entries.size)
        assertEquals(listOf(0, 2), entries.map { it.turnIndex })
    }
}
