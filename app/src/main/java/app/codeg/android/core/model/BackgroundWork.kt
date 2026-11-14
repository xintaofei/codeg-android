package app.codeg.android.core.model

import kotlinx.serialization.Serializable

/** What an async task has cost so far, as the adapter last reported it (all three, or none). */
@Serializable
data class AsyncTaskUsage(val totalTokens: Long = 0, val toolUses: Long = 0, val durationMs: Long = 0)

/**
 * One JetBrains AIR async task (Rust `AsyncTaskRecord`): an agent's non-agent background work —
 * claude-agent-acp's background shells, workflows and monitors (and, from 0.97.0, a turn
 * Claude Code started on its own, `task_type` `turn`), codex-acp's background terminals, Grok's
 * workflows. The merged row, not a wire frame: [AsyncTaskDelta]s revise it.
 */
@Serializable
data class AsyncTaskRecord(
    val taskId: String,
    val name: String = "Background task",
    /** Already friendly: `shell`, `workflow`, `monitor`, `turn`, `task`, or whatever is new. */
    val taskType: String = "task",
    val description: String = "",
    val showInTranscript: Boolean = true,
    val canStop: Boolean = false,
    /** `running`, `paused`, or one of the terminal `completed` / `failed` / `stopped`. */
    val state: String = "running",
    val summary: String? = null,
    val lastToolName: String? = null,
    val usage: AsyncTaskUsage? = null,
    val outputFilePath: String? = null,
    val toolCallId: String? = null,
    val phase: String? = null,
    val currentAgent: String? = null,
)

/**
 * A revision of an async task (Rust `AsyncTaskDelta`): [spawned] announces it with its whole
 * identity; every other delta carries only what changed. A delta for a task never announced
 * changes nothing.
 */
@Serializable
data class AsyncTaskDelta(
    val taskId: String,
    val spawned: Boolean = false,
    val name: String? = null,
    val taskType: String? = null,
    val description: String? = null,
    val showInTranscript: Boolean? = null,
    val canStop: Boolean? = null,
    val state: String? = null,
    val summary: String? = null,
    val lastToolName: String? = null,
    val usage: AsyncTaskUsage? = null,
    val outputFilePath: String? = null,
    val toolCallId: String? = null,
    val phase: String? = null,
    val currentAgent: String? = null,
)

/**
 * A background task launched and not yet resolved, as the server's transcript watcher accounts
 * it (Rust `BackgroundOutstandingTask`): a sub-agent ([kind] `agent`) or a background shell
 * (`shell`, which the async-task channel lists with a stop instead).
 */
@Serializable
data class BackgroundOutstandingTask(
    val taskId: String,
    val kind: String = "agent",
    /** The launching tool call, when the watcher knows it. */
    val toolUseId: String? = null,
    val description: String? = null,
    val startedAtMs: Long = 0,
)

/**
 * A background task a `<task-notification>` settled (Rust `BackgroundSettledInfo`). [interim]:
 * the task said it will notify again, so this isn't its end.
 */
@Serializable
data class BackgroundSettledInfo(
    val taskId: String,
    val status: String = "completed",
    val summary: String? = null,
    val toolUseId: String? = null,
    val result: String? = null,
    val interim: Boolean = false,
)
