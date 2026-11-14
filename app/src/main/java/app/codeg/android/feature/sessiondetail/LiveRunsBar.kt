package app.codeg.android.feature.sessiondetail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Timelapse
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.codeg.android.R
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.core.model.AgentType
import app.codeg.android.core.model.AsyncTaskRecord
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * "3 running" above the composer (web `LiveRunsChip`): what the connection has going beyond the
 * reply — background tasks, sub-agents, delegations. A turn can end with work still running, so
 * the chip outlives the turn. Tapping it lists the work, with a stop for a task that offers one.
 */
@Composable
fun LiveRunsBar(
    runs: LiveRuns,
    agentName: String,
    onStopTask: ((String, (Boolean) -> Unit) -> Unit)?,
    onOpenConversation: ((Int) -> Unit)? = null,
) {
    if (runs.count == 0) return
    val colors = CodegTheme.colors
    var open by remember { mutableStateOf(false) }
    val res = LocalContext.current.resources
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.bgElevated.copy(alpha = 0.92f))
            .padding(horizontal = 12.dp, vertical = 2.dp),
    ) {
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .clickable { open = true }
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(Icons.Rounded.Timelapse, contentDescription = null, tint = colors.accent, modifier = Modifier.size(14.dp))
            Text(res.getQuantityString(R.plurals.live_runs_count, runs.count, runs.count), fontSize = 12.sp, color = colors.textSecondary)
        }
    }
    if (open) LiveRunsSheet(runs, agentName, onStopTask, onOpenConversation, onDismiss = { open = false })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LiveRunsSheet(
    runs: LiveRuns,
    agentName: String,
    onStopTask: ((String, (Boolean) -> Unit) -> Unit)?,
    onOpenConversation: ((Int) -> Unit)?,
    onDismiss: () -> Unit,
) {
    val colors = CodegTheme.colors
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = colors.bgElevated) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 12.dp),
        ) {
            if (runs.tasks.isNotEmpty()) {
                RunGroupTitle(stringResource(R.string.async_tasks_title), runs.tasks.size)
                runs.tasks.forEach { AsyncTaskRow(it, agentName, onStopTask) }
            }
            if (runs.subagents.isNotEmpty()) {
                RunGroupTitle(stringResource(R.string.live_runs_subagents), runs.subagents.size)
                runs.subagents.forEach { run ->
                    RunRow(
                        icon = Icons.Rounded.Groups,
                        name = run.title ?: run.description ?: stringResource(R.string.live_runs_subagent_fallback),
                        meta = listOfNotNull(
                            if (run.background) stringResource(R.string.live_runs_background) else null,
                            run.description?.takeIf { run.title != null },
                        ).joinToString(" · "),
                    )
                }
            }
            if (runs.delegations.isNotEmpty()) {
                RunGroupTitle(stringResource(R.string.live_runs_delegations), runs.delegations.size)
                runs.delegations.forEach { run ->
                    val binding = run.binding
                    val agent = binding?.agentType?.takeIf { it.isNotEmpty() }?.let(AgentType::fromWire)
                    val child = binding?.childConversationId
                    RunRow(
                        icon = Icons.Rounded.SmartToy,
                        name = listOfNotNull(binding?.assistantName, agent?.displayName).distinct().joinToString(" · ")
                            .ifEmpty { stringResource(R.string.live_runs_delegated_fallback) },
                        meta = binding?.task.orEmpty(),
                        trailing = if (child != null && onOpenConversation != null) {
                            {
                                TextButton(onClick = { onDismiss(); onOpenConversation(child) }) {
                                    Text(stringResource(R.string.delegation_open_session), fontSize = 13.sp)
                                }
                            }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun RunGroupTitle(title: String, count: Int) {
    val colors = CodegTheme.colors
    Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = colors.textSecondary)
        Text("$count", fontSize = 12.sp, color = colors.textTertiary)
    }
}

@Composable
private fun RunRow(icon: ImageVector, name: String, meta: String, trailing: (@Composable () -> Unit)? = null, dim: Boolean = false) {
    val colors = CodegTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, contentDescription = null, tint = if (dim) colors.textTertiary else colors.accent, modifier = Modifier.size(18.dp))
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (meta.isNotEmpty()) Text(meta, fontSize = 12.sp, color = colors.textTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        trailing?.invoke()
    }
}

/** One background task (web `AsyncTaskRow`): what it is, how far it got, and its stop. */
@Composable
private fun AsyncTaskRow(task: AsyncTaskRecord, agentName: String, onStopTask: ((String, (Boolean) -> Unit) -> Unit)?) {
    val colors = CodegTheme.colors
    var stopping by remember(task.taskId) { mutableStateOf(false) }
    val paused = task.state == "paused"
    val isTurn = task.taskType == AsyncTasks.AUTONOMOUS_TURN
    val icon = when {
        paused -> Icons.Rounded.PauseCircle
        task.taskType == "shell" -> Icons.Rounded.Terminal
        task.taskType == "workflow" -> Icons.Rounded.AccountTree
        task.taskType == "monitor" -> Icons.Rounded.MonitorHeart
        isTurn -> Icons.Rounded.SmartToy
        else -> Icons.Rounded.Timelapse
    }
    val cause = if (isTurn) {
        when (val c = AsyncTasks.turnCause(task.description)) {
            AsyncTasks.TurnCause.Unknown -> null
            is AsyncTasks.TurnCause.ScheduledWakeup -> c.at?.let {
                stringResource(
                    R.string.async_task_scheduled_wakeup_at,
                    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(it.atZone(ZoneId.systemDefault())),
                )
            } ?: stringResource(R.string.async_task_scheduled_wakeup)
            AsyncTasks.TurnCause.ScheduledTask -> stringResource(R.string.async_task_scheduled_task)
            is AsyncTasks.TurnCause.Text -> c.text
        }
    } else {
        null
    }
    val meta = if (isTurn) {
        listOfNotNull(cause)
    } else {
        listOfNotNull(
            task.phase,
            task.currentAgent,
            task.lastToolName,
            task.usage?.let { stringResource(R.string.async_task_tokens, compactCount(it.totalTokens)) },
        ).ifEmpty { listOfNotNull(task.description.takeIf { it.isNotBlank() && it != task.name }) }
    }
    RunRow(
        icon = icon,
        name = if (isTurn) stringResource(R.string.async_task_agent_working, agentName) else task.name,
        meta = meta.joinToString(" · "),
        dim = paused,
        trailing = if (task.canStop && onStopTask != null) {
            {
                TextButton(
                    onClick = {
                        stopping = true
                        onStopTask(task.taskId) { stopping = false }
                    },
                    enabled = !stopping,
                ) {
                    if (stopping) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = colors.textSecondary)
                    } else {
                        Icon(Icons.Rounded.Stop, contentDescription = null, tint = colors.danger, modifier = Modifier.size(16.dp))
                    }
                    Text(stringResource(R.string.async_task_stop), fontSize = 13.sp, color = colors.danger, modifier = Modifier.padding(start = 4.dp))
                }
            }
        } else {
            null
        },
    )
}

/** `12.3k`, `4.5M` — a token count as the run list shows it. */
internal fun compactCount(n: Long): String = when {
    n >= 1_000_000 -> String.format(java.util.Locale.ROOT, "%.1fM", n / 1_000_000.0)
    n >= 1_000 -> String.format(java.util.Locale.ROOT, "%.1fk", n / 1_000.0)
    else -> n.toString()
}
