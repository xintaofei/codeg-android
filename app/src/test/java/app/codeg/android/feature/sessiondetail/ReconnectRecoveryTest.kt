package app.codeg.android.feature.sessiondetail

import app.codeg.android.core.model.AcpEvent
import app.codeg.android.core.model.ContentBlock
import app.codeg.android.core.model.ConversationDetail
import app.codeg.android.core.model.ImageData
import app.codeg.android.core.model.LiveSessionSnapshot
import app.codeg.android.core.model.MessageTurn
import app.codeg.android.core.model.TurnRole
import app.codeg.android.core.model.UserMessageBlock
import app.codeg.android.core.network.CodegJson
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a session does with a stream that now stays attached between turns: classifying a
 * reconnect snapshot, showing another client's prompt, and rebuilding a turn from a snapshot.
 */
class ReconnectRecoveryTest {

    private val json = CodegJson.response

    private fun snap(raw: String): LiveSessionSnapshot =
        LiveSessionSnapshot.fromWire(json.parseToJsonElement(raw).jsonObject, json)

    private val prompting = snap("""{"connection_id":"c1","status":"prompting"}""")
    private val idle = snap("""{"connection_id":"c1","status":"connected"}""")

    // A turn's permission can outlive it: the server publishes it after `turn_complete`.
    private val idleWithCard = snap(
        """{"connection_id":"c1","status":"connected",
            "pending_permission":{"request_id":"r1","tool_call":{},"options":[]}}""",
    )

    @Test
    fun `a running turn that still runs is rebuilt from the snapshot`() {
        assertEquals(ReconnectRecovery.RESEED, reconnectRecovery(prompting, turnRunning = true, ownTurnStarted = true))
    }

    @Test
    fun `a running turn the snapshot shows idle ended inside the gap`() {
        assertEquals(ReconnectRecovery.ENDED_UNSEEN, reconnectRecovery(idle, turnRunning = true, ownTurnStarted = true))
        // A card left over from the turn doesn't make it still running.
        assertEquals(ReconnectRecovery.ENDED_UNSEEN, reconnectRecovery(idleWithCard, turnRunning = true, ownTurnStarted = true))
    }

    @Test
    fun `an idle snapshot for a send not yet seen to start is checked against the transcript`() {
        // The prompt may still be queued (the server buffers it while the agent initializes,
        // reporting `connected`), or its whole turn may have passed while paused: only the
        // transcript can tell.
        assertEquals(ReconnectRecovery.VERIFY, reconnectRecovery(idle, turnRunning = true, ownTurnStarted = false))
        // Running: the turn before it, or this one between `prompting` and `user_message`.
        assertEquals(ReconnectRecovery.NONE, reconnectRecovery(prompting, turnRunning = true, ownTurnStarted = false))
    }

    private fun detail(offset: Int, total: Int, vararg turns: String): ConversationDetail = json.decodeFromString(
        ConversationDetail.serializer(),
        """{"summary":{"id":1,"folder_id":1,"agent_type":"codex","status":"completed","message_count":0,
             "created_at":"2024-01-02T03:04:05Z","updated_at":"2024-01-02T03:04:05Z"},
            "turns_offset":$offset,"turns_total":$total,"prefix_hash":"h",
            "turns":[${turns.joinToString(",")}]}""",
    )

    private fun turn(id: String, role: String, text: String) =
        """{"id":"$id","role":"$role","timestamp":"2024-01-02T03:04:05Z","blocks":[{"type":"text","text":"$text"}]}"""

    private val sent = MessageTurn("pending-1", TurnRole.USER, listOf(ContentBlock.Text("fix the bug")), java.time.Instant.EPOCH)

    @Test
    fun `the sent prompt is found past the baseline`() {
        // Global indices 12..13; the send started when the transcript held 12 turns.
        val fromBaseline = detail(12, 14, turn("u1", "user", "<context/> fix the bug"), turn("a1", "assistant", "done"))
        assertTrue(sentPromptAt(fromBaseline, baseline = 12, sent = sent))
        assertTrue(reachesEnd(fromBaseline))
    }

    @Test
    fun `a matching prompt before the baseline, or another prompt, is not this send`() {
        val earlierSame = detail(10, 12, turn("u0", "user", "fix the bug"), turn("a0", "assistant", "ok"))
        assertFalse(sentPromptAt(earlierSame, baseline = 12, sent = sent))
        val someoneElse = detail(12, 14, turn("u1", "user", "other"), turn("a1", "assistant", "ok"))
        assertFalse(sentPromptAt(someoneElse, baseline = 12, sent = sent))
    }

    @Test
    fun `a window capped short of the end is known not to reach it`() {
        // One long round: the window from the baseline holds the prompt but not the round's
        // end, so the end has to be read off the tail.
        val capped = detail(12, 900, turn("u1", "user", "fix the bug"), turn("a1", "assistant", "step"))
        assertTrue(sentPromptAt(capped, baseline = 12, sent = sent))
        assertFalse(reachesEnd(capped))
    }

    @Test
    fun `a turn that came and went while nothing ran here refreshes the transcript`() {
        assertEquals(
            ReconnectRecovery.REFRESH,
            reconnectRecovery(idle, turnRunning = false, ownTurnStarted = true, missedEvents = true),
        )
        assertEquals(
            ReconnectRecovery.NONE,
            reconnectRecovery(idle, turnRunning = false, ownTurnStarted = true, missedEvents = false),
        )
    }

    @Test
    fun `a turn that started inside the gap is observed`() {
        assertEquals(ReconnectRecovery.OBSERVE, reconnectRecovery(prompting, turnRunning = false, ownTurnStarted = true))
        assertEquals(ReconnectRecovery.NONE, reconnectRecovery(idle, turnRunning = false, ownTurnStarted = true))
        // An out-of-turn card alone is not a turn to observe: nothing would ever end it.
        assertEquals(ReconnectRecovery.NONE, reconnectRecovery(idleWithCard, turnRunning = false, ownTurnStarted = true))
    }

    @Test
    fun `only prompting counts as running, the looser test also sees cards`() {
        assertTrue(snapshotTurnRunning(prompting))
        assertFalse(snapshotTurnRunning(idleWithCard))
        assertTrue(snapshotShowsTurn(idleWithCard))
        assertFalse(snapshotShowsTurn(idle))
    }

    @Test
    fun `another client's prompt becomes a user turn`() {
        val image = ImageData("AAA", "image/png")
        val turn = observedUserTurn(
            AcpEvent.UserMessage("m1", listOf(UserMessageBlock.Text("hi"), UserMessageBlock.Image(image), UserMessageBlock.Unknown)),
        )
        assertEquals("observed-m1", turn?.id)
        assertEquals(TurnRole.USER, turn?.role)
        assertEquals(listOf(ContentBlock.Text("hi"), ContentBlock.Image(image)), turn?.blocks)
        assertNull(observedUserTurn(AcpEvent.UserMessage("m2", listOf(UserMessageBlock.Text("  ")))))
    }

    @Test
    fun `reseeding replaces the turn but keeps a trimmed call's payload`() {
        val b = LiveTurnBuilder("t")
        b.appendText("before the gap ")
        b.upsertToolCall("old", "Read", "read", "completed", """{"file_path":"a"}""", "file body", null)
        b.upsertToolCall("gone", "Bash", "execute", "completed", "{}", "x", null)
        // The server trimmed the finished call's input/output out of a large snapshot.
        b.reseedFrom(
            snap(
                """{"connection_id":"c1","status":"prompting",
                    "live_message":{"content":[
                      {"kind":"text","text":"whole reply so far "},
                      {"kind":"tool_call_ref","tool_call_id":"old"},
                      {"kind":"text","text":"and more"}]},
                    "active_tool_calls":[{"id":"old","kind":"read","label":"Read","status":"completed"}]}""",
            ),
        )
        val segs = b.snapshot().segments
        assertEquals(
            listOf("whole reply so far ", "and more"),
            segs.filterIsInstance<LiveSegment.Text>().map { it.text },
        )
        val tools = segs.filterIsInstance<LiveSegment.Tool>().map { it.call }
        assertEquals(listOf("old"), tools.map { it.id })
        assertEquals("""{"file_path":"a"}""", tools.single().rawInput)
        assertEquals("file body", tools.single().rawOutput)
        assertTrue(b.isStreaming)
        assertEquals("t", b.id)
    }
}
