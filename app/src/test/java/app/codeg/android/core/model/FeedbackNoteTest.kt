package app.codeg.android.core.model

import app.codeg.android.core.network.CodegJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wire contract for mid-turn steering notes, decoded through the real response codec.
 *
 * The two halves of the feature arrive by different routes — the session snapshot decodes
 * with kotlinx (snake_case naming strategy), while the event stream is hand-parsed — so both
 * are exercised here against payloads shaped like the server's.
 */
class FeedbackNoteTest {

    private val json: Json = CodegJson.response

    private fun snapshot(raw: String): SessionSnapshot =
        json.decodeFromString(SessionSnapshot.serializer(), raw)

    // --- snapshot ----------------------------------------------------------

    @Test
    fun `snapshot carries the turn's notes and both channel flags`() {
        val snap = snapshot(
            """
            {
              "selectors_ready": true,
              "feedback": [
                {"id":"f1","text":"use UserService","created_at":"2026-08-12T10:00:00Z","status":"pending"},
                {"id":"f2","text":"skip the migration","created_at":"2026-08-12T10:00:03Z","status":"delivered","delivered_at":"2026-08-12T10:00:04Z"}
              ],
              "feedback_tool_available": true,
              "native_steering_available": true
            }
            """.trimIndent(),
        )

        assertEquals(listOf("f1", "f2"), snap.feedback.map { it.id })
        assertEquals("use UserService", snap.feedback[0].text)
        assertFalse(snap.feedback[0].isDelivered)
        assertTrue(snap.feedback[1].isDelivered)
        assertTrue(snap.feedbackToolAvailable)
        assertTrue(snap.nativeSteeringAvailable)
    }

    @Test
    fun `a server without the feature reads as no channel and no notes`() {
        // Every field is optional on the wire; a pre-0.24.0 snapshot omits all three, and
        // absent must mean "can't take a note" — offering one that the server will refuse is
        // worse than not offering it.
        val snap = snapshot("""{"selectors_ready": true}""")

        assertTrue(snap.feedback.isEmpty())
        assertFalse(snap.feedbackToolAvailable)
        assertFalse(snap.nativeSteeringAvailable)
    }

    @Test
    fun `a status this build doesn't know is not treated as read`() {
        val snap = snapshot(
            """{"feedback":[{"id":"f1","text":"hi","created_at":"","status":"acknowledged"}]}""",
        )

        // Claiming the agent read a note it may not have would tell the user to stop waiting
        // for a correction that never landed.
        assertFalse(snap.feedback.single().isDelivered)
    }

    @Test
    fun `a note with no status defaults to pending`() {
        val snap = snapshot("""{"feedback":[{"id":"f1","text":"hi","created_at":""}]}""")

        assertEquals(FeedbackNote.PENDING, snap.feedback.single().status)
        assertFalse(snap.feedback.single().isDelivered)
    }

    // --- events ------------------------------------------------------------

    private fun event(raw: String): AcpEvent =
        AcpEvent.fromWire(json.parseToJsonElement(raw).jsonObject, json)

    @Test
    fun `feedback_submitted carries the note`() {
        val decoded = event(
            """
            {"type":"feedback_submitted","connection_id":"c1",
             "item":{"id":"f1","text":"use UserService","created_at":"2026-08-12T10:00:00Z","status":"pending"}}
            """.trimIndent(),
        )

        val note = (decoded as AcpEvent.FeedbackSubmitted).note
        assertEquals("f1", note.id)
        assertEquals("use UserService", note.text)
        assertFalse(note.isDelivered)
    }

    @Test
    fun `a natively steered note arrives already delivered`() {
        // The `_session/steering` push records the note delivered from birth — the adapter
        // consumed the text before the note existed, so no `feedback_consumed` follows and
        // rendering it as "waiting" would never resolve.
        val decoded = event(
            """
            {"type":"feedback_submitted","connection_id":"c1",
             "item":{"id":"f1","text":"stop and reconsider","created_at":"2026-08-12T10:00:00Z",
                     "status":"delivered","delivered_at":"2026-08-12T10:00:00Z"}}
            """.trimIndent(),
        )

        assertTrue((decoded as AcpEvent.FeedbackSubmitted).note.isDelivered)
    }

    @Test
    fun `a submitted event with no item is unknown, not an empty note`() {
        // A blank note would put an untitled row on screen that no `feedback_consumed` could
        // ever resolve — its id would match nothing.
        val decoded = event("""{"type":"feedback_submitted","connection_id":"c1"}""")

        assertEquals(AcpEvent.Unknown("feedback_submitted"), decoded)
    }

    @Test
    fun `feedback_consumed carries the ids`() {
        val decoded = event(
            """
            {"type":"feedback_consumed","connection_id":"c1","ids":["f1","f2"],
             "delivered_at":"2026-08-12T10:00:09Z"}
            """.trimIndent(),
        )

        assertEquals(listOf("f1", "f2"), (decoded as AcpEvent.FeedbackConsumed).ids)
    }

    @Test
    fun `feedback_consumed with no ids decodes to an empty batch`() {
        val decoded = event("""{"type":"feedback_consumed","connection_id":"c1"}""")

        assertTrue((decoded as AcpEvent.FeedbackConsumed).ids.isEmpty())
    }

    // --- attach snapshot ---------------------------------------------------

    @Test
    fun `the attach snapshot carries feedback state too`() {
        // The only recovery when a reconnect's gap is too wide to replay: the events this
        // covers are never re-sent, so anything not read here is lost for the turn.
        val snap = LiveSessionSnapshot.fromWire(
            json.parseToJsonElement(
                """
                {"connection_id":"c1","status":"prompting",
                 "feedback":[{"id":"f1","text":"use UserService","created_at":"","status":"delivered"}],
                 "feedback_tool_available":true,"native_steering_available":false}
                """.trimIndent(),
            ).jsonObject,
            json,
        )

        assertEquals(listOf("f1"), snap.feedback.map { it.id })
        assertTrue(snap.feedback.single().isDelivered)
        assertTrue(snap.feedbackToolAvailable)
        assertFalse(snap.nativeSteeringAvailable)
    }

    @Test
    fun `an attach snapshot without the fields reads as no notes and no channel`() {
        val snap = LiveSessionSnapshot.fromWire(
            json.parseToJsonElement("""{"connection_id":"c1"}""").jsonObject,
            json,
        )

        assertTrue(snap.feedback.isEmpty())
        assertFalse(snap.feedbackToolAvailable)
        assertFalse(snap.nativeSteeringAvailable)
        assertFalse(snap.hasPendingUserMessage)
    }

    @Test
    fun `a prompting snapshot only proves a note would survive once the user message is on it`() {
        // The gate for reattaching to a running turn. The connection loop reports `prompting`
        // BEFORE it emits `user_message`, and that event clears the turn's notes — so a
        // snapshot caught in between describes a turn that would accept a note and then wipe
        // it unread. `pending_user_message` is the server's own record of having emitted it.
        fun snapshot(raw: String) =
            LiveSessionSnapshot.fromWire(json.parseToJsonElement(raw).jsonObject, json)

        assertFalse(
            snapshot("""{"connection_id":"c1","status":"prompting"}""").hasPendingUserMessage,
        )
        assertTrue(
            snapshot(
                """{"connection_id":"c1","status":"prompting",
                    "pending_user_message":{"message_id":"m1","blocks":[]}}""",
            ).hasPendingUserMessage,
        )
    }
}
