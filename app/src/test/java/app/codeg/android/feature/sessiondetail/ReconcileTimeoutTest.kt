package app.codeg.android.feature.sessiondetail

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the mode/config pickers show when the ~2.3s reconcile budget runs out without the
 * agent confirming the pick.
 *
 * The set call only enqueues the change, so a timeout is genuinely ambiguous — and the
 * choice made here is which way to be wrong. Getting it backwards means cline's
 * `auto_approve` reads OFF for a toggle the agent is a second away from turning ON.
 */
class ReconcileTimeoutTest {

    @Test
    fun `a value still sitting where it started keeps the optimistic pick`() {
        // The only thing observed is that nothing has happened YET. An agent slower than the
        // budget is far likelier than one that accepts the request and ignores it.
        assertEquals("true", reconcileTimeout(lastSeen = "false", desired = "true", previous = "false"))
    }

    @Test
    fun `a value that moved somewhere else is adopted`() {
        // The agent normalized the pick — that IS an answer, and it's authoritative.
        assertEquals("medium", reconcileTimeout(lastSeen = "medium", desired = "high", previous = "low"))
    }

    @Test
    fun `no readable snapshot keeps the optimistic pick`() {
        // Every poll failed or reported nothing for this key: no evidence either way.
        assertEquals("true", reconcileTimeout(lastSeen = null, desired = "true", previous = "false"))
        assertEquals("true", reconcileTimeout(lastSeen = null, desired = "true", previous = null))
    }

    @Test
    fun `an unknown starting point cannot prove movement`() {
        // `previous` is null when the option had no value on screen to begin with. A snapshot
        // value then tells us what the agent holds, but not whether the apply caused it — so
        // it is not treated as a normalization.
        assertEquals("false", reconcileTimeout(lastSeen = "false", desired = "false", previous = null))
        assertEquals("high", reconcileTimeout(lastSeen = "low", desired = "high", previous = null))
    }

    @Test
    fun `a snapshot that agrees with the pick is returned unchanged`() {
        // Totality, not a reachable path: the polling loop returns the moment a snapshot
        // matches, including on the final attempt, so this input never arrives in production.
        // Pinned so the helper stays safe to call from anywhere.
        assertEquals("true", reconcileTimeout(lastSeen = "true", desired = "true", previous = "false"))
    }
}
