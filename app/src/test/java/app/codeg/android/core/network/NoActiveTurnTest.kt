package app.codeg.android.core.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Telling "the turn already ended" apart from every other refused steering note.
 *
 * It has to come from the message. The HTTP layer folds all three recoverable feedback
 * refusals — turn ended, session has no channel, text empty or oversized — onto one
 * `AppErrorCode::InvalidInput`, so they arrive as the same 400 `invalid_input` and only the
 * `AcpError` Display string tells them apart. Getting this wrong in either direction is
 * user-visible: a missed match blames the user for a race, and a false match claims the turn
 * ended when the note was really rejected for its content.
 */
class NoActiveTurnTest {

    private fun refusal(message: String) =
        ApiError.Server(status = 400, code = "invalid_input", serverMessage = message)

    @Test
    fun `recognises the backend's turn-ended refusal`() {
        assertTrue(refusal("no active turn to send feedback to").isNoActiveTurn)
    }

    @Test
    fun `matches a substring so the server can elaborate the message`() {
        assertTrue(refusal("no active turn to send feedback to (conn c1)").isNoActiveTurn)
    }

    @Test
    fun `the other feedback refusals are not it`() {
        // Both are 400 `invalid_input` too — only the text differs. Treating either as the
        // turn-end race would tell the user to just try again, which cannot work.
        assertFalse(refusal("live feedback is disabled").isNoActiveTurn)
        assertFalse(refusal("invalid feedback: note exceeds 4096 characters").isNoActiveTurn)
    }

    @Test
    fun `transport and auth failures are not it`() {
        assertFalse(ApiError.Transport("timeout").isNoActiveTurn)
        assertFalse(ApiError.Unauthorized.isNoActiveTurn)
        assertFalse(ApiError.TurnInProgress.isNoActiveTurn)
    }

    // --- the "session lost its channel" refusal ----------------------------

    @Test
    fun `recognises the no-channel refusal`() {
        // Distinct from the turn-end race in what it means for the UI: the turn ending is
        // temporary, this one is the session telling us to stop offering notes at all.
        assertTrue(refusal("live feedback is disabled").isFeedbackDisabled)
    }

    @Test
    fun `the turn-end race is not a lost channel`() {
        // Retiring the affordance over a turn that merely finished would take the feature
        // away for the rest of the session.
        assertFalse(refusal("no active turn to send feedback to").isFeedbackDisabled)
        assertFalse(refusal("invalid feedback: note exceeds 4096 characters").isFeedbackDisabled)
        assertFalse(ApiError.Transport("timeout").isFeedbackDisabled)
    }
}
