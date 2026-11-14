package app.codeg.android.core.network

import app.codeg.android.core.model.AgentType
import app.codeg.android.core.model.ConnectBody
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
    fun `bare JSON integer decodes (create_conversation)`() {
        assertEquals(42, CodegJson.response.decodeFromString(Int.serializer(), "42"))
    }
}
