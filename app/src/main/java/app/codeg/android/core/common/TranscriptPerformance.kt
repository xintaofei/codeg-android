package app.codeg.android.core.common

import app.codeg.android.BuildConfig
import java.util.logging.Logger

/** Debug-only timings; never logs message text, server URLs or credentials. */
internal fun logTranscriptTiming(stage: String, startedNanos: Long, records: Int? = null, payload: String? = null) {
    if (!BuildConfig.DEBUG) return
    val elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000
    val bytes = payload?.toByteArray(Charsets.UTF_8)?.size
    Logger.getLogger("CodegTranscript").info("$stage elapsedMs=$elapsedMs records=$records payloadBytes=$bytes")
}
