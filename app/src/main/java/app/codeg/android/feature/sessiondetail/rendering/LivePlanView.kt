package app.codeg.android.feature.sessiondetail.rendering

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.codeg.android.R
import app.codeg.android.core.designsystem.markdown.MarkdownContent
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.core.model.PlanEntry

private val SuccessGreen = androidx.compose.ui.graphics.Color(0xFF85D18F)

/** The agent's live plan/TODO checklist (display-only). Port of iOS `LivePlanView`. */
@Composable
fun LivePlanView(entries: List<PlanEntry>, modifier: Modifier = Modifier) {
    if (entries.isEmpty()) return
    val colors = CodegTheme.colors
    val done = entries.count { it.normalizedStatus == PlanEntry.Status.COMPLETED }

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.textPrimary.copy(alpha = 0.03f))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.Checklist, null, tint = colors.textSecondary, modifier = Modifier.size(15.dp))
            Text(stringResource(R.string.liveplan_title), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colors.textSecondary, modifier = Modifier.weight(1f))
            Text("$done/${entries.size}", fontSize = 11.sp, color = colors.textTertiary)
        }
        for (entry in entries) PlanRow(entry)
    }
}

@Composable
private fun PlanRow(entry: PlanEntry) {
    val colors = CodegTheme.colors
    val completed = entry.normalizedStatus == PlanEntry.Status.COMPLETED
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        when (entry.normalizedStatus) {
            PlanEntry.Status.COMPLETED ->
                Icon(Icons.Rounded.CheckCircle, null, tint = SuccessGreen, modifier = Modifier.size(15.dp))
            PlanEntry.Status.IN_PROGRESS ->
                CircularProgressIndicator(modifier = Modifier.size(13.dp), strokeWidth = 2.dp, color = colors.accent)
            PlanEntry.Status.PENDING ->
                Icon(Icons.Rounded.RadioButtonUnchecked, null, tint = colors.textTertiary, modifier = Modifier.size(15.dp))
        }
        Text(
            text = entry.content,
            fontSize = 13.sp,
            color = if (completed) colors.textTertiary else colors.textPrimary,
            textDecoration = if (completed) TextDecoration.LineThrough else null,
            modifier = Modifier.weight(1f),
        )
        if (entry.normalizedPriority == PlanEntry.Priority.HIGH) {
            Text(
                stringResource(R.string.liveplan_priority_high),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = colors.danger,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(colors.danger.copy(alpha = 0.14f))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
        Box(Modifier.size(0.dp))
    }
}

/**
 * codex's Plan-mode plan document (`<proposed_plan>`), as a card of its own rather than raw
 * text with visible tags (web `ProposedPlanPart`). Free-form markdown, unlike [LivePlanView]'s
 * checklist; while it is still being written and has nothing in it yet, it says so.
 */
@Composable
fun ProposedPlanCard(markdown: String, streaming: Boolean, modifier: Modifier = Modifier) {
    val colors = CodegTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.textPrimary.copy(alpha = 0.03f))
            .border(0.5.dp, colors.hairline, RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.Checklist, null, tint = colors.accent, modifier = Modifier.size(15.dp))
            Text(stringResource(R.string.proposed_plan_title), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colors.textSecondary)
        }
        if (markdown.isNotBlank()) {
            MarkdownContent(markdown)
        } else if (streaming) {
            Text(stringResource(R.string.proposed_plan_planning), fontSize = 12.sp, color = colors.textTertiary)
        }
    }
}
