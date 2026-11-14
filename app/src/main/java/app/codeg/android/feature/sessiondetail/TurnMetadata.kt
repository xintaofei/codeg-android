package app.codeg.android.feature.sessiondetail

import app.codeg.android.core.model.MessageTurn
import app.codeg.android.core.model.TurnRole
import app.codeg.android.core.model.TurnUsage
import app.codeg.android.core.model.TurnWindowState
import app.codeg.android.core.model.isTurnErrorRecord
import java.time.Instant

// The turn-end metadata patch (codeg web 0.44 `syncTurnMetadata` / `computeTurnMetadataPatches`):
// a turn handed off locally (TurnHandoff.kt) streamed its content but not what the server
// records about it afterwards — token usage, duration, model, completion time — all of which
// the reply footer shows. These are read back from the transcript and filled in, never
// touching the content, ids or order of the local turns.

/** The fingerprint of an empty prefix: FNV-1a 64's offset basis. */
internal const val FNV_SEED_HEX = "cbf29ce484222325"

private const val FNV_PRIME = 0x100000001b3UL
private val HEX16 = Regex("^[0-9a-f]{16}$")

/**
 * Chain-extend a transcript prefix fingerprint over [turns], bit for bit the server's
 * (`commands::turn_window::prefix_fingerprint`): per turn, FNV-1a 64 over the role tag byte
 * (user 0, assistant 1, system 2), then the timestamp's epoch milliseconds as 8 little-endian
 * bytes. No ids, on purpose: the server rewrites the in-flight prompt's id.
 *
 * [seedHex] is a prior fingerprint ([FNV_SEED_HEX] for an empty prefix). Null when it isn't
 * one — a caller can then not verify, and must not guess.
 */
internal fun extendPrefixFingerprint(seedHex: String, turns: List<MessageTurn>): String? {
    if (!HEX16.matches(seedHex)) return null
    var hash = seedHex.toULong(16)
    fun extend(byte: Int) {
        hash = (hash xor (byte and 0xff).toULong()) * FNV_PRIME
    }
    for (turn in turns) {
        extend(
            when (turn.role) {
                TurnRole.USER -> 0
                TurnRole.ASSISTANT -> 1
                TurnRole.SYSTEM -> 2
            },
        )
        var millis = turn.timestamp.toEpochMilli()
        repeat(8) {
            extend((millis and 0xff).toInt())
            millis = millis ushr 8
        }
    }
    return hash.toString(16).padStart(16, '0')
}

/**
 * The fingerprint of the transcript before a batch that starts right after [turns] — the
 * loaded server turns, from [window]'s offset. Without a window there is one prefix still
 * known for certain: none at all, a session this client has just created. Otherwise (a
 * pre-0.24.0 server's whole transcript) it can't be known.
 */
internal fun batchPrefixHash(window: TurnWindowState?, turns: List<MessageTurn>): String? = when {
    window != null -> extendPrefixFingerprint(window.prefixHash, turns)
    turns.isEmpty() -> FNV_SEED_HEX
    else -> null
}

/** What the transcript recorded about one assistant turn, to fill in where a local copy lacks it. */
internal data class TurnMetadataPatch(
    val usage: TurnUsage?,
    val durationMs: Int?,
    val model: String?,
    val completedAt: Instant?,
)

/**
 * Whether [parsed] (a read of the batch) already holds its newest round: it ends on a reply,
 * or on a failure record where the local batch does too. Short of that the newest round
 * isn't written yet, and pairing would hand its local turns an earlier round's figures.
 */
internal fun parseHoldsTheLatestRound(parsed: List<MessageTurn>, local: List<MessageTurn>): Boolean {
    val last = parsed.lastOrNull() ?: return false
    if (last.role == TurnRole.ASSISTANT) return true
    return last.isTurnErrorRecord && local.lastOrNull()?.isTurnErrorRecord == true
}

/**
 * The assistant turns of each round of [turns], as positions into it. A round is what one
 * user turn set off; round 0 holds those before the first user turn (empty for a batch that
 * opens on its prompt, the whole reply for one that doesn't). Empty rounds are kept so two
 * sequences pair round by round. A system turn belongs to the round it closes.
 */
private fun assistantTurnRounds(turns: List<MessageTurn>): List<List<Int>> {
    val rounds = mutableListOf(mutableListOf<Int>())
    turns.forEachIndexed { i, turn ->
        when (turn.role) {
            TurnRole.USER -> rounds += mutableListOf<Int>()
            TurnRole.ASSISTANT -> rounds.last() += i
            TurnRole.SYSTEM -> Unit
        }
    }
    return rounds
}

private operator fun TurnUsage.plus(other: TurnUsage) = TurnUsage(
    inputTokens = inputTokens + other.inputTokens,
    outputTokens = outputTokens + other.outputTokens,
    cacheCreationInputTokens = cacheCreationInputTokens + other.cacheCreationInputTokens,
    cacheReadInputTokens = cacheReadInputTokens + other.cacheReadInputTokens,
)

/**
 * The metadata to fill into [local] (a batch's local turns), keyed by position, from
 * [parsed] — the transcript read from the same batch start, already verified to open there.
 *
 * Paired round by round from the start: the stream and the parser split a reply into
 * sub-turns by different rules, and only a user turn says where one reply ends and the next
 * begins. Within a round the parse tail-aligns onto the local turns, and a leading surplus
 * (the parser split finer) is summed into the round's first local turn, so the round adds up
 * to the reply's parsed total — what the footer, which merges a reply's turns, shows after a
 * reopen. A deficit head-aligns and leaves the rest for a later read. Rounds the parse holds
 * beyond the local ones aren't this batch's yet; rounds it lacks stay unpatched.
 */
internal fun turnMetadataPatches(local: List<MessageTurn>, parsed: List<MessageTurn>): Map<Int, TurnMetadataPatch> {
    if (!parseHoldsTheLatestRound(parsed, local)) return emptyMap()
    val localRounds = assistantTurnRounds(local)
    val parsedRounds = assistantTurnRounds(parsed).map { round -> round.map { parsed[it] } }
    val out = mutableMapOf<Int, TurnMetadataPatch>()
    for (r in 0 until minOf(localRounds.size, parsedRounds.size)) {
        val localRound = localRounds[r]
        val parsedRound = parsedRounds[r]
        val offset = parsedRound.size - localRound.size
        for (i in localRound.indices) {
            val parsedIdx = maxOf(offset, 0) + i
            if (parsedIdx >= parsedRound.size) break
            val matched = parsedRound[parsedIdx]
            var usage = matched.usage
            var duration = matched.durationMs
            var model = matched.model
            // The rolled-in sub-turns all precede the matched one, so its completion time is
            // already the latest; only the totals and a missing model take from them.
            if (i == 0) {
                for (j in 0 until offset) {
                    val extra = parsedRound[j]
                    extra.usage?.let { usage = usage?.plus(it) ?: it }
                    extra.durationMs?.let { duration = (duration ?: 0) + it }
                    if (model.isNullOrEmpty() && !extra.model.isNullOrEmpty()) model = extra.model
                }
            }
            out[localRound[i]] = TurnMetadataPatch(usage, duration, model, matched.completedAt)
        }
    }
    return out
}

/** Fill [patches] into [local]: first write wins per field, and nothing else changes. */
internal fun applyMetadataPatches(local: List<MessageTurn>, patches: Map<Int, TurnMetadataPatch>): List<MessageTurn> {
    if (patches.isEmpty()) return local
    return local.mapIndexed { i, turn ->
        val p = patches[i] ?: return@mapIndexed turn
        turn.copy(
            usage = turn.usage ?: p.usage,
            durationMs = turn.durationMs ?: p.durationMs,
            model = turn.model ?: p.model,
            completedAt = turn.completedAt ?: p.completedAt,
        )
    }
}

/** What a metadata read leaves to do next. */
internal enum class MetadataSyncStep { RETRY, STOP }

/**
 * Whether another read is worth making, judged by the newest local reply after this one's
 * patches. Still missing everything: retry — unless the read can never verify (it opened at
 * the batch start yet its fingerprint differs, i.e. the prefix was rewritten); one opening
 * short of the batch start is merely behind and worth another look. Missing only usage (an
 * agent that reports none): one more look ([usageOnlyRetries] counts those). Otherwise done.
 */
internal fun metadataSyncStep(
    local: List<MessageTurn>,
    verified: Boolean,
    readOffset: Int,
    batchStart: Int,
    usageOnlyRetries: Int,
): MetadataSyncStep {
    val newest = local.lastOrNull { it.role == TurnRole.ASSISTANT } ?: return MetadataSyncStep.STOP
    return when {
        newest.usage == null && newest.completedAt == null ->
            if (!verified && readOffset >= batchStart) MetadataSyncStep.STOP else MetadataSyncStep.RETRY
        newest.usage == null -> if (usageOnlyRetries < 1) MetadataSyncStep.RETRY else MetadataSyncStep.STOP
        else -> MetadataSyncStep.STOP
    }
}
