package app.codeg.android.feature.sessiondetail.rendering

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * "No matches" for a search codex reported as failed (codeg web 0.44 `isGrepNoMatchResult` in
 * `ai-elements-adapter.ts`, and `grep-no-match.ts`).
 *
 * rg, grep, egrep and fgrep share one exit contract: 0 when a line was selected, 1 when none
 * was, 2 when anything went wrong. codex derives a tool's status from the exit code alone, so
 * a healthy negative search arrives as a FAILED call. Two of its shapes are a search that
 * simply found nothing, and settle as a success; every other failure stays one:
 *
 * 1. codex's own search card (`grep`, see [CodexCommandAction]) with its command envelope at
 *    exit 1 and nothing printed ([isEmptySearchResult], which draws it as "No matches").
 * 2. codex's own search card arriving live as a bare `failed` with NO output, marked by the
 *    server as codex's search ([CODEX_SEARCH_ACTION_META_KEY]). With codeg's terminal output
 *    deltas codex stops sending `rawOutput` on completion, so a search that printed nothing
 *    has no exit code left to check; rg prints a diagnostic on a real failure, so output is
 *    the discriminator. A persisted row carries no such mark and keeps case 1.
 * 3. A shell command whose exit status is a grep-like search's own ([endsInGrepSearch]) —
 *    `rg --files src | rg foo`, `cat f | grep foo`: codex reduces a pipeline to several
 *    actions, so it arrives as a plain command, and the only record of its exit is the line
 *    the server appends. That line, at exit 1, must be ALL the call printed: a broken path
 *    upstream of the search also exits 1, but only after rg reported it.
 */
object GrepNoMatch {

    /** The line the server appends when a process exits 1 (`hosted_terminal_exit_line`). */
    const val EXIT_ONE_LINE = "[terminal exited: exit code: 1]"

    private val GREP_COMMANDS = setOf("rg", "grep", "egrep", "fgrep")
    private val SHELLS = setOf("sh", "bash", "zsh")

    /** `NAME=value` words in front of a command (`LC_ALL=C grep …`). */
    private val ASSIGNMENT = Regex("^[A-Za-z_][A-Za-z0-9_]*=")

    /** A command word the shell runs as written: anything else can expand into another program. */
    private val PLAIN_WORD = Regex("^[\\w./+-]+$")

    /** How many `sh -c '…'` wrappers to look through. */
    private const val MAX_WRAPPERS = 3

    private val strict = Json

    /** The server's mark on a live call that is codex's own search action. */
    const val CODEX_SEARCH_ACTION_META_KEY = "codeg.codexSearchAction"

    /** Whether a failed call — by its resolved name, input, output and meta — is a search that found nothing. */
    fun settles(name: String, bucket: ToolKindBucket, input: String?, output: String?, meta: JsonObject? = null): Boolean {
        if (name == "grep") {
            if (output != null && isEmptySearchResult(name, isError = true, CodexCommandAction.parseEnvelope(output))) return true
            val marked = (meta?.get(CODEX_SEARCH_ACTION_META_KEY) as? JsonPrimitive)?.let { !it.isString && it.content == "true" } == true
            if (marked && output.isNullOrBlank()) return true
        }
        if (bucket != ToolKindBucket.EXECUTE || CodexCodeMode.isScriptName(name) || CodexCodeMode.isShellSessionName(name)) {
            return false
        }
        return isGrepNoMatchCommandResult(input, output)
    }

    /** Exit 1, nothing printed but the exit line, on a command that ends in a search. */
    fun isGrepNoMatchCommandResult(input: String?, output: String?): Boolean {
        if (output?.trim() != EXIT_ONE_LINE) return false
        val command = commandFromToolInput(input) ?: return false
        return endsInGrepSearch(command)
    }

    /** What a shell call ran: a script for the shell to split, or an argv. */
    private sealed interface Command {
        data class Script(val text: String) : Command
        data class Argv(val words: List<String>) : Command
    }

    /**
     * The command a shell call ran: `{command}` / `{cmd}` (a script or an argv), a bare JSON
     * string or argv, or the raw text itself when the input isn't JSON at all.
     */
    private fun commandFromToolInput(input: String?): Command? {
        val trimmed = input?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val parsed = runCatching { strict.parseToJsonElement(trimmed) }.getOrNull() ?: return Command.Script(trimmed)
        fun read(value: Any?): Command? = when (value) {
            is JsonPrimitive -> if (value.isString) Command.Script(value.content) else null
            is JsonArray -> value.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: return null }
                .takeIf { it.isNotEmpty() }?.let(Command::Argv)
            else -> null
        }
        if (parsed is JsonObject) {
            for (key in listOf("command", "cmd")) read(parsed[key])?.let { return it }
            return null
        }
        return read(parsed)
    }

    private fun endsInGrepSearch(command: Command): Boolean = when (command) {
        is Command.Script -> endsInGrepSearch(command.text)
        is Command.Argv -> endsInGrepSearch(command.words)
    }

    /** Whether script [command] exits with the status of a grep-like search. */
    fun endsInGrepSearch(command: String): Boolean = endsIn(pipelineStages(command))

    /** Whether argv [command] exits with the status of a grep-like search. */
    fun endsInGrepSearch(command: List<String>): Boolean = endsIn(listOf(command))

    /**
     * ONE pipeline whose last stage runs rg / grep / egrep / fgrep, read through any
     * `sh|bash|zsh -c` / `-lc` wrapper. A pipeline exits with its last stage's status, so exit
     * 1 there is the search's own "nothing selected". A leading `!` inverts it and is refused.
     */
    private fun endsIn(start: List<List<String>>?): Boolean {
        var stages = start
        var unwrapped = 0
        while (stages != null && unwrapped <= MAX_WRAPPERS) {
            val script = if (stages.size == 1) shellWrapperScript(stages[0]) else null
            if (script == null) return stages[0].firstOrNull() != "!" && isGrepInvocation(stages.last())
            stages = pipelineStages(script)
            unwrapped++
        }
        return false
    }

    /** `<script>` of `bash -lc '<script>'`, `/bin/zsh -lc "<script>"`, `sh -c …`. */
    private fun shellWrapperScript(words: List<String>): String? {
        if (words.size != 3) return null
        val (shell, flag, script) = words
        val name = programName(shell) ?: return null
        return if (name in SHELLS && Regex("^-l?c$").matches(flag)) script else null
    }

    private fun isGrepInvocation(words: List<String>): Boolean {
        var index = 0
        while (index < words.size && ASSIGNMENT.containsMatchIn(words[index])) index++
        if (index >= words.size) return false
        return programName(words[index])?.let { it in GREP_COMMANDS } == true
    }

    /** The program a command word runs (`/opt/homebrew/bin/rg` → `rg`), or null when not plain. */
    private fun programName(word: String): String? =
        if (PLAIN_WORD.matches(word)) word.substring(word.lastIndexOf('/') + 1) else null

    private enum class Redirect { OUTPUT, DUPLICATE, INPUT }

    /**
     * Whether the shell can fail to set up a redirection — it then exits 1 without running the
     * command, as silently as a search that found nothing once `2>/dev/null` came first.
     */
    private fun redirectionCanFail(kind: Redirect, target: String): Boolean = when (kind) {
        Redirect.OUTPUT -> target != "/dev/null"
        Redirect.DUPLICATE -> target != "1" && target != "2" && target != "/dev/null"
        Redirect.INPUT -> true
    }

    /**
     * The words of each stage of [script] when it is ONE pipeline of simple commands, or null
     * for anything else (web `pipelineStages`). A recognizer for that one shape, not a shell
     * parser: whatever could make the exit status come from somewhere other than the last
     * stage's command (`&&`, `||`, `;`, `&`, a newline, a subshell, `|&`, a last-stage
     * redirection the shell could fail to set up) and whatever it can't follow (command and
     * process substitution, `$'…'`, a heredoc, a comment, an unterminated quote) answers null.
     * Quotes and backslashes resolve the POSIX way.
     */
    fun pipelineStages(script: String): List<List<String>>? {
        val stages = mutableListOf(mutableListOf<String>())
        val word = StringBuilder()
        var inWord = false
        var pendingRedirect: Redirect? = null
        var stageRedirectCanFail = false

        fun endWord() {
            if (!inWord) return
            val text = word.toString()
            val pending = pendingRedirect
            if (pending != null) {
                if (redirectionCanFail(pending, text)) stageRedirectCanFail = true
                pendingRedirect = null
            } else {
                stages.last().add(text)
            }
            word.setLength(0)
            inWord = false
        }

        var index = 0
        while (index < script.length) {
            val char = script[index]
            val next = script.getOrNull(index + 1)
            when (char) {
                ' ', '\t' -> endWord()
                '\'' -> {
                    val close = script.indexOf('\'', index + 1)
                    if (close < 0) return null
                    word.append(script, index + 1, close)
                    inWord = true
                    index = close
                }
                '"' -> {
                    var cursor = index + 1
                    while (cursor < script.length && script[cursor] != '"') {
                        val quoted = script[cursor]
                        val after = script.getOrNull(cursor + 1)
                        if (quoted == '`' || (quoted == '$' && after == '(')) return null
                        if (quoted == '\\' && after != null && after in "\$`\"\\\n") {
                            cursor++
                            if (after != '\n') word.append(after)
                            cursor++
                            continue
                        }
                        word.append(quoted)
                        cursor++
                    }
                    if (cursor >= script.length) return null
                    inWord = true
                    index = cursor
                }
                '\\' -> {
                    if (next == null) return null
                    index++
                    if (next != '\n') {
                        word.append(next)
                        inWord = true
                    }
                }
                '|' -> {
                    if (next == '|' || next == '&') return null
                    endWord()
                    if (pendingRedirect != null || stages.last().isEmpty()) return null
                    stages.add(mutableListOf())
                    stageRedirectCanFail = false
                }
                '<', '>' -> {
                    if (next == '(' || (char == '<' && next == '<')) return null
                    // A bare number right before the operator is its descriptor (`2>`).
                    if (inWord && pendingRedirect == null && word.isNotEmpty() && word.all { it.isDigit() }) {
                        word.setLength(0)
                        inWord = false
                    } else {
                        endWord()
                    }
                    if (pendingRedirect != null) return null
                    pendingRedirect = when {
                        char == '<' -> Redirect.INPUT
                        next == '&' -> Redirect.DUPLICATE
                        else -> Redirect.OUTPUT
                    }
                    // The rest of a two-character operator: `>>`, `>&`, `>|`, `<>`, `<&`.
                    if (next == '>' || next == '&' || (char == '>' && next == '|')) index++
                }
                '&' -> {
                    // `&>file` / `&>>file`; any other `&` is `&&` or a background job.
                    if (next != '>') return null
                    endWord()
                    if (pendingRedirect != null) return null
                    index += if (script.getOrNull(index + 2) == '>') 2 else 1
                    pendingRedirect = Redirect.OUTPUT
                }
                '$' -> {
                    // `$(…)` runs a command; `$'…'` quotes by rules of its own.
                    if (next == '(' || next == '\'') return null
                    word.append(char)
                    inWord = true
                }
                '#' -> {
                    if (!inWord) return null
                    word.append(char)
                }
                ';', '\n', '\r', '(', ')', '`' -> return null
                else -> {
                    word.append(char)
                    inWord = true
                }
            }
            index++
        }
        endWord()
        if (pendingRedirect != null || stageRedirectCanFail) return null
        if (stages.any { it.isEmpty() }) return null
        return stages
    }
}
