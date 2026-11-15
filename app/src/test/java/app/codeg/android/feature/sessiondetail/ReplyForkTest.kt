package app.codeg.android.feature.sessiondetail

import app.codeg.android.core.model.AgentType
import app.codeg.android.core.model.ContentBlock
import app.codeg.android.core.model.ConversationDetail
import app.codeg.android.core.model.ConversationStatus
import app.codeg.android.core.model.ConversationSummary
import app.codeg.android.core.model.MessageTurn
import app.codeg.android.core.model.TurnRole
import app.codeg.android.core.network.ApiError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Where a reply may fork, and when a read shows the fork (ported from iOS `ReplyForkTests`). */
class ReplyForkTest {

    private val claude = AgentType.CLAUDE_CODE

    @Test
    fun `only Claude Code, Codex and DeepSeek fork at a chosen message`() {
        assertTrue(ReplyFork.canForkAtTurn(AgentType.CLAUDE_CODE))
        assertTrue(ReplyFork.canForkAtTurn(AgentType.CODEX))
        assertTrue(ReplyFork.canForkAtTurn(AgentType.DEEPSEEK))
        assertFalse(ReplyFork.canForkAtTurn(AgentType.GEMINI))
        assertFalse(ReplyFork.canForkAtTurn(AgentType.OPEN_CODE))
        assertFalse(ReplyFork.canForkAtTurn(AgentType.Custom("my-agent")))
        assertFalse(ReplyFork.canForkAtTurn(AgentType.Unknown("future_agent")))
    }

    @Test
    fun `mid-thread, a transcript turn with text forks at itself`() {
        assertEquals(ReplyFork.Offer("turn-4", ready = true), ReplyFork.offer("turn-4", atTail = false, hasText = true, agent = claude))
        assertEquals(ReplyFork.Offer("turn-4", ready = true), ReplyFork.offer("turn-4", atTail = false, hasText = true, agent = AgentType.CODEX))
    }

    @Test
    fun `mid-thread, a fork the backend would move to the tail is not offered`() {
        // The agent can't fork at a message at all.
        assertNull(ReplyFork.offer("turn-4", atTail = false, hasText = true, agent = AgentType.GEMINI))
        // Nothing of its own to name the point by.
        assertNull(ReplyFork.offer("turn-4", atTail = false, hasText = false, agent = claude))
    }

    @Test
    fun `mid-thread, a reply folded in here waits for its transcript name`() {
        assertEquals(ReplyFork.Offer("live-1", ready = false), ReplyFork.offer("live-1", atTail = false, hasText = true, agent = claude))
        assertEquals(ReplyFork.Offer("bg-1-0", ready = false), ReplyFork.offer("bg-1-0", atTail = false, hasText = true, agent = claude))
    }

    @Test
    fun `at the tail every reply forks, naming only what the backend can look up`() {
        assertEquals(ReplyFork.Offer("turn-9", ready = true), ReplyFork.offer("turn-9", atTail = true, hasText = false, agent = claude))
        assertEquals(ReplyFork.Offer(null, ready = true), ReplyFork.offer("live-1", atTail = true, hasText = true, agent = claude))
        assertEquals(ReplyFork.Offer(null, ready = true), ReplyFork.offer("turn-9", atTail = true, hasText = true, agent = AgentType.GEMINI))
        assertNull(ReplyFork.offer(null, atTail = true, hasText = true, agent = claude))
    }

    @Test
    fun `a fork point is named by the turn's text blocks, not its tool calls or thoughts`() {
        fun turn(vararg blocks: ContentBlock) = MessageTurn("t", TurnRole.ASSISTANT, blocks.toList(), Instant.EPOCH)
        assertTrue(ReplyFork.hasForkableText(turn(ContentBlock.Text("done"))))
        assertFalse(ReplyFork.hasForkableText(turn(ContentBlock.Text("  \n"), ContentBlock.Thinking("hm"))))
        assertFalse(ReplyFork.hasForkableText(turn(ContentBlock.ToolUse("c", "Bash", "{}"), ContentBlock.ToolResult("c", "ok", false))))
    }

    private fun detail(externalId: String?, turns: Int, total: Int? = null) = ConversationDetail(
        summary = ConversationSummary(
            id = 7, folderId = 1, agentType = claude, status = ConversationStatus.COMPLETED,
            externalId = externalId, createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
        ),
        turns = List(turns) { MessageTurn("turn-$it", TurnRole.ASSISTANT, listOf(ContentBlock.Text("x")), Instant.EPOCH) },
        turnsTotal = total,
    )

    @Test
    fun `a read shows the fork once the row points at it and its transcript is there`() {
        assertTrue(ReplyFork.isApplied(detail("s2", turns = 2), "s2"))
        // The row still points at the old session.
        assertFalse(ReplyFork.isApplied(detail("s1", turns = 2), "s2"))
        // The agent hasn't written the forked transcript yet.
        assertFalse(ReplyFork.isApplied(detail("s2", turns = 0), "s2"))
        // A window read counts the whole transcript.
        assertTrue(ReplyFork.isApplied(detail("s2", turns = 0, total = 5), "s2"))
        assertFalse(ReplyFork.isApplied(detail("s2", turns = 3, total = 0), "s2"))
    }

    @Test
    fun `an agent with no session fork says so for good`() {
        assertTrue(ReplyFork.isUnsupported(ApiError.Server(500, "task_execution_failed", "ACP protocol error: This agent does not support session/fork")))
        assertFalse(ReplyFork.isUnsupported(ApiError.Server(500, "task_execution_failed", "connection not found: c1")))
        assertFalse(ReplyFork.isUnsupported(ApiError.TurnInProgress))
    }

    @Test
    fun `reads after a fork back off for about six seconds`() {
        assertEquals(6, ReplyFork.RELOAD_ATTEMPTS)
        assertEquals(listOf(400L, 800L, 1200L, 1600L, 2000L), (0 until ReplyFork.RELOAD_ATTEMPTS - 1).map(ReplyFork::reloadDelayMs))
    }
}
