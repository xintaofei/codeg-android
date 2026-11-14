package app.codeg.android.feature.sessiondetail.rendering

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GppBad
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.codeg.android.R
import app.codeg.android.core.designsystem.theme.CodegTheme

/** The tone of a step waiting on the user, and of an approval that let a call through. */
internal val ToolWarning = Color(0xFFE0A33A)

/**
 * The live connection's view of the thread, for the rows it marks ([liveToolStep]). Provided by
 * the session screen; anywhere else (a preview, a test host) there is none.
 */
val LocalActivityLive = compositionLocalOf { ActivityLive() }

/**
 * `🛡 approved once` — how the call's permission request was answered, at the end of its row
 * (web `ApprovalChip`). What the web keeps in the tooltip — the agent's own wording of the
 * option, who answered when it wasn't a person at the card — is read out to accessibility.
 */
@Composable
fun ApprovalChip(approval: ToolApproval, modifier: Modifier = Modifier) {
    val colors = CodegTheme.colors
    val rejected = approval.decision == ApprovalDecision.REJECTED
    val label = stringResource(
        when (approval.decision) {
            ApprovalDecision.ALLOW_ONCE -> R.string.approval_allow_once
            ApprovalDecision.ALLOW_ALWAYS -> R.string.approval_allow_always
            ApprovalDecision.REJECTED -> R.string.approval_rejected
        },
    )
    val who = when (approval.origin) {
        "chat_channel" -> stringResource(R.string.approval_via_chat_channel)
        "cli_policy" -> stringResource(R.string.approval_by_policy)
        else -> null
    }
    val spoken = listOfNotNull(label, approval.optionName, who).joinToString(" · ")
    Row(
        modifier.semantics { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(
            if (rejected) Icons.Rounded.GppBad else Icons.Rounded.VerifiedUser,
            contentDescription = null,
            tint = if (rejected) colors.textTertiary else ToolWarning,
            modifier = Modifier.size(12.dp),
        )
        Text(
            if (who != null) "$label · $who" else label,
            fontSize = 11.sp,
            color = colors.textSecondary,
            maxLines = 1,
        )
    }
}
