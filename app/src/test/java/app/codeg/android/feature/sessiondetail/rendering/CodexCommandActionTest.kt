package app.codeg.android.feature.sessiondetail.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * codex-acp command actions, whose title is the ONLY thing they carry.
 *
 * codex announces a shell command it classified as a search or a listing with a
 * human sentence and no `rawInput` at all, so a title that fails to parse costs
 * the card its identity: a wrench icon, an "other" tally in the tool group, and
 * a body that prints the raw result envelope.
 */
class CodexCommandActionTest {

    @Test
    fun `a search title yields the query and the scope path`() {
        assertEquals(
            CodexCommandAction.Search("Tests run:", "com.forwayaudio.app"),
            CodexCommandAction.parseSearchTitle("Search for 'Tests run:' in com.forwayaudio.app"),
        )
        assertEquals(
            CodexCommandAction.Search("TODO", null),
            CodexCommandAction.parseSearchTitle("Search for 'TODO'"),
        )
        assertEquals(
            CodexCommandAction.Search(null, "src/lib"),
            CodexCommandAction.parseSearchTitle("Search in 'src/lib'"),
        )
        // A bare "Search" parses successfully with nothing in it — the caller
        // tells that apart from "not a search title" by the null return.
        assertEquals(CodexCommandAction.Search(null, null), CodexCommandAction.parseSearchTitle("Search"))
    }

    @Test
    fun `the separator is matched from the right because a query may contain it`() {
        // The path is NOT quoted, so `' in ` is the only boundary, and only the
        // last one can be it.
        assertEquals(
            CodexCommandAction.Search("a' in b", "src/lib"),
            CodexCommandAction.parseSearchTitle("Search for 'a' in b' in src/lib"),
        )
        // …and with no trailing path, the whole rest is the query.
        assertEquals(
            CodexCommandAction.Search("x in y", null),
            CodexCommandAction.parseSearchTitle("Search for 'x in y'"),
        )
        // An apostrophe inside the query survives.
        assertEquals(
            CodexCommandAction.Search("don't stop", null),
            CodexCommandAction.parseSearchTitle("Search for 'don't stop'"),
        )
    }

    @Test
    fun `titles that are not command actions are left alone`() {
        assertNull(CodexCommandAction.parseSearchTitle("Search for ''"))
        assertNull(CodexCommandAction.parseSearchTitle("Searching for 'x'"))
        assertNull(CodexCommandAction.parseSearchTitle("Research 'x' in y"))
        assertNull(CodexCommandAction.parseSearchTitle(null))
        assertNull(CodexCommandAction.parseListFilesTitle("Search for 'x'"))
        assertNull(CodexCommandAction.resolve("Read file 'src/main.kt'"))
    }

    @Test
    fun `a list-files title yields its directory`() {
        assertEquals(CodexCommandAction.ListFiles("src"), CodexCommandAction.parseListFilesTitle("List files in 'src'"))
        assertEquals(CodexCommandAction.ListFiles(null), CodexCommandAction.parseListFilesTitle("List files"))
    }

    @Test
    fun `resolve recovers the canonical identity plus the input codex never sent`() {
        val search = requireNotNull(CodexCommandAction.resolve("Search for 'TODO' in src"))
        assertEquals("grep", search.name)
        assertEquals("""{"pattern":"TODO","path":"src"}""", search.input.toString())
        // …which is what turns the title into "Grep TODO" and the bucket into a search.
        assertEquals("Grep TODO", ToolDerive.title(search.name, search.input))
        assertEquals(ToolKindBucket.SEARCH, ToolDerive.bucket(search.name, ""))

        val list = requireNotNull(CodexCommandAction.resolve("List files in 'src/lib'"))
        assertEquals("glob", list.name)
        assertEquals(ToolKindBucket.SEARCH, ToolDerive.bucket(list.name, ""))
    }

    @Test
    fun `the envelope needs both of its keys`() {
        assertEquals(
            CodexCommandAction.Envelope("src/a.kt:1:hit\n", 0L),
            CodexCommandAction.parseEnvelope("""{"exit_code":0,"formatted_output":"src/a.kt:1:hit\n"}"""),
        )
        // A genuine tool payload that merely resembles one must pass through
        // untouched — unwrapping it would hand the card a fragment.
        assertNull(CodexCommandAction.parseEnvelope("""{"output":"hi"}"""))
        assertNull(CodexCommandAction.parseEnvelope("""{"exit_code":0}"""))
        assertNull(CodexCommandAction.parseEnvelope("""{"formatted_output":"hi"}"""))
        assertNull(CodexCommandAction.parseEnvelope("""{"exit_code":"0","formatted_output":"hi"}"""))
        assertNull(CodexCommandAction.parseEnvelope("not json"))
    }

    @Test
    fun `the envelope is read strictly, as the web's JSON parse reads it`() {
        // The shared lenient parser this package uses to DISPLAY arguments
        // accepts unquoted keys. Deciding whether output belongs to codex at
        // all is a different job: anything the web shows raw must stay raw
        // here, or a payload that merely resembles the envelope gets eaten.
        assertNull(CodexCommandAction.parseEnvelope("""{exit_code: 0, formatted_output: "hi"}"""))
        assertNull(CodexCommandAction.parseEnvelope("""{'exit_code':0,'formatted_output':'hi'}"""))
        assertNull(CodexCommandAction.parseEnvelope("""{"exit_code":0,"formatted_output":"hi"} trailing"""))
        assertNull(CodexCommandAction.parseEnvelope("""[{"exit_code":0,"formatted_output":"hi"}]"""))
        // A non-integer exit code is not codex's either.
        assertNull(CodexCommandAction.parseEnvelope("""{"exit_code":0.5,"formatted_output":"hi"}"""))
        assertNull(CodexCommandAction.parseEnvelope("""{"exit_code":true,"formatted_output":"hi"}"""))
        // kotlinx keeps the number's source text and `toIntOrNull` accepts
        // forms the JSON grammar does not, so these have to be rejected by
        // hand or a payload `JSON.parse` throws on gets unwrapped here.
        assertNull(CodexCommandAction.parseEnvelope("""{"exit_code":01,"formatted_output":"hi"}"""))
        assertNull(CodexCommandAction.parseEnvelope("""{"exit_code":+1,"formatted_output":"hi"}"""))
        assertEquals(
            CodexCommandAction.Envelope("hi", -1L),
            CodexCommandAction.parseEnvelope("""{"exit_code":-1,"formatted_output":"hi"}"""),
        )
        // A process cannot exit past Int range, but `JSON.parse` accepts the
        // number and the web unwraps it, so narrowing here would show the raw
        // envelope for a payload the other client renders.
        assertEquals(
            CodexCommandAction.Envelope("hi", 2147483648L),
            CodexCommandAction.parseEnvelope("""{"exit_code":2147483648,"formatted_output":"hi"}"""),
        )
        // Extra keys are fine — the web only looks at the two as well.
        assertEquals(
            CodexCommandAction.Envelope("hi", 0L),
            CodexCommandAction.parseEnvelope("""{"exit_code":0,"formatted_output":"hi","extra":1}"""),
        )
    }

    @Test
    fun `only grep exit 1 with no output reads as no matches`() {
        fun envelope(out: String, exit: Long) = CodexCommandAction.Envelope(out, exit)

        // codex marks a grep that selected nothing as FAILED; that exact shape
        // is the empty result.
        assertTrue(isEmptySearchResult("grep", isError = true, envelope = envelope("", 1L)))
        // A real grep failure — exit 2 (bad pattern), 127 (missing binary), or
        // one carrying output — must keep its error rendering, not be reported
        // as a successful empty search.
        assertFalse(isEmptySearchResult("grep", isError = true, envelope = envelope("", 2L)))
        assertFalse(isEmptySearchResult("grep", isError = true, envelope = envelope("", 127L)))
        assertFalse(isEmptySearchResult("grep", isError = true, envelope = envelope("rg: bad regex", 1L)))
        // For a list-files action resolved to glob, any non-zero exit is real.
        assertFalse(isEmptySearchResult("glob", isError = true, envelope = envelope("", 1L)))
        // …but its empty SUCCESS says the same thing an empty search does.
        assertTrue(isEmptySearchResult("glob", isError = false, envelope = envelope("", 0L)))
        // No envelope means another agent's output, which is never claimed.
        assertFalse(isEmptySearchResult("grep", isError = true, envelope = null))
    }

    @Test
    fun `a call that sent arguments keeps them, and still gets the identity`() {
        // Only the INPUT recovery is conditional. The name is read from the
        // title either way, as the web's `normalizeToolName` does — a tool
        // genuinely called "Search for 'x'" does not exist, and leaving the
        // title as the name costs the card its icon and its group tally.
        val vm = ToolCallVM.of(
            id = "t1",
            rawName = "Search for 'stale'",
            kind = "search",
            state = ToolCallState.DONE,
            input = """{"pattern":"real","path":"src"}""",
            output = null,
            content = null,
            isError = false,
        )

        assertEquals("grep", vm.rawName)
        assertEquals(ToolKindBucket.SEARCH, vm.bucket)
        // The pattern the call actually sent is never replaced by the guess.
        assertEquals("""{"pattern":"real","path":"src"}""", vm.input)
    }

    @Test
    fun `an empty argument object counts as no arguments`() {
        // Hosts encode "no arguments" as `{}` as readily as they omit the
        // field. Reading that as real input leaves the action unrecovered: no
        // query, a wrench icon, and the raw envelope in the body.
        assertFalse(sentArguments("{}"))
        assertFalse(sentArguments("""{"input":{}}"""))
        assertFalse(sentArguments(null))
        assertFalse(sentArguments("   "))
        assertTrue(sentArguments("""{"pattern":"x"}"""))
        // Not JSON at all is still something the call sent.
        assertTrue(sentArguments("ls -la"))

        val vm = ToolCallVM.of(
            id = "t3",
            rawName = "Search for 'TODO' in src",
            kind = "search",
            state = ToolCallState.DONE,
            input = "{}",
            output = null,
            content = null,
            isError = false,
        )

        assertEquals("grep", vm.rawName)
        assertEquals("""{"pattern":"TODO","path":"src"}""", vm.input)
    }

    @Test
    fun `a bare Search recovers an identity but not a body`() {
        // "Search" carries no query and no path. Leaving the host's `{}` as the
        // input gives the card an expander with nothing behind it.
        val vm = ToolCallVM.of(
            id = "t4",
            rawName = "Search",
            kind = "search",
            state = ToolCallState.DONE,
            input = "{}",
            output = null,
            content = null,
            isError = false,
        )

        assertEquals("grep", vm.rawName)
        assertNull(vm.input)
    }

    @Test
    fun `a recovered list-files action outranks the read kind hint codex sends`() {
        // codex announces the listing with `kind: "read"`. Kind-first bucketing
        // would file a directory listing as a file read and route its envelope
        // through the Read-file body, which prints nothing for it.
        val vm = ToolCallVM.of(
            id = "t2",
            rawName = "List files in 'src'",
            kind = "read",
            state = ToolCallState.DONE,
            input = null,
            output = """{"exit_code":0,"formatted_output":"src/a.kt\n"}""",
            content = null,
            isError = false,
        )

        assertEquals("glob", vm.rawName)
        assertEquals(ToolKindBucket.SEARCH, vm.bucket)
        assertEquals("""{"path":"src"}""", vm.input)
    }
}
