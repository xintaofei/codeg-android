package app.codeg.android.core.model

import app.codeg.android.core.network.CodegJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Session config options as the server actually sends them. A boolean option
 * (ACP's `unstable_boolean_config`, shipped by cline 3.0.50 as `auto_approve`)
 * arrives with a real JSON `true`/`false` in a field this client types as a
 * `String` — the whole feature rests on that coercion, and nothing else in the
 * codebase decodes a boolean into a string, so nothing else would catch it
 * being turned off.
 */
class SessionConfigKindTest {

    private fun decode(json: String): SessionSnapshot =
        CodegJson.response.decodeFromString(SessionSnapshot.serializer(), json)

    /** A live cline snapshot, verbatim in shape. */
    private val clineSnapshot = """
        {"selectors_ready":true,"config_options":[
          {"id":"auto_approve","name":"Auto-approve tools",
           "description":"Run tools without asking",
           "kind":{"type":"boolean","current_value":true}}]}
    """.trimIndent()

    @Test
    fun `a boolean current_value decodes to its string form`() {
        val option = decode(clineSnapshot).configOptions.single()

        assertEquals("auto_approve", option.id)
        assertTrue(option.kind.isBoolean)
        // Not `true` the JSON literal: the read path is typed but every codeg input
        // (the set call, connect preferences, the preference store) is a string.
        assertEquals("true", option.kind.currentValue)
        assertEquals(true, SessionConfigKind.booleanState(option.kind.currentValue))
    }

    @Test
    fun `an off toggle decodes and reads as off`() {
        val off = decode(clineSnapshot.replace("\"current_value\":true", "\"current_value\":false"))

        assertEquals("false", off.configOptions.single().kind.currentValue)
        assertEquals(false, SessionConfigKind.booleanState(off.configOptions.single().kind.currentValue))
    }

    @Test
    fun `a value that isn't a toggle position is unknown, not off`() {
        // `current_value` is a required field on the server's `SessionConfigBooleanInfo`, so
        // this only happens against a payload something is wrong with. Reporting "off" would
        // tell the user cline's auto-approve is asking first when it may not be — the sheet
        // renders the unknown state read-only instead.
        val absent = decode("""{"config_options":[{"id":"auto_approve","kind":{"type":"boolean"}}]}""")

        assertNull(absent.configOptions.single().kind.currentValue)
        assertNull(SessionConfigKind.booleanState(null))
        assertNull(SessionConfigKind.booleanState(""))
        assertNull(SessionConfigKind.booleanState("TRUE"))
        assertNull(SessionConfigKind.booleanState("1"))
    }

    @Test
    fun `a select option is untouched by the boolean path`() {
        val snap = decode(
            """{"config_options":[{"id":"effort","name":"Reasoning effort","kind":{"type":"select",
               "current_value":"high","options":[{"value":"high","name":"High"},
               {"value":"low","name":"Low"}],"groups":[]}}]}""",
        )
        val kind = snap.configOptions.single().kind

        assertFalse(kind.isBoolean)
        assertEquals(listOf("high", "low"), kind.acceptedValues)
    }

    @Test
    fun `an unknown future kind is not mistaken for a toggle`() {
        // An *agent* can't send an unknown kind — the server drops those before Android sees
        // them (`strip_unknown_config_options`, allow-list `["select", "boolean"]`). Only a
        // newer server that widened that list can. The one thing that must hold is that such a
        // kind never claims to be a toggle, which would let a tap send `"true"` to an option
        // that doesn't accept one. What it renders instead is a best-effort fallback, not a
        // correctness claim: this build cannot know how a future kind wants to be encoded.
        val kind = decode("""{"config_options":[{"id":"x","kind":{"type":"slider","current_value":"3"}}]}""")
            .configOptions.single().kind

        assertFalse(kind.isBoolean)
        assertEquals(emptyList<String>(), kind.acceptedValues)
    }

    @Test
    fun `an unknown kind's values are still accepted as remembered preferences`() {
        // `acceptedValues` only — whether the sheet renders them as rows is not asserted here.
        val kind = decode(
            """{"config_options":[{"id":"x","kind":{"type":"radio","current_value":"a",
               "options":[{"value":"a"},{"value":"b"}]}}]}""",
        ).configOptions.single().kind

        assertFalse(kind.isBoolean)
        assertEquals(listOf("a", "b"), kind.acceptedValues)
    }

    @Test
    fun `a boolean accepts both positions as remembered preferences`() {
        // `loadAgentOptions` validates a saved preference against this list. A boolean
        // advertises no `options`, so an accepted-values list built only from `options`
        // would silently reject every remembered toggle — and the sheet would then show
        // the agent's default while `connectWithPreferences` applied the saved value.
        val kind = SessionConfigKind(type = "boolean", currentValue = "false")

        assertEquals(listOf("true", "false"), kind.acceptedValues)
        assertTrue(SessionConfigKind.booleanValue(true) in kind.acceptedValues)
        assertTrue(SessionConfigKind.booleanValue(false) in kind.acceptedValues)
    }

    @Test
    fun `the toggle wire values are the ones the server compares against`() {
        // `encode_config_option_value` server-side is literally `value == "true"`, so any
        // other spelling ("TRUE", "1", "on") silently becomes false instead of failing.
        assertEquals("true", SessionConfigKind.booleanValue(true))
        assertEquals("false", SessionConfigKind.booleanValue(false))
    }

    @Test
    fun `grouped select values are all accepted`() {
        val kind = SessionConfigKind(
            type = "select",
            groups = listOf(
                SessionConfigSelectGroup(group = "g1", options = listOf(SessionConfigSelectOption("a"))),
                SessionConfigSelectGroup(group = "g2", options = listOf(SessionConfigSelectOption("b"))),
            ),
        )

        assertEquals(listOf("a", "b"), kind.acceptedValues)
    }
}
