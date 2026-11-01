package app.codeg.android.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class ConversationPaginationTest {
    @Test fun `entry refresh and history use bounded request selectors`() = runTest {
        val requests = mutableListOf<Pair<String, String>>()
        val http = HttpClient(MockEngine { request ->
            requests += request.url.encodedPath to (request.body as TextContent).text
            val body = if (request.url.encodedPath.endsWith("get_folder_conversation_turns")) {
                """{"turns":[],"turns_offset":0,"turns_total":0,"prefix_hash":"cbf29ce484222325","prefix_hash_before_index":"cbf29ce484222325"}"""
            } else {
                """{"summary":{"id":7,"folder_id":1,"agent_type":"claude_code","status":"idle","created_at":"2026-01-01T00:00:00Z","updated_at":"2026-01-01T00:00:00Z"},"turns":[],"turns_offset":0,"turns_total":0,"prefix_hash":"cbf29ce484222325"}"""
            }
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val client = CodegClient("https://example.test", "test-token", http)
            val detail = client.conversationDetail(7)
            assertEquals(0, detail.turnsOffset)
            assertEquals("cbf29ce484222325", detail.prefixHash)
            client.conversationDetail(7, fromIndex = 12)
            client.conversationTurns(7, beforeIndex = 12)
            val entry = Json.parseToJsonElement(requests[0].second).jsonObject
            assertEquals("5", entry["tailRounds"].toString())
            assertFalse(entry.containsKey("fromIndex"))
            val refresh = Json.parseToJsonElement(requests[1].second).jsonObject
            assertEquals("12", refresh["fromIndex"].toString())
            assertFalse(refresh.containsKey("tailRounds"))
            val older = Json.parseToJsonElement(requests[2].second).jsonObject
            assertTrue(requests[2].first.endsWith("get_folder_conversation_turns"))
            assertEquals("12", older["beforeIndex"].toString())
            assertEquals("5", older["roundLimit"].toString())
            assertEquals("30", older["limit"].toString())
        } finally {
            http.close()
        }
    }
}
