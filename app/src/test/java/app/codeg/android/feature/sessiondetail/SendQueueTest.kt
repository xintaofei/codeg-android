package app.codeg.android.feature.sessiondetail

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** The send queue: one message at a time, in order, and never from inside a turn's end. */
@OptIn(ExperimentalCoroutinesApi::class)
class SendQueueTest {

    @Test
    fun `a queued message goes out at once when nothing bounced recently`() {
        assertEquals(0L, flushRetryDelayMs(now = 50_000, lastBounceAt = 49_000))
    }

    @Test
    fun `a just-bounced retry waits out the rest of the gap`() {
        assertEquals(1_000L, flushRetryDelayMs(now = 50_000, lastBounceAt = 50_000))
        assertEquals(300L, flushRetryDelayMs(now = 50_000, lastBounceAt = 49_300))
    }

    @Test
    fun `a bounce stamped in the future waits the full gap, never more`() {
        assertEquals(1_000L, flushRetryDelayMs(now = 50_000, lastBounceAt = 90_000))
    }

    private fun item(id: String) = QueuedPrompt(id, "msg $id")

    private class Harness(
        val ui: MutableStateFlow<SessionDetailUiState>,
        val queue: SendQueueController,
        val sent: MutableList<String>,
        var held: Boolean = false,
    )

    /** The collector runs on an IMMEDIATE dispatcher, as the ViewModel's `Main.immediate` scope does. */
    private fun TestScope.harness(vararg queued: String, inFlight: Boolean = true): Harness {
        val ui = MutableStateFlow(SessionDetailUiState(isNew = false, isInFlight = inFlight, queue = queued.map(::item)))
        val sent = mutableListOf<String>()
        lateinit var h: Harness
        val queue = SendQueueController(
            scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
            ui = ui,
            flushDispatcher = StandardTestDispatcher(testScheduler),
            clock = { testScheduler.currentTime },
            held = { h.held },
            // A send puts the session in flight, as the ViewModel's does.
            sendQueued = { next ->
                sent += next.id
                ui.update { it.copy(isInFlight = true) }
            },
        )
        h = Harness(ui, queue, sent)
        queue.start()
        return h
    }

    @Test
    fun `a turn's end never sends the next message from inside itself`() = runTest {
        val h = harness("B", "C")
        runCurrent()
        // A turn ends: the composer is released first, and the hand-off of the turn follows
        // in the same call. Nothing may be sent before that call returns.
        h.ui.update { it.copy(isInFlight = false) }
        assertEquals(emptyList<String>(), h.sent)
        runCurrent()
        // Then exactly one goes out; the next waits for the turn it started.
        assertEquals(listOf("B"), h.sent)
        assertEquals(listOf("C"), h.ui.value.queue.map { it.id })
        h.ui.update { it.copy(isInFlight = false) }
        runCurrent()
        assertEquals(listOf("B", "C"), h.sent)
    }

    @Test
    fun `after a bounce the next attempt waits out the backoff`() = runTest {
        val h = harness(inFlight = true)
        runCurrent()
        h.queue.noteBounce()
        h.queue.enqueue(item("B"), front = true)
        h.ui.update { it.copy(isInFlight = false) }
        advanceTimeBy(QUEUE_FLUSH_RETRY_BACKOFF_MS - 1)
        runCurrent()
        assertEquals(emptyList<String>(), h.sent)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf("B"), h.sent)
    }

    @Test
    fun `a held queue waits until re-armed`() = runTest {
        val h = harness("B", inFlight = true)
        h.held = true
        runCurrent()
        h.ui.update { it.copy(isInFlight = false) }
        runCurrent()
        assertEquals(emptyList<String>(), h.sent)
        h.held = false
        h.queue.schedule()
        runCurrent()
        assertEquals(listOf("B"), h.sent)
    }

    @Test
    fun `a failed queued send goes back to the head and holds the rest until resumed`() = runTest {
        val h = harness("C", inFlight = true)
        runCurrent()
        // "B" was sent and failed: it returns ahead of "C", in the same write that frees the
        // session, and nothing goes out on its own.
        h.ui.update { it.pausedAfterFailure(requeue = item("B")).copy(isInFlight = false) }
        runCurrent()
        assertEquals(emptyList<String>(), h.sent)
        assertEquals(listOf("B", "C"), h.ui.value.queue.map { it.id })
        assertEquals(true, h.ui.value.queuePaused)
        // An explicit re-arm (another hold clearing) doesn't lift it either.
        h.queue.schedule()
        runCurrent()
        assertEquals(emptyList<String>(), h.sent)
        h.queue.resume()
        runCurrent()
        assertEquals(listOf("B"), h.sent)
    }

    @Test
    fun `a died turn holds the queue, and the user's next message resumes it`() = runTest {
        val h = harness("B", inFlight = true)
        runCurrent()
        h.ui.update { it.pausedAfterFailure(requeue = null) }
        h.ui.update { it.copy(isInFlight = false) }
        runCurrent()
        assertEquals(emptyList<String>(), h.sent)
        h.queue.enqueue(item("C"), front = false, resume = true)
        runCurrent()
        assertEquals(listOf("B"), h.sent)
        assertEquals(listOf("C"), h.ui.value.queue.map { it.id })
    }

    @Test
    fun `a pause never outlives the queue it held`() {
        // Nothing queued: a failure has nothing to hold.
        assertEquals(false, SessionDetailUiState(isNew = false).pausedAfterFailure(requeue = null).queuePaused)
        val paused = SessionDetailUiState(isNew = false, queue = listOf(item("B"), item("C")), queuePaused = true)
        val one = paused.withQueue(paused.queue.drop(1))
        assertEquals(true, one.queuePaused)
        // The last one removed or taken back to edit: the next message queued must not
        // inherit a hold nobody can see the reason for.
        assertEquals(false, one.withQueue(emptyList()).queuePaused)
        // Nor does a change of queue invent one.
        assertEquals(false, SessionDetailUiState(isNew = false).withQueue(listOf(item("B"))).queuePaused)
    }

    @Test
    fun `a bounced message goes back ahead of the rest, a direct send behind them`() {
        val queue = listOf(item("C"))
        assertEquals(listOf("B", "C"), SendQueueController.queued(queue, item("B"), front = true).map { it.id })
        assertEquals(listOf("C", "D"), SendQueueController.queued(queue, item("D"), front = false).map { it.id })
    }
}
