package app.codeg.android.core.model

import app.codeg.android.core.network.CodegJson
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `available_commands` on every wire it rides: the live event, the attach snapshot, the
 *  by-conversation snapshot and the draft probe (iOS `AvailableCommandsDecodingTests`). Plus the
 *  workspace file search's entry. */
class AvailableCommandsDecodingTest {
    private val json: Json = CodegJson.response

    private fun obj(raw: String): JsonObject = json.parseToJsonElement(raw).jsonObject

    @Test
    fun `the live event replaces the list`() {
        val event = AcpEvent.fromWire(
            obj(
                """{"type":"available_commands","commands":[
                  {"name":"review","description":"Review the branch","input_hint":"branch name"},
                  {"name":"simplify","description":"","skill":true},
                  {"name":"user-skill","description":"Mine","skill":true,"skill_path":"/u/SKILL.md"},
                  {"name":"${'$'}deploy","description":"Deploy"}
                ]}""",
            ),
            json,
        )
        val commands = (event as AcpEvent.AvailableCommands).commands
        assertEquals(listOf("review", "simplify", "user-skill", "\$deploy"), commands.map { it.name })
        assertEquals("branch name", commands[0].inputHint)
        assertFalse(commands[0].skill)
        assertTrue(commands[1].skill)
        assertNull(commands[1].skillPath)
        assertEquals("/u/SKILL.md", commands[2].skillPath)
    }

    @Test
    fun `an empty list is a list`() {
        val envelope = EventEnvelope.fromWire(obj("""{"seq":3,"connection_id":"c1","type":"available_commands","commands":[]}"""), json)
        assertEquals(AcpEvent.AvailableCommands(emptyList()), envelope.event)
        assertEquals(3L, envelope.seq)
    }

    @Test
    fun `a list that does not decode is not an empty one`() {
        assertEquals(
            AcpEvent.Unknown("available_commands"),
            AcpEvent.fromWire(obj("""{"type":"available_commands","commands":[{"description":"no name"}]}"""), json),
        )
        assertEquals(AcpEvent.Unknown("available_commands"), AcpEvent.fromWire(obj("""{"type":"available_commands"}"""), json))
    }

    @Test
    fun `the attach snapshot carries them, and a snapshot without them says nothing`() {
        val snapshot = LiveSessionSnapshot.fromWire(
            obj("""{"connection_id":"c","status":"connected","available_commands":[{"name":"compact","description":"Compact"}]}"""),
            json,
        )
        assertEquals(listOf("compact"), snapshot.availableCommands?.map { it.name })
        assertNull(LiveSessionSnapshot.fromWire(obj("""{"connection_id":"c","status":"connected"}"""), json).availableCommands)
        val broken = LiveSessionSnapshot.fromWire(obj("""{"connection_id":"c","available_commands":"nope"}"""), json)
        assertNull("a shape surprise must not fail the snapshot", broken.availableCommands)
        assertEquals("c", broken.connectionId)
    }

    @Test
    fun `the conversation snapshot carries skill marks`() {
        val snapshot = json.decodeFromString(
            SessionSnapshot.serializer(),
            """{"selectors_ready":true,"available_commands":[{"name":"loop","description":"Loop","skill":true}]}""",
        )
        assertTrue(snapshot.availableCommands.first().skill)
    }

    @Test
    fun `the draft probe carries them`() {
        val probe = json.decodeFromString(
            AgentOptionsSnapshot.serializer(),
            """{"modes":null,"config_options":[],"available_commands":[{"name":"init","description":"Init","input_hint":null}]}""",
        )
        assertEquals(listOf("init"), probe.availableCommands.map { it.name })
        val older = json.decodeFromString(AgentOptionsSnapshot.serializer(), """{"modes":null,"config_options":[]}""")
        assertTrue(older.availableCommands.isEmpty())
    }

    @Test
    fun `a file search answers forward-slashed entries`() {
        val entries = json.decodeFromString(
            ListSerializer(WorkspaceFileEntry.serializer()),
            """[{"name":"app.ts","path":"src/app.ts","kind":"file"},{"name":"","path":"src/lib","kind":"dir"}]""",
        )
        assertEquals("app.ts", entries[0].displayName)
        assertFalse(entries[0].isDir)
        assertEquals("lib", entries[1].displayName)
        assertTrue(entries[1].isDir)
    }

    @Test
    fun `a search request goes out camelCase`() {
        val body = CodegJson.request.encodeToString(
            WorkspaceFileSearchBody.serializer(),
            WorkspaceFileSearchBody("/repo", "app", 51, WorkspaceSearchRequest("c1", 4)),
        )
        assertEquals("""{"path":"/repo","query":"app","limit":51,"request":{"consumerId":"c1","generation":4}}""", body)
    }
}
