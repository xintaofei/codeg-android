package app.codeg.android.feature.sessiondetail

import app.codeg.android.core.model.ConversationDetail
import app.codeg.android.core.model.ConversationTurnsPage
import app.codeg.android.core.model.MessageTurn
import app.codeg.android.core.model.TurnRole

/** Coordinates describe persisted records only; optimistic/live turns never change them. */
data class TranscriptWindow(val offset: Int, val total: Int, val prefixHash: String) {
    fun countIncludingLocal(loadedCount: Int): Int = maxOf(total, offset + loadedCount)

    fun acceptsRefresh(next: TranscriptWindow?): Boolean =
        next != null && next.offset == offset && next.prefixHash == prefixHash

    fun prepend(page: ConversationTurnsPage, current: List<MessageTurn>): TranscriptWindow? {
        if (page.turnsOffset !in 0 until offset || page.turnsTotal < total ||
            page.turns.size != offset - page.turnsOffset ||
            page.prefixHashBeforeIndex != prefixHash ||
            extendTranscriptHash(page.prefixHash, page.turns) != prefixHash
        ) return null
        val ids = current.mapTo(HashSet()) { it.id }
        if (page.turns.any { !ids.add(it.id) }) return null
        return copy(offset = page.turnsOffset, prefixHash = page.prefixHash)
    }

    companion object {
        fun from(detail: ConversationDetail): TranscriptWindow? {
            val offset = detail.turnsOffset ?: return null
            val total = detail.turnsTotal ?: return null
            val hash = detail.prefixHash ?: return null
            if (offset < 0 || total < offset || total - offset != detail.turns.size ||
                !hash.matches(Regex("[0-9a-f]{16}"))
            ) return null
            return TranscriptWindow(offset, total, hash)
        }
    }
}

/** Matches Rust's id-free FNV-1a over role and little-endian timestamp millis. */
internal fun extendTranscriptHash(seed: String, turns: List<MessageTurn>): String? =
    app.codeg.android.core.model.extendTranscriptHash(seed, turns)
