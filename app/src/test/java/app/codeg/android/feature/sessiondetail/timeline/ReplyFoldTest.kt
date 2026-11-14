package app.codeg.android.feature.sessiondetail.timeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which replies show their steps: the web's round model. */
class ReplyFoldTest {

    private fun step(prev: ReplyFoldState, signal: Int, running: Boolean, runId: String? = null) =
        advanceReplyFold(prev, ReplyFoldInput(signal, running, runId))

    @Test
    fun `a send folds what is above it and arms the reply it starts`() {
        val opened = ReplyFoldState(signal = 0, epoch = 3, armed = true, running = false, runId = "live-1", roundOpen = false)
        // This client's send starts its live reply in the same update.
        val sent = step(opened, signal = 1, running = true, runId = "live-2")
        assertEquals(4, sent.epoch)
        assertTrue(sent.armed)
        assertTrue(sent.roundOpen)
        // The reply settling folds nothing.
        val settled = step(sent, signal = 1, running = false, runId = null)
        assertTrue(settled.armed)
        assertTrue(settled.roundOpen)
        assertEquals(4, settled.epoch)
    }

    @Test
    fun `a reply starting without a send is a new round`() {
        val idle = ReplyFoldState(signal = 2, epoch = 1)
        val started = step(idle, signal = 2, running = true, runId = "live-9")
        assertEquals(2, started.epoch)
        assertTrue(started.armed)
    }

    @Test
    fun `the same reply resuming, or renamed while it runs, is not a new round`() {
        val running = ReplyFoldState(signal = 1, epoch = 5, armed = true, running = true, runId = "live-1", roundOpen = false)
        val dipped = step(running, signal = 1, running = false, runId = "live-1")
        val resumed = step(dipped, signal = 1, running = true, runId = "live-1")
        assertEquals(5, resumed.epoch)
        assertFalse(resumed.roundOpen)
        val renamed = step(running, signal = 1, running = true, runId = "live-2")
        assertEquals(5, renamed.epoch)
        assertEquals("live-2", renamed.runId)
        assertSame(renamed, step(renamed, signal = 1, running = true, runId = "live-2"))
    }

    @Test
    fun `a settled reply outside the round is folded unless the reader opened it this epoch`() {
        val fold = ReplyFoldState(epoch = 2, armed = true, roundOpen = false)
        assertFalse(replyOpen(fold, currentRound = false, manual = null, completed = true))
        assertTrue(replyOpen(fold, currentRound = false, manual = ManualFold(2, true), completed = true))
        // Opened before the last send: folded again.
        assertFalse(replyOpen(fold, currentRound = false, manual = ManualFold(1, true), completed = true))
        // A reply being written shows its steps.
        assertTrue(replyOpen(fold, currentRound = false, manual = null, completed = false))
        // The current round follows its own toggle.
        assertFalse(replyOpen(fold, currentRound = true, manual = ManualFold(2, true), completed = true))
    }

    @Test
    fun `a round giving way without a send keeps its expansion`() {
        val fold = ReplyFoldState(epoch = 4, armed = true, roundOpen = true)
        assertEquals("r1" to ManualFold(4, true), carriedFold(fold, previousRound = "r1", previousEpoch = 4, currentRound = "r2"))
        // A send or a new round moved the epoch: everything above folds instead.
        assertNull(carriedFold(fold, previousRound = "r1", previousEpoch = 3, currentRound = "r2"))
        assertNull(carriedFold(fold, previousRound = "r1", previousEpoch = 4, currentRound = "r1"))
    }
}
