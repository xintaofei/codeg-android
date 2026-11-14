package app.codeg.android.feature.sessiondetail

import app.codeg.android.core.model.SessionConfigKind
import app.codeg.android.core.model.SessionConfigOption
import app.codeg.android.core.model.SessionModeInfo
import app.codeg.android.core.model.SessionModeState
import app.codeg.android.core.model.SessionSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Reading a selector's authoritative value back off a session snapshot.
 *
 * `"mode"` is both the session-mode selector and a real config-option id — codeg synthesizes
 * an "Approval Preset" option with exactly that id for Codex, and guards against overwriting
 * one an agent already advertises. A string key can't tell the two apart, and an apply that
 * reconciled the wrong one would adopt an unrelated value as if the agent had normalized to it.
 */
class SelectorKeyTest {

    /** A Codex-shaped snapshot: a session mode AND a config option, both called "mode". */
    private val snapshot = SessionSnapshot(
        modes = SessionModeState(
            currentModeId = "plan",
            availableModes = listOf(SessionModeInfo("plan"), SessionModeInfo("default")),
        ),
        configOptions = listOf(
            SessionConfigOption(
                id = "mode",
                name = "Approval Preset",
                kind = SessionConfigKind(type = "select", currentValue = "read-only"),
            ),
            SessionConfigOption(
                id = "auto_approve",
                kind = SessionConfigKind(type = "boolean", currentValue = "true"),
            ),
        ),
    )

    @Test
    fun `the mode selector reads the session mode`() {
        assertEquals("plan", snapshot.selectionFor(SelectorKey.Mode))
    }

    @Test
    fun `a config option named mode reads its own value, not the session mode`() {
        assertEquals("read-only", snapshot.selectionFor(SelectorKey.Config("mode")))
    }

    @Test
    fun `a boolean option reads its coerced string value`() {
        assertEquals("true", snapshot.selectionFor(SelectorKey.Config("auto_approve")))
    }

    @Test
    fun `an option the snapshot does not carry reads as absent`() {
        // `reconcileSelection` treats null as "this poll told us nothing" and keeps polling,
        // rather than as an answer.
        assertNull(snapshot.selectionFor(SelectorKey.Config("nope")))
        assertNull(SessionSnapshot().selectionFor(SelectorKey.Mode))
    }
}
