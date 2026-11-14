package app.codeg.android.feature.sessiondetail

import app.codeg.android.core.model.ConnectionStatus
import app.codeg.android.core.model.SessionLastError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Whether a reattach snapshot may blame the turn on screen for the failure it carries.
 *
 * `SessionLastError` has no turn identity. Attributing it wrongly is not a cosmetic miss: it
 * prints the PREVIOUS turn's failure, with its stderr, at the tail of the turn the user is
 * watching run. Exactly one fact dates it — the server clears `last_error` in the same arm
 * that writes `status = Prompting` — so these tests are mostly about the gates that look like
 * proof and are not.
 */
class SnapshotTurnErrorTest {

    private val fallback = "The agent reported an error."

    private fun error(details: String? = "stderr:\n  Error: 401", message: String = "Empty turn.") =
        SessionLastError(message = message, code = "turn_failed_empty", details = details)

    private fun resolve(
        status: ConnectionStatus?,
        lastError: SessionLastError? = error(),
        current: LiveTurnError? = null,
    ) = snapshotTurnError(status, lastError, current, fallback)

    @Test
    fun `a prompting snapshot dates the error to this turn`() {
        assertEquals(
            LiveTurnError("Empty turn.", "stderr:\n  Error: 401"),
            resolve(ConnectionStatus.PROMPTING),
        )
    }

    @Test
    fun `an idle snapshot is refused however live the session looks`() {
        // The tempting gates, each of which the server can satisfy with a STALE error:
        //  - `connected`: a prompt is enqueued and its conversation row already says
        //    in_progress long before the loop emits `Prompting` and clears the error, so this
        //    is what the pre-prompting window of a BRAND NEW turn looks like;
        //  - a pending permission held behind the queue lock is published deliberately after
        //    `TurnComplete`, "as the out-of-turn request it now is", so "something is pending"
        //    outlives the turn whose error is still recorded;
        //  - a fork emits `SessionStarted`, which sets `connected` and leaves `last_error`.
        for (status in listOf(ConnectionStatus.CONNECTED, ConnectionStatus.CONNECTING, null)) {
            assertNull("status=$status must not date the error", resolve(status))
        }
    }

    @Test
    fun `an ordinary failure carries nothing to show`() {
        // Evidence-only. Without this scope, every attach to a connection that once hit an
        // ordinary error would re-raise a failure the user already dealt with.
        assertNull(resolve(ConnectionStatus.PROMPTING, error(details = null)))
        assertNull(resolve(ConnectionStatus.PROMPTING, error(details = "   \n ")))
        assertNull(resolve(ConnectionStatus.PROMPTING, lastError = null))
    }

    @Test
    fun `a message from an event we saw outranks the snapshot's`() {
        // The live copy is the localized, specific one; the snapshot only fills a gap.
        assertEquals(
            LiveTurnError("Claude Code could not parse its own output.", "stderr:\n  Error: 401"),
            resolve(
                ConnectionStatus.PROMPTING,
                current = LiveTurnError("Claude Code could not parse its own output."),
            ),
        )
    }

    @Test
    fun `an evidence-only error still gets a headline`() {
        // `details` renders as a disclosure hanging under the message, so evidence with no
        // message would draw an unlabelled expander.
        assertEquals(
            LiveTurnError(fallback, "stderr:\n  Error: 401"),
            resolve(ConnectionStatus.PROMPTING, error(message = "  ")),
        )
    }
}
