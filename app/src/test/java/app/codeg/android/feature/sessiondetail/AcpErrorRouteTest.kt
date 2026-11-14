package app.codeg.android.feature.sessiondetail

import app.codeg.android.feature.sessiondetail.AcpErrorRoute.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the web's `acp-error-presentation.ts` table and its `case "error"` turn rule. */
class AcpErrorRouteTest {

    @Test
    fun `a failed-turn verdict is the turn's failure line, as codeg's account`() {
        for (code in listOf(
            "turn_failed_auth_required", "turn_failed_empty", "turn_failed_empty_protocol",
            "turn_failed_empty_metadata", "turn_failed_unknown",
        )) {
            val d = AcpErrorRoute.decide(code, "Nothing came back")
            assertTrue(code, d.failsTurn)
            assertFalse(code, d.fromAgent)
            assertEquals(Kind.SESSION, d.kind)
        }
    }

    @Test
    fun `a turn that ended early with output is told, not kept`() {
        for (code in listOf("turn_failed_refusal", "turn_failed_max_tokens", "turn_failed_max_turn_requests")) {
            val d = AcpErrorRoute.decide(code, "Stopped")
            assertFalse(code, d.failsTurn)
            assertEquals(Kind.SESSION, d.kind)
        }
    }

    @Test
    fun `a rejected prompt is the agent's own words without the protocol prefix`() {
        val d = AcpErrorRoute.decide(null, "ACP protocol error: Invalid params: bad model")
        assertTrue(d.failsTurn)
        assertTrue(d.fromAgent)
        assertEquals("Invalid params: bad model", d.text)
    }

    @Test
    fun `mid-turn recoverable codes never touch the turn`() {
        val expected = mapOf(
            "image_dropped" to Kind.ACTION,
            "set_mode_failed" to Kind.ACTION,
            "set_config_option_failed" to Kind.ACTION,
            "goal_control_failed" to Kind.ACTION,
            "grok_model_switch_incompatible_agent" to Kind.ACTION,
            "session_load_fallback" to Kind.SESSION,
            "agent_runtime_outdated" to Kind.SESSION,
            "compaction_failed" to Kind.TRANSCRIPT,
        )
        for ((code, kind) in expected) {
            val d = AcpErrorRoute.decide(code, "msg")
            assertFalse(code, d.failsTurn)
            assertEquals(code, kind, d.kind)
        }
    }

    @Test
    fun `an unknown code is never demoted below a session error`() {
        val d = AcpErrorRoute.decide("something_new", "msg")
        assertEquals(Kind.SESSION, d.kind)
        assertFalse(d.failsTurn)
    }
}
