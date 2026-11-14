package app.codeg.android.feature.sessiondetail.rendering

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Context-compaction tool-call detection (port of the web `lib/context-compaction.ts`).
 *
 * A compaction lifecycle arrives as an ACP `tool_call` whose `_meta.contextCompaction` takes
 * one of two shapes, both accepted long-term:
 * - the boolean marker `true` — codex-acp 1.1.3–1.2.x and codeg's Grok bridge, which stamps
 *   top-level `tokensBefore` / `tokensAfter` counts next to it;
 * - the versioned object `{version: 1, …}` — codex-acp 1.3.0+ (bare `{version: 1}`),
 *   deepseek-acp, and claude-agent-acp 0.75.0+, whose schema reserves optional `trigger`,
 *   `preTokens`, `postTokens`, `durationMs` and `error` fields.
 *
 * Recognized by that meta key, NOT by tool name or agent, so an adapter adopting the
 * convention lights the divider up with no wiring. It renders as a chrome-less divider
 * ("context was compacted here"), not as a tool card, and never folds into a tool group.
 */
object ContextCompaction {

    /**
     * The `_meta` key with which the server claims a compaction call's output IS its retained
     * summary (`COMPACTION_SUMMARY_META_KEY`, stamped by the readers of the ACP compaction
     * lifecycle and by the history parsers that recover it from the transcript).
     */
    const val SUMMARY_META_KEY = "codeg.compactionSummary"

    /** Whether this call's `meta` marks it as a compaction. A JSON string `"true"` doesn't count. */
    fun matches(meta: JsonObject?): Boolean = when (val marker = meta?.get("contextCompaction")) {
        is JsonPrimitive -> !marker.isString && marker.content == "true"
        is JsonObject -> (marker["version"] as? JsonPrimitive)
            ?.takeIf { !it.isString }?.content?.toDoubleOrNull()
            ?.let { it >= 1 && it == Math.floor(it) && it.isFinite() } == true
        else -> false
    }

    /** The versioned marker's payload, or null for the boolean shape and non-compactions. */
    private fun payload(meta: JsonObject?): JsonObject? =
        if (matches(meta)) meta?.get("contextCompaction") as? JsonObject else null

    /**
     * The token counts either side of the boundary: Grok's top-level `tokensBefore` /
     * `tokensAfter`, else the versioned payload's `preTokens` / `postTokens`. Often absent
     * (codex sends none) — the divider then falls back to a plain label.
     */
    fun tokens(meta: JsonObject?): Pair<Int?, Int?> {
        val payload = payload(meta)
        return (count(meta, "tokensBefore") ?: count(payload, "preTokens")) to
            (count(meta, "tokensAfter") ?: count(payload, "postTokens"))
    }

    /** How long the compaction took, when the payload says. */
    fun durationMs(meta: JsonObject?): Int? = count(payload(meta), "durationMs")

    /** The adapter's own account of a compaction that failed. */
    fun error(meta: JsonObject?): String? =
        (payload(meta)?.get("error") as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * The retained summary a compaction call carries, or null. Only on the server's explicit
     * claim ([SUMMARY_META_KEY]): the legacy call puts other things on the same channel.
     */
    fun summary(meta: JsonObject?, output: String?): String? {
        if (!matches(meta)) return null
        val claimed = (meta?.get(SUMMARY_META_KEY) as? JsonPrimitive)?.let { !it.isString && it.content == "true" } == true
        return output?.takeIf { claimed && it.isNotBlank() }
    }

    /**
     * Identity of a compaction EVENT, or null when the payload can't name one: the token
     * counts either side plus a duration in milliseconds. All three are required — codex
     * sends a bare `{version: 1}` for every compaction, and a looser key would fold separate
     * compactions into one. Web `compactionEventKey`.
     */
    fun eventKey(meta: JsonObject?): String? {
        val payload = payload(meta) ?: return null
        val nums = listOf("preTokens", "postTokens", "durationMs").map { count(payload, it) ?: return null }
        return "compaction:${nums.joinToString(":")}"
    }

    /** A non-negative, finite integer field off the opaque meta pass-through. */
    private fun count(meta: JsonObject?, key: String): Int? {
        val value = (meta?.get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()
        return value?.takeIf { it.isFinite() && it >= 0 }?.toInt()
    }

    /** A compaction call → its divider part. */
    fun part(callId: String?, meta: JsonObject?, running: Boolean, failed: Boolean, output: String?): RenderPart.Compaction {
        val (before, after) = tokens(meta)
        return RenderPart.Compaction(
            before = before,
            after = after,
            running = running,
            failed = failed || error(meta) != null,
            durationMs = durationMs(meta),
            summary = summary(meta, output),
            callId = callId,
            eventKey = eventKey(meta),
        )
    }
}
