package app.codeg.android.feature.sessiondetail

import app.codeg.android.core.model.AcpEvent
import app.codeg.android.core.model.ContentBlock
import app.codeg.android.core.model.LiveSessionSnapshot
import app.codeg.android.core.model.TurnRole
import app.codeg.android.core.network.CodegJson
import app.codeg.android.feature.sessiondetail.rendering.MessageRender
import app.codeg.android.feature.sessiondetail.rendering.RenderPart
import app.codeg.android.feature.sessiondetail.rendering.ToolKindBucket
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Locks down the live-turn accumulation logic (ported from iOS `LiveTurn`). */
class LiveTurnBuilderTest {

    @Test
    fun `consecutive text deltas coalesce into one segment`() {
        val b = LiveTurnBuilder("t")
        b.appendText("Hel")
        b.appendText("lo")
        val segs = b.snapshot().segments
        assertEquals(1, segs.size)
        assertEquals("Hello", (segs[0] as LiveSegment.Text).text)
    }

    @Test
    fun `thinking breaks the text run`() {
        val b = LiveTurnBuilder("t")
        b.appendText("a")
        b.appendThinking("reason")
        b.appendText("b")
        val segs = b.snapshot().segments
        assertEquals(3, segs.size)
        assertTrue(segs[0] is LiveSegment.Text)
        assertTrue(segs[1] is LiveSegment.Thinking)
        assertTrue(segs[2] is LiveSegment.Text)
    }

    @Test
    fun `tool call upsert then append updates output in place`() {
        val b = LiveTurnBuilder("t")
        b.upsertToolCall("id1", "Bash", "execute", "in_progress", """{"command":"ls"}""", null, null)
        b.updateToolCall("id1", title = null, status = null, rawInput = null, rawOutput = "line1\n", content = null, append = true)
        b.updateToolCall("id1", title = null, status = "completed", rawInput = null, rawOutput = "line2", content = null, append = true)
        val segs = b.snapshot().segments
        assertEquals(1, segs.size)
        val call = (segs[0] as LiveSegment.Tool).call
        assertEquals("line1\nline2", call.rawOutput)
        assertEquals("completed", call.status)
        assertTrue(call.isFinished)
        assertFalse(call.isError)
    }

    @Test
    fun `a subagent chunk is dropped instead of folded into the turn`() {
        // The live-event path (and replay, which shares it) folds chunks through
        // `append`. A Claude subagent's prose must not reach the assistant's message.
        val b = LiveTurnBuilder("t")
        assertTrue(b.append(AcpEvent.ContentDelta("main ")))
        assertFalse(b.append(AcpEvent.ContentDelta("SUBAGENT", parentToolUseId = "toolu_1")))
        assertFalse(b.append(AcpEvent.Thinking("SUBTHINK", parentToolUseId = "toolu_1")))
        assertTrue(b.append(AcpEvent.ContentDelta("thread")))

        val segs = b.snapshot().segments
        assertEquals(1, segs.size)
        assertEquals("main thread", (segs[0] as LiveSegment.Text).text)
    }

    @Test
    fun `main-thread thinking still folds in`() {
        val b = LiveTurnBuilder("t")
        assertTrue(b.append(AcpEvent.Thinking("reasoning")))
        val segs = b.snapshot().segments
        assertEquals(1, segs.size)
        assertEquals("reasoning", (segs[0] as LiveSegment.Thinking).text)
    }

    @Test
    fun `seeding a snapshot skips subagent-attributed blocks`() {
        // A mid-turn reattach must rebuild the same turn a client that never dropped
        // saw: a Claude subagent's text belongs to its Agent tool call, so seeding it
        // into the main thread would splice a second voice into the assistant's reply.
        val snapshot = LiveSessionSnapshot.fromWire(
            CodegJson.response.parseToJsonElement(
                """{"connection_id":"c1","live_message":{"content":[
                     {"kind":"text","text":"before "},
                     {"kind":"text","text":"SUBAGENT","parent_tool_use_id":"toolu_1"},
                     {"kind":"thinking","text":"SUBTHINK","parent_tool_use_id":"toolu_1"},
                     {"kind":"text","text":"after"}]}}""",
            ).jsonObject,
            CodegJson.response,
        )
        val b = LiveTurnBuilder("t")
        b.seedFrom(snapshot)

        val segs = b.snapshot().segments
        // The two main-thread runs coalesce; nothing of the subagent survives.
        assertEquals(1, segs.size)
        assertEquals("before after", (segs[0] as LiveSegment.Text).text)
    }

    @Test
    fun `snapshotAsMessageTurn emits paired tool blocks`() {
        val b = LiveTurnBuilder("live-1")
        b.appendText("done")
        b.upsertToolCall("id1", "Read foo", "read", "completed", null, "file contents", null)
        val turn = b.snapshotAsMessageTurn()
        assertEquals(TurnRole.ASSISTANT, turn.role)
        // text + (toolUse, toolResult)
        assertEquals(3, turn.blocks.size)
        assertTrue(turn.blocks[0] is ContentBlock.Text)
        assertTrue(turn.blocks[1] is ContentBlock.ToolUse)
        assertTrue(turn.blocks[2] is ContentBlock.ToolResult)
    }

    @Test
    fun `a call handed off locally classifies as it did live, not by its title`() {
        val b = LiveTurnBuilder("live-1")
        val meta = CodegJson.response.parseToJsonElement("""{"claudeCode":{"toolName":"Agent"}}""").jsonObject
        b.upsertToolCall("a1", "Explore the codebase", "think", "completed", """{"description":"Explore the codebase","prompt":"look"}""", "done", null, meta)
        b.upsertToolCall("r1", "Run tests", "execute", "completed", null, "ok", null)
        val tools = MessageRender.adaptParts(b.snapshotAsMessageTurn())
            .flatMap { p -> (p as? RenderPart.ToolGroup)?.items ?: listOfNotNull((p as? RenderPart.Tool)?.vm) }
        assertEquals(listOf(ToolKindBucket.TASK, ToolKindBucket.EXECUTE), tools.map { it.bucket })
        // A call the transcript recorded carries no kind, and is named by the tool itself.
        val persisted = ContentBlock.ToolUse("a1", "Explore the codebase", null, null)
        assertEquals(null, persisted.liveKind)
    }

    @Test
    fun `a permission request announces the call it comes ahead of, and fills one that sent no input`() {
        val b = LiveTurnBuilder("t")
        assertTrue(b.noteAwaitedCall("c1", "Run", "execute", """{"command":"ls"}"""))
        val announced = (b.snapshot().segments.single() as LiveSegment.Tool).call
        assertEquals("pending", announced.status)
        assertFalse(announced.isFinished)
        // The call itself then arrives and settles in place.
        b.upsertToolCall("c1", "Bash", "execute", "in_progress", null, null, null)
        assertEquals("""{"command":"ls"}""", b.toolCall("c1")?.rawInput)
        assertEquals(1, b.snapshot().segments.size)

        b.upsertToolCall("c2", "Edit", "edit", "in_progress", null, null, null)
        assertTrue(b.noteAwaitedCall("c2", "", "", """{"file_path":"a"}"""))
        // Arguments the call sent are never replaced.
        assertFalse(b.noteAwaitedCall("c2", "", "", """{"file_path":"b"}"""))
        assertEquals("""{"file_path":"a"}""", b.toolCall("c2")?.rawInput)
    }
}
