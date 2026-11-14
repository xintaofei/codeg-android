package app.codeg.android.feature.sessiondetail

import app.codeg.android.core.model.AcpEvent
import app.codeg.android.core.model.ContentBlock
import app.codeg.android.core.model.LiveSessionSnapshot
import app.codeg.android.core.model.UserMessageBlock
import kotlinx.serialization.json.jsonObject
import app.codeg.android.core.model.ConversationDetail
import app.codeg.android.core.model.MessageTurn
import app.codeg.android.core.model.TurnRole
import app.codeg.android.core.model.TurnWindowState
import app.codeg.android.core.network.CodegJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** The turn-end hand-off: what a finished round becomes locally, and when a read replaces it. */
class TurnHandoffTest {

    private val json = CodegJson.response

    private fun detail(offset: Int, total: Int, vararg turns: String, inFlight: String? = null): ConversationDetail =
        json.decodeFromString(
            ConversationDetail.serializer(),
            """{"summary":{"id":1,"folder_id":1,"agent_type":"codex","status":"completed","message_count":0,
                 "created_at":"2024-01-02T03:04:05Z","updated_at":"2024-01-02T03:04:05Z"},
                "turns_offset":$offset,"turns_total":$total,"prefix_hash":"h",
                ${inFlight?.let { "\"in_flight_user_turn_id\":\"$it\"," } ?: ""}
                "turns":[${turns.joinToString(",")}]}""",
        )

    private fun user(id: String, text: String = "q") =
        """{"id":"$id","role":"user","timestamp":"2024-01-02T03:04:05Z","blocks":[{"type":"text","text":"$text"}]}"""

    private fun reply(id: String) =
        """{"id":"$id","role":"assistant","timestamp":"2024-01-02T03:04:05Z","blocks":[{"type":"text","text":"a"}]}"""

    private fun failed(id: String) =
        """{"id":"$id","role":"system","timestamp":"2024-01-02T03:04:05Z","blocks":[{"type":"turn_error","message":"boom"}]}"""

    private fun prompt(text: String) = MessageTurn("pending-$text", TurnRole.USER, listOf(ContentBlock.Text(text)), Instant.EPOCH)

    @Test
    fun `a finished round keeps its prompt and the reply as it streamed`() {
        val b = LiveTurnBuilder("live-1")
        b.appendText("the answer")
        b.isStreaming = false
        val turns = promotedTurns(listOf(prompt("q")), b)
        assertEquals(listOf(TurnRole.USER, TurnRole.ASSISTANT), turns.map { it.role })
        assertEquals("live-1", turns[1].id)
        assertEquals(listOf(ContentBlock.Text("the answer")), turns[1].blocks)
    }

    @Test
    fun `a failed round keeps its failure as the record the transcript would hold`() {
        val b = LiveTurnBuilder("live-1")
        b.appendText("partial")
        b.error = LiveTurnError("Agent crashed", "stderr")
        val turns = promotedTurns(listOf(prompt("q")), b)
        assertEquals(listOf(TurnRole.USER, TurnRole.ASSISTANT, TurnRole.SYSTEM), turns.map { it.role })
        assertEquals(listOf(ContentBlock.TurnError("Agent crashed")), turns[2].blocks)
        // An error alone still ends the round on its record.
        val bare = LiveTurnBuilder("live-2").apply { error = LiveTurnError("Empty turn.") }
        assertEquals(listOf(TurnRole.USER, TurnRole.SYSTEM), promotedTurns(listOf(prompt("q")), bare).map { it.role })
    }

    @Test
    fun `a cancel marker and an empty reply leave only the prompt`() {
        val cancelled = LiveTurnBuilder().apply {
            error = LiveTurnError("Cancelled")
            cancelledLocally = true
        }
        assertEquals(listOf(TurnRole.USER), promotedTurns(listOf(prompt("q")), cancelled).map { it.role })
        assertEquals(listOf(TurnRole.USER), promotedTurns(listOf(prompt("q")), LiveTurnBuilder()).map { it.role })
        assertEquals(listOf(TurnRole.USER), promotedTurns(listOf(prompt("q")), null).map { it.role })
    }

    @Test
    fun `local turns attach where the loaded window ends`() {
        val window = TurnWindowState(offset = 10, total = 14, prefixHash = "h")
        assertEquals(12, loadedEnd(window, listOf(prompt("a"), prompt("b"))))
        assertEquals(2, loadedEnd(null, listOf(prompt("a"), prompt("b"))))
    }

    @Test
    fun `a read holding every local round replaces them, however it splits the replies`() {
        // Batch from 12: two rounds shown locally; the server split the second reply in two.
        val read = detail(10, 17, user("u0"), reply("a0"), user("u1"), reply("a1"), user("u2"), reply("a2"), reply("a2b"))
        assertTrue(holdsBatch(read, batchStart = 12, prompts = 2))
        // A failed round ends on its record.
        assertTrue(holdsBatch(detail(12, 14, user("u1"), failed("e1")), batchStart = 12, prompts = 1))
        // A prompt steered into a turn persists as one more user turn: still holds.
        assertTrue(holdsBatch(detail(12, 15, user("u1"), user("steer"), reply("a1")), batchStart = 12, prompts = 1))
    }

    @Test
    fun `a read short of the batch is not taken`() {
        // The last round's reply isn't written yet.
        assertFalse(holdsBatch(detail(12, 15, user("u1"), reply("a1"), user("u2")), batchStart = 12, prompts = 2))
        // One round behind: the newest prompt isn't there at all.
        assertFalse(holdsBatch(detail(12, 14, user("u1"), reply("a1")), batchStart = 12, prompts = 2))
        // A round still running on the server.
        assertFalse(holdsBatch(detail(12, 14, user("u1"), reply("a1"), inFlight = "u1"), batchStart = 12, prompts = 1))
        // A read starting past the batch can't show its first rounds.
        assertFalse(holdsBatch(detail(13, 14, reply("a1")), batchStart = 12, prompts = 0))
        // A read that stops short of the end.
        assertFalse(holdsBatch(detail(12, 90, user("u1"), reply("a1")), batchStart = 12, prompts = 1))
    }

    @Test
    fun `an earlier local round's prompt is not mistaken for this send`() {
        // Batch from 12 holds one earlier round that asked the same thing.
        val read = detail(12, 14, user("u1", "continue"), reply("a1"))
        val sent = prompt("continue")
        assertTrue(sentPromptAt(read, baseline = 12, sent = sent, priorPrompts = 0))
        assertFalse(sentPromptAt(read, baseline = 12, sent = sent, priorPrompts = 1))
        val ran = detail(12, 16, user("u1", "continue"), reply("a1"), user("u2", "continue"), reply("a2"))
        assertTrue(sentPromptAt(ran, baseline = 12, sent = sent, priorPrompts = 1))
    }

    @Test
    fun `a refresh never lands on a round still on screen`() {
        // Issued before another client's turn started, answered after: the read may hold that
        // round, which will be handed off locally too.
        val read = detail(12, 14, user("m1"), reply("a1"), inFlight = "m1")
        assertEquals(RefreshAdoption.SKIP, refreshAdoption(read, false, roundOnScreen = true, localTurns = emptyList(), batchStart = 12))
        assertEquals(RefreshAdoption.TAKE_ALL, refreshAdoption(read, false, roundOnScreen = false, localTurns = emptyList(), batchStart = 12))
    }

    @Test
    fun `a refresh takes local turns only when it holds them`() {
        val local = listOf(prompt("q"), MessageTurn("live-1", TurnRole.ASSISTANT, listOf(ContentBlock.Text("a")), Instant.EPOCH))
        val holding = detail(12, 14, user("u1"), reply("a1"))
        assertEquals(RefreshAdoption.TAKE_ALL, refreshAdoption(holding, false, false, local, batchStart = 12))
        val behind = detail(12, 13, user("u1"))
        assertEquals(RefreshAdoption.SKIP, refreshAdoption(behind, false, false, local, batchStart = 12))
        // A rewrite: no coordinates to check, even for a batch with no prompt of its own.
        val replyOnly = listOf(MessageTurn("live-1", TurnRole.ASSISTANT, listOf(ContentBlock.Text("a")), Instant.EPOCH))
        assertEquals(RefreshAdoption.TAKE_KEEP_LOCAL, refreshAdoption(holding, true, false, replyOnly, batchStart = 12))
    }

    @Test
    fun `an observed turn whose prompt never arrived is read back, not handed off`() {
        assertTrue(endsByHandOff(unseen = false, observed = false, fromReattach = false, promptKnown = true))
        assertTrue(endsByHandOff(unseen = false, observed = true, fromReattach = false, promptKnown = true))
        // Its prompt sits in the window it was reattached from.
        assertTrue(endsByHandOff(unseen = false, observed = true, fromReattach = true, promptKnown = false))
        assertFalse(endsByHandOff(unseen = false, observed = true, fromReattach = false, promptKnown = false))
        assertFalse(endsByHandOff(unseen = true, observed = false, fromReattach = false, promptKnown = true))
    }

    @Test
    fun `a snapshot carries the running turn's prompt for a client that missed it`() {
        val snap = LiveSessionSnapshot.fromWire(
            json.parseToJsonElement(
                """{"connection_id":"c1","status":"prompting",
                    "pending_user_message":{"message_id":"m1","blocks":[{"type":"text","text":"new prompt"}]}}""",
            ).jsonObject,
            json,
        )
        assertTrue(snap.hasPendingUserMessage)
        assertEquals(AcpEvent.UserMessage("m1", listOf(UserMessageBlock.Text("new prompt"))), snap.pendingUserMessage)
        assertEquals(listOf(ContentBlock.Text("new prompt")), observedUserTurn(snap.pendingUserMessage!!)?.blocks)
    }

    @Test
    fun `revision notes answered on a turn that ended meanwhile are still owed to it`() {
        val origin = LiveTurnBuilder().apply { stopReason = "end_turn" }
        // Handed off before the answer came back: still that turn.
        assertTrue(planOriginCurrent(origin, live = null, handedOff = origin))
        assertEquals(
            PlanFollowUp.SEND_NOW,
            planFollowUpAction(planOriginCurrent(origin, null, origin), inFlight = false, stopReason = origin.stopReason, errorMessage = null),
        )
        // Another turn is live now, or a different one was handed off.
        assertFalse(planOriginCurrent(origin, live = LiveTurnBuilder(), handedOff = origin))
        assertFalse(planOriginCurrent(origin, live = null, handedOff = LiveTurnBuilder()))
    }

    @Test
    fun `revision notes answered on a turn the user stopped are dropped`() {
        val origin = LiveTurnBuilder().apply {
            appendText("plan")
            stopReason = STOP_REASON_CANCELLED
        }
        assertEquals(
            PlanFollowUp.DROP,
            planFollowUpAction(planOriginCurrent(origin, null, origin), inFlight = false, stopReason = origin.stopReason, errorMessage = null),
        )
    }
}
