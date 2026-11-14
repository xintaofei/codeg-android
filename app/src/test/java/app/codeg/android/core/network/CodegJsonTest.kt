package app.codeg.android.core.network

import app.codeg.android.core.model.AgentType
import app.codeg.android.core.model.ConnectBody
import app.codeg.android.core.model.InlineModelsKeySource
import app.codeg.android.core.model.KimiInterfaceType
import app.codeg.android.core.model.ListProviderModelsBody
import app.codeg.android.core.model.ProviderModelsParams
import app.codeg.android.core.model.ProviderModelsProtocol
import app.codeg.android.core.model.kimiInterfaceMeta
import app.codeg.android.core.model.modelsBaseUrl
import app.codeg.android.core.model.ContentBlock
import app.codeg.android.core.model.MessageTurn
import app.codeg.android.core.model.isTurnErrorRecord
import app.codeg.android.core.model.turnErrors
import app.codeg.android.core.model.ConversationStatus
import app.codeg.android.core.model.ConversationSummary
import app.codeg.android.core.model.FolderDetail
import app.codeg.android.core.model.QuestionAnswer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Locks down codeg's split casing: requests camelCase, responses snake_case. */
class CodegJsonTest {

    @Test
    fun `request bodies encode camelCase and omit nulls`() {
        val json = CodegJson.request.encodeToString(
            ConnectBody(agentType = AgentType.CLAUDE_CODE, workingDir = "/work"),
        )
        assertTrue(json.contains("\"agentType\":\"claude_code\""))
        assertTrue(json.contains("\"workingDir\":\"/work\""))
        // null optionals are omitted (Swift `encodeIfPresent` parity).
        assertFalse(json.contains("sessionId"))
        assertFalse(json.contains("preferredModeId"))
    }

    @Test
    fun `non-null defaults are still encoded`() {
        // declined defaults to false but must be sent (encodeDefaults = true).
        val json = CodegJson.request.encodeToString(QuestionAnswer())
        assertTrue(json.contains("\"declined\":false"))
    }

    @Test
    fun `folder response decodes snake_case keys`() {
        val folder = CodegJson.response.decodeFromString<FolderDetail>(
            """{"id":1,"name":"web","path":"/srv/web","git_branch":"main",
               "default_agent_type":"codex","sort_order":3,"color":"#fff","is_chat":true}""",
        )
        assertEquals("main", folder.gitBranch)
        assertEquals(AgentType.CODEX, folder.defaultAgentType)
        assertEquals(3, folder.sortOrder)
        assertTrue(folder.isChat)
    }

    @Test
    fun `conversation summary decodes enums and counts`() {
        val c = CodegJson.response.decodeFromString<ConversationSummary>(
            """{"id":7,"folder_id":2,"title":"Fix auth","agent_type":"gemini",
               "status":"in_progress","message_count":5,
               "created_at":"2024-01-02T03:04:05Z","updated_at":"2024-01-02T03:04:06Z"}""",
        )
        assertEquals(2, c.folderId)
        assertEquals(AgentType.GEMINI, c.agentType)
        assertEquals(ConversationStatus.IN_PROGRESS, c.status)
        assertEquals(5, c.messageCount)
        assertEquals("Fix auth", c.trimmedTitle)
    }

    @Test
    fun `unknown enum values fall back instead of throwing`() {
        val c = CodegJson.response.decodeFromString<ConversationSummary>(
            """{"id":1,"folder_id":1,"agent_type":"brand_new_agent","status":"weird_status",
               "message_count":0,"created_at":"2024-01-02T03:04:05Z","updated_at":"2024-01-02T03:04:05Z"}""",
        )
        // An agent this build doesn't know keeps its own identity and its raw wire value
        // instead of being mislabelled as Claude Code on every session row.
        assertEquals(AgentType.Unknown("brand_new_agent"), c.agentType)
        assertEquals(ConversationStatus.OTHER, c.status)
    }

    @Test
    fun `a custom agent's session decodes to its registry id`() {
        val c = CodegJson.response.decodeFromString<ConversationSummary>(
            """{"id":1,"folder_id":1,"agent_type":"custom:goose","status":"completed",
               "message_count":0,"created_at":"2024-01-02T03:04:05Z","updated_at":"2024-01-02T03:04:05Z"}""",
        )
        assertEquals(AgentType.Custom("goose"), c.agentType)
        assertEquals("goose", c.agentType.displayName)
        assertEquals("custom:goose", c.agentType.wire)
    }

    @Test
    fun `a failed round's turn_error record decodes as one`() {
        val turn = CodegJson.response.decodeFromString<MessageTurn>(
            """{"id":"s1","role":"system","timestamp":"2024-01-02T03:04:05Z",
               "blocks":[{"type":"turn_error","message":"API Error: 503"}]}""",
        )
        assertEquals(listOf(ContentBlock.TurnError("API Error: 503")), turn.blocks)
        assertTrue(turn.isTurnErrorRecord)
        assertEquals(listOf("API Error: 503"), turn.turnErrors)
    }

    @Test
    fun `list_provider_models request matches the server's params shape`() {
        val body = ListProviderModelsBody(
            ProviderModelsParams(ProviderModelsProtocol.ANTHROPIC, "https://api.anthropic.com", InlineModelsKeySource("sk")),
        )
        assertEquals(
            """{"params":{"protocol":"anthropic","baseUrl":"https://api.anthropic.com",""" +
                """"keySource":{"apiKey":"sk","kind":"inline"}}}""",
            CodegJson.request.encodeToString(body),
        )
    }

    @Test
    fun `kimi model listing falls back to the interface SDK's default endpoint`() {
        assertEquals("https://api.anthropic.com", kimiInterfaceMeta(KimiInterfaceType.ANTHROPIC).modelsBaseUrl(" "))
        assertEquals("https://x.test/v1", kimiInterfaceMeta(KimiInterfaceType.OPENAI).modelsBaseUrl("https://x.test/v1"))
        // OpenAI-compatible endpoints have no usable default, so a blank one stays blank.
        assertEquals("", kimiInterfaceMeta(KimiInterfaceType.OPENAI).modelsBaseUrl(""))
        assertEquals(null, kimiInterfaceMeta(KimiInterfaceType.VERTEXAI).modelsProtocol)
    }

    @Test
    fun `bare JSON integer decodes (create_conversation)`() {
        assertEquals(42, CodegJson.response.decodeFromString(Int.serializer(), "42"))
    }

    @Test
    fun `a summary carries its kind, parent and tags, and says whether it may move`() {
        val root = CodegJson.response.decodeFromString<ConversationSummary>(
            """{"id":1,"folder_id":1,"agent_type":"codex","status":"completed","kind":"chat","tag_ids":[2,5],
               "message_count":0,"created_at":"2024-01-02T03:04:05Z","updated_at":"2024-01-02T03:04:05Z"}""",
        )
        assertEquals(listOf(2, 5), root.tagIds)
        assertTrue(root.isMovable)
        val child = CodegJson.response.decodeFromString<ConversationSummary>(
            """{"id":2,"folder_id":1,"agent_type":"codex","status":"completed","kind":"delegate","parent_id":1,
               "message_count":0,"created_at":"2024-01-02T03:04:05Z","updated_at":"2024-01-02T03:04:05Z"}""",
        )
        assertFalse(child.isMovable)
        assertEquals(emptyList<Int>(), child.tagIds)
        val loop = root.copy(kind = "loop")
        assertFalse(loop.isMovable)
        // An older server says nothing about kind: a root row moves.
        assertTrue(root.copy(kind = null).isMovable)
    }

    @Test
    fun `a move answers in camelCase`() {
        val camel = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val result = camel.decodeFromString<app.codeg.android.core.model.MoveConversationsResult>(
            """{"moved":[1],"skipped":[{"conversationId":2,"reason":"busy"}]}""",
        )
        assertEquals(listOf(1), result.moved)
        assertEquals(2, result.skipped.single().conversationId)
        assertEquals("busy", result.skipped.single().reason)
        assertTrue(CodegJson.request.encodeToString(app.codeg.android.core.model.MoveConversationsBody(listOf(3), 7))
            .let { it.contains("\"conversationIds\":[3]") && it.contains("\"targetFolderId\":7") })
    }
}
