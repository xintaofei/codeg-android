package app.codeg.android.feature.sessiondetail.rendering

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Codex "code mode": recent codex builds wrap every tool call in a JavaScript
 * program executed as one `exec` call.
 *
 * The heavy lifting is the server's — `parsers/codex_code_mode.rs` reads the
 * program back into the calls it makes and re-emits them as the tool calls
 * codex wrote before code mode, so the ordinary cards keep working. Two
 * synthetic identities reach the client from that work and need names of their
 * own here, or the generic rules mis-file them:
 *
 *  - [SCRIPT_TOOL_NAME] — a program the parser could NOT decompose. Its input
 *    carries the raw JS source, so the freeform "exec*" rules would collapse it
 *    to `bash` and render a JavaScript program as a shell command.
 *  - [WAIT_TOOL_NAME] / [WRITE_STDIN_TOOL_NAME] — codex's unified-exec session
 *    tools. Neither carries a command: both address a background shell an
 *    earlier `exec_command` started, by the id that command announced (`wait`
 *    calls it `cell_id`, `write_stdin` calls it `session_id`). The history
 *    parser resolves that id back to the command and adds it as
 *    `session_command`, which is what these cards are titled by; the field is
 *    absent when the id was never announced in a form the parser trusts, and
 *    the card then names the session id instead.
 *
 * Port of the web `codex-code-mode.ts` / `shell-session-tool.ts`, narrowed to
 * what the Android card actually renders: the script source and the two session
 * titles. The web's per-command `codeg.codexScript` marks (table label, lost
 * output, shared spans) are not surfaced here — see the deferred task.
 */
object CodexCodeMode {

    const val SCRIPT_TOOL_NAME = "codex_script"
    const val WAIT_TOOL_NAME = "wait"
    const val WRITE_STDIN_TOOL_NAME = "write_stdin"

    /**
     * A session tool addresses a shell, not a command of its own.
     *
     * Matched on the name as sent, WITHOUT the `mcp__<server>__` strip every
     * other tool name goes through. `wait` and `write_stdin` are ordinary
     * words: an MCP database tool named `mcp__database__wait`, whose arguments
     * carry a `session_id`, would otherwise be titled "Wait cell db-7" and
     * filed with the shell commands. Only codex sends these unprefixed.
     */
    fun isShellSessionName(name: String): Boolean {
        val n = name.trim().lowercase()
        return n == WAIT_TOOL_NAME || n == WRITE_STDIN_TOOL_NAME
    }

    /** The code-mode script tool, matched exactly — see [isShellSessionName]. */
    fun isScriptName(name: String): Boolean = name.trim().lowercase() == SCRIPT_TOOL_NAME

    /** The undecomposable program a script card shows. */
    data class Script(val source: String, val title: String?, val callCount: Int)

    fun parseScript(obj: JsonObject?): Script? {
        val source = obj?.string("source") ?: return null
        return Script(
            source = source,
            title = obj.string("title"),
            callCount = (obj["call_count"] as? JsonPrimitive)
                ?.takeIf { !it.isString }?.content?.toIntOrNull() ?: 0,
        )
    }

    /** What a `wait` / `write_stdin` call addresses. */
    data class SessionCall(
        /** `cell_id` / `session_id`, normalized to a string (codex sends both). */
        val sessionId: String?,
        /** Command that started the session, resolved by the history parser. */
        val command: String?,
        /** Keystrokes sent (`write_stdin` only). Empty means "just collect output". */
        val chars: String,
        /** `wait` may ask to kill the session instead of waiting on it. */
        val terminate: Boolean,
    )

    fun parseSessionCall(obj: JsonObject?): SessionCall? {
        if (obj == null) return null
        return SessionCall(
            sessionId = readId(obj["cell_id"]) ?: readId(obj["session_id"]),
            command = obj.string("session_command")?.takeIf { it.isNotBlank() },
            chars = obj.string("chars").orEmpty(),
            terminate = (obj["terminate"] as? JsonPrimitive)?.let { !it.isString && it.content == "true" } == true,
        )
    }

    private fun readId(value: Any?): String? {
        val prim = value as? JsonPrimitive ?: return null
        return prim.content.trim().takeIf { it.isNotEmpty() }
    }

    private val NAMED_CONTROL_CHARS = mapOf('\n' to "⏎", '\r' to "⏎", '\t' to "⇥", '\u007F' to "^?")

    /**
     * Keystrokes sent to a session, rendered so a card title can show them: the
     * payload is usually a control character (`Ctrl-C` to interrupt, a bare
     * newline to confirm a prompt), which would otherwise be invisible.
     */
    fun describeStdinChars(chars: String, maxLen: Int = 40): String {
        val out = buildString {
            for (ch in chars) {
                val named = NAMED_CONTROL_CHARS[ch]
                if (named != null) {
                    append(named)
                    continue
                }
                val code = ch.code
                // Remaining C0 controls render as caret notation (`Ctrl-C` → `^C`).
                if (code < 0x20) append('^').append((code + 64).toChar()) else append(ch)
            }
        }.trim()
        return if (out.length > maxLen) out.take(maxLen - 1) + "…" else out
    }

    private fun JsonObject.string(key: String): String? {
        val prim = this[key] as? JsonPrimitive ?: return null
        return if (prim.isString) prim.content else null
    }
}
