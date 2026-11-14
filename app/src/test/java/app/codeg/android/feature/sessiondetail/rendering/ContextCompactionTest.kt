package app.codeg.android.feature.sessiondetail.rendering

import app.codeg.android.core.model.ContentBlock
import app.codeg.android.core.model.MessageTurn
import app.codeg.android.core.model.TurnRole
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Locks down compaction detection and its "never a tool card" rendering. */
class ContextCompactionTest {

    private fun meta(raw: String): JsonObject = Json.parseToJsonElement(raw) as JsonObject

    @Test
    fun `matches only a real boolean true flag`() {
        assertTrue(ContextCompaction.matches(meta("""{"contextCompaction":true}""")))
        assertFalse(ContextCompaction.matches(meta("""{"contextCompaction":false}""")))
        // The web tests `=== true`, so a stringly-typed flag does not count.
        assertFalse(ContextCompaction.matches(meta("""{"contextCompaction":"true"}""")))
        assertFalse(ContextCompaction.matches(meta("""{"x.ai/tool":{"kind":"bash"}}""")))
        assertFalse(ContextCompaction.matches(null))
    }

    @Test
    fun `token counts are read only when finite and non-negative`() {
        val (before, after) = ContextCompaction.tokens(meta("""{"tokensBefore":120000,"tokensAfter":38000}"""))
        assertEquals(120000, before)
        assertEquals(38000, after)
        // codex sends no counts at all — the divider falls back to the plain label.
        assertEquals(null to null, ContextCompaction.tokens(meta("""{"contextCompaction":true}""")))
        assertNull(ContextCompaction.tokens(meta("""{"tokensBefore":-1}""")).first)
        assertNull(ContextCompaction.tokens(meta("""{"tokensBefore":"lots"}""")).first)
    }

    @Test
    fun `a compaction renders as a divider part, never a tool card or group member`() {
        val turn = MessageTurn(
            id = "turn-1",
            role = TurnRole.ASSISTANT,
            blocks = listOf(
                ContentBlock.ToolUse("t1", "Read", null),
                ContentBlock.ToolResult("t1", "ok", false),
                ContentBlock.ToolUse("t2", "Context compacted", null, meta("""{"contextCompaction":true,"tokensBefore":9,"tokensAfter":4}""")),
                ContentBlock.ToolResult("t2", "", false),
                ContentBlock.ToolUse("t3", "Grep", null),
                ContentBlock.ToolResult("t3", "ok", false),
            ),
            timestamp = Instant.EPOCH,
        )
        val parts = MessageRender.adaptTurn(turn)
        // Read | compaction | Grep — the compaction splits the run, so neither
        // neighbouring tool is swept into a group with it.
        assertEquals(3, parts.size)
        assertTrue(parts[0] is RenderPart.Tool)
        val compaction = parts[1] as RenderPart.Compaction
        assertEquals(9, compaction.before)
        assertEquals(4, compaction.after)
        assertFalse(compaction.running)
        assertTrue(parts[2] is RenderPart.Tool)
        assertTrue(parts.none { it is RenderPart.ToolGroup })
    }

    @Test
    fun `a compaction with no result renders as running only while its round is in flight`() {
        val turn = MessageTurn(
            id = "turn-2",
            role = TurnRole.ASSISTANT,
            blocks = listOf(ContentBlock.ToolUse("t1", "Context compacting", null, meta("""{"contextCompaction":true}"""))),
            timestamp = Instant.EPOCH,
        )
        val running = MessageRender.adaptTurn(turn, runningToolIds = setOf("t1")).single() as RenderPart.Compaction
        assertTrue(running.running)
        assertNull(running.before)
        // In settled history a missing result means the round ended, not that it still runs.
        val settled = MessageRender.adaptTurn(turn).single() as RenderPart.Compaction
        assertFalse(settled.running)
    }
}
