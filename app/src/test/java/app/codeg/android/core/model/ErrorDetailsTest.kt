package app.codeg.android.core.model

import app.codeg.android.core.network.CodegJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The wire contract for an error's diagnostic evidence.
 *
 * `details` is the only account a user ever gets of an *inferred* failure — a turn where the
 * agent claimed success and produced nothing — so a decode that silently drops it turns a
 * diagnosable problem back into "it just didn't answer". It rides two routes: the live `error`
 * event, and `last_error` on the attach snapshot for clients that arrived after the fact.
 */
class ErrorDetailsTest {

    private val json: Json = CodegJson.response

    private fun event(raw: String): AcpEvent =
        AcpEvent.fromWire(json.parseToJsonElement(raw).jsonObject, json)

    private fun snapshot(raw: String): LiveSessionSnapshot =
        LiveSessionSnapshot.fromWire(json.parseToJsonElement(raw).jsonObject, json)

    // --- live event ---------------------------------------------------------

    @Test
    fun `an inferred empty turn carries its evidence`() {
        val e = event(
            """
            {"type":"error","connection_id":"c1","agent_type":"claude_code",
             "code":"turn_failed_empty_protocol",
             "message":"Claude Code produced output that codeg could not parse.",
             "details":"dropped 2 update(s) (1 decode, 1 dispatch)\nstderr (this turn, last 1 lines):\n  Error: 401"}
            """.trimIndent(),
        ) as AcpEvent.Error

        assertEquals("turn_failed_empty_protocol", e.code)
        assertEquals(
            "dropped 2 update(s) (1 decode, 1 dispatch)\nstderr (this turn, last 1 lines):\n  Error: 401",
            e.details,
        )
    }

    @Test
    fun `an ordinary error has no evidence`() {
        // The server omits the key entirely for every error it RECEIVED rather than inferred,
        // and for every pre-0.24.0 server. Absent must read as "nothing to show", which is
        // what keeps the disclosure off errors that have nothing behind it.
        val e = event(
            """{"type":"error","connection_id":"c1","agent_type":"codex",
                "code":"turn_failed_refusal","message":"Codex refused to continue."}""",
        ) as AcpEvent.Error

        assertNull(e.details)
    }

    // --- attach snapshot ----------------------------------------------------

    @Test
    fun `the snapshot carries the connection's last error`() {
        val snap = snapshot(
            """
            {"connection_id":"c1","status":"connected",
             "last_error":{"message":"Claude Code sent only status updates this turn and no reply.",
                           "code":"turn_failed_empty_metadata",
                           "details":"stderr (recent, last 1 lines):\n  connect-time failure"}}
            """.trimIndent(),
        )

        val err = requireNotNull(snap.lastError)
        assertEquals("turn_failed_empty_metadata", err.code)
        assertEquals("stderr (recent, last 1 lines):\n  connect-time failure", err.details)
    }

    @Test
    fun `a healthy connection reports no last error`() {
        assertNull(snapshot("""{"connection_id":"c1","status":"prompting"}""").lastError)
    }

    @Test
    fun `a last error with no evidence still decodes`() {
        // `details` is optional independently of `last_error`: an ordinary failure records the
        // message and code with nothing behind them. It must not collapse the whole object to
        // null — the attach path decides what to surface, and it can only decide on facts it
        // was given.
        val err = requireNotNull(
            snapshot(
                """{"connection_id":"c1","last_error":{"message":"boom","code":"spawn_failed"}}""",
            ).lastError,
        )

        assertEquals("boom", err.message)
        assertEquals("spawn_failed", err.code)
        assertNull(err.details)
    }

    @Test
    fun `a last error missing its message decodes to an empty one rather than failing`() {
        // Whole-frame tolerance: a shape surprise here would otherwise cost the attach its
        // live message, tool calls and pending cards — everything else in the same snapshot.
        val err = requireNotNull(
            snapshot("""{"connection_id":"c1","last_error":{"details":"stderr: …"}}""").lastError,
        )

        assertEquals("", err.message)
        assertNull(err.code)
        assertEquals("stderr: …", err.details)
    }
}
