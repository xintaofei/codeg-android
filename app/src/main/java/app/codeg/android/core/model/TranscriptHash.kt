package app.codeg.android.core.model

/** Matches Rust's id-free FNV-1a over role and little-endian timestamp millis. */
internal fun extendTranscriptHash(seed: String, turns: List<MessageTurn>): String? {
    var hash = seed.toULongOrNull(16) ?: return null
    fun append(byte: ULong) { hash = (hash xor byte) * 0x100000001b3UL }
    for (turn in turns) {
        append(when (turn.role) {
            TurnRole.USER -> 0UL
            TurnRole.ASSISTANT -> 1UL
            TurnRole.SYSTEM -> 2UL
        })
        val millis = turn.timestamp.toEpochMilli()
        repeat(8) { shift -> append(((millis ushr (shift * 8)) and 255L).toULong()) }
    }
    return hash.toString(16).padStart(16, '0')
}
