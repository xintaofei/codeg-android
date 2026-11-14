package app.codeg.android.feature.sessiondetail.live

import app.codeg.android.core.model.AcpEvent
import app.codeg.android.core.model.EventEnvelope
import app.codeg.android.core.model.LiveSessionSnapshot
import app.codeg.android.core.network.CodegJson
import app.codeg.android.core.network.StreamFrame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The subscription transport: handshake, cursor/dedup, silent re-attach, give-up. */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionStreamTest {

    /** One fake socket: frames the test pushes, and the attaches the stream sent. */
    private class FakeSocket : FrameSource {
        val incoming = Channel<StreamFrame>(Channel.UNLIMITED)
        val attaches = mutableListOf<Pair<String, Long?>>()
        override fun frames(): Flow<StreamFrame> = incoming.receiveAsFlow()
        override fun attach(subscriptionId: String, connectionId: String, sinceSeq: Long?) {
            attaches += connectionId to sinceSeq
        }
        fun push(vararg frames: StreamFrame) = frames.forEach { incoming.trySend(it) }
    }

    private class Recorder(var reconnect: Boolean = true) : SessionStream.Listener {
        val log = mutableListOf<String>()
        override fun onSnapshot(snapshot: LiveSessionSnapshot, cause: SessionStream.AttachCause) {
            log += "snapshot:${cause.name}"
        }
        override fun onEvent(event: AcpEvent, seq: Long) {
            log += "event:$seq"
        }
        override fun shouldReconnect() = reconnect
        override fun onReconnecting(attempt: Int) {
            log += "reconnecting:$attempt"
        }
        override fun onConnectionGone() {
            log += "gone"
        }
        override fun onGaveUp() {
            log += "gaveUp"
        }
    }

    private val sockets = mutableListOf<FakeSocket>()

    private fun TestScope.newStream(listener: Recorder, maxReconnects: Int = 6) = SessionStream(
        scope = backgroundScope,
        open = { FakeSocket().also { sockets += it } },
        listener = listener,
        maxReconnects = maxReconnects,
        backoffMs = { 1_000L },
        attachTimeoutMs = 5_000L,
    )

    private fun snapshot(seq: Long) = StreamFrame.Snapshot(
        LiveSessionSnapshot.fromWire(
            CodegJson.response.parseToJsonElement("""{"connection_id":"c1","status":"connected","event_seq":$seq}""").jsonObject,
            CodegJson.response,
        ),
    )

    private fun event(seq: Long) = EventEnvelope(seq, "c1", AcpEvent.TurnComplete("end_turn"))

    @Test
    fun `attach confirms on the snapshot, then applies new events once`() = runTest {
        val rec = Recorder()
        val stream = newStream(rec)
        val attach = async { stream.attach("c1") }
        runCurrent()
        sockets.single().push(StreamFrame.Ready, snapshot(seq = 5))
        attach.await()
        assertEquals(listOf("c1" to null), sockets.single().attaches)
        // An overlapping replay re-sends what the snapshot already covered.
        sockets.single().push(StreamFrame.Replay(listOf(event(4), event(5), event(6))), StreamFrame.Event(event(6)), StreamFrame.Event(event(7)))
        runCurrent()
        assertEquals(listOf("snapshot:ATTACH", "event:6", "event:7"), rec.log)
        assertEquals(7, stream.lastSeq)
    }

    @Test
    fun `a detach before the confirmation fails the attach without reconnecting`() = runTest {
        val rec = Recorder()
        val stream = newStream(rec)
        val attach = async { runCatching { stream.attach("c1") } }
        runCurrent()
        sockets.single().push(StreamFrame.Ready, StreamFrame.Detached("unknown_connection"))
        val result = attach.await()
        assertTrue(result.exceptionOrNull() is StreamAttachFailed)
        assertFalse(stream.isActive)
        advanceTimeBy(10_000)
        assertEquals(1, sockets.size)
    }

    @Test
    fun `an unanswered attach times out`() = runTest {
        val stream = newStream(Recorder())
        val attach = async { runCatching { stream.attach("c1") } }
        advanceTimeBy(6_000)
        assertTrue(attach.await().exceptionOrNull() is StreamAttachFailed)
        assertFalse(stream.isActive)
    }

    @Test
    fun `a transient drop re-attaches from the cursor after the backoff`() = runTest {
        val rec = Recorder()
        val stream = newStream(rec)
        val attach = async { stream.attach("c1") }
        runCurrent()
        sockets[0].push(StreamFrame.Ready, snapshot(seq = 3), StreamFrame.Event(event(4)), StreamFrame.Closed("lagged"))
        attach.await()
        runCurrent()
        assertEquals("reconnecting:1", rec.log.last())
        assertEquals(1, sockets.size)
        advanceTimeBy(1_001)
        runCurrent()
        sockets[1].push(StreamFrame.Ready)
        runCurrent()
        assertEquals(listOf("c1" to 4L), sockets[1].attaches)
        sockets[1].push(StreamFrame.Replay(listOf(event(4), event(5))))
        runCurrent()
        assertEquals(listOf("snapshot:ATTACH", "event:4", "reconnecting:1", "event:5"), rec.log)
    }

    @Test
    fun `a reconnect answered by a snapshot reports it as a reconnect`() = runTest {
        val rec = Recorder()
        val stream = newStream(rec)
        val attach = async { stream.attach("c1") }
        runCurrent()
        sockets[0].push(StreamFrame.Ready, snapshot(seq = 3), StreamFrame.Detached("lagged"))
        attach.await()
        advanceTimeBy(1_001)
        runCurrent()
        sockets[1].push(StreamFrame.Ready, snapshot(seq = 40))
        runCurrent()
        assertEquals("snapshot:RECONNECT", rec.log.last())
        assertEquals(40, stream.lastSeq)
    }

    @Test
    fun `a gone connection stops the stream`() = runTest {
        val rec = Recorder()
        val stream = newStream(rec)
        val attach = async { stream.attach("c1") }
        runCurrent()
        sockets[0].push(StreamFrame.Ready, snapshot(seq = 1), StreamFrame.Detached(REASON_CONNECTION_GONE))
        attach.await()
        runCurrent()
        assertEquals("gone", rec.log.last())
        assertFalse(stream.isActive)
        advanceTimeBy(10_000)
        assertEquals(1, sockets.size)
    }

    @Test
    fun `reconnecting gives up after the budget`() = runTest {
        val rec = Recorder()
        val stream = newStream(rec, maxReconnects = 2)
        val attach = async { stream.attach("c1") }
        runCurrent()
        sockets[0].push(StreamFrame.Ready, snapshot(seq = 1), StreamFrame.Closed(null))
        attach.await()
        repeat(2) { i ->
            advanceTimeBy(1_001)
            runCurrent()
            sockets[i + 1].push(StreamFrame.Closed(null))
            runCurrent()
        }
        assertEquals("gaveUp", rec.log.last())
        assertFalse(stream.isActive)
    }

    @Test
    fun `no reconnect when the listener doesn't want one`() = runTest {
        val rec = Recorder(reconnect = false)
        val stream = newStream(rec)
        val attach = async { stream.attach("c1") }
        runCurrent()
        sockets[0].push(StreamFrame.Ready, snapshot(seq = 1), StreamFrame.Closed(null))
        attach.await()
        advanceTimeBy(10_000)
        assertEquals(1, sockets.size)
        assertTrue(rec.log.none { it.startsWith("reconnecting") })
    }

    @Test
    fun `pause keeps the cursor and resume re-attaches from it`() = runTest {
        val rec = Recorder()
        val stream = newStream(rec)
        val attach = async { stream.attach("c1") }
        runCurrent()
        sockets[0].push(StreamFrame.Ready, snapshot(seq = 9))
        attach.await()
        stream.pause()
        // A paused socket's late frames are ignored.
        sockets[0].push(StreamFrame.Event(event(10)))
        runCurrent()
        assertTrue(rec.log.none { it == "event:10" })
        stream.resume()
        runCurrent()
        sockets[1].push(StreamFrame.Ready)
        runCurrent()
        assertEquals(listOf("c1" to 9L), sockets[1].attaches)
    }

    @Test
    fun `the cursor only resumes the connection it counts for`() = runTest {
        val stream = newStream(Recorder())
        val attach = async { stream.attach("c1") }
        runCurrent()
        sockets[0].push(StreamFrame.Ready, snapshot(seq = 9))
        attach.await()
        stream.stop()
        assertEquals(9L, stream.cursorFor("c1"))
        assertNull(stream.cursorFor("c2"))
        if (stream.isActive) fail("stopped stream is still active")
    }
}
