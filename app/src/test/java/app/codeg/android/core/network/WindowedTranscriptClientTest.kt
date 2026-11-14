package app.codeg.android.core.network

import app.codeg.android.core.model.TurnWindow
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The windowed-transcript requests as they actually leave the client. The window
 * selectors are the one place a wrong body is silently accepted: the server
 * answers a request with no selector — or an unknown one — with the WHOLE
 * transcript, which looks like success and quietly restores the behaviour the
 * window exists to avoid.
 */
class WindowedTranscriptClientTest {

    private val summary =
        """"summary":{"id":1,"folder_id":2,"agent_type":"claude_code","status":"completed",
            "created_at":"2026-08-11T00:00:00Z","updated_at":"2026-08-11T00:00:00Z"}"""

    private val sentBodies = mutableListOf<String>()
    private val sentPaths = mutableListOf<String>()

    private fun client(vararg responses: String): CodegClient {
        val queue = responses.toMutableList()
        val engine = MockEngine { request ->
            sentPaths += request.url.encodedPath
            sentBodies += (request.body as io.ktor.http.content.TextContent).text
            respond(
                content = ByteReadChannel(queue.removeAt(0)),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        return CodegClient("https://example.test", "tok", HttpClient(engine))
    }

    @Test
    fun `a tail request carries only the tail selector`() = runTest {
        val c = client("""{$summary,"turns":[],"turns_offset":280,"turns_total":400,
            "assistant_turns_before_offset":140,"prefix_hash":"7060d8542023feb2"}""")

        val detail = c.conversationDetail(7, TurnWindow.Tail(120))

        assertEquals("/api/get_folder_conversation", sentPaths.single())
        assertEquals("""{"conversationId":7,"tailTurns":120}""", sentBodies.single())
        assertEquals(280, detail.window!!.offset)
        assertEquals(400, detail.window!!.total)
        assertEquals("7060d8542023feb2", detail.window!!.prefixHash)
    }

    @Test
    fun `a from-index request carries only the exact selector`() = runTest {
        // Sending `tailTurns` alongside would make the server round-align the start,
        // and the response offset would no longer be the coordinate that was asked
        // about — leaving the two prefix fingerprints incomparable.
        val c = client("""{$summary,"turns":[],"turns_offset":280,"turns_total":401,
            "prefix_hash":"7060d8542023feb2"}""")

        c.conversationDetail(7, TurnWindow.FromIndex(280))

        assertEquals("""{"conversationId":7,"fromIndex":280}""", sentBodies.single())
    }

    @Test
    fun `no selector asks for the whole transcript`() = runTest {
        val c = client("""{$summary,"turns":[]}""")

        val detail = c.conversationDetail(7)

        assertEquals("""{"conversationId":7}""", sentBodies.single())
        // A pre-0.24.0 server answers every request this way; the absent fields are
        // the only signal, and they must not be invented.
        assertNull(detail.window)
    }

    @Test
    fun `a history page sends its bounds and returns the seam proof`() = runTest {
        val c = client(
            """{"turns":[{"id":"turn-160","role":"user","blocks":[],
                "timestamp":"2026-08-10T09:00:00Z"}],
                "turns_offset":160,"turns_total":400,"assistant_turns_before_offset":80,
                "prefix_hash":"cbf29ce484222325","prefix_hash_before_index":"7060d8542023feb2"}""",
        )

        val page = c.conversationTurnsPage(7, beforeIndex = 280, limit = 120)

        assertEquals("/api/get_folder_conversation_turns", sentPaths.single())
        assertEquals("""{"conversationId":7,"beforeIndex":280,"limit":120}""", sentBodies.single())
        assertEquals(160, page.turnsOffset)
        assertEquals("7060d8542023feb2", page.prefixHashBeforeIndex)
        assertEquals(listOf("turn-160"), page.turns.map { it.id })
    }

    @Test
    fun `a window keeps the transcript's own global turn ids`() = runTest {
        // The ids are what make the list keys survive a prepend, and what the seam
        // de-dup compares. A window that renumbered from zero would collide with the
        // page above it.
        val c = client(
            """{$summary,"turns":[
                {"id":"turn-280","role":"user","blocks":[],"timestamp":"2026-08-10T09:00:00Z"},
                {"id":"turn-281","role":"assistant","blocks":[],"timestamp":"2026-08-10T09:00:01Z"}],
                "turns_offset":280,"turns_total":400,"prefix_hash":"7060d8542023feb2"}""",
        )

        val detail = c.conversationDetail(7, TurnWindow.Tail(120))

        assertEquals(listOf("turn-280", "turn-281"), detail.turns.map { it.id })
        assertTrue(detail.turns.first().id.removePrefix("turn-").toInt() >= detail.window!!.offset)
    }
}
