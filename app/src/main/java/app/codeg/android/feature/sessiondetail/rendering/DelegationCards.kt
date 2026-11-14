package app.codeg.android.feature.sessiondetail.rendering

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.codeg.android.R
import app.codeg.android.core.designsystem.component.AgentIcon
import app.codeg.android.core.designsystem.markdown.SingleBlockView
import app.codeg.android.core.designsystem.markdown.parseMarkdownBlocks
import app.codeg.android.core.designsystem.theme.CodegColors
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.core.model.AgentType
import app.codeg.android.feature.sessiondetail.timeline.formatElapsed

/** Opens a conversation by id — a delegated child's session. Provided by the session screen. */
val LocalOpenConversation = staticCompositionLocalOf<((Int) -> Unit)?> { null }

/** A finished step's tint, as the rail's tool markers use it. */
internal val DelegationDone = Color(0xFF85D18F)

private val Whitespace = Regex("\\s+")

/** The delegation [vm] stands for, read against the live connection and the thread's checks. */
@Composable
fun rememberDelegationModel(vm: ToolCallVM): DelegationModel? {
    val activity = LocalActivityLive.current
    val later = LocalDelegationTasks.current.byCall[vm.id]
    val binding = remember(vm, activity.delegations) {
        activity.delegations[vm.id] ?: Delegations.resumedBinding(vm, activity.delegations.values)
    }
    return remember(vm, binding, later) { Delegations.model(vm, binding, later) }
}

/** A delegation's status as a colour: quiet when done, loud when it failed or waits on the user. */
@Composable
fun delegationTint(status: DelegationModel.Status, awaiting: Boolean = false): Color {
    val colors = CodegTheme.colors
    return when {
        awaiting -> ToolWarning
        else -> when (status) {
            DelegationModel.Status.STARTING, DelegationModel.Status.RUNNING -> colors.accent
            DelegationModel.Status.WAITING -> ToolWarning
            DelegationModel.Status.OK -> DelegationDone
            DelegationModel.Status.ERR -> colors.danger
        }
    }
}

/**
 * A delegation's rail marker (web `DelegationIdentityGlyph` in a `RailIcon`): the logo of the
 * agent that took the task, ringed in its status. A resume wears a ⟳ on its ring.
 */
@Composable
fun DelegationRailGlyph(agent: AgentType?, tint: Color, size: Dp, resumed: Boolean = false) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(tint.copy(alpha = 0.14f))
                .border(1.dp, tint.copy(alpha = 0.6f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (agent != null) {
                AgentIcon(agent, size = size * 0.56f)
            } else {
                Icon(Icons.Rounded.Groups, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.5f))
            }
        }
        if (resumed) {
            val colors = CodegTheme.colors
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 3.dp, y = 2.dp)
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(colors.bg)
                    .border(0.75.dp, tint.copy(alpha = 0.6f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Replay, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(8.dp))
            }
        }
    }
}

/** Why a delegation failed, by the broker's code (web `ErrorLabel`). */
@Composable
private fun delegationErrorLabel(code: String?): String = stringResource(
    when (code) {
        "delegation_disabled" -> R.string.delegation_err_disabled
        "depth_limit" -> R.string.delegation_err_depth_limit
        "invalid_agent_type" -> R.string.delegation_err_invalid_agent_type
        "spawn_failed" -> R.string.delegation_err_spawn_failed
        "send_failed" -> R.string.delegation_err_send_failed
        "timeout" -> R.string.delegation_err_timeout
        "canceled" -> R.string.delegation_err_canceled
        "child_refusal" -> R.string.delegation_err_child_refusal
        "child_max_tokens" -> R.string.delegation_err_child_max_tokens
        "child_max_turn_requests" -> R.string.delegation_err_child_max_turn_requests
        "child_empty" -> R.string.delegation_err_child_empty
        "child_auth_required" -> R.string.delegation_err_child_auth_required
        "child_rejected" -> R.string.delegation_err_child_rejected
        "child_unknown" -> R.string.delegation_err_child_unknown
        "unknown" -> R.string.delegation_err_unknown_task
        "assistant_not_found" -> R.string.delegation_err_assistant_not_found
        "assistants_unsupported" -> R.string.delegation_err_assistants_unsupported
        "assistant_support_unknown" -> R.string.delegation_err_assistant_support_unknown
        else -> R.string.delegation_failed
    },
)

private fun took(durationMs: Long?): String? =
    durationMs?.takeIf { it > 0 }?.let { formatElapsed(it.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) }

@Composable
private fun StatusWord(text: String, color: Color) {
    Text(text, fontSize = 11.5.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1)
}

/** How a delegation stands, at the end of its line (web `DelegationStatusWord`). */
@Composable
private fun DelegationStatusWord(model: DelegationModel, awaiting: Boolean) {
    val colors = CodegTheme.colors
    if (awaiting) {
        StatusWord(stringResource(R.string.activity_needs_you), ToolWarning)
        return
    }
    val duration = took(model.durationMs)?.let { " · $it" }.orEmpty()
    when (model.status) {
        DelegationModel.Status.STARTING -> StatusWord(stringResource(R.string.delegation_status_starting), colors.textTertiary)
        DelegationModel.Status.RUNNING -> StatusWord(stringResource(R.string.delegation_running), colors.accent)
        DelegationModel.Status.WAITING -> StatusWord(stringResource(R.string.delegation_status_waiting), ToolWarning)
        DelegationModel.Status.OK -> StatusWord(stringResource(R.string.delegation_done) + duration, colors.textSecondary)
        DelegationModel.Status.ERR -> StatusWord(delegationErrorLabel(model.errorCode) + duration, colors.danger)
    }
}

/** A result's first line, as one line of plain words (web `previewLine`). */
private fun previewLine(text: String?): String? {
    val line = text?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() && !it.startsWith("```") } ?: return null
    return line.trimStart('#', '>', '-', '*', ' ').replace("**", "").replace("`", "").replace(Whitespace, " ").trim()
        .takeIf { it.isNotEmpty() }?.take(240)
}

@Composable
private fun OpenSessionLink(childConversationId: Int) {
    val open = LocalOpenConversation.current ?: return
    val colors = CodegTheme.colors
    Row(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable { open(childConversationId) }
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            stringResource(R.string.delegation_open_session),
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Medium,
            color = colors.accent,
            maxLines = 1,
        )
        Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, tint = colors.accent, modifier = Modifier.size(12.dp))
    }
}

/** A delegation card's surface, as the tool cards draw theirs. */
private fun Modifier.delegationCard(colors: CodegColors, nested: Boolean): Modifier =
    fillMaxWidth()
        .clip(RoundedCornerShape(10.dp))
        .background(if (nested) Color.Transparent else colors.bgElevated.copy(alpha = 0.5f))
        .border(0.5.dp, colors.hairline, RoundedCornerShape(10.dp))

@Composable
private fun Chevron(expanded: Boolean) {
    Icon(
        if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
        contentDescription = null,
        tint = CodegTheme.colors.textTertiary,
        modifier = Modifier.size(18.dp),
    )
}

/**
 * One delegation, start to finish (web `DelegationRow`): `Codex  #14be49e7  done · 1m 34s`, the
 * task it was handed, and — once it reports — how far it got, the first line of what it came
 * back with, and the way into its own session. Opened, the whole task, where it ran, why it was
 * resumed and the full result.
 */
@Composable
fun DelegationCard(vm: ToolCallVM, model: DelegationModel, modifier: Modifier = Modifier, nested: Boolean = false) {
    val colors = CodegTheme.colors
    val live = liveToolStep(vm, LocalActivityLive.current)
    val agent = model.agentType?.let(AgentType::fromWire)
    val name = model.assistantName ?: agent?.displayName ?: stringResource(R.string.live_runs_delegated_fallback)
    val taskLine = remember(model.task) { model.task?.replace(Whitespace, " ")?.trim()?.takeIf { it.isNotEmpty() } }
    val hasDetail = model.task != null || model.workingDir != null || model.reason != null || model.outcome != null
    var expanded by remember(vm.id) { mutableStateOf(false) }
    // Waiting on the user's approval, it shows what it would hand over.
    LaunchedEffect(live.awaiting) { if (live.awaiting && hasDetail) expanded = true }

    Column(modifier.delegationCard(colors, nested)) {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = hasDetail) { expanded = !expanded }
                .padding(start = 12.dp, end = 10.dp, top = 9.dp, bottom = 9.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        name,
                        fontSize = 13.5.sp,
                        lineHeight = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (model.assistantName != null && agent != null) {
                        Text("· ${agent.displayName}", fontSize = 12.sp, lineHeight = 16.sp, color = colors.textTertiary, maxLines = 1)
                    }
                    model.taskId?.let {
                        Text(
                            "#${it.take(8)}",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            color = colors.textTertiary,
                            maxLines = 1,
                        )
                    }
                    if (model.resumed) {
                        Text(
                            stringResource(R.string.delegation_resumed),
                            fontSize = 10.5.sp,
                            lineHeight = 13.sp,
                            color = colors.textSecondary,
                            maxLines = 1,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(colors.textTertiary.copy(alpha = 0.14f))
                                .padding(horizontal = 5.dp, vertical = 1.dp),
                        )
                    }
                }
                DelegationStatusWord(model, live.awaiting)
                if (live.showsApproval) live.approval?.let { ApprovalChip(it) }
                if (hasDetail) Chevron(expanded)
            }
            // The task, until the panel shows it whole.
            if (!expanded && taskLine != null) {
                Text(
                    taskLine,
                    fontSize = 12.5.sp,
                    lineHeight = 17.sp,
                    color = colors.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            DelegationProgressLine(model)
        }
        AnimatedVisibility(
            visible = expanded && hasDetail,
            enter = expandVertically(tween(220)) + fadeIn(tween(220)),
            exit = shrinkVertically(tween(180)) + fadeOut(tween(120)),
        ) {
            DelegationDetail(model, vm.id)
        }
    }
}

/**
 * The line under a delegation's title (web `ProgressLine`): its steps, what it is doing now —
 * or, once it ended, the first line of its result — and the way into its own session.
 */
@Composable
private fun DelegationProgressLine(model: DelegationModel) {
    val colors = CodegTheme.colors
    val steps = model.progress?.steps?.takeIf { it > 0 }
    val ended = model.status == DelegationModel.Status.OK || model.status == DelegationModel.Status.ERR
    val summary = if (ended) previewLine(model.outcome) else model.progress?.lastStep.takeIf { model.working }
    val child = model.childConversationId?.takeIf { LocalOpenConversation.current != null }
    if (steps == null && summary == null && child == null) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (steps != null) {
            Text(
                pluralStringResource(R.plurals.activity_steps, steps, steps),
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = colors.textTertiary,
                maxLines = 1,
            )
        }
        Text(
            summary.orEmpty(),
            fontSize = 12.sp,
            lineHeight = 16.sp,
            color = if (ended) colors.textSecondary else colors.accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        child?.let { OpenSessionLink(it) }
    }
}

@Composable
private fun DetailLabel(text: String) {
    Text(text, fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold, color = CodegTheme.colors.textTertiary)
}

/** The panel a delegation opens to: what was handed over, where, why again, and what came back. */
@Composable
private fun DelegationDetail(model: DelegationModel, id: String) {
    val colors = CodegTheme.colors
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(thickness = 0.5.dp, color = colors.hairline)
        Column(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            model.task?.let { task ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    DetailLabel(stringResource(R.string.delegation_task_label))
                    // A task is often a whole brief: a screenful until asked for the rest.
                    ClippedPlainText(task.trim(), "$id#task")
                }
            }
            model.workingDir?.let { dir ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    DetailLabel(stringResource(R.string.delegation_dir_label))
                    SelectionContainer {
                        Text(dir, fontFamily = FontFamily.Monospace, fontSize = 11.5.sp, lineHeight = 16.sp, color = colors.textSecondary)
                    }
                }
            }
            model.reason?.let { reason ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    DetailLabel(stringResource(R.string.delegation_resume_reason))
                    // The context passed into the child's continuation, as sent: plain text.
                    ClippedPlainText(reason, "$id#reason")
                }
            }
            model.outcome?.let { outcome ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    DetailLabel(stringResource(R.string.delegation_result_label))
                    if (model.status == DelegationModel.Status.ERR) {
                        SelectionContainer { Text(outcome.trim(), fontSize = 13.sp, lineHeight = 19.sp, color = colors.danger) }
                    } else {
                        ClippedMarkdown(outcome, id)
                    }
                }
            }
        }
    }
}

/** Show more / less, under a text cut short. */
@Composable
private fun MoreToggle(expanded: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onToggle)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(if (expanded) R.string.plan_document_show_less else R.string.plan_document_show_more),
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Medium,
            color = CodegTheme.colors.textSecondary,
        )
        Chevron(expanded)
    }
}

/** Plain text, cut to [lineLimit] lines / [charLimit] characters until asked for the rest. */
@Composable
private fun ClippedPlainText(text: String, key: String, lineLimit: Int = 10, charLimit: Int = 700) {
    val clipped = remember(text) { clipPlanSource(text, lineLimit, charLimit).trimEnd() }
    var expanded by remember(key) { mutableStateOf(false) }
    val isLong = clipped.length < text.length
    Column {
        SelectionContainer {
            Text(
                if (isLong && !expanded) "$clipped…" else text,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = CodegTheme.colors.textPrimary,
            )
        }
        if (isLong) MoreToggle(expanded, { expanded = !expanded })
    }
}

/** A result in Markdown, cut to a screenful until asked for the rest. */
@Composable
private fun ClippedMarkdown(markdown: String, id: String) {
    val colors = CodegTheme.colors
    val body = remember(markdown) { markdown.trimEnd() }
    val clipped = remember(body) { clipPlanSource(body, lineLimit = 16, charLimit = 1200) }
    var expanded by remember(id) { mutableStateOf(false) }
    val isLong = clipped.length < body.length
    val source = if (isLong && !expanded) clipped else body
    val blocks = remember(source) { parseMarkdownBlocks(source) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(0.5.dp, colors.hairline, RoundedCornerShape(8.dp)),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { for (block in blocks) SingleBlockView(block) }
            }
        }
        if (isLong) {
            HorizontalDivider(thickness = 0.5.dp, color = colors.hairline)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(vertical = 7.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(if (expanded) R.string.plan_document_show_less else R.string.plan_document_show_more),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.textSecondary,
                )
                Chevron(expanded)
            }
        }
    }
}

// region Checks on a delegated task

/** The rail status of a run of checks (web `pollsStatus`); [items] for one that names no task yet. */
fun checksStatus(rows: List<DelegationParse.TaskRow>, items: List<ToolCallVM>): DelegationModel.Status {
    val badges = rows.map { it.badge.badge }
    return when {
        // Still in flight and naming no task: its card says running, so does its marker.
        rows.isEmpty() -> if (items.any { it.state == ToolCallState.RUNNING || it.state == ToolCallState.INPUT_STREAMING }) {
            DelegationModel.Status.RUNNING
        } else {
            DelegationModel.Status.OK
        }
        DelegationParse.Badge.WAITING in badges -> DelegationModel.Status.WAITING
        DelegationParse.Badge.RUNNING in badges || DelegationParse.Badge.STARTING in badges -> DelegationModel.Status.RUNNING
        DelegationParse.Badge.ERR in badges -> DelegationModel.Status.ERR
        else -> DelegationModel.Status.OK
    }
}

/** A run of checks (or a cancel) read into one row per task. */
@Composable
fun rememberCheckRows(items: List<ToolCallVM>): List<DelegationParse.TaskRow> {
    val cancel = items.isNotEmpty() && items.all { Delegations.isCancel(it.rawName) }
    return remember(items) { DelegationParse.taskRows(items.map(DelegationParse::parseCheck), cancel) }
}

/** The glyph a run of checks wears on the rail: an hourglass, or a stop sign for a cancel. */
fun checksIcon(items: List<ToolCallVM>): ImageVector =
    if (items.isNotEmpty() && items.all { Delegations.isCancel(it.rawName) }) Icons.Rounded.Block else Icons.Rounded.HourglassTop

/**
 * The agent's checks on its delegations (web `DelegationPollsRow` / `DelegationCancelRow`): one
 * thin row per task — `Waiting for task #… result  done · 1m 34s` — however often it asked, the
 * newest answer opening under it.
 */
@Composable
fun DelegationChecksCard(items: List<ToolCallVM>, modifier: Modifier = Modifier, nested: Boolean = false) {
    val colors = CodegTheme.colors
    val cancel = items.isNotEmpty() && items.all { Delegations.isCancel(it.rawName) }
    val rows = rememberCheckRows(items)
    Column(modifier.delegationCard(colors, nested)) {
        if (rows.isEmpty()) {
            // Still in flight and naming no task yet.
            CheckRow(null, cancel, items.lastOrNull()?.id.orEmpty())
        }
        rows.forEachIndexed { i, row ->
            if (i > 0) HorizontalDivider(thickness = 0.5.dp, color = colors.hairline)
            CheckRow(row, cancel, row.key)
        }
    }
}

@Composable
private fun CheckRow(row: DelegationParse.TaskRow?, cancel: Boolean, key: String) {
    val colors = CodegTheme.colors
    val tasks = LocalDelegationTasks.current
    val report = row?.report
    val agent = (report?.agentType ?: row?.taskId?.let(tasks.agentByTask::get))?.let(AgentType::fromWire)
    val task = row?.taskId?.let { "#${it.take(8)}" }
    val title = when {
        cancel && task != null -> stringResource(R.string.delegation_cancel_task, task)
        cancel -> stringResource(R.string.delegation_cancel_task_no_task)
        task != null -> stringResource(R.string.delegation_wait_result, task)
        else -> stringResource(R.string.delegation_wait_result_no_task)
    }
    val badge = row?.badge?.badge ?: DelegationParse.Badge.RUNNING
    val text = report?.text?.trim()?.takeIf { it.isNotEmpty() }
    var expanded by remember(key) { mutableStateOf(false) }
    // While it still runs, what it was doing at the check; once ended, the first line of its result.
    val summary = when (badge) {
        DelegationParse.Badge.OK -> if (cancel) null else previewLine(text)
        DelegationParse.Badge.CHECKED, DelegationParse.Badge.WAITING -> report?.progress?.lastStep
        else -> null
    }
    Column(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = text != null) { expanded = !expanded }
                .padding(start = 12.dp, end = 10.dp, top = 9.dp, bottom = 9.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (agent != null) {
                    AgentIcon(agent, size = 15.dp)
                } else {
                    Icon(
                        if (cancel) Icons.Rounded.Block else Icons.Rounded.HourglassTop,
                        contentDescription = null,
                        tint = colors.textTertiary,
                        modifier = Modifier.size(15.dp),
                    )
                }
                Text(
                    title,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if ((row?.checks ?: 0) > 1) {
                    Text("×${row!!.checks}", fontSize = 11.sp, lineHeight = 14.sp, color = colors.textTertiary, maxLines = 1)
                }
                CheckStatusWord(badge, row?.badge?.errorCode, report?.durationMs)
                if (text != null) Chevron(expanded)
            }
            if (!expanded && summary != null) {
                Text(
                    summary,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 23.dp),
                )
            }
        }
        AnimatedVisibility(
            visible = expanded && text != null,
            enter = expandVertically(tween(220)) + fadeIn(tween(220)),
            exit = shrinkVertically(tween(180)) + fadeOut(tween(120)),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                when {
                    text == null -> Unit
                    badge == DelegationParse.Badge.OK && !cancel -> ClippedMarkdown(text, key)
                    else -> SelectionContainer {
                        Text(
                            text,
                            fontSize = 12.5.sp,
                            lineHeight = 18.sp,
                            color = if (badge == DelegationParse.Badge.ERR) colors.danger else colors.textSecondary,
                        )
                    }
                }
                report?.childConversationId?.let { OpenSessionLink(it) }
            }
        }
    }
}

/** A check's word on its task (web `StatusBadge` text). */
@Composable
private fun CheckStatusWord(badge: DelegationParse.Badge, errorCode: String?, durationMs: Long?) {
    val colors = CodegTheme.colors
    val duration = took(durationMs)?.let { " · $it" }.orEmpty()
    when (badge) {
        DelegationParse.Badge.STARTING -> StatusWord(stringResource(R.string.delegation_status_starting), colors.textTertiary)
        DelegationParse.Badge.RUNNING -> StatusWord(stringResource(R.string.delegation_running), colors.accent)
        DelegationParse.Badge.CHECKED -> StatusWord(stringResource(R.string.delegation_status_checked), colors.textTertiary)
        DelegationParse.Badge.WAITING -> StatusWord(stringResource(R.string.delegation_status_waiting), ToolWarning)
        DelegationParse.Badge.OK -> StatusWord(stringResource(R.string.delegation_done) + duration, colors.textSecondary)
        DelegationParse.Badge.ERR -> StatusWord(delegationErrorLabel(errorCode) + duration, colors.danger)
    }
}

// endregion
