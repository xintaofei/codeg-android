package app.codeg.android.feature.sessiondetail

import app.codeg.android.core.model.AgentType
import app.codeg.android.core.model.PluginLoadFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The one-shot advisories (web `session-notices.test.ts`, `plugin-load-failures.test.ts`). */
class SessionNoticesTest {

    @Test
    fun `a notice's first line heads it, the rest and its description follow`() {
        assertEquals(
            SessionNotices.Headline("Model switched", "to sonnet\nbecause of load"),
            SessionNotices.sessionNotice("warning", "\nModel switched\nto sonnet", "because of load"),
        )
        assertEquals(SessionNotices.Headline("Heads up", null), SessionNotices.sessionNotice("info", "Heads up", "  "))
        assertNull(SessionNotices.sessionNotice("info", "   ", null))
    }

    @Test
    fun `a notice that only restates a compaction isn't told`() {
        assertNull(SessionNotices.sessionNotice("info", "Context compacted", null))
        assertNull(SessionNotices.sessionNotice("warning", "Heads up", "This thread has had multiple compactions"))
        assertEquals("Context compacted", SessionNotices.sessionNotice("warning", "Context compacted", null)?.title)
    }

    @Test
    fun `failed plugins are counted once each, and a positional tag gives way to its path`() {
        val failures = listOf(
            PluginLoadFailure("lint@market", "hook-load-failed", "hook x failed"),
            PluginLoadFailure("lint@market", "generic-error", ""),
            PluginLoadFailure("inline[0]", "path-not-found", "missing", path = "/p/plug"),
        )
        val told = SessionNotices.pluginFailures(failures)!!
        assertEquals(2, told.count)
        assertEquals("lint@market: hook x failed\nlint@market (generic-error)\n/p/plug: missing", told.description)
        assertNull(SessionNotices.pluginFailures(emptyList()))
    }

    @Test
    fun `only codex, and only a real session id, gets a command to paste`() {
        val id = "019a2b3c-4d5e-6f70-8192-a3b4c5d6e7f8"
        assertEquals("codex unarchive $id", SessionNotices.recoveryCommand("session_archived", AgentType.CODEX, id))
        assertNull(SessionNotices.recoveryCommand("session_archived", AgentType.CLAUDE_CODE, id))
        assertNull(SessionNotices.recoveryCommand("session_archived", AgentType.CODEX, "$id; rm -rf ~"))
        assertNull(SessionNotices.recoveryCommand("session_busy", AgentType.CODEX, id))
    }
}
