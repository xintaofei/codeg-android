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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.codeg.android.R
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.feature.sessiondetail.timeline.LocalDisclosures

/** The session's goal controls. The default offers none (a preview, a read-only host). */
val LocalGoalControl = staticCompositionLocalOf { GoalControl(onControl = null, actions = emptyList()) }

/**
 * A goal call outside any run on the rail (web `GoalToolCallPart`): the goal row of [GoalFrame],
 * with no steps of its own.
 */
@Composable
fun GoalCard(vm: ToolCallVM, modifier: Modifier = Modifier) {
    // Working while the call itself is.
    GoalFrame(start = vm, end = null, running = GoalRuns.isRunningCall(vm), stepCount = 0, modifier = modifier, steps = null)
}

/**
 * A goal on the rail (web `GoalCard`): `Goal: <objective>` with what it has used and how it stands,
 * opening to its objective, its budget, why it failed, and — while it is active or paused, on a
 * live connection whose adapter offers them — Pause and Clear. A Codex `/goal` run ([start] …
 * [end]) opens further onto its own [steps], drawn on a rail of their own, ending on a foot that
 * folds the row back.
 *
 * Whether it is open is DERIVED until the reader taps it: a running run opens as soon as it holds
 * anything (its steps arrive after its header does), it folds again once it settles (the answer
 * has been lifted out of it by then), and a failed goal opens to say why.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GoalFrame(
    start: ToolCallVM,
    end: ToolCallVM?,
    running: Boolean,
    stepCount: Int,
    modifier: Modifier = Modifier,
    /** Its row's id: with a [LocalDisclosures] store above, the reader's choice lives there (and
     *  "show the failure" can open the row onto a failed step). */
    disclosureKey: String? = null,
    steps: (@Composable () -> Unit)?,
) {
    val colors = CodegTheme.colors
    val goal = remember(start, end) { Goals.parseRun(start, end) }
    val errorText = remember(start, end) { Goals.errorText(start, end) }
    val control = LocalGoalControl.current
    val failed = start.state == ToolCallState.ERROR || start.isError ||
        end?.let { it.state == ToolCallState.ERROR || it.isError } == true || goal.failed
    val store = LocalDisclosures.current?.takeIf { disclosureKey != null }
    var ownOpen by remember(start.id) { mutableStateOf<Boolean?>(null) }
    val userOpen = if (store != null) store[disclosureKey] else ownOpen
    fun setUserOpen(value: Boolean) {
        if (store != null) store[disclosureKey!!] = value else ownOpen = value
    }
    val autoOpens = failed || (running && steps != null)
    val open = userOpen ?: autoOpens
    val title = stringResource(R.string.goal_title)
    // A full-width colon (the Chinese title) takes no space after it.
    val header = goal.objective?.let { if (title.endsWith("：")) "$title$it" else "$title $it" } ?: title
    val status = goal.normalizedStatus
    val statusLabel = when (status) {
        "active" -> stringResource(R.string.goal_status_active)
        "paused" -> stringResource(R.string.goal_status_paused)
        "blocked" -> stringResource(R.string.goal_status_blocked)
        "usage_limited" -> stringResource(R.string.goal_status_usage_limited)
        "budget_limited" -> stringResource(R.string.goal_status_budget_limited)
        "limited" -> stringResource(R.string.goal_status_limited)
        "complete", "completed" -> stringResource(R.string.goal_status_complete)
        else -> goal.status
    }
    val tokensWord = stringResource(R.string.goal_tokens)
    // Only the thread's latest goal call may act on the session's goal (see [GoalControl]).
    val onControl = control.onControl?.takeIf { control.currentCallId != null && (control.currentCallId == start.id || control.currentCallId == end?.id) }
    val canPause = onControl != null && "pause" in control.actions && status == "active"
    val canClear = onControl != null && "clear" in control.actions && (status == "active" || status == "paused")
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.bgElevated.copy(alpha = 0.5f))
            .border(0.5.dp, colors.hairline, RoundedCornerShape(10.dp)),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { setUserOpen(!open) }.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Rounded.Flag, null, tint = if (failed) colors.danger else colors.accent, modifier = Modifier.size(15.dp))
            Text(
                header,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = if (failed) colors.danger else colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            goal.tokensUsed?.let { Text("${Goals.tokens(it)} $tokensWord", fontSize = 11.sp, color = colors.textTertiary, maxLines = 1) }
            goal.timeUsedSeconds?.let { Text(Goals.duration(it), fontSize = 11.sp, color = colors.textTertiary, maxLines = 1) }
            // Working, failed, or where the goal stands.
            when {
                running -> CircularProgressIndicator(strokeWidth = 1.5.dp, color = colors.accent, modifier = Modifier.size(12.dp))
                failed && errorText != null -> Text(stringResource(R.string.goal_failed), fontSize = 11.sp, color = colors.danger, maxLines = 1)
                statusLabel != null -> Text(
                    statusLabel,
                    fontSize = 11.sp,
                    color = if (goal.failed) colors.danger else colors.textTertiary,
                    maxLines = 1,
                )
            }
            Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = colors.textTertiary, modifier = Modifier.size(18.dp))
        }
        AnimatedVisibility(
            visible = open,
            enter = expandVertically(tween(220)) + fadeIn(tween(220)),
            exit = shrinkVertically(tween(180)) + fadeOut(tween(120)),
        ) {
            Column(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                goal.objective?.let {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(R.string.goal_objective), fontSize = 11.sp, color = colors.textTertiary)
                        Text(it, fontSize = 13.sp, color = colors.textPrimary)
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    statusLabel?.let { Figure(stringResource(R.string.goal_status_label), it) }
                    goal.tokensUsed?.let { Figure(stringResource(R.string.goal_tokens_used), "${Goals.tokens(it)} $tokensWord") }
                    goal.tokenBudget?.let { Figure(stringResource(R.string.goal_budget), "${Goals.tokens(it)} $tokensWord") }
                    goal.remainingTokens?.let { Figure(stringResource(R.string.goal_remaining), "${Goals.tokens(it)} $tokensWord") }
                    goal.timeUsedSeconds?.let { Figure(stringResource(R.string.goal_elapsed), Goals.duration(it)) }
                }
                errorText?.let {
                    SelectionContainer {
                        Text(
                            it,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            fontFamily = FontFamily.Monospace,
                            color = colors.danger,
                            maxLines = 16,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(colors.danger.copy(alpha = 0.1f))
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                        )
                    }
                }
                if (canPause || canClear) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (canPause) OutlinedButton(onClick = { onControl?.invoke("pause") }) { Text(stringResource(R.string.goal_pause), fontSize = 13.sp) }
                        if (canClear) OutlinedButton(onClick = { onControl?.invoke("clear") }) { Text(stringResource(R.string.goal_clear), fontSize = 13.sp) }
                    }
                }
                if (steps != null) {
                    steps()
                    // A settled run ends its steps on the control that folds the row.
                    if (!running) GoalFoot(stepCount) { setUserOpen(false) }
                }
            }
        }
    }
}

/** `Collapse · 3 steps`: folds a settled goal run's row back. */
@Composable
private fun GoalFoot(stepCount: Int, onFold: () -> Unit) {
    val colors = CodegTheme.colors
    val res = LocalContext.current.resources
    val what = listOfNotNull(
        stringResource(R.string.activity_collapse),
        stepCount.takeIf { it > 0 }?.let { res.getQuantityString(R.plurals.activity_steps, it, it) },
    ).joinToString(stringResource(R.string.activity_separator))
    Row(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(role = Role.Button, onClick = onFold)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(Icons.Rounded.ExpandLess, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(16.dp))
        Text(what, fontSize = 12.sp, color = colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Figure(label: String, value: String) {
    val colors = CodegTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("$label:", fontSize = 12.sp, color = colors.textTertiary)
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = colors.textPrimary)
    }
}
