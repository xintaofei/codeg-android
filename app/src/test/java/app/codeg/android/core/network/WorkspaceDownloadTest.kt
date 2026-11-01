package app.codeg.android.core.network

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

class WorkspaceDownloadTest {
    @Test fun `ticket request is authenticated and yields a download URL without credentials`() = runTest {
        var requests = 0
        val http = HttpClient(MockEngine { request ->
            requests++
            assertEquals("/codeg/api/workspace_download_ticket", request.url.encodedPath)
            assertEquals("Bearer test-token", request.headers[HttpHeaders.Authorization])
            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            assertEquals("/work", body["rootPath"]?.jsonPrimitive?.content)
            assertEquals("app/build/app.apk", body["path"]?.jsonPrimitive?.content)
            assertEquals("file", body["kind"]?.jsonPrimitive?.content)
            respond("""{"ticket":"abc-123","url":"/api/workspace_download/abc-123","filename":"app.apk","expiresAt":123}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val client = CodegClient("https://example.test/codeg/", "test-token", http)
            val first = client.workspaceDownloadTicket("/work", "app/build/app.apk")
            assertEquals("https://example.test/codeg/api/workspace_download/abc-123", first.url)
            assertEquals("app.apk", first.filename)
            assertFalse(first.url.contains("test-token"))
            client.workspaceDownloadTicket("/work", "app/build/app.apk")
            assertEquals(2, requests) // Retry always asks for a new one-use ticket.
        } finally { http.close() }
    }

    @Test fun `ticket URLs cannot redirect the download to an unrelated host or API`() = runTest {
        listOf("https://other.test/app.apk", "//other.test/app.apk", "/api/secret", "/api/workspace_download/../secret").forEach { url ->
            val http = HttpClient(MockEngine { respond("""{"url":"$url","filename":"app.apk"}""") })
            try {
                try {
                    CodegClient("https://example.test", "test-token", http).workspaceDownloadTicket("/work", "app.apk")
                    fail("Accepted $url")
                } catch (_: ApiError.Decoding) { }
            } finally { http.close() }
        }
    }

    @Test fun `missing files surface failures before enqueue`() = runTest {
        val http = HttpClient(MockEngine { respond("""{"message":"Missing file"}""", HttpStatusCode.NotFound) })
        try {
            try {
                CodegClient("https://example.test", "test-token", http).workspaceDownloadTicket("/work", "gone.apk")
                fail("Missing file was accepted")
            } catch (e: ApiError.Server) { assertEquals(404, e.status) }
        } finally { http.close() }
    }
}
