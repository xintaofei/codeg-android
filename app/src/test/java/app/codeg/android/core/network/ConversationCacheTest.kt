package app.codeg.android.core.network

import app.codeg.android.core.cache.ConversationDiskCache
import app.codeg.android.core.model.ContentBlock
import app.codeg.android.feature.sessiondetail.TranscriptWindow
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConversationCacheTest {
    @get:Rule val temp = TemporaryFolder()
    private val seed = "cbf29ce484222325"
    private val summary = """{"id":7,"folder_id":1,"agent_type":"claude_code","status":"completed","created_at":"2026-01-01T00:00:00Z","updated_at":"2026-01-01T00:00:00Z"}"""

    private fun detail(rounds: Int, answer: String = "answer"): String {
        val records = (0 until rounds).flatMap { i -> listOf(
            """{"id":"u$i","role":"user","timestamp":"2026-01-01T00:00:00Z","blocks":[{"type":"text","text":"prompt $i"}]}""",
            """{"id":"a$i","role":"assistant","timestamp":"2026-01-01T00:00:01Z","blocks":[{"type":"text","text":"$answer $i"},{"type":"future_block","opaque":{"value":42}},{"type":"tool_use","tool_name":"shell","input_preview":"pwd","meta":{"custom":true}}]}""",
        ) }
        return """{"summary":$summary,"turns":[${records.joinToString(",")}],"turns_offset":0,"turns_total":${records.size},"prefix_hash":"$seed"}"""
    }

    private fun http(body: suspend (String) -> String) = HttpClient(MockEngine { request ->
        respond(body(request.url.encodedPath), headers = headersOf(HttpHeaders.ContentType, "application/json"))
    })

    @Test fun `restart reads five round tail and older pages offline without losing unknown blocks`() = runBlocking {
        val root = temp.newFolder()
        val cache = ConversationDiskCache(root)
        val scope = cache.scope("profile", "https://test", "token")
        val http = http { detail(12) }
        try {
            val client = CodegClient("https://test", "token", http, cache, scope)
            client.conversationDetail(7, fromIndex = 0)
            val restarted = ConversationDiskCache(root)
            val offlineHttp = http { throw java.io.IOException("offline") }
            try {
                val offline = CodegClient("https://test", "token", offlineHttp, restarted, restarted.scope("profile", "https://test", "token"))
                val cached = requireNotNull(offline.cachedConversation(7))
                assertEquals(14, cached.value.turnsOffset)
                assertEquals(10, cached.value.turns.size)
                assertEquals("u7", cached.value.turns.first().id)
                assertTrue(cached.value.turns.last().blocks.any { it is ContentBlock.ToolUse })
                val raw = requireNotNull(restarted.read(restarted.scope("profile", "https://test", "token"), "session-7", "detail"))
                val blocks = Json.parseToJsonElement(raw.text).jsonObject["turns"]!!.jsonArray.last().jsonObject["blocks"]!!.jsonArray
                assertEquals("{\"value\":42}", blocks[1].jsonObject["opaque"].toString())
                var window = requireNotNull(TranscriptWindow.from(cached.value))
                var turns = cached.value.turns
                while (window.offset > 0) {
                    val page = requireNotNull(offline.cachedConversationTurns(7, window.offset, cached.snapshot))
                    window = requireNotNull(window.prepend(page, turns))
                    turns = page.turns + turns
                }
                assertEquals((0 until 12).flatMap { listOf("u$it", "a$it") }, turns.map { it.id })
            } finally { offlineHttp.close() }
        } finally { http.close() }
    }

    @Test fun `explicit detail fetch bypasses fresh cache and discards old history with same structure`() = runBlocking {
        val cache = ConversationDiskCache(temp.newFolder())
        val scope = cache.scope("profile", "https://test", "token")
        var requests = 0
        val http = http { requests++; if (requests == 1) detail(12) else detail(12, "changed") }
        try {
            val client = CodegClient("https://test", "token", http, cache, scope)
            client.conversationDetail(7)
            val previous = requireNotNull(client.cachedConversation(7))
            assertTrue(previous.isFresh())
            assertNotNull(client.cachedConversationTurns(7, 14, previous.snapshot))
            assertEquals(1, requests)
            client.conversationDetail(7)
            assertEquals(2, requests)
            val current = requireNotNull(client.cachedConversation(7))
            assertNotEquals(previous.snapshot, current.snapshot)
            assertTrue((current.value.turns.last().blocks.first() as ContentBlock.Text).text.startsWith("changed"))
            assertNull(client.cachedConversationTurns(7, 14, previous.snapshot))
            val rewritten = requireNotNull(client.cachedConversationTurns(7, 14, current.snapshot))
            assertTrue((rewritten.turns.last().blocks.first() as ContentBlock.Text).text.startsWith("changed"))
        } finally { http.close() }
    }

    @Test fun `clearing while network request runs does not repopulate cache`() = runBlocking {
        val cache = ConversationDiskCache(temp.newFolder())
        val scope = cache.scope("profile", "https://test", "token")
        val started = CompletableDeferred<Unit>()
        val response = CompletableDeferred<String>()
        val http = http { started.complete(Unit); response.await() }
        try {
            val client = CodegClient("https://test", "token", http, cache, scope)
            val pending = async { client.conversationDetail(7) }
            started.await()
            cache.clearAll()
            response.complete(detail(1))
            assertEquals(2, pending.await().turns.size)
            assertNull(client.cachedConversation(7))
        } finally { http.close() }
    }

    @Test fun `network or disk failure preserves readable prior content and successful fetch`() = runBlocking {
        val cache = ConversationDiskCache(temp.newFolder())
        val scope = cache.scope("profile", "https://test", "token")
        var offline = false
        val http = http { if (offline) throw java.io.IOException("offline") else detail(1) }
        try {
            val client = CodegClient("https://test", "token", http, cache, scope)
            client.conversationDetail(7)
            val old = requireNotNull(client.cachedConversation(7))
            offline = true
            try { client.conversationDetail(7); fail("expected transport failure") } catch (_: ApiError.Transport) { }
            assertEquals(old, client.cachedConversation(7))
            offline = false
            val broken = ConversationDiskCache(temp.newFile())
            val noDisk = CodegClient("https://test", "token", http, broken, broken.scope("profile", "https://test", "token"))
            assertEquals(2, noDisk.conversationDetail(7).turns.size)
            assertNull(noDisk.cachedConversation(7))
        } finally { http.close() }
    }

    @Test fun `delete invalidates both detail and session list`() = runBlocking {
        val cache = ConversationDiskCache(temp.newFolder())
        val scope = cache.scope("profile", "https://test", "token")
        val http = http { path -> when {
            path.endsWith("get_folder_conversation") -> detail(1)
            path.endsWith("list_all_conversations") -> "[$summary]"
            path.endsWith("delete_conversation") -> "null"
            else -> "[]"
        } }
        try {
            val client = CodegClient("https://test", "token", http, cache, scope)
            client.sessionList()
            client.conversationDetail(7)
            assertNotNull(client.cachedSessionList())
            assertNotNull(client.cachedConversation(7))
            client.deleteConversation(7)
            assertNull(client.cachedSessionList())
            assertNull(client.cachedConversation(7))
        } finally { http.close() }
    }

    @Test fun `prompt expires authoritative history without saving optimistic or live content`() = runBlocking {
        val cache = ConversationDiskCache(temp.newFolder())
        val scope = cache.scope("profile", "https://test", "token")
        val http = http { path -> if (path.endsWith("get_folder_conversation")) detail(1) else "null" }
        try {
            val client = CodegClient("https://test", "token", http, cache, scope)
            client.conversationDetail(7)
            val before = requireNotNull(client.cachedConversation(7))
            client.prompt("connection", emptyList(), 1, 7, "pending-user")
            val after = requireNotNull(client.cachedConversation(7))
            assertEquals(before.value, after.value)
            assertFalse(after.isFresh())
        } finally { http.close() }
    }

    @Test fun `active reply never replaces confirmed disk history`() = runBlocking {
        val cache = ConversationDiskCache(temp.newFolder())
        val scope = cache.scope("profile", "https://test", "token")
        var live = false
        val http = http {
            if (live) detail(2, "partial").replace("\"completed\"", "\"in_progress\"")
            else detail(1)
        }
        try {
            val client = CodegClient("https://test", "token", http, cache, scope)
            client.conversationDetail(7)
            val confirmed = requireNotNull(client.cachedConversation(7))
            live = true
            assertEquals(4, client.conversationDetail(7).turns.size)
            val cached = requireNotNull(client.cachedConversation(7))
            assertEquals(confirmed.value, cached.value)
            assertFalse(cached.isFresh())
        } finally { http.close() }
    }

    @Test fun `huge single round is cached in bounded pages with continuous seams`() = runBlocking {
        val cache = ConversationDiskCache(temp.newFolder())
        val scope = cache.scope("profile", "https://test", "token")
        val turns = (0..1100).joinToString(",") { i ->
            """{"id":"$i","role":"${if (i == 0) "user" else "assistant"}","timestamp":"2026-01-01T00:00:00Z","blocks":[]}"""
        }
        val raw = """{"summary":$summary,"turns":[$turns],"turns_offset":0,"turns_total":1101,"prefix_hash":"$seed"}"""
        val http = http { raw }
        try {
            val client = CodegClient("https://test", "token", http, cache, scope)
            client.conversationDetail(7, 0)
            val cached = requireNotNull(client.cachedConversation(7))
            assertEquals(500, cached.value.turns.size)
            assertEquals(601, cached.value.turnsOffset)
            var window = requireNotNull(TranscriptWindow.from(cached.value))
            var loaded = cached.value.turns
            while (window.offset > 0) {
                val page = requireNotNull(client.cachedConversationTurns(7, window.offset, cached.snapshot))
                assertTrue(page.turns.size <= 500)
                window = requireNotNull(window.prepend(page, loaded))
                loaded = page.turns + loaded
            }
            assertEquals((0..1100).map(Int::toString), loaded.map { it.id })
        } finally { http.close() }
    }
}
