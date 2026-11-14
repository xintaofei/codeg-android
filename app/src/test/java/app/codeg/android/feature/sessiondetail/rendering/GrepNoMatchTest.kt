package app.codeg.android.feature.sessiondetail.rendering

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A search codex reported as failed that only found nothing. Vectors from the web's `grep-no-match.test.ts`. */
class GrepNoMatchTest {

    /** The command from codeg #877, as codex-acp sent it. */
    private val issue877 = "rg --files scripts src/grapal/phonics tests | rg 'sequence_(nested|rerank_candidate)'"

    @Test
    fun `a pipeline splits, keeping a pipe inside quotes`() {
        assertEquals(
            listOf(listOf("rg", "--files", "scripts", "src/grapal/phonics", "tests"), listOf("rg", "sequence_(nested|rerank_candidate)")),
            GrepNoMatch.pipelineStages(issue877),
        )
        assertEquals(
            listOf(listOf("grep", "-e", "a \"b\" \\d", "cd", "e f")),
            GrepNoMatch.pipelineStages("grep -e \"a \\\"b\\\" \\d\" 'c'\"d\" e\\ f"),
        )
        assertEquals(listOf(listOf("rg", "")), GrepNoMatch.pipelineStages("rg ''"))
        // Redirections the last stage can't fail to set up are dropped.
        assertEquals(
            listOf(listOf("rg", "--files"), listOf("rg", "-n", "x")),
            GrepNoMatch.pipelineStages("rg --files 2>err.log < in | rg -n x 2>/dev/null >/dev/null 2>&1 >&2 &>>/dev/null"),
        )
    }

    @Test
    fun `anything but one plain pipeline is refused`() {
        listOf(
            "cat /dev/null | grep needle 2>/dev/null </dev/null/codeg-877",
            "rg x 2>/dev/null > out.txt",
            "rg x <> f",
            "rg x <&3",
            "< in grep x",
            "rg --files && rg x",
            "rg x || true",
            "rg a; rg b",
            "rg a\nrg b",
            "rg a & rg b",
            "(rg a) | rg b",
            "rg $(cat pattern)",
            "rg \"$(cat pattern)\"",
            "rg `cat pattern`",
            "false $'\\' | grep -q x'\\'",
            "rg x <(ls)",
            "grep x <<EOF",
            "rg x # why",
            "rg --files |& rg x",
            "rg --files | | rg x",
            "rg --files |",
            "rg x >",
            "rg x > > out",
            "rg 'x",
            "rg \"x",
            "rg x\\",
            "   ",
        ).forEach { assertNull(it, GrepNoMatch.pipelineStages(it)) }
    }

    @Test
    fun `a command ending in a search has the search's exit status`() {
        listOf(
            issue877,
            "rg -n '__absent__' README.md",
            "cat README.md | grep -n '__absent__'",
            "ls | egrep x",
            "/opt/homebrew/bin/rg x",
            "LC_ALL=C GREP_COLOR=1 grep x f",
            "'rg' x",
            "bash -lc 'rg --files | rg x'",
            "/bin/zsh -lc \"$issue877\"",
            "/bin/zsh -lc \"bash -c 'rg x'\"",
        ).forEach { assertTrue(it, GrepNoMatch.endsInGrepSearch(it)) }
        assertTrue(GrepNoMatch.endsInGrepSearch(listOf("rg", "-n", "x")))
        assertTrue(GrepNoMatch.endsInGrepSearch(listOf("bash", "-lc", issue877)))
        listOf(
            "rg x | wc -l",
            "find . -name x",
            "rg --files | xargs grep x",
            "! cat f | grep -q x",
            "bash -c '! cat f | grep -q x'",
            "git grep --no-index --open-files-in-pager=false x -- f",
            "A=1",
            "\${IFS:+false\$IFS}/grep needle",
            "\$PREFIX/rg x",
            "~/bin/rg x",
            "/opt/*/bin/rg x",
            "grep -q needle f && test -d out",
            "bash -c 'rg x' zero one",
            "bash -e -c 'rg x'",
        ).forEach { assertFalse(it, GrepNoMatch.endsInGrepSearch(it)) }
    }

    private fun failed(name: String, input: String?, output: String) =
        ToolCallVM.of("t", name, "", ToolCallState.ERROR, input, output, null, true)

    @Test
    fun `a search that found nothing settles as a success, a real failure stays one`() {
        val input = """{"command":"/bin/zsh -lc \"$issue877\"","cwd":"/ws"}"""
        assertEquals(ToolCallState.DONE, failed("Bash", input, GrepNoMatch.EXIT_ONE_LINE).state)
        // Something printed before the exit: rg reported a broken path.
        assertEquals(ToolCallState.ERROR, failed("Bash", input, "rg: no_such_dir: No such file\n${GrepNoMatch.EXIT_ONE_LINE}").state)
        assertEquals(ToolCallState.ERROR, failed("Bash", """{"command":"rg x | wc -l"}""", GrepNoMatch.EXIT_ONE_LINE).state)
        // codex's own search card: exit 1, nothing printed. Exit 2 is a real grep failure.
        val noMatch = failed("grep", """{"pattern":"x"}""", """{"formatted_output":"","exit_code":1}""")
        assertEquals(ToolCallState.DONE, noMatch.state)
        assertTrue("the body still reads it as the no-match envelope", noMatch.isError)
        assertEquals(StepStatus.SUCCEEDED, stepStatus(noMatch))
        assertEquals(ToolCallState.ERROR, failed("grep", """{"pattern":"x"}""", """{"formatted_output":"","exit_code":2}""").state)
    }

    @Test
    fun `codex's live search failing with nothing printed found nothing`() {
        val mark = buildJsonObject { put(GrepNoMatch.CODEX_SEARCH_ACTION_META_KEY, JsonPrimitive(true)) }
        fun live(output: String?, meta: JsonObject?) =
            ToolCallVM.of("t", "grep", "", ToolCallState.ERROR, """{"pattern":"x"}""", output, null, true, meta)
        assertEquals(ToolCallState.DONE, live(null, mark).state)
        assertEquals(ToolCallState.DONE, live("  ", mark).state)
        // A diagnostic is a real failure; without the mark, another adapter's grep looks the same.
        assertEquals(ToolCallState.ERROR, live("rg: regex parse error", mark).state)
        assertEquals(ToolCallState.ERROR, live(null, null).state)
    }
}
