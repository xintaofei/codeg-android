package app.codeg.android.feature.sessiondetail.rendering

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * codex-acp "command action" tool calls.
 *
 * When codex classifies a shell command as a known action (read / search /
 * list-files), codex-acp announces it as an ACP `tool_call` carrying ONLY a
 * human title — no `rawInput` at all — and later completes it with
 * `rawOutput: { formatted_output, exit_code }`. The query and the path
 * therefore exist nowhere but the title, so the tool identity and the card's
 * input both have to be read back out of it. Without that the whole title
 * becomes the tool "name": a generic wrench icon, an "other" tally in the tool
 * group, and a body that prints the raw envelope.
 *
 * The title shapes are codex-acp's `createSearchTitle` / list-files literals.
 * Note the asymmetric quoting — the search path is NOT quoted, every other
 * interpolation is:
 *
 *   Search for '<query>' in <path>
 *   Search for '<query>'
 *   Search in '<path>'
 *   Search
 *   List files in '<path>'
 *   List files
 *   Read file '<path>'            ← the file only: the card needs it as its input
 *
 * Port of the web `codex-command-action.ts`.
 */
object CodexCommandAction {

    /** A recovered action: the canonical tool name plus the input codex never sent. */
    data class Resolved(val name: String, val input: JsonObject)

    /** Search pattern / scope path, either of which codex may have failed to extract. */
    data class Search(val query: String?, val path: String?)

    /** The directory a list-files action was scoped to; null for a bare "List files". */
    data class ListFiles(val path: String?)

    /**
     * Recover the tool identity from a command-action title, or null when
     * [title] isn't one. Only called when the call carries no input of its own,
     * so a real `grep` tool's arguments can never be replaced by this.
     */
    fun resolve(title: String): Resolved? {
        // A read names its file in the title only (the web takes it from `locations`, which
        // this client doesn't keep): a `file_path` gives the card "Read <file>" and the file
        // view, as a read's saved copy has ([CodexShellAction]).
        parseReadFileTitle(title)?.let { path ->
            return Resolved(name = "read", input = buildJsonObject { put("file_path", path) })
        }
        parseSearchTitle(title)?.let { search ->
            return Resolved(
                name = "grep",
                input = buildJsonObject {
                    search.query?.let { put("pattern", it) }
                    search.path?.let { put("path", it) }
                },
            )
        }
        parseListFilesTitle(title)?.let { listFiles ->
            return Resolved(
                name = "glob",
                input = buildJsonObject { listFiles.path?.let { put("path", it) } },
            )
        }
        return null
    }

    private const val SEARCH_FOR_PREFIX = "Search for '"

    /**
     * Separator between a quoted query and the UNQUOTED path. Matched from the
     * right: a path never contains `' in `, but a query may.
     */
    private const val QUERY_PATH_SEPARATOR = "' in "
    private val SEARCH_IN = Regex("""^Search in '([\s\S]*)'$""")
    private val LIST_FILES_IN = Regex("""^List files in '([\s\S]*)'$""")
    private val READ_FILE = Regex("""^Read file '([^']*)'$""")

    /**
     * Parse a search title into its query / path, or null when the title isn't
     * one. A bare "Search" is a successful parse with both fields null — the
     * caller tells the two apart by the null return, not by the fields.
     */
    fun parseSearchTitle(title: String?): Search? {
        val trimmed = title?.trim()
        if (trimmed.isNullOrEmpty()) return null
        if (trimmed == "Search") return Search(null, null)

        if (trimmed.startsWith(SEARCH_FOR_PREFIX)) {
            val rest = trimmed.substring(SEARCH_FOR_PREFIX.length)
            val separator = rest.lastIndexOf(QUERY_PATH_SEPARATOR)
            if (separator > 0) {
                val path = rest.substring(separator + QUERY_PATH_SEPARATOR.length).trim()
                if (path.isNotEmpty()) return Search(rest.substring(0, separator), path)
            }
            if (rest.endsWith("'")) {
                val query = rest.dropLast(1)
                if (query.isNotEmpty()) return Search(query, null)
            }
            return null
        }

        val pathOnly = SEARCH_IN.find(trimmed)?.groupValues?.get(1)
        if (!pathOnly.isNullOrEmpty()) return Search(null, pathOnly)
        return null
    }

    /**
     * The file a `Read file '<path>'` title names, or null. One quoted path only: codex-acp
     * joins the titles of a multi-command line with ", ", which this leaves alone.
     */
    fun parseReadFileTitle(title: String?): String? =
        READ_FILE.find(title?.trim().orEmpty())?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }

    /**
     * Parse a list-files title into its path, or null when it isn't one. As
     * with [parseSearchTitle], the wrapper is what distinguishes "not a
     * list-files title" from the bare "List files", whose path is null.
     */
    fun parseListFilesTitle(title: String?): ListFiles? {
        val trimmed = title?.trim()
        if (trimmed.isNullOrEmpty()) return null
        if (trimmed == "List files") return ListFiles(null)

        val path = LIST_FILES_IN.find(trimmed)?.groupValues?.get(1)
        if (!path.isNullOrEmpty()) return ListFiles(path)
        return null
    }

    /** The unwrapped `{formatted_output, exit_code}` result envelope. */
    data class Envelope(val output: String, val exitCode: Long)

    /** Default settings, i.e. what `JSON.parse` accepts — see [parseEnvelope]. */
    private val strict = Json

    /**
     * A JSON integer as the grammar defines one. kotlinx keeps the number's
     * source text, and `toLongOrNull` is happy with forms JSON is not — `01`,
     * `+1` — so the exit code would parse where `JSON.parse` throws.
     */
    private val JSON_INT = Regex("""-?(0|[1-9][0-9]*)""")

    /**
     * Unwrap codex-acp's command-execution result envelope, which always sends
     * BOTH `formatted_output` and `exit_code`. Requiring both keeps a genuine
     * JSON payload (`{output: …}`, `{stdout: …}`, a lone `{exit_code: 0}`, …)
     * from being mistaken for the envelope. Null for anything else — including
     * every other agent's search output, which passes through byte for byte.
     *
     * Parsed strictly, unlike the rest of this package: everywhere else JSON is
     * read to DISPLAY an agent's arguments, where leniency salvages a messy
     * payload, but here it decides whether the text belongs to codex at all.
     * The shared lenient parser accepts unquoted keys, so `{exit_code: 0,
     * formatted_output: "…"}` — which the web's `JSON.parse` rejects and shows
     * raw — would have been unwrapped here instead.
     */
    fun parseEnvelope(raw: String): Envelope? {
        val obj = runCatching { strict.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        val exit = (obj["exit_code"] as? JsonPrimitive)
            ?.takeIf { !it.isString && JSON_INT.matches(it.content) }
            ?.content?.toLongOrNull()
            ?: return null
        val formatted = (obj["formatted_output"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return null
        return Envelope(formatted, exit)
    }
}
