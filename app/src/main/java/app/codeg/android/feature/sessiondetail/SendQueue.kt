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
 * becoming idle flushes — a turn's end, a failed send, a bounce's backoff running out — and
 * the caller re-arms it ([schedule]) when one of its own [held] reasons clears. One message
 * at a time: the send it starts puts the session back in flight, which holds the rest.
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

    /** Start flushing whenever there is something queued and nothing running. */
    fun start() {
        scope.launch {
            ui.map { it.queue.isNotEmpty() && !it.isInFlight && !it.loading }
                .distinctUntilChanged()
                .collect { ready -> if (ready) schedule() }
        }
    }

    fun enqueue(item: QueuedPrompt, front: Boolean) {
        ui.update { it.copy(queue = queued(it.queue, item, front)) }
    }

    fun remove(id: String) {
        ui.update { it.copy(queue = it.queue.filterNot { q -> q.id == id }) }
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
            if (state.isInFlight || state.loading || held()) return@launch
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
