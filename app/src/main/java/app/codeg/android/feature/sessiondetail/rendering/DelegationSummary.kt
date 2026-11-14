package app.codeg.android.feature.sessiondetail.rendering

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.codeg.android.R
import app.codeg.android.core.designsystem.component.AgentAvatar
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.core.model.AgentType
import app.codeg.android.core.model.DelegationBinding
import app.codeg.android.feature.sessiondetail.timeline.formatElapsed

/** Opens a conversation by id — a delegated child's session. Provided by the session screen. */
val LocalOpenConversation = staticCompositionLocalOf<((Int) -> Unit)?> { null }

/** How a delegation stands, as the end of its card's header says it. */
@Composable
fun DelegationStatusLabel(view: DelegationView) {
    val colors = CodegTheme.colors
    val (text, color) = when (view.status) {
        DelegationBinding.Status.RUNNING -> stringResource(R.string.delegation_running) to colors.accent
        DelegationBinding.Status.OK -> listOfNotNull(
            stringResource(R.string.delegation_done),
            view.durationMs?.takeIf { it > 0 }?.let { formatElapsed(it.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) },
        ).joinToString(" · ") to colors.textSecondary
        DelegationBinding.Status.ERR -> stringResource(R.string.delegation_failed) to colors.danger
    }
    Text(text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1)
}

/**
 * Who took the task, what it was, and the way into the child's own session (web
 * `DelegationRow`'s identity line and its open action).
 */
@Composable
fun DelegationSummary(view: DelegationView) {
    val colors = CodegTheme.colors
    val open = LocalOpenConversation.current
    val agent = view.agentType?.let(AgentType::fromWire)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (agent != null) AgentAvatar(agent, size = 18.dp)
            Text(
                listOfNotNull(view.assistantName, agent?.displayName).distinct().joinToString(" · ")
                    .ifEmpty { stringResource(R.string.live_runs_delegated_fallback) },
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            val child = view.childConversationId
            if (child != null && open != null) {
                TextButton(onClick = { open(child) }) {
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(14.dp))
                    Text(stringResource(R.string.delegation_open_session), fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp))
                }
            }
        }
        view.task?.let { Text(it, fontSize = 12.sp, color = colors.textSecondary, maxLines = 4, overflow = TextOverflow.Ellipsis) }
        view.errorCode?.takeIf { view.status == DelegationBinding.Status.ERR }?.let {
            Text(it, fontSize = 11.sp, color = colors.danger)
        }
    }
}
