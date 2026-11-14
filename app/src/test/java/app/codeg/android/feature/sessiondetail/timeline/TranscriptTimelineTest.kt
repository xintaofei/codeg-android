package app.codeg.android.feature.sessiondetail.timeline

import app.codeg.android.core.model.AgentType
import app.codeg.android.core.model.ContentBlock
import app.codeg.android.core.model.MessageTurn
import app.codeg.android.core.model.TurnRole
import app.codeg.android.core.model.TurnUsage
import app.codeg.android.feature.sessiondetail.LiveSegment
import app.codeg.android.feature.sessiondetail.LiveTurnError
import app.codeg.android.feature.sessiondetail.LiveTurnBuilder
import app.codeg.android.feature.sessiondetail.LiveTurnState
import app.codeg.android.feature.sessiondetail.rendering.ToolCallState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Locks down the transcript → timeline-node flattening (merge, grouping, footers, endpoints). */
class TranscriptTimelineTest {

    private val agent = AgentType.CLAUDE_CODE

    private fun user(id: String, text: String) =
        MessageTurn(id, TurnRole.USER, listOf(ContentBlock.Text(text)), Instant.EPOCH)

    private fun assistant(id: String, blocks: List<ContentBlock>, usage: TurnUsage? = null) =
        MessageTurn(id, TurnRole.ASSISTANT, blocks, Instant.EPOCH, usage = usage)

    @Test
    fun `the rail terminates at its endpoints`() {
        val nodes = TranscriptTimeline.build(listOf(user("u1", "hi")), emptyList(), null, agent)
        assertTrue(nodes.isNotEmpty())
        assertFalse(nodes.first().connectTop)
        assertFalse(nodes.last().connectBottom)
    }

    @Test
    fun `consecutive assistant turns merge into one footer with summed usage`() {
        val turns = listOf(
            user("u1", "hi"),
            assistant("a1", listOf(ContentBlock.Text("part one")), TurnUsage(outputTokens = 10)),
            assistant("a2", listOf(ContentBlock.Text("part two")), TurnUsage(outputTokens = 5)),
        )
        val nodes = TranscriptTimeline.build(turns, emptyList(), null, agent)
        val footers = nodes.mapNotNull { it.content as? NodeContent.Footer }
        assertEquals(1, footers.size)
        assertEquals(15, footers.single().turn.usage?.total)
        // Each single-line reply is one Markdown block → one standalone AssistantBlock node.
        val textNodes = nodes.filter { it.content is NodeContent.AssistantBlock }
        assertEquals(2, textNodes.size)
        assertTrue(textNodes.all { it.rail == RailStyle.Standalone })
    }

    @Test
    fun `consecutive tools collapse into one group node`() {
        val blocks = listOf(
            ContentBlock.ToolUse("t1", "Read", """{"file_path":"a"}"""),
            ContentBlock.ToolResult("t1", "ok", false),
            ContentBlock.ToolUse("t2", "Read", """{"file_path":"b"}"""),
            ContentBlock.ToolResult("t2", "ok", false),
        )
        val nodes = TranscriptTimeline.build(listOf(user("u1", "hi"), assistant("a1", blocks)), emptyList(), null, agent)
        assertEquals(1, nodes.count { it.content is NodeContent.ToolGroup })
        assertEquals(0, nodes.count { it.content is NodeContent.Tool })
    }

    @Test
    fun `the reply footer links back to its prompting user message`() {
        val nodes = TranscriptTimeline.build(
            listOf(user("u1", "hi"), assistant("a1", listOf(ContentBlock.Text("reply")))),
            emptyList(),
            null,
            agent,
        )
        val footer = nodes.mapNotNull { it.content as? NodeContent.Footer }.single()
        assertEquals("u1", footer.questionId)
    }

    @Test
    fun `anonymous tools across replies get unique node ids`() {
        // Two replies whose first tool call has no ACP id: the synthetic fallback must be
        // turn-qualified, else both collapse to the same LazyColumn key and crash.
        val turns = listOf(
            user("u1", "a"),
            assistant(
                "a1",
                listOf(
                    ContentBlock.ToolUse(null, "Read", """{"file_path":"x"}"""),
                    ContentBlock.ToolResult(null, "ok", false),
                ),
            ),
            user("u2", "b"),
            assistant(
                "a2",
                listOf(
                    ContentBlock.ToolUse(null, "Read", """{"file_path":"y"}"""),
                    ContentBlock.ToolResult(null, "ok", false),
                ),
            ),
        )
        val ids = TranscriptTimeline.build(turns, emptyList(), null, agent).map { it.id }
        assertEquals("node ids must be unique", ids.size, ids.toSet().size)
    }

    @Test
    fun `a multi-block assistant reply splits into per-block nodes with rail head-middle-tail`() {
        val turns = listOf(
            user("u1", "hi"),
            assistant("a1", listOf(ContentBlock.Text("# Title\n\nbody paragraph\n\n- one\n- two"))),
        )
        val nodes = TranscriptTimeline.build(turns, emptyList(), null, agent)
        val blocks = nodes.filter { it.content is NodeContent.AssistantBlock }
        assertEquals(3, blocks.size)
        assertEquals(listOf(RailStyle.Head, RailStyle.Middle, RailStyle.Tail), blocks.map { it.rail })
        // First block keeps the base id (streaming continuity); the rest get #b<k>.
        assertEquals(listOf("a1#0", "a1#0#b1", "a1#0#b2"), blocks.map { it.id })
    }

    @Test
    fun `split block node ids stay unique`() {
        val turns = listOf(
            user("u1", "hi"),
            assistant("a1", listOf(ContentBlock.Text("para a\n\npara b\n\npara c"))),
        )
        val ids = TranscriptTimeline.build(turns, emptyList(), null, agent).map { it.id }
        assertEquals("node ids must be unique", ids.size, ids.toSet().size)
    }

    @Test
    fun `a streaming multi-block reply carries the caret on the last block only and ends the rail`() {
        val live = LiveTurnState(
            id = "live1",
            segments = listOf(LiveSegment.Text("t0", "intro paragraph\n\ntail paragraph")),
            livePlan = emptyList(),
            isStreaming = true,
            stopReason = null,
            error = null,
        )
        val nodes = TranscriptTimeline.build(emptyList(), emptyList(), live, agent)
        val blocks = nodes.filter { it.content is NodeContent.AssistantBlock }
        assertEquals(2, blocks.size)
        assertFalse((blocks.first().content as NodeContent.AssistantBlock).streaming)
        val last = blocks.last().content as NodeContent.AssistantBlock
        assertTrue("caret rides the final block", last.streaming)
        assertFalse("rail terminates at the streaming tail", blocks.last().connectBottom)
        assertEquals(RailStyle.Tail, blocks.last().rail)
    }

    @Test
    fun `a failed turn's node carries the evidence alongside the message`() {
        // The evidence has to reach the node as its OWN field: folded into the message it
        // would land in the one-line danger banner (and, via the same string, in the status
        // line and notices) instead of the disclosure it was bounded and redacted for.
        val live = LiveTurnState(
            id = "live1",
            segments = emptyList(),
            livePlan = emptyList(),
            isStreaming = false,
            stopReason = "empty",
            error = LiveTurnError("The agent produced no reply.", "stderr:\n  Error: 401"),
        )
        val error = TranscriptTimeline.build(emptyList(), emptyList(), live, agent)
            .map { it.content }
            .filterIsInstance<NodeContent.Error>()
            .single()

        assertEquals("The agent produced no reply.", error.message)
        assertEquals("stderr:\n  Error: 401", error.details)
    }

    @Test
    fun `an ordinary failure renders without a disclosure`() {
        val live = LiveTurnState(
            id = "live1",
            segments = listOf(LiveSegment.Text("t0", "partial")),
            livePlan = emptyList(),
            isStreaming = false,
            stopReason = null,
            error = LiveTurnError("Cancelled"),
        )
        val error = TranscriptTimeline.build(emptyList(), emptyList(), live, agent)
            .map { it.content }
            .filterIsInstance<NodeContent.Error>()
            .single()

        assertNull(error.details)
    }

    @Test
    fun `an empty system turn produces no node`() {
        val turns = listOf(
            user("u1", "hi"),
            MessageTurn("s1", TurnRole.SYSTEM, listOf(ContentBlock.Text("   ")), Instant.EPOCH),
        )
        val nodes = TranscriptTimeline.build(turns, emptyList(), null, agent)
        assertEquals(0, nodes.count { it.content is NodeContent.System })
    }

    private fun turnError(id: String, message: String) =
        MessageTurn(id, TurnRole.SYSTEM, listOf(ContentBlock.TurnError(message)), Instant.EPOCH)

    @Test
    fun `a persisted turn_error renders as an error row, not a system note`() {
        val turns = listOf(
            user("u1", "hi"),
            assistant("a1", listOf(ContentBlock.Text("working on it"))),
            turnError("s1", "  API Error: 503 overloaded "),
        )
        val nodes = TranscriptTimeline.build(turns, emptyList(), null, agent)
        assertEquals(0, nodes.count { it.content is NodeContent.System })
        val error = nodes.single { it.content is NodeContent.Error }
        assertEquals("s1#error", error.id)
        assertEquals("API Error: 503 overloaded", (error.content as NodeContent.Error).message)
        // It closes the reply it ends rather than opening a new group…
        assertFalse(error.startsGroup)
        // …and sits after that reply's footer.
        assertTrue(nodes.indexOf(error) > nodes.indexOfFirst { it.content is NodeContent.Footer })
    }

    @Test
    fun `a blank turn_error produces no node`() {
        val nodes = TranscriptTimeline.build(listOf(user("u1", "hi"), turnError("s1", " ")), emptyList(), null, agent)
        assertEquals(0, nodes.count { it.content is NodeContent.Error })
    }

    @Test
    fun `a round ends on a reply or on its failure record`() {
        assertTrue(endsRound(listOf(user("u1", "hi"), assistant("a1", listOf(ContentBlock.Text("done"))))))
        // A failed round may persist no reply at all — its turn_error is what closes it.
        assertTrue(endsRound(listOf(user("u1", "hi"), turnError("s1", "API Error"))))
        assertFalse(endsRound(listOf(user("u1", "hi"))))
        assertFalse(endsRound(listOf(user("u1", "hi"), assistant("a1", emptyList()))))
        // An ordinary system note is not a round's end.
        assertFalse(
            endsRound(
                listOf(user("u1", "hi"), MessageTurn("s1", TurnRole.SYSTEM, listOf(ContentBlock.Text("note")), Instant.EPOCH)),
            ),
        )
    }

    private fun toolStates(nodes: List<TimelineNode>) =
        nodes.flatMap {
            when (val c = it.content) {
                is NodeContent.Tool -> listOf(c.vm)
                is NodeContent.ToolGroup -> c.items
                else -> emptyList()
            }
        }.associate { it.id to it.state }

    @Test
    fun `a persisted tool without a result is settled history, not running`() {
        // An earlier round that was interrupted left a call with no result. That round
        // ended, so the call must not spin forever.
        val turns = listOf(
            user("u1", "hi"),
            assistant("a1", listOf(ContentBlock.ToolUse("t1", "Bash", """{"command":"sleep 99"}"""))),
        )
        val states = toolStates(TranscriptTimeline.build(turns, emptyList(), null, agent))
        assertEquals(mapOf("t1" to ToolCallState.DONE), states)
    }

    @Test
    fun `only the in-flight round's result-less tools run`() {
        val turns = listOf(
            user("u1", "first"),
            assistant("a1", listOf(ContentBlock.ToolUse("old", "Bash", """{"command":"a"}"""))),
            user("u2", "second"),
            assistant(
                "a2",
                listOf(
                    ContentBlock.ToolUse("done", "Bash", """{"command":"b"}"""),
                    ContentBlock.ToolResult("done", "ok", false),
                    ContentBlock.ToolUse("live", "Bash", """{"command":"c"}"""),
                ),
            ),
        )
        val states = toolStates(TranscriptTimeline.build(turns, emptyList(), null, agent, inFlightUserTurnId = "u2"))
        assertEquals(ToolCallState.DONE, states["old"])
        assertEquals(ToolCallState.DONE, states["done"])
        assertEquals(ToolCallState.RUNNING, states["live"])
    }

    @Test
    fun `the in-flight reply is hidden from its prompt on, past mid-turn user messages`() {
        // The round runs past its prompt: a mid-turn user message, then more assistant turns.
        val turns = listOf(
            user("u1", "earlier"),
            assistant("a0", listOf(ContentBlock.Text("earlier reply"))),
            user("u2", "prompt"),
            assistant("a1", listOf(ContentBlock.Text("partial one"))),
            user("u3", "steer"),
            assistant("a2", listOf(ContentBlock.Text("partial two"))),
        )
        assertEquals(
            listOf("u1", "a0", "u2", "u3"),
            turns.hideInFlightReply("u2").map { it.id },
        )
        // Only the trailing run would have been hidden before, leaving "partial one" doubled.
        assertEquals(listOf("u1", "a0", "u2", "a1", "u3"), turns.dropTrailingAssistantRun().map { it.id })
        // An id this window doesn't contain falls back to the trailing run.
        assertEquals(turns.dropTrailingAssistantRun(), turns.hideInFlightReply("gone"))
        assertEquals(turns.dropTrailingAssistantRun(), turns.hideInFlightReply(null))
    }

    @Test
    fun `local turns follow the server window, untouched by the in-flight hiding`() {
        val nodes = TranscriptTimeline.buildPersisted(
            listOf(user("u1", "q"), assistant("a1", listOf(ContentBlock.Text("partial")))),
            emptyList(),
            agent,
            hideTrailingAssistantReply = true,
            inFlightUserTurnId = "u1",
            localTurns = listOf(assistant("live-1", listOf(ContentBlock.Text("whole")))),
        )
        assertTrue(nodes.none { it.id.startsWith("a1") })
        assertTrue(nodes.any { it.id.startsWith("live-1") })
    }

    @Test
    fun `a note steered into the live reply splits it around the user's message`() {
        val b = LiveTurnBuilder("live-1")
        b.appendText("before")
        b.appendSteer("n1", "also do this")
        b.appendText("after")
        val nodes = TranscriptTimeline.withLive(emptyList(), b.snapshot(), agent)
        val steerAt = nodes.indexOfFirst { it.id == "steer-n1" }
        assertTrue(nodes[steerAt].content is NodeContent.User)
        assertTrue(nodes.take(steerAt).all { it.id.startsWith("live-1#") })
        // The part after takes the id its settled turn will have.
        assertTrue(nodes.drop(steerAt + 1).all { it.id.startsWith("live-1-1#") })
    }

    @Test
    fun `a note steered in with nothing after it yet shows the agent working`() {
        val b = LiveTurnBuilder("live-1")
        b.appendText("before")
        b.appendSteer("n1", "also do this")
        val nodes = TranscriptTimeline.withLive(emptyList(), b.snapshot(), agent)
        assertEquals(NodeContent.Thinking, nodes.last().content)
    }
}
