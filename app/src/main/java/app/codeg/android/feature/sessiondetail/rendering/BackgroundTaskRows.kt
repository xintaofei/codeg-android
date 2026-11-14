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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.codeg.android.R
import app.codeg.android.core.designsystem.component.CodeBlock
import app.codeg.android.core.designsystem.markdown.MarkdownContent
import app.codeg.android.core.designsystem.theme.CodegTheme

/**
 * One background task (web `BackgroundTaskRowView`): `Task finished #beecec7d  Regenerate audit
 * report · report.md written  done`, its polls folded together (the latest one speaks), named
 * after the command that started it when that is in the same reply ([launch]). Opens onto the
 * task's output — a shell's as a console, a polled sub-agent's report as Markdown — and opens by
 * itself when the task failed.
 */
@Composable
fun BackgroundTaskRowCard(
    row: BackgroundTaskPolls.Row,
    launch: BackgroundTaskPolls.Launch?,
    key: String,
    modifier: Modifier = Modifier,
) {
    val colors = CodegTheme.colors
    val failed = row.badge == BackgroundTaskPolls.Badge.FAILED
    val output = remember(row) { BackgroundTaskPolls.displayOutput(row)?.trimEnd()?.takeIf { it.isNotBlank() } }
    val detail = remember(row, launch) { BackgroundTaskPolls.rowDetail(row, launch) }
    var userOpen by remember(key) { mutableStateOf<Boolean?>(null) }
    val open = output != null && (userOpen ?: failed)
    val label = stringResource(
        when (row.badge) {
            BackgroundTaskPolls.Badge.COMPLETED -> R.string.bg_task_finished
            BackgroundTaskPolls.Badge.FAILED -> R.string.bg_task_failed
            BackgroundTaskPolls.Badge.STOPPED -> R.string.bg_task_stopped
            BackgroundTaskPolls.Badge.RUNNING -> R.string.bg_task_running
        },
    )
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.bgElevated.copy(alpha = 0.5f))
            .border(0.5.dp, colors.hairline, RoundedCornerShape(10.dp)),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = output != null) { userOpen = !open }
                .padding(start = 12.dp, end = 10.dp, top = 9.dp, bottom = 9.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    label,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (failed) colors.danger else colors.textPrimary,
                    maxLines = 1,
                )
                BackgroundTaskPolls.shortId(row)?.let {
                    Text(
                        "#$it",
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        fontFamily = FontFamily.Monospace,
                        color = colors.textSecondary,
                        maxLines = 1,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                Spacer(Modifier.weight(1f))
                if (row.pollCount > 1) {
                    val polled = pluralStringResource(R.plurals.bg_task_polled, row.pollCount, row.pollCount)
                    Text(
                        "×${row.pollCount}",
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        color = colors.textTertiary,
                        maxLines = 1,
                        modifier = Modifier.semantics { contentDescription = polled },
                    )
                }
                BackgroundTaskMetric(row)
                if (output != null) {
                    Icon(
                        if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = null,
                        tint = colors.textTertiary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            if (detail != null) {
                Text(
                    detail,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        AnimatedVisibility(
            visible = open,
            enter = expandVertically(tween(220)) + fadeIn(tween(220)),
            exit = shrinkVertically(tween(180)) + fadeOut(tween(120)),
        ) {
            Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                when {
                    output == null -> Unit
                    row.isSubagent -> MarkdownContent(output)
                    else -> CodeBlock(code = output, language = "console")
                }
            }
        }
    }
}

/** How the task stands, at the end of its line: `exit 1`, `failed`, `stopped`, `running`, `done`. */
@Composable
private fun BackgroundTaskMetric(row: BackgroundTaskPolls.Row) {
    val colors = CodegTheme.colors
    val (text, color) = when (row.badge) {
        BackgroundTaskPolls.Badge.FAILED ->
            (row.exitCode?.let { stringResource(R.string.bg_task_metric_exit, it) } ?: stringResource(R.string.bg_task_metric_failed)) to colors.danger
        BackgroundTaskPolls.Badge.STOPPED -> stringResource(R.string.bg_task_metric_stopped) to colors.textTertiary
        BackgroundTaskPolls.Badge.RUNNING ->
            stringResource(R.string.bg_task_metric_running) to if (row.isInFlight) colors.accent else colors.textTertiary
        BackgroundTaskPolls.Badge.COMPLETED -> stringResource(R.string.bg_task_metric_done) to colors.textSecondary
    }
    Text(text, fontSize = 11.5.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1)
}
