package app.codeg.android.core.network

import app.codeg.android.core.cache.ConversationDiskCache
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class SessionFilesTest {
    @get:Rule val temp = TemporaryFolder()
    private val summary = """{"id":42,"folder_id":3,"agent_type":"codex","status":"completed","created_at":"2026-10-24T00:00:00Z","updated_at":"2026-10-24T00:00:00Z"}"""

    @Test fun `file index requests complete session without history pagination`() = runTest {
        val http = HttpClient(MockEngine { request ->
            assertEquals("/api/get_conversation_files", request.url.encodedPath)
            assertEquals("Bearer test-token", request.headers[HttpHeaders.Authorization])
            assertEquals("""{"conversationId":42}""", (request.body as TextContent).text)
            respond("""[{"path":"/repo/early.kt","operation":"edit","turn_id":"turn-1","timestamp":"2026-10-24T00:00:00Z","available":true,"size":42}]""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val file = CodegClient("https://example.test", "test-token", http).conversationFiles(42).single()
            assertEquals("turn-1", file.turnId)
            assertEquals("early.kt", file.filename)
            assertEquals(true, file.available)
        } finally { http.close() }
    }

    @Test fun `preview uses a ticket without token and bounds the returned bytes`() = runTest {
        var count = 0
        val http = HttpClient(MockEngine { request ->
            count++
            if (request.url.encodedPath.endsWith("workspace_download_ticket")) {
                assertEquals("Bearer test-token", request.headers[HttpHeaders.Authorization])
                respond("""{"url":"/api/workspace_download/preview-1","filename":"large.txt"}""")
            } else {
                assertEquals("/api/workspace_download/preview-1", request.url.encodedPath)
                assertNull(request.headers[HttpHeaders.Authorization])
                respond("abcdefghijk")
            }
        })
        try {
            val bytes = CodegClient("https://example.test", "test-token", http).workspacePreviewBytes("/repo", "large.txt", 4)
            assertEquals("abcd", bytes.toString(Charsets.UTF_8))
            assertEquals(2, count)
        } finally { http.close() }
    }

    @Test fun `501 from old server falls back to complete history and caches files without changing transcript cache`() = runTest {
        val requests = mutableListOf<String>()
        val http = HttpClient(MockEngine { request ->
            requests += request.url.encodedPath
            assertEquals("Bearer test-token", request.headers[HttpHeaders.Authorization])
            when (request.url.encodedPath) {
                "/api/get_conversation_files" -> respond("""{"code":"not_implemented","message":"API endpoint 'get_conversation_files' is not available in web mode"}""", HttpStatusCode.NotImplemented)
                "/api/get_folder_conversation" -> {
                    val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                    assertEquals("0", body["fromIndex"]?.jsonPrimitive?.content)
                    assertNull(body["tailRounds"])
                    respond("""{"summary":$summary,"turns_offset":0,"turns":[
                        {"id":"early","role":"assistant","timestamp":"2026-10-24T00:00:00Z","blocks":[{"type":"tool_use","tool_name":"Write","input_preview":"{\"file_path\":\"old.kt\",\"content\":\"x\"}"}]},
                        {"id":"latest","role":"assistant","timestamp":"2026-10-24T00:00:01Z","blocks":[{"type":"text","text":"[Image](/Downloads/new.png)"}]}
                    ]}""")
                }
                "/api/list_all_folder_details" -> respond("""[{"id":3,"name":"Repo","path":"/repo"}]""")
                else -> error("Unexpected request: ${request.url}")
            }
        })
        val cache = ConversationDiskCache(temp.newFolder())
        val scope = cache.scope("profile", "https://example.test", "test-token")
        try {
            val client = CodegClient("https://example.test", "test-token", http, cache, scope)
            val files = client.conversationFiles(42)
            assertEquals(listOf("/Downloads/new.png", "/repo/old.kt"), files.map { it.path })
            assertTrue(files.all { it.available == null })
            assertEquals(files, client.cachedConversationFiles(42))
            assertNull(client.cachedConversation(42))
            assertEquals(3, requests.size)
        } finally { http.close() }
    }

    @Test fun `plain 404 also falls back and original working directory wins over current folder`() = runTest {
        val requests = mutableListOf<String>()
        val http = HttpClient(MockEngine { request ->
            requests += request.url.encodedPath
            if (request.url.encodedPath == "/api/get_conversation_files") respond("", HttpStatusCode.NotFound)
            else {
                assertEquals("/api/get_folder_conversation", request.url.encodedPath)
                respond("""{"summary":${summary.dropLast(1)},"origin_cwd":"/original"},"turns":[{"id":"old","role":"assistant","timestamp":"2026-10-24T00:00:00Z","blocks":[{"type":"tool_use","tool_name":"Write","input_preview":"{\"path\":\"a.kt\"}"}]}]}""")
            }
        })
        try {
            assertEquals("/original/a.kt", CodegClient("https://example.test", "test-token", http).conversationFiles(42).single().path)
            assertEquals(2, requests.size)
        } finally { http.close() }
    }

    @Test fun `permission and server failures never trigger compatibility history reads`() = runTest {
        for (status in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden, HttpStatusCode.InternalServerError)) {
            var requests = 0
            val http = HttpClient(MockEngine {
                requests++
                respond("""{"code":"failure","message":"failed"}""", status)
            })
            try {
                try {
                    CodegClient("https://example.test", "test-token", http).conversationFiles(42)
                    fail("Expected $status error")
                } catch (_: ApiError) { }
                assertEquals(1, requests)
            } finally { http.close() }
        }
    }

    @Test fun `legacy fallback rejects incomplete history instead of hiding older files`() = runTest {
        for (metadata in listOf("\"turns_offset\":10", "\"turns_offset\":0,\"turns_total\":10")) {
            val http = HttpClient(MockEngine { request ->
                if (request.url.encodedPath == "/api/get_conversation_files") respond("""{"code":"not_implemented"}""", HttpStatusCode.NotImplemented)
                else {
                    assertEquals("/api/get_folder_conversation", request.url.encodedPath)
                    respond("""{"summary":$summary,"turns":[],$metadata}""")
                }
            })
            try {
                try {
                    CodegClient("https://example.test", "test-token", http).conversationFiles(42)
                    fail("Incomplete history was accepted")
                } catch (_: ApiError.Decoding) { }
            } finally { http.close() }
        }
    }
}
