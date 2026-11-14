package app.codeg.android.feature.sessiondetail.timeline

import app.codeg.android.core.model.AgentType
import app.codeg.android.core.model.ContentBlock
import app.codeg.android.core.model.MessageTurn
import app.codeg.android.core.model.PlanEntry
import app.codeg.android.core.model.TurnRole
import app.codeg.android.core.model.TurnUsage
import app.codeg.android.feature.sessiondetail.LiveSegment
import app.codeg.android.feature.sessiondetail.LiveTurnError
import app.codeg.android.feature.sessiondetail.LiveTurnBuilder
import app.codeg.android.feature.sessiondetail.LiveTurnState
import app.codeg.android.feature.sessiondetail.rendering.ToolCallState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
        val nodes = TranscriptTimeline.build(
            listOf(user("u1", "q"), assistant("a1", listOf(ContentBlock.Text("partial")))),
            emptyList(),
            null,
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
        val nodes = TranscriptTimeline.build(emptyList(), emptyList(), b.snapshot(), agent)
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
        val nodes = TranscriptTimeline.build(emptyList(), emptyList(), b.snapshot(), agent)
        assertEquals(NodeContent.Thinking, nodes.last().content)
    }

    private fun call(id: String, name: String, input: String = """{"file_path":"$id"}""", error: Boolean = false) = listOf(
        ContentBlock.ToolUse(id, name, input),
        ContentBlock.ToolResult(id, if (error) "boom" else "ok", error),
    )

    /** A reply that looked around, then answered. */
    private fun worked(id: String) = assistant(
        id,
        listOf(ContentBlock.Text("let me look")) + call("$id-r1", "Read") + call("$id-r2", "Grep") + ContentBlock.Text("the answer"),
    )

    private fun entries(turns: List<MessageTurn>, live: LiveTurnState? = null) =
        TranscriptTimeline.persistedEntries(turns, emptyList(), agent) + TranscriptTimeline.liveEntries(live, agent)

    @Test
    fun `a reply is headed by what it did, and folded keeps only its answer`() {
        val entries = entries(listOf(user("u1", "q"), worked("a1")))
        val open = TranscriptTimeline.flatten(entries) { true }.map { it.id }
        assertEquals(
            listOf("u1", "a1#header", "a1#0", "toolgroup-a1-r1", "a1#foot", "a1#2", "a1#footer"),
            open,
        )
        val folded = TranscriptTimeline.flatten(entries) { false }.map { it.id }
        assertEquals(listOf("u1", "a1#header", "a1#2", "a1#footer"), folded)
        val header = TranscriptTimeline.flatten(entries) { false }[1].content as NodeContent.ReplyHeader
        assertEquals(2, header.summary.steps)
        assertTrue(header.foldable)
        assertFalse(header.open)
    }

    @Test
    fun `a reply with nothing to trade away doesn't fold`() {
        // Words alone: a header, nothing to fold.
        val wordsOnly = entries(listOf(user("u1", "q"), assistant("a1", listOf(ContentBlock.Text("hi")))))
        val rows = TranscriptTimeline.flatten(wordsOnly) { false }
        assertEquals(listOf("u1", "a1#header", "a1#0", "a1#footer"), rows.map { it.id })
        assertFalse((rows[1].content as NodeContent.ReplyHeader).foldable)
        // Stopped on a call: no answer to keep, so its steps stay in view.
        val stopped = entries(listOf(user("u1", "q"), assistant("a1", call("c1", "Bash", """{"command":"ls"}"""))))
        assertEquals(listOf("u1", "a1#header", "tool-c1", "a1#footer"), TranscriptTimeline.flatten(stopped) { false }.map { it.id })
        // An empty placeholder reply gets no header.
        val blank = entries(listOf(user("u1", "q"), assistant("a1", emptyList())))
        assertEquals(listOf("u1", "a1#footer"), TranscriptTimeline.flatten(blank) { false }.map { it.id })
    }

    @Test
    fun `show the failure points at the row holding it`() {
        val reply = assistant(
            "a1",
            call("r1", "Read") + call("r2", "Read", error = true) + call("e1", "Edit", error = true) + ContentBlock.Text("sorry"),
        )
        val rows = TranscriptTimeline.flatten(entries(listOf(user("u1", "q"), reply))) { true }
        val header = rows.first { it.content is NodeContent.ReplyHeader }.content as NodeContent.ReplyHeader
        assertEquals(2, header.summary.unrecovered)
        // Folded into the exploration group.
        assertEquals("toolgroup-r1", header.failureRowId)
        assertTrue(rows.any { it.id == header.failureRowId })
    }

    @Test
    fun `a sub-agent still running stays in view when its reply is folded`() {
        val turns = listOf(
            user("u1", "q"),
            assistant("a1", listOf(ContentBlock.ToolUse("t1", "Task", """{"description":"dig"}"""), ContentBlock.Text("waiting"))),
            user("u2", "q2"),
        )
        val rows = TranscriptTimeline.flatten(
            TranscriptTimeline.persistedEntries(turns, emptyList(), agent, inFlightUserTurnId = "u1"),
        ) { false }.map { it.id }
        assertTrue("tool-t1" in rows)
    }

    @Test
    fun `the reply being written reads as working, and a steered note heads a reply of its own`() {
        val b = LiveTurnBuilder("live-1")
        b.appendText("before")
        b.appendSteer("n1", "also do this")
        b.appendText("after")
        val rows = TranscriptTimeline.flatten(entries(emptyList(), b.snapshot())) { true }
        val headers = rows.mapNotNull { it.content as? NodeContent.ReplyHeader }
        assertEquals(listOf("live-1", "live-1-1"), headers.map { it.replyId })
        // The part before the note is done; the part after is being written.
        assertEquals(listOf(true, false), headers.map { it.completed })
        assertTrue(rows.indexOfFirst { it.id == "steer-n1" } < rows.indexOfFirst { it.id == "live-1-1#header" })
    }

    @Test
    fun `a reply the transcript holds mid-round is still being written`() {
        val turns = listOf(user("u1", "q"), worked("a1"), user("u2", "q2"), worked("a2"))
        val headers = TranscriptTimeline.flatten(
            TranscriptTimeline.persistedEntries(turns, emptyList(), agent, inFlightUserTurnId = "u2"),
        ) { true }.mapNotNull { it.content as? NodeContent.ReplyHeader }
        assertEquals(listOf(true, false), headers.map { it.completed })
        // A settled reply carries the time it took; one being written doesn't show it.
        assertNull(headers[1].durationMs)
    }

    private fun compactionMeta(extra: String = "") =
        Json.parseToJsonElement("""{"contextCompaction":{"version":1$extra}}""") as JsonObject

    private fun compaction(id: String, callId: String = "$id-call", extra: String = "") = assistant(
        id,
        listOf(ContentBlock.ToolUse(callId, "Context compacted", null, compactionMeta(extra)), ContentBlock.ToolResult(callId, "", false)),
    )

    private fun shape(nodes: List<TimelineNode>) = nodes.map {
        when (val c = it.content) {
            is NodeContent.ReplyHeader -> "header:${c.replyId}"
            is NodeContent.Compaction -> "compaction:${it.id}"
            is NodeContent.User -> "user:${it.id}"
            else -> null
        }
    }.filterNotNull()

    @Test
    fun `a compaction between replies divides them, one mid-reply stays a step`() {
        val between = TranscriptTimeline.build(
            listOf(user("u1", "q"), assistant("a1", listOf(ContentBlock.Text("done"))), compaction("c1"), user("u2", "next")),
            emptyList(), null, agent,
        )
        assertEquals(listOf("user:u1", "header:a1", "compaction:c1#0", "user:u2"), shape(between))
        // The agent compacted by itself and went on with the same reply.
        val midReply = TranscriptTimeline.build(
            listOf(user("u1", "q"), assistant("a1", listOf(ContentBlock.Text("working"))), compaction("c1"), assistant("a2", listOf(ContentBlock.Text("done")))),
            emptyList(), null, agent,
        )
        assertEquals(listOf("user:u1", "header:a1", "compaction:a1#1"), shape(midReply))
        // At the very end: a divider too.
        val atEnd = TranscriptTimeline.build(listOf(user("u1", "/compact"), compaction("c1")), emptyList(), null, agent)
        assertEquals(listOf("user:u1", "compaction:c1#0"), shape(atEnd))
    }

    @Test
    fun `one compaction reaching the thread twice is drawn once`() {
        val live = LiveTurnBuilder("live-1").apply {
            upsertToolCall("c1-call", "Context compacted", "", "completed", null, "", null, compactionMeta())
        }.snapshot()
        val nodes = TranscriptTimeline.build(listOf(user("u1", "/compact"), compaction("c1")), emptyList(), live, agent)
        assertEquals(1, nodes.count { it.content is NodeContent.Compaction })
        // Under different ids, the figures name the event.
        val figures = ""","preTokens":9,"postTokens":4,"durationMs":70"""
        val claude = LiveTurnBuilder("live-2").apply {
            upsertToolCall("compacting-status", "Compacting", "", "completed", null, "", null, compactionMeta(figures))
        }.snapshot()
        val twice = TranscriptTimeline.build(listOf(user("u1", "/compact"), compaction("c1", extra = figures)), emptyList(), claude, agent)
        assertEquals(1, twice.count { it.content is NodeContent.Compaction })
        // codex's bare markers under different ids are two compactions.
        val two = TranscriptTimeline.build(listOf(user("u1", "q"), compaction("c1"), user("u2", "q"), compaction("c2")), emptyList(), null, agent)
        assertEquals(2, two.count { it.content is NodeContent.Compaction })
    }

    @Test
    fun `an empty placeholder turn doesn't cut a reply in two`() {
        val turns = listOf(
            user("u1", "q"),
            assistant("a1", listOf(ContentBlock.Text("first"))),
            MessageTurn("s1", TurnRole.SYSTEM, emptyList(), Instant.EPOCH),
            MessageTurn("p1", TurnRole.USER, listOf(ContentBlock.Text(" ")), Instant.EPOCH),
            assistant("a2", listOf(ContentBlock.Text("second"))),
        )
        assertEquals(listOf("user:u1", "header:a1"), shape(TranscriptTimeline.build(turns, emptyList(), null, agent)))
    }

    @Test
    fun `a live compaction is a divider until the reply goes on`() {
        val b = LiveTurnBuilder("live-1")
        b.upsertToolCall("k1", "Compacting", "", "in_progress", null, "", null, compactionMeta())
        assertEquals(listOf("compaction:live-1#0"), shape(TranscriptTimeline.build(emptyList(), emptyList(), b.snapshot(), agent)))
        b.appendText("carrying on")
        assertEquals(listOf("header:live-1", "compaction:live-1#0"), shape(TranscriptTimeline.build(emptyList(), emptyList(), b.snapshot(), agent)))
    }

    @Test
    fun `the live plan stands where the agent last revised it`() {
        val b = LiveTurnBuilder("live-1")
        b.updatePlan(listOf(PlanEntry("A")))
        b.appendText("working on it")
        b.updatePlan(listOf(PlanEntry("A", status = "completed")))
        val ids = TranscriptTimeline.build(emptyList(), emptyList(), b.snapshot(), agent).map { it.id }
        assertEquals(listOf("live-1#header", "live-1#0", "live-1#1"), ids)
        val plan = TranscriptTimeline.build(emptyList(), emptyList(), b.snapshot(), agent).single { it.content is NodeContent.Plan }
        assertEquals("live-1#1", plan.id)
        // An empty plan takes it away.
        b.updatePlan(emptyList())
        assertTrue(TranscriptTimeline.build(emptyList(), emptyList(), b.snapshot(), agent).none { it.content is NodeContent.Plan })
    }

    @Test
    fun `a turn handed off keeps its plan in place, as the same row`() {
        val b = LiveTurnBuilder("live-1")
        b.appendText("first")
        b.updatePlan(listOf(PlanEntry("Step one")))
        b.appendText("then")
        b.isStreaming = false
        val live = TranscriptTimeline.build(emptyList(), emptyList(), b.snapshot(), agent)
        val handed = TranscriptTimeline.build(listOf(user("u1", "q")) + b.snapshotAsMessageTurns(), emptyList(), null, agent)
        val livePlan = live.single { it.content is NodeContent.Plan }
        val keptPlan = handed.single { it.content is NodeContent.Plan }
        assertEquals(livePlan.id, keptPlan.id)
        assertEquals(livePlan.content, keptPlan.content)
    }

    @Test
    fun `prose the plan cut in two is one again once it moves on`() {
        val b = LiveTurnBuilder("live-1")
        b.appendText("Hello wor")
        b.updatePlan(listOf(PlanEntry("A")))
        b.appendText("ld, done.")
        b.updatePlan(listOf(PlanEntry("A", status = "completed")))
        val segments = b.snapshot().segments
        assertEquals(listOf("Hello world, done."), segments.filterIsInstance<LiveSegment.Text>().map { it.text })
        assertTrue(segments.last() is LiveSegment.Plan)
    }

    @Test
    fun `a reply the agent began on its own joins the one before it`() {
        val turns = listOf(user("u1", "q"), worked("a1"))
        // It speaks first: the earlier answer and the new words are one answer.
        val talking = LiveTurnBuilder("live-1").apply { appendText("and one more thing") }.snapshot()
        val spoken = TranscriptTimeline.joinLive(TranscriptTimeline.persistedEntries(turns, emptyList(), agent), TranscriptTimeline.liveEntries(talking, agent))
        val reply = (spoken.last() as ThreadEntry.Reply).rows
        assertEquals(2, spoken.size)
        assertEquals("a1", reply.id)
        assertEquals(listOf("a1#2", "live-1#0"), reply.answer.map { it.id })
        assertFalse("still being written", reply.completed)
        assertTrue("the earlier footer gives way", reply.trailer.none { it.id == "a1#footer" })
        // It takes a step: everything before it is process, and both replies' steps count.
        val working = LiveTurnBuilder("live-1").apply {
            appendText("checking")
            upsertToolCall("t9", "Bash", "execute", "in_progress", """{"command":"ls"}""", null, null)
        }.snapshot()
        val stepped = (TranscriptTimeline.joinLive(TranscriptTimeline.persistedEntries(turns, emptyList(), agent), TranscriptTimeline.liveEntries(working, agent)).last() as ThreadEntry.Reply).rows
        assertTrue(stepped.process.any { it.id == "a1#2" })
        assertEquals(3, stepped.summary.steps)
        // A prompt between keeps them apart.
        val prompted = TranscriptTimeline.joinLive(
            TranscriptTimeline.persistedEntries(turns, listOf(user("u2", "more")), agent),
            TranscriptTimeline.liveEntries(talking, agent),
        )
        assertEquals(4, prompted.size)
    }

    @Test
    fun `only the thread's latest goal call may offer controls`() {
        val first = assistant("a1", call("g1", "create_goal", """{"objective":"A"}""") + call("g2", "update_goal", """{"status":"complete"}"""))
        val second = assistant("a2", call("g3", "create_goal", """{"objective":"B"}""") + listOf(ContentBlock.Text("on it")))
        val entries = TranscriptTimeline.persistedEntries(listOf(user("u1", "q"), first, user("u2", "q2"), second), emptyList(), agent)
        assertEquals("g3", TranscriptTimeline.latestGoalCallId(entries))
        assertNull(TranscriptTimeline.latestGoalCallId(TranscriptTimeline.persistedEntries(listOf(user("u1", "q")), emptyList(), agent)))
    }
}
