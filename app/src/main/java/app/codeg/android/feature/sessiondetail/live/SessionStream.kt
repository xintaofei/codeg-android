package app.codeg.android.feature.sessiondetail.live

import app.codeg.android.core.model.AcpEvent
import app.codeg.android.core.model.LiveSessionSnapshot
import app.codeg.android.core.network.EventStream
import app.codeg.android.core.network.StreamFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/** Detach reason for a connection that is gone server-side: no socket reopen recovers it. */
const val REASON_CONNECTION_GONE = "connection_gone"

/** Thrown by [SessionStream.attach] when the server doesn't confirm the subscription: it
 *  detached or closed first (a stale/unknown connection), or the confirmation timed out. */
class StreamAttachFailed(val reason: String?) : Exception("stream attach failed: ${reason ?: "closed"}")

/** One WebSocket session's frames and its outgoing attach/detach. [EventStream] in production;
 *  a fake in tests. */
interface FrameSource {
    fun frames(): Flow<StreamFrame>
    fun attach(subscriptionId: String, connectionId: String, sinceSeq: Long?)
}

fun EventStream.asFrameSource(): FrameSource = object : FrameSource {
    override fun frames(): Flow<StreamFrame> = this@asFrameSource.frames()
    override fun attach(subscriptionId: String, connectionId: String, sinceSeq: Long?) =
        this@asFrameSource.attach(subscriptionId, connectionId, sinceSeq)
}

/**
 * The event subscription to ONE ACP connection: socket, attach handshake, silent re-attach
 * with backoff, and the event cursor.
 *
 * Owns no session state. It delivers what the server sends to a [Listener] and decides only
 * transport questions: whether a frame is new (events at or below the cursor are dropped, so
 * a replay overlapping what was already applied can't double a reply), when to reopen the
 * socket, and when to give up.
 *
 * The server counts `event_seq` per connection, so [attach] to a different connection starts
 * a fresh cursor.
 *
 * Not thread-safe: drive it from one dispatcher (the ViewModel's main scope).
 */
class SessionStream(
    private val scope: CoroutineScope,
    private val open: suspend () -> FrameSource?,
    private val listener: Listener,
    private val maxReconnects: Int = MAX_RECONNECTS,
    private val backoffMs: (attempt: Int) -> Long = ::defaultBackoffMs,
    private val attachTimeoutMs: Long = ATTACH_TIMEOUT_MS,
) {
    interface Listener {
        /** The subscription is confirmed with a full snapshot. [cause] says which attach it
         *  answers: the caller's own [attach], or a silent re-attach after a drop.
         *  [missedEvents]: a re-attach whose snapshot is past the cursor — events happened
         *  in the gap and won't be delivered. */
        fun onSnapshot(snapshot: LiveSessionSnapshot, cause: AttachCause, missedEvents: Boolean)

        /** A new event, in order. Replayed events arrive here too, one by one. */
        fun onEvent(event: AcpEvent, seq: Long)

        /** The subscription was confirmed (snapshot or replay). */
        fun onAttached(cause: AttachCause) {}

        /** Whether a dropped socket should be reopened. Asked on every drop. */
        fun shouldReconnect(): Boolean = true

        /** A silent re-attach is about to be attempted after [attempt] drops in a row. */
        fun onReconnecting(attempt: Int) {}

        /** The connection is gone server-side ([REASON_CONNECTION_GONE]); the stream stopped. */
        fun onConnectionGone() {}

        /** Re-attaching failed [maxReconnects] times in a row; the stream stopped. */
        fun onGaveUp() {}
    }

    enum class AttachCause {
        /** The caller's own [attach]. */
        ATTACH,

        /** A silent re-attach after a transient drop, resuming from the cursor. */
        RECONNECT,
    }

    /** Stable across reconnects so the server tracks one logical subscription. */
    private val subscriptionId = UUID.randomUUID().toString()

    /** The connection the stream is attached (or attaching) to; null once stopped. */
    var connectionId: String? = null
        private set

    /** The last event sequence applied for [cursorConnectionId]. 0 = none. */
    var lastSeq: Long = 0
        private set

    /** The connection [lastSeq] counts for. Kept after [stop], so attaching to the same
     *  connection again can resume from it ([cursorFor]). */
    private var cursorConnectionId: String? = null

    private var consumer: Job? = null
    private var reconnectJob: Job? = null
    /** Bumped whenever a consumer is superseded, so its trailing frames are ignored. */
    private var generation = 0
    /** Consecutive failed re-attaches; reset by a confirmed attach. */
    private var reconnects = 0
    /** The caller's [attach] still awaiting the server. */
    private var pendingConfirm: CompletableDeferred<Unit>? = null
    /** [pause] arrived while an [attach] was awaiting: honoured once it is answered, so the
     *  caller's handshake isn't failed (which would make it connect afresh). */
    private var pauseRequested = false

    /** The connection whose subscription the server has confirmed and is still streaming.
     *  Null while attaching, re-attaching, paused or stopped. */
    private var confirmedConnectionId: String? = null

    /** Whether events for [connectionId] are flowing right now, so a caller can skip a
     *  redundant [attach]. */
    fun isAttachedTo(connectionId: String): Boolean =
        confirmedConnectionId == connectionId && consumer?.isActive == true

    /** Whether a consumer is running or a re-attach is pending. */
    val isActive: Boolean get() = connectionId != null

    /**
     * Attach to [connectionId] and suspend until the server confirms (snapshot or replay).
     * [sinceSeq] asks for a replay of the events after it instead of a snapshot; null asks
     * for a snapshot. Throws [StreamAttachFailed] when the server detaches or closes first,
     * or doesn't answer in time; the stream is then stopped.
     */
    suspend fun attach(connectionId: String, sinceSeq: Long? = null) {
        // A previous attach still waiting is superseded: fail it now rather than leaving it
        // to time out, so its caller doesn't mistake this attach's connection for a dead one.
        pendingConfirm?.takeIf { !it.isCompleted }?.completeExceptionally(StreamAttachFailed(REASON_SUPERSEDED))
        stopConsumer()
        reconnects = 0
        pauseRequested = false
        this.connectionId = connectionId
        cursorConnectionId = connectionId
        lastSeq = sinceSeq ?: 0
        val confirmed = CompletableDeferred<Unit>()
        pendingConfirm = confirmed
        startConsumer(connectionId, AttachCause.ATTACH, confirmed)
        val ok = withTimeoutOrNull(attachTimeoutMs) { confirmed.await() }
        if (ok == null) {
            stop()
            throw StreamAttachFailed("attach timeout")
        }
    }

    /**
     * Re-attach silently to the current connection from the cursor (e.g. back from the
     * background). No-op when stopped or already consuming.
     */
    fun resume() {
        val conn = connectionId ?: return
        pauseRequested = false
        if (consumer?.isActive == true || reconnectJob?.isActive == true) return
        reconnects = 0
        startConsumer(conn, AttachCause.RECONNECT, null)
    }

    /** The cursor to resume [connectionId] from, or null when none is held for it (the server
     *  then answers the attach with a snapshot). */
    fun cursorFor(connectionId: String): Long? =
        lastSeq.takeIf { it > 0 && connectionId == cursorConnectionId }

    /** Close the socket but keep the connection and cursor, for a later [resume]. */
    fun pause() {
        if (pendingConfirm?.isCompleted == false) {
            pauseRequested = true
            return
        }
        stopConsumer()
    }

    /** Close the socket and forget the connection. */
    fun stop() {
        stopConsumer()
        connectionId = null
    }

    private fun stopConsumer() {
        confirmedConnectionId = null
        generation += 1
        reconnectJob?.cancel()
        reconnectJob = null
        consumer?.cancel()
        consumer = null
    }

    private fun startConsumer(conn: String, cause: AttachCause, confirmed: CompletableDeferred<Unit>?) {
        generation += 1
        val gen = generation
        consumer?.cancel()
        consumer = scope.launch {
            val source = try {
                open()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (source == null) {
                if (gen == generation) ended(gen, null, confirmed)
                return@launch
            }
            source.frames().collect { frame ->
                if (gen != generation) return@collect
                when (frame) {
                    StreamFrame.Ready -> source.attach(subscriptionId, conn, lastSeq.takeIf { it > 0 })
                    is StreamFrame.Snapshot -> {
                        reconnects = 0
                        val previous = lastSeq
                        frame.snapshot.eventSeq?.let { lastSeq = it }
                        val missed = cause == AttachCause.RECONNECT && (frame.snapshot.eventSeq ?: 0) > previous
                        listener.onSnapshot(frame.snapshot, cause, missed)
                        confirm(gen, cause, confirmed)
                    }
                    is StreamFrame.Replay -> {
                        reconnects = 0
                        for (envelope in frame.events) {
                            if (gen != generation) break
                            deliver(envelope.event, envelope.seq)
                        }
                        confirm(gen, cause, confirmed)
                    }
                    is StreamFrame.Event -> deliver(frame.envelope.event, frame.envelope.seq)
                    is StreamFrame.Detached -> ended(gen, frame.reason, confirmed)
                    is StreamFrame.Closed -> ended(gen, frame.reason, confirmed)
                    StreamFrame.Pong -> Unit
                }
            }
        }
    }

    /** The server answered the attach. The caller's [attach] returns even if the listener
     *  stopped the stream while handling the answer (an idle reattach aborting): the attach
     *  itself succeeded. */
    private fun confirm(gen: Int, cause: AttachCause, confirmed: CompletableDeferred<Unit>?) {
        if (confirmed != null && !confirmed.isCompleted) confirmed.complete(Unit)
        if (gen != generation) return
        confirmedConnectionId = connectionId
        listener.onAttached(cause)
        if (pauseRequested && gen == generation) {
            pauseRequested = false
            stopConsumer()
        }
    }

    /** Apply an event unless the cursor already covers it. A seq of 0 means unsequenced:
     *  always delivered. */
    private fun deliver(event: AcpEvent, seq: Long) {
        if (seq > 0) {
            if (seq <= lastSeq) return
            lastSeq = seq
        }
        listener.onEvent(event, seq)
    }

    private fun ended(gen: Int, reason: String?, confirmed: CompletableDeferred<Unit>?) {
        if (gen != generation) return
        confirmedConnectionId = null
        // Ended before the attach was confirmed: the server rejected this connection
        // (stale/unknown). Fail the caller's handshake instead of reconnecting to a dead id.
        if (confirmed != null && !confirmed.isCompleted) {
            pauseRequested = false
            stopConsumer()
            connectionId = null
            confirmed.completeExceptionally(StreamAttachFailed(reason))
            return
        }
        if (reason == REASON_CONNECTION_GONE) {
            stop()
            listener.onConnectionGone()
            return
        }
        if (!listener.shouldReconnect()) {
            stopConsumer()
            return
        }
        reconnect()
    }

    /** Silently re-attach after a transient drop (`lagged`, `server_shutdown`, a socket blip),
     *  backing off between tries, and give up after [maxReconnects] in a row. */
    private fun reconnect() {
        val conn = connectionId ?: return
        reconnects += 1
        if (reconnects > maxReconnects) {
            stop()
            listener.onGaveUp()
            return
        }
        // Supersede the dying consumer at once so its trailing frames can't land during the
        // backoff window.
        stopConsumer()
        val gen = generation
        val attempt = reconnects
        listener.onReconnecting(attempt)
        reconnectJob = scope.launch {
            delay(backoffMs(attempt))
            if (gen != generation || connectionId != conn) return@launch
            if (!listener.shouldReconnect()) return@launch
            startConsumer(conn, AttachCause.RECONNECT, null)
        }
    }

    companion object {
        const val MAX_RECONNECTS = 6

        /** [StreamAttachFailed.reason] for an attach replaced by a newer one. */
        const val REASON_SUPERSEDED = "superseded"
        const val ATTACH_TIMEOUT_MS = 12_000L

        /** 0.5s, 1s, 2s, … capped at 8s. */
        fun defaultBackoffMs(attempt: Int): Long = (500L shl (attempt - 1).coerceIn(0, 10)).coerceAtMost(8_000L)
    }
}
