package app.codeg.android.feature.sessiondetail

import app.codeg.android.core.model.AcpEvent
import app.codeg.android.core.model.AgentType
import app.codeg.android.core.model.SessionConfigKind
import app.codeg.android.core.model.SessionConfigOption
import app.codeg.android.core.model.SessionModeInfo
import app.codeg.android.core.model.SessionModeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The selector-push state machine: what each event does to the state held for one connection,
 * and which revisions it is allowed to move.
 *
 * Two rules here are only visible across a *sequence* of pushes, which is why they are worth
 * testing rather than reading: an empty catalog is ambiguous until `selectors_ready` resolves
 * it, and a bare `mode_changed` must not restamp the catalog it indexes into.
 */
class SelectorPushFoldTest {

    private val empty = LiveSelectors(AgentType.CLINE, connectionId = "conn-1")

    private fun option(id: String, value: String?) =
        SessionConfigOption(id = id, kind = SessionConfigKind(currentValue = value))

    private fun options(vararg ids: String) = AcpEvent.SessionConfigOptions(ids.map { option(it, "on") })

    private val modes = SessionModeState(
        currentModeId = "default",
        availableModes = listOf(SessionModeInfo("default"), SessionModeInfo("plan")),
    )

    /** Apply pushes in order, using the index as the (monotonic) revision. */
    private fun fold(vararg events: AcpEvent): LiveSelectors =
        events.foldIndexed(empty) { i, state, event -> state.foldSelectorPush(event, i + 1) }

    @Test
    fun `a catalog push publishes at its own revision`() {
        val state = fold(options("effort"))
        assertEquals(listOf("effort"), state.configOptions?.value?.map { it.id })
        assertEquals(1, state.configOptions?.revision)
        assertFalse(state.emptyConfigHeld)
    }

    @Test
    fun `an empty catalog before the latch is parked, not published`() {
        // The setup transient: publishing it would clear every control off an open sheet for as
        // long as the agent takes to push the real list.
        val state = fold(AcpEvent.SessionConfigOptions(emptyList()))
        assertNull(state.configOptions)
        assertTrue(state.emptyConfigHeld)
    }

    @Test
    fun `the real catalog clears a parked empty one`() {
        val state = fold(AcpEvent.SessionConfigOptions(emptyList()), options("effort"), AcpEvent.SelectorsReady)
        assertEquals(listOf("effort"), state.configOptions?.value?.map { it.id })
        assertEquals(2, state.configOptions?.revision)
        assertFalse(state.emptyConfigHeld)
        assertTrue(state.selectorsReady)
    }

    @Test
    fun `the latch publishes a parked empty catalog`() {
        // An agent with nothing to configure emits exactly this pair. Dropping the empty list
        // and never revisiting it would leave a previous connection's controls on screen.
        val state = fold(AcpEvent.SessionConfigOptions(emptyList()), AcpEvent.SelectorsReady)
        assertEquals(emptyList<SessionConfigOption>(), state.configOptions?.value)
        assertEquals(2, state.configOptions?.revision)
        assertFalse(state.emptyConfigHeld)
    }

    @Test
    fun `an empty catalog after the latch is published straight away`() {
        val state = fold(options("effort"), AcpEvent.SelectorsReady, AcpEvent.SessionConfigOptions(emptyList()))
        assertEquals(emptyList<SessionConfigOption>(), state.configOptions?.value)
        assertEquals(3, state.configOptions?.revision)
    }

    @Test
    fun `session modes publishes catalog and pick together`() {
        val state = fold(AcpEvent.SessionModes(modes))
        assertEquals(modes, state.modes?.value)
        assertEquals(1, state.modes?.revision)
        assertEquals("default", state.currentModeId?.value)
        assertEquals(1, state.currentModeId?.revision)
    }

    @Test
    fun `mode changed moves the pick and leaves the catalog's revision alone`() {
        // The sheet applies each at its own revision, so restamping the catalog here would
        // republish it over a catalog the sheet's own load may have already corrected.
        val state = fold(AcpEvent.SessionModes(modes), AcpEvent.ModeChanged("plan"))
        assertEquals("plan", state.currentModeId?.value)
        assertEquals(2, state.currentModeId?.revision)
        assertEquals(modes, state.modes?.value)
        assertEquals(1, state.modes?.revision)
    }

    @Test
    fun `mode changed with no catalog yet still records the pick`() {
        val state = fold(AcpEvent.ModeChanged("plan"))
        assertNull(state.modes)
        assertEquals("plan", state.currentModeId?.value)
    }

    @Test
    fun `a config push leaves both mode revisions alone, and the reverse`() {
        val state = fold(AcpEvent.SessionModes(modes), options("effort"))
        assertEquals(1, state.modes?.revision)
        assertEquals(1, state.currentModeId?.revision)
        assertEquals(2, state.configOptions?.revision)

        val other = fold(options("effort"), AcpEvent.SessionModes(modes))
        assertEquals(1, other.configOptions?.revision)
        assertEquals(2, other.modes?.revision)
    }

    @Test
    fun `session modes with no pick keeps the last one`() {
        val state = fold(
            AcpEvent.ModeChanged("plan"),
            AcpEvent.SessionModes(SessionModeState(currentModeId = null, availableModes = modes.availableModes)),
        )
        assertEquals("plan", state.currentModeId?.value)
        assertEquals(1, state.currentModeId?.revision)
        assertEquals(2, state.modes?.revision)
    }

    @Test
    fun `an unrelated event changes nothing`() {
        assertEquals(empty, empty.foldSelectorPush(AcpEvent.TurnComplete("end_turn"), 9))
    }
}
