package app.codeg.android.feature.sessiondetail

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// The send queue (codeg web 0.44 `useMessageQueue` + `queue-flush.ts`): a message written while
// a turn runs waits for it to end, then goes out on its own, one at a time, in order.

/** A message waiting for the running turn to end. */
data class QueuedPrompt(
    val id: String,
    val text: String,
    val attachments: List<Attachment> = emptyList(),
)

/** Minimum gap between flush attempts after a send bounced off a turn already running. */
internal const val QUEUE_FLUSH_RETRY_BACKOFF_MS = 1_000L

/**
 * How long to wait before the next flush attempt, given [now] and when a send last bounced
 * ([lastBounceAt]). Zero unless that was recent: a queued message goes out
 * promptly, only a just-bounced retry waits.
 *
 * The wait is what bounds the retry RATE. A bounce means the server is running a turn this
 * client doesn't know about — another client's, before its `prompting` reaches the stream —
 * and without it every flush would bounce straight back, one round trip after another.
 * Elapsed time is clamped at zero, so a clock that went backwards waits the full gap rather
 * than an unbounded one.
 */
internal fun flushRetryDelayMs(now: Long, lastBounceAt: Long, backoffMs: Long = QUEUE_FLUSH_RETRY_BACKOFF_MS): Long {
    val elapsed = maxOf(0L, now - lastBounceAt)
    return if (elapsed >= backoffMs) 0L else backoffMs - elapsed
}

/**
 * The queue in [SessionDetailUiState.queue] and when its head goes out.
 *
 * Flushing is level-triggered off the state rather than off turn ends, so every way of
 * becoming idle flushes — a turn's end, a bounce's backoff running out, a pause lifted — and
 * the caller re-arms it ([schedule]) when one of its own [held] reasons clears. One message
 * at a time: the send it starts puts the session back in flight, which holds the rest. A
 * failure pauses it instead ([pausedAfterFailure]) until the user says go on.
 *
 * @param flushDispatcher runs the flush, and must DISPATCH rather than run it in place. The
 *   state turns idle in the middle of a turn's end (the composer is released before the turn
 *   is handed off), and a collector resumed on an immediate dispatcher would send the next
 *   message from inside that — handing ITS fresh turn off as the old one's. Dispatching puts
 *   the flush after whatever made the state idle has finished.
 * @param held the caller's reasons to wait, checked when the flush runs.
 * @param sendQueued sends a message taken off the head; it must put the session in flight.
 */
internal class SendQueueController(
    private val scope: CoroutineScope,
    private val ui: MutableStateFlow<SessionDetailUiState>,
    private val flushDispatcher: CoroutineDispatcher,
    private val clock: () -> Long,
    private val held: () -> Boolean,
    private val sendQueued: (QueuedPrompt) -> Unit,
) {
    private var flushJob: Job? = null
    /** When a send last bounced ([clock]); null if none has. */
    private var lastBounceAt: Long? = null

    /** Start flushing whenever there is something queued, nothing running, and no hold. */
    fun start() {
        scope.launch {
            ui.map { it.queue.isNotEmpty() && !it.isInFlight && !it.loading && !it.queuePaused }
                .distinctUntilChanged()
                .collect { ready -> if (ready) schedule() }
        }
    }

    /**
     * Queue [item]. One the user just wrote ([resume]) is also how they take a paused queue
     * off hold: sending again says "go on".
     */
    fun enqueue(item: QueuedPrompt, front: Boolean, resume: Boolean = false) {
        ui.update {
            it.copy(queue = queued(it.queue, item, front), queuePaused = it.queuePaused && !resume)
        }
    }

    fun remove(id: String) {
        ui.update { it.withQueue(it.queue.filterNot { q -> q.id == id }) }
    }

    /** "Send now" on a paused queue. */
    fun resume() {
        ui.update { if (it.queuePaused) it.copy(queuePaused = false) else it }
    }

    /** A send just bounced off a busy turn: hold the next flush back by the backoff. */
    fun noteBounce() {
        lastBounceAt = clock()
    }

    /** Send the head once nothing holds it. A no-op while a flush is already pending. */
    fun schedule() {
        if (flushJob?.isActive == true) return
        flushJob = scope.launch(flushDispatcher) {
            delay(lastBounceAt?.let { flushRetryDelayMs(clock(), it) } ?: 0L)
            val state = ui.value
            if (state.isInFlight || state.loading || state.queuePaused || held()) return@launch
            val next = state.queue.firstOrNull() ?: return@launch
            remove(next.id)
            sendQueued(next)
        }
    }

    companion object {
        /** [queue] with [item] added at the head or the tail. */
        fun queued(queue: List<QueuedPrompt>, item: QueuedPrompt, front: Boolean): List<QueuedPrompt> =
            if (front) listOf(item) + queue else queue + item
    }
}

/** This state with [queue] as its queue; a hold ends with the last message it held. */
internal fun SessionDetailUiState.withQueue(queue: List<QueuedPrompt>): SessionDetailUiState =
    copy(queue = queue, queuePaused = queuePaused && queue.isNotEmpty())

/**
 * The queue after a send that FAILED, or a turn that died — not a send that bounced off a busy
 * turn, which waits and retries on its own. A queued message that failed goes back to the head,
 * where it was; and whatever is queued then waits for the user, since it would most likely fail
 * the same way, one by one. (A failed message from the composer or the app goes back to the
 * composer instead: the caller's part.)
 */
internal fun SessionDetailUiState.pausedAfterFailure(requeue: QueuedPrompt?): SessionDetailUiState {
    val queue = if (requeue != null) SendQueueController.queued(queue, requeue, front = true) else queue
    return copy(queue = queue, queuePaused = queue.isNotEmpty())
}
