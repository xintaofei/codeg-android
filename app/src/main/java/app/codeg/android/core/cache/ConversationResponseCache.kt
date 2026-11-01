package app.codeg.android.core.cache

import app.codeg.android.core.model.ConversationDetail
import app.codeg.android.core.model.MessageTurn
import app.codeg.android.core.model.TurnRole
import app.codeg.android.core.model.extendTranscriptHash
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Retain the original wire blocks: ContentBlock intentionally has no encoder. */
internal fun transcriptCachePages(text: String, detail: ConversationDetail): Map<String, String> {
    val original = Json.parseToJsonElement(text).jsonObject
    val turns = original["turns"] as? JsonArray ?: JsonArray(emptyList())
    val offset = detail.turnsOffset
    val total = detail.turnsTotal
    val seed = detail.prefixHash
    if (offset == null || total == null || seed == null) return mapOf("detail" to text)
    require(offset >= 0 && total == offset + detail.turns.size && turns.size == detail.turns.size)
    require(seed.matches(Regex("[0-9a-f]{16}")))

    val ranges = mutableListOf<IntRange>()
    var end = turns.size
    while (end > 0) {
        val start = roundStart(detail.turns, end)
        ranges += start until end
        end = start
    }
    if (ranges.isEmpty()) return mapOf("detail" to text)
    val hashes = mutableMapOf(0 to seed)
    var previous = 0
    var hash: String = seed
    for (range in ranges.asReversed()) {
        val endIndex = range.last + 1
        hash = requireNotNull(extendTranscriptHash(hash, detail.turns.subList(previous, endIndex)))
        hashes[endIndex] = hash
        previous = endIndex
    }
    val pages = linkedMapOf<String, String>()
    for ((index, range) in ranges.withIndex()) {
        val start = range.first
        val endIndex = range.last + 1
        val body = if (index == 0) original.toMutableMap() else mutableMapOf()
        body["turns"] = JsonArray(turns.subList(start, endIndex))
        body["turns_offset"] = JsonPrimitive(offset + start)
        body["turns_total"] = JsonPrimitive(total)
        body["prefix_hash"] = JsonPrimitive(hashes.getValue(start))
        if (index > 0) body["prefix_hash_before_index"] = JsonPrimitive(hashes.getValue(endIndex))
        pages[if (index == 0) "detail" else "before-${offset + endIndex}"] = JsonObject(body).toString()
    }
    return pages
}

private fun roundStart(turns: List<MessageTurn>, end: Int): Int {
    var rounds = 0
    var start = end
    while (start > 0 && end - start < 500) {
        start--
        if (turns[start].role == TurnRole.USER && ++rounds == 5) break
    }
    return start
}
