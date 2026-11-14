package app.codeg.android.feature.sessiondetail.interactive

import app.codeg.android.feature.sessiondetail.LiveToolCallState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** A permission request and the live call it is about (web `mergePermissionToolCallWithLiveInfo`). */
class PermissionMergeTest {

    private fun el(raw: String): JsonElement = Json.parseToJsonElement(raw)

    private fun live(rawInput: String?, meta: JsonObject? = null) =
        LiveToolCallState("call-1", "Edit a.kt", "edit", "pending", rawInput, "", null, meta)

    @Test
    fun `a bare request takes the live call's input, title, kind and id`() {
        val merged = PermissionMerge.merge(el("""{"options":[]}"""), live("""{"command":"ls"}""")).jsonObject
        assertEquals("""{"command":"ls"}""", (merged["rawInput"] as JsonPrimitive).content)
        assertEquals("Edit a.kt", (merged["title"] as JsonPrimitive).content)
        assertEquals("call-1", permissionToolCallId(merged))
        assertEquals("ls", ParsedPermission.parse(merged).command)
    }

    @Test
    fun `the request's own fields win, and nothing to add changes nothing`() {
        val request = el("""{"toolCallId":"call-1","title":"Run","kind":"execute","rawInput":{"command":"pwd"}}""")
        assertSame(request, PermissionMerge.merge(request, live("""{"command":"ls"}""")))
        assertSame(request, PermissionMerge.merge(request, null))
    }

    @Test
    fun `a stripped edit gets its text back from a live input about the same file`() {
        val request = el("""{"toolCallId":"call-1","rawInput":{"file_path":"a.kt"}}""")
        val merged = PermissionMerge.merge(request, live("""{"file_path":"a.kt","old_string":"x","new_string":"y"}"""))
        assertEquals(1, ParsedPermission.parse(merged).diffFiles.size)
        // Another file lends nothing; text the request has is never replaced.
        assertNull(PermissionMerge.fillStrippedEditInput(el("""{"file_path":"a.kt"}"""), """{"file_path":"b.kt","new_string":"y"}"""))
        assertNull(PermissionMerge.fillStrippedEditInput(el("""{"file_path":"a.kt","new_string":"z"}"""), """{"file_path":"a.kt","new_string":"y"}"""))
    }

    @Test
    fun `the live call's Claude Code tool name reaches the request`() {
        val meta = el("""{"claudeCode":{"toolName":"SandboxNetworkAccess"}}""").jsonObject
        val merged = PermissionMerge.merge(el("""{"toolCallId":"call-1","rawInput":{"host":"example.com"}}"""), live(null, meta))
        assertEquals("example.com", ParsedPermission.parse(merged).networkHost)
    }

    @Test
    fun `the request's input, as a tool call would carry it`() {
        assertEquals("""{"command":"ls"}""", PermissionMerge.requestInput(el("""{"rawInput":{"command":"ls"}}""")))
        assertEquals("""{"path":"x"}""", PermissionMerge.requestInput(el("""{"toolCallId":"c","title":"Read","path":"x"}""")))
        assertNull(PermissionMerge.requestInput(el("""{"toolCallId":"c","title":"Read"}""")))
    }

    @Test
    fun `a call that brings its details after the card fills it then`() {
        // The request names the call and nothing else; the call has sent nothing yet either.
        val request = el("""{"toolCallId":"call-1"}""")
        val first = PermissionMerge.merge(request, live(null))
        assertNull(ParsedPermission.parse(first).command)
        // Its update lands: the card, merged again from what it holds, shows the command.
        val later = PermissionMerge.merge(first, live("""{"command":"make test"}"""))
        assertEquals("make test", ParsedPermission.parse(later).command)
        // A stripped edit gets its text from an update too, and the network host its tool name.
        val edit = PermissionMerge.merge(el("""{"toolCallId":"call-1","rawInput":{"file_path":"a.kt"}}"""), live(null))
        assertEquals(1, ParsedPermission.parse(PermissionMerge.merge(edit, live("""{"file_path":"a.kt","new_string":"y","old_string":"x"}"""))).diffFiles.size)
        val ask = el("""{"toolCallId":"call-1","rawInput":{"host":"h.io"}}""")
        assertNull(ParsedPermission.parse(PermissionMerge.merge(ask, live(null))).networkHost)
        val named = live(null, el("""{"claudeCode":{"toolName":"SandboxNetworkAccess"}}""").jsonObject)
        assertEquals("h.io", ParsedPermission.parse(PermissionMerge.merge(ask, named)).networkHost)
    }
}
