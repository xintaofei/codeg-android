package app.codeg.android.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The agent identity has to survive a round-trip through the wire unchanged: it is
 * sent back on `create_conversation`, keys the per-agent settings, and keys the lists
 * that render it. Custom (user-registered) agents travel as `custom:<id>`, and a
 * value from a newer server must keep its own identity rather than borrow another
 * agent's.
 */
class AgentTypeTest {

    @Test
    fun `every built-in round-trips through its wire value`() {
        for (agent in AgentType.BUILTINS) {
            assertEquals(agent, AgentType.fromWire(agent.wire))
        }
        assertEquals("claude_code", AgentType.CLAUDE_CODE.wire)
        assertEquals("code_buddy", AgentType.CODE_BUDDY.wire)
        assertEquals(12, AgentType.BUILTINS.size)
    }

    @Test
    fun `a custom agent decodes to its registry id and back`() {
        val agent = AgentType.fromWire("custom:goose")
        assertEquals(AgentType.Custom("goose"), agent)
        assertEquals("custom:goose", agent.wire)
        assertTrue(agent.isCustom)
        assertNull(agent.builtin)
        // With no server row at hand the id is the only label available.
        assertEquals("goose", agent.displayName)
    }

    @Test
    fun `id slugs the server would reject are kept verbatim`() {
        // Mirrors the Rust `is_valid_custom_agent_id`. Parsing one of these as a Custom
        // would rebuild a DIFFERENT wire value ("custom:" + id) and send it back.
        val rejected = listOf(
            "custom:",
            "custom:../escape",
            "custom:a/b",
            "custom:.hidden",
            "custom:" + "x".repeat(65),
            // Would shadow a built-in and make the wire form ambiguous.
            "custom:claude_code",
        )
        for (raw in rejected) {
            val agent = AgentType.fromWire(raw)
            assertTrue(raw, agent is AgentType.Unknown)
            assertEquals(raw, agent.wire)
        }
    }

    @Test
    fun `ids at the edge of the slug rules are accepted`() {
        for (id in listOf("a", "x".repeat(64), "my-agent_1.2", "Goose")) {
            assertTrue(id, AgentType.isValidCustomId(id))
            assertEquals(AgentType.Custom(id), AgentType.fromWire("custom:$id"))
        }
    }

    @Test
    fun `an unrecognised wire value keeps its own identity`() {
        // The old decoder collapsed these onto Claude Code, which mislabelled every
        // session row and produced duplicate keys in agent-keyed lists.
        val a = AgentType.fromWire("future_agent_a")
        val b = AgentType.fromWire("future_agent_b")
        assertEquals(AgentType.Unknown("future_agent_a"), a)
        assertNotEquals(a, b)
        assertNotEquals(AgentType.CLAUDE_CODE, a)
        assertEquals("future_agent_a", a.wire)
        assertFalse(a.isCustom)
        assertNull(a.builtin)
    }

    @Test
    fun `a picker entry labels a custom agent by its registered name`() {
        val custom = AgentChoice.of(
            AcpAgentInfo(agentType = AgentType.Custom("goose"), name = "Goose"),
        )
        assertEquals("Goose", custom.label)

        // No registered name: the id is the fallback, never blank.
        assertEquals(
            "goose",
            AgentChoice.of(AcpAgentInfo(agentType = AgentType.Custom("goose"), name = "  ")).label,
        )
    }

    @Test
    fun `a picker entry keeps the curated label for a built-in`() {
        // The server's `name` for a built-in is a long product name ("Claude Code");
        // the picker is a 64dp cell, so the curated short label wins.
        val choice = AgentChoice.of(AcpAgentInfo(agentType = AgentType.CLAUDE_CODE, name = "Claude Code"))
        assertEquals("Claude", choice.label)
    }
}
