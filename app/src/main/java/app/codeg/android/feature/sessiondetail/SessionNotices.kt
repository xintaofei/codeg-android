package app.codeg.android.feature.sessiondetail

import app.codeg.android.core.model.AgentType
import app.codeg.android.core.model.PluginLoadFailure

/**
 * How the connection's one-shot advisories read (codeg web 0.44 `lib/session-notices.ts`,
 * `lib/plugin-load-failures.ts`, and the `session_load_failed` handler). Pure, for tests.
 */
object SessionNotices {

    /** A headline and what follows it. */
    data class Headline(val title: String, val description: String?)

    private val COMPACTION_ADVISORY = Regex("\\bmultiple compactions\\b", RegexOption.IGNORE_CASE)

    /** A notice that only restates a compaction the transcript's divider already shows. */
    fun isCompactionEcho(severity: String, title: String, description: String?): Boolean {
        if (COMPACTION_ADVISORY.containsMatchIn("$title\n${description.orEmpty()}")) return true
        return severity == "info" && title.trim().equals("context compacted", ignoreCase = true)
    }

    /** [text]'s first non-blank line as the headline; the rest of it, then [more], below. */
    fun splitHeadline(text: String, more: String? = null): Headline? {
        val lines = text.split(Regex("\\r?\\n"))
        val first = lines.indexOfFirst { it.isNotBlank() }
        if (first < 0) return null
        val rest = lines.drop(first + 1).joinToString("\n").trim()
        val description = listOf(rest, more?.trim().orEmpty()).filter { it.isNotEmpty() }.joinToString("\n")
        return Headline(lines[first].trim(), description.ifEmpty { null })
    }

    /** A session notice as it is told, or null for one not worth telling. */
    fun sessionNotice(severity: String, title: String, description: String?): Headline? {
        if (isCompactionEcho(severity, title, description)) return null
        return splitHeadline(title, description)
    }

    private val POSITIONAL_TAG = Regex("^(?:inline|synced)\\[\\d+]$")

    /** What names a failed plugin best: its path, for an entry that failed before it had a name. */
    fun pluginLabel(failure: PluginLoadFailure): String {
        val path = failure.path?.trim()
        return if (POSITIONAL_TAG.matches(failure.plugin) && !path.isNullOrEmpty()) path else failure.plugin
    }

    /** How many plugins failed (one may report several entries), and one line per entry. */
    data class PluginFailures(val count: Int, val description: String)

    fun pluginFailures(failures: List<PluginLoadFailure>): PluginFailures? {
        if (failures.isEmpty()) return null
        val labels = failures.map(::pluginLabel).toSet()
        val description = failures.joinToString("\n") { f ->
            val message = f.message.trim()
            if (message.isNotEmpty()) "${pluginLabel(f)}: $message" else "${pluginLabel(f)} (${f.kind})"
        }
        return PluginFailures(labels.size, description)
    }

    private val SESSION_UUID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    /**
     * The one command that undoes `codex archive`, or null. Only for codex (telling another
     * agent's user to run it would be worse than nothing), and only for an id shaped like a
     * codex rollout id: this is a line the user is invited to paste into a shell, so it must
     * not be able to carry anything else.
     */
    fun recoveryCommand(code: String, agent: AgentType, sessionId: String): String? =
        if (code == "session_archived" && agent == AgentType.CODEX && SESSION_UUID.matches(sessionId)) {
            "codex unarchive $sessionId"
        } else {
            null
        }
}
