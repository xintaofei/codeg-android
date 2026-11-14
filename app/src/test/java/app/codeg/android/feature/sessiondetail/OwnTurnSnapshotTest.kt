package app.codeg.android.feature.sessiondetail

import app.codeg.android.core.model.AcpEvent
import app.codeg.android.core.model.ConnectionStatus
import app.codeg.android.core.model.LiveSessionSnapshot
import app.codeg.android.core.network.CodegJson
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a stream snapshot proves a send's own turn has started. A reconnect that falls back
 * to a snapshot can skip the `prompting` that normally opens it; an agent-initiated turn
 * (Grok's own, which the server ends just before starting a queued prompt) must not.
 */
class OwnTurnSnapshotTest {

    private val json = CodegJson.response

    private fun snap(raw: String): LiveSessionSnapshot =
        LiveSessionSnapshot.fromWire(json.parseToJsonElement(raw).jsonObject, json)

    private val ownTurn = snap(
        """{"connection_id":"c1","status":"prompting",
            "pending_user_message":{"message_id":"m1","blocks":[{"type":"text","text":"hi"}]}}""",
    )

    @Test
    fun `a reconnect snapshot prompting for a user message is this send's turn`() {
        assertTrue(snapshotStartsOwnTurn(ownTurn, isReconnect = true))
    }

    @Test
    fun `an agent-initiated turn prompts with no user message`() {
        assertFalse(snapshotStartsOwnTurn(snap("""{"connection_id":"c1","status":"prompting"}"""), isReconnect = true))
    }

    @Test
    fun `the first attach precedes the prompt, so its turn is someone else's`() {
        assertFalse(snapshotStartsOwnTurn(ownTurn, isReconnect = false))
    }

    @Test
    fun `an idle connection starts nothing`() {
        assertFalse(snapshotStartsOwnTurn(snap("""{"connection_id":"c1","status":"connected"}"""), isReconnect = true))
    }

    @Test
    fun `this send's user_message opens its turn when the snapshot skipped prompting`() {
        // The server emits `prompting` and `user_message` separately; a reconnect snapshot
        // taken between them shows prompting with no pending message, and skips the first.
        assertFalse(snapshotStartsOwnTurn(snap("""{"connection_id":"c1","status":"prompting"}"""), isReconnect = true))
        assertTrue(opensOwnTurn(AcpEvent.UserMessage("m1", emptyList()), ownClientMessageId = "m1"))
        // Another prompt's message is not this send's turn.
        assertFalse(opensOwnTurn(AcpEvent.UserMessage("other", emptyList()), ownClientMessageId = "m1"))
        assertFalse(opensOwnTurn(AcpEvent.UserMessage("m1", emptyList()), ownClientMessageId = null))
    }

    @Test
    fun `prompting opens the turn and the preempted turn's end does not`() {
        assertTrue(opensOwnTurn(AcpEvent.StatusChanged(ConnectionStatus.PROMPTING), ownClientMessageId = "m1"))
        assertFalse(opensOwnTurn(AcpEvent.StatusChanged(ConnectionStatus.CONNECTED), ownClientMessageId = "m1"))
        assertFalse(opensOwnTurn(AcpEvent.TurnComplete("cancelled"), ownClientMessageId = "m1"))
    }
}
