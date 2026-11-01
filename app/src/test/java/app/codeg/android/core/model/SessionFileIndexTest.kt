package app.codeg.android.core.model

import java.time.Instant
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class SessionFileIndexTest {
    private fun turn(id: String, vararg blocks: ContentBlock) = MessageTurn(id, TurnRole.ASSISTANT, blocks.toList(), Instant.parse("2026-10-24T00:00:00Z"))
    private fun call(id: String, path: String, status: String? = null) = ContentBlock.ToolUse(id, "functions.mcp__fastctx__replace", """{"path":"$path"}""", status = status)

    @Test fun `complete history deduplicates paths and ignores failed and pending calls`() {
        val files = collectSessionFiles(listOf(
            turn("early", call("a", "src/../a.rs"), call("old", "old.rs")),
            turn("later", call("b", "/repo/a.rs"), call("c", "bad.rs"), call("d", "pending.rs", "pending")),
            turn("result", ContentBlock.ToolResult("c", "failed", true)),
        ), "/repo")
        assertEquals(listOf("/repo/a.rs", "/repo/old.rs"), files.map { it.path })
        assertEquals("later", files.first().turnId)
        assertEquals("edit", files.first().operation)
        assertTrue(files.all { it.available == null })
    }

    @Test fun `wrapped edits and patches never index patch examples inside file content`() {
        val files = collectSessionFiles(listOf(turn("t",
            ContentBlock.ToolUse("a", "Write", """{"arguments":{"filePath":"a.rs","content":"*** Add File: fake.rs"}}"""),
            ContentBlock.ToolUse("b", "apply_patch", """{"changes":{"b.rs":{"diff":"+++ b/fake.rs"}}}"""),
            ContentBlock.ToolUse("c", "functions.apply_patch", "*** Add File: c.rs\n+x\n*** Delete File: d.rs"),
            ContentBlock.ToolUse("d", "write_file", """{"file_path":"truncated.rs","content":"a long unfinished payload"""),
        )), "/repo")
        assertEquals(listOf("a.rs", "b.rs", "c.rs", "d.rs", "truncated.rs"), files.map { it.filename })
        assertEquals("delete", files.first { it.filename == "d.rs" }.operation)
        assertNull(files.first { it.filename == "d.rs" }.available)
    }

    @Test fun `local links preserve spaces percent signs and plus signs and exclude web links`() {
        val files = collectSessionFiles(listOf(turn("t",
            call("a", "literal%23name.rs"),
            ContentBlock.Text("[artifact](</Downloads/hello%20world%23v2+.py:12:3>) [web](https://example.test/a) [anchor](#top) [remote](file://remote/a)"),
        )), "/repo")
        assertEquals(listOf("/Downloads/hello world#v2+.py", "/repo/literal%23name.rs"), files.map { it.path })
    }

    @Test fun `windows roots are resolved as server paths and tool status survives wire decoding`() {
        val files = collectSessionFiles(listOf(turn("t", ContentBlock.ToolUse("a", "Write", """{"path":"src/a.kt"}"""))), "C:\\repo")
        assertEquals("C:/repo/src/a.kt", files.single().path)
        val block = ContentBlock.fromWire(Json.parseToJsonElement("""{"type":"tool_use","tool_name":"Write","status":"in_progress"}""") as kotlinx.serialization.json.JsonObject) as ContentBlock.ToolUse
        assertEquals("in_progress", block.status)
    }
}
