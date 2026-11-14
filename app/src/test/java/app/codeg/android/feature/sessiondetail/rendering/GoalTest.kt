package app.codeg.android.feature.sessiondetail.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A goal's card (web `goal-tool-call.tsx` `parseGoal`). */
class GoalTest {

    private fun call(name: String, input: String?, output: String?) =
        ToolCallVM.of("g", name, "", ToolCallState.DONE, input, output, null, false)

    @Test
    fun `the result goal speaks first, then the input, and a create is active`() {
        val created = Goals.parse(call("create_goal", """{"objective":"Ship the port","token_budget":200000}""", null))
        assertEquals("Ship the port", created.objective)
        assertEquals("active", created.normalizedStatus)
        assertEquals(200000.0, created.tokenBudget!!, 0.0)
        val updated = Goals.parse(
            call(
                "mcp__codex__update_goal",
                """{"status":"complete"}""",
                """{"goal":{"objective":"Ship the port","status":"Budget-Limited","tokensUsed":1234,"timeUsedSeconds":95},"remainingTokens":0}""",
            ),
        )
        assertEquals("budget_limited", updated.normalizedStatus)
        assertTrue(updated.failed)
        assertEquals(1234.0, updated.tokensUsed!!, 0.0)
        assertEquals(0.0, updated.remainingTokens!!, 0.0)
        assertNull(Goals.parse(call("update_goal", null, null)).status)
    }

    @Test
    fun `figures read the way the web gives them`() {
        assertEquals("1.2K", Goals.tokens(1234.0))
        assertEquals("2K", Goals.tokens(2000.0))
        assertEquals("340", Goals.tokens(340.0))
        assertEquals("42s", Goals.duration(42.0))
        assertEquals("1.5m", Goals.duration(90.0))
        assertEquals("2h", Goals.duration(7200.0))
    }

    @Test
    fun `goal calls are told by their bare name`() {
        assertTrue(Goals.isGoalCall("create_goal"))
        assertTrue(Goals.isGoalCall("mcp__codex__update_goal"))
        assertFalse(Goals.isGoalCall("get_goal_status"))
    }
}
