package app.codeg.android.feature.sessiondetail.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** codex's own reading of a shell command, and the saved call drawn as the live one was. */
class CodexShellActionTest {

    private fun resolved(command: String): Pair<String, String>? =
        CodexShellAction.resolve(command)?.let { it.name to it.input.toString() }

    @Test
    fun `reads codex takes for reads`() {
        assertEquals("read" to """{"file_path":"/root/a/SKILL.md"}""", resolved("cat /root/a/SKILL.md"))
        assertEquals("read" to """{"file_path":"README.md"}""", resolved("head -n 35 README.md"))
        assertEquals("read" to """{"file_path":"README.md"}""", resolved("head -n35 README.md"))
        assertEquals("read" to """{"file_path":"log.txt"}""", resolved("tail -n +10 log.txt"))
        assertEquals("read" to """{"file_path":"Main.kt"}""", resolved("sed -n '1,120p' Main.kt"))
        assertEquals("read" to """{"file_path":"Main.kt"}""", resolved("nl -ba Main.kt"))
        assertEquals("read" to """{"file_path":"my file.md"}""", resolved("cat \"my file.md\""))
    }

    @Test
    fun `listings and searches`() {
        assertEquals("glob" to "{}", resolved("ls"))
        assertEquals("glob" to """{"path":"src"}""", resolved("ls src"))
        assertEquals("glob" to """{"path":"app"}""", resolved("ls -la /root/codeg/src/app/"))
        assertEquals("glob" to """{"path":"lib"}""", resolved("rg --files lib"))
        assertEquals("grep" to """{"pattern":"TODO","path":"lib"}""", resolved("rg -n TODO src/lib"))
        assertEquals("grep" to """{"pattern":"a b","path":"."}""", resolved("grep -rn 'a b' ."))
        assertEquals("grep" to """{"pattern":"x"}""", resolved("git grep -e x"))
    }

    @Test
    fun `what codex leaves a command stays one`() {
        // `head -5` isn't a form codex reads (live it streamed as a command too).
        assertNull(resolved("head -5 README.md"))
        assertNull(resolved("git log --oneline -3"))
        assertNull(resolved("wc -l package.json"))
        assertNull(resolved("cat a.md b.md"))
        assertNull(resolved("sed -i 's/a/b/' f.txt"))
        assertNull(resolved("sed 's/a/b/' f.txt"))
        // More than one plain command: not guessed at.
        assertNull(resolved("cat a | head -n 5"))
        assertNull(resolved("cd app && cat b"))
        assertNull(resolved("cat *.md"))
        assertNull(resolved("cat \$HOME/x"))
        assertNull(resolved("cat 'unclosed"))
        assertNull(resolved(""))
    }

    @Test
    fun `codex shortens a listed path the way it shows it`() {
        assertEquals("webview", CodexShellAction.shortDisplayPath("webview/src"))
        assertEquals("app", CodexShellAction.shortDisplayPath("packages/app/node_modules/"))
        assertEquals("src", CodexShellAction.shortDisplayPath("src"))
    }

    private fun live(title: String, kind: String) =
        ToolCallVM.of("c1", title, kind, ToolCallState.DONE, null, "out", null, false, liveName = "x")

    private fun saved(command: String) =
        ToolCallVM.of("c1", "exec_command", "", ToolCallState.DONE, command, "out", null, false)

    private fun identity(vm: ToolCallVM) = listOf(vm.rawName, vm.input, vm.displayTitle, vm.bucket)

    @Test
    fun `a saved codex command reads back as the action it streamed as`() {
        assertEquals(identity(live("Read file '/root/a/SKILL.md'", "read")), identity(saved("cat /root/a/SKILL.md")))
        assertEquals(identity(live("List files in 'src'", "search")), identity(saved("ls src")))
        assertEquals(identity(live("List files", "search")), identity(saved("ls")))
        assertEquals(identity(live("Search for 'TODO' in lib", "search")), identity(saved("rg TODO src/lib")))
    }

    @Test
    fun `a saved command codex ran as a command keeps its command line`() {
        val vm = saved("git log --oneline -3")
        assertEquals("exec_command", vm.rawName)
        assertEquals("git log --oneline -3", vm.input)
        assertEquals("git log --oneline -3", vm.displayTitle)
    }

    @Test
    fun `a live command titled by its own command line is titled by it once`() {
        val vm = ToolCallVM.of(
            "c1", "git log --oneline -3", "execute", ToolCallState.DONE,
            """{"command":"git log --oneline -3"}""", null, null, false, liveName = "bash",
        )
        assertEquals("git log --oneline -3", vm.displayTitle)
    }
}
