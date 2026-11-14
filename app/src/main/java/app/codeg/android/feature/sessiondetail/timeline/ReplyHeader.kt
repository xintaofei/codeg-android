package app.codeg.android.feature.sessiondetail.timeline

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.codeg.android.R
import app.codeg.android.core.designsystem.theme.CodegTheme

/** What a reply's header and foot ask of the screen holding the thread's fold state. */
interface ReplyFoldActions {
    /** Fold or unfold reply [replyId]'s steps. */
    fun setOpen(replyId: String, open: Boolean)

    /** Fold reply [replyId]'s steps from their foot, and bring its header back into view. */
    fun foldBack(replyId: String)

    /** Open reply [replyId] at row [rowId] — its first unrecovered failure. */
    fun showFailure(replyId: String, rowId: String)
}

/** Provided by the screen; the default does nothing (a preview, a test host). */
val LocalReplyFoldActions = staticCompositionLocalOf<ReplyFoldActions> {
    object : ReplyFoldActions {
        override fun setOpen(replyId: String, open: Boolean) = Unit
        override fun foldBack(replyId: String) = Unit
        override fun showFailure(replyId: String, rowId: String) = Unit
    }
}

/**
 * Open/closed choices of the thread's disclosures (a tool group's), by row id, held above the
 * list so a row scrolled away and back keeps its choice, and so "show the failure" can open
 * the group the failure is folded into. Web `disclosure-store.ts`. Null: each row keeps its own.
 */
val LocalDisclosures = staticCompositionLocalOf<MutableMap<String, Boolean>?> { null }

/**
 * The reply's header (web `ActivityHeader`): a capsule only as wide as what it says —
 * `Activity · 14 steps · 1 failed · 3m 34s ⌄`. The count comes first, then how the turn
 * ended, then the time it took; the chevron ends it. While the reply is written it reads
 * `Working · 6 steps`, pulsing; a reply with no steps keeps a `Reply · 3s` header so the row
 * never blinks in and out as a turn settles.
 *
 * The whole capsule folds and unfolds the reply when it can fold. An unrecovered failure is
 * red and opens the reply at that step.
 */
@Composable
fun ReplyHeaderBody(header: NodeContent.ReplyHeader, modifier: Modifier = Modifier) {
    val colors = CodegTheme.colors
    val actions = LocalReplyFoldActions.current
    val res = LocalContext.current.resources
    val separator = stringResource(R.string.activity_separator)
    val summary = header.summary
    val label = when {
        !header.completed -> stringResource(R.string.activity_working)
        header.hasSteps -> stringResource(R.string.activity_label)
        else -> stringResource(R.string.activity_reply)
    }
    val steps = if (summary.steps > 0) res.getQuantityString(R.plurals.activity_steps, summary.steps, summary.steps) else null
    val failure = when {
        summary.failed == 0 -> null
        summary.unrecovered > 0 -> res.getQuantityString(R.plurals.activity_failed, summary.unrecovered, summary.unrecovered)
        else -> res.getQuantityString(R.plurals.activity_failed_recovered, summary.failed, summary.failed)
    }
    val danger = summary.unrecovered > 0
    val interrupted = if (summary.interrupted > 0) stringResource(R.string.activity_interrupted, summary.interrupted) else null
    val elapsed = header.durationMs?.takeIf { header.completed && it > 0 }?.let(::formatElapsed)
    val toggleLabel = stringResource(if (header.open) R.string.activity_collapse else R.string.activity_expand)

    // The label pulses while the reply is written, as the web's shimmer does.
    val pulse = if (header.completed) {
        1f
    } else {
        val transition = rememberInfiniteTransition(label = "reply-working")
        val a by transition.animateFloat(0.45f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "reply-working-alpha")
        a
    }
    val chevronTurn by animateFloatAsState(if (header.open) 180f else 0f, tween(180), label = "reply-chevron")
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .heightIn(min = 32.dp)
            .clip(shape)
            .background(colors.bgElevated.copy(alpha = 0.7f))
            .border(0.5.dp, colors.hairline, shape)
            .then(
                if (header.foldable) {
                    Modifier
                        .clickable(role = Role.Button) { actions.setOpen(header.replyId, !header.open) }
                        .semantics { contentDescription = toggleLabel }
                } else {
                    Modifier
                },
            )
            .padding(start = 14.dp, end = if (header.foldable) 8.dp else 14.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (header.completed) colors.textPrimary else colors.accent,
            maxLines = 1,
            modifier = Modifier.alpha(pulse),
        )
        val pieces = listOfNotNull(steps)
        if (pieces.isNotEmpty()) {
            Text(
                separator + pieces.joinToString(separator),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        if (failure != null) {
            Text(separator, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = colors.textSecondary, maxLines = 1)
            val rowId = header.failureRowId
            Text(
                failure,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (danger) FontWeight.SemiBold else FontWeight.Normal,
                color = if (danger) colors.danger else colors.textSecondary,
                maxLines = 1,
                modifier = if (danger && rowId != null) {
                    Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable(role = Role.Button) { actions.showFailure(header.replyId, rowId) }
                        .semantics { contentDescription = res.getString(R.string.activity_show_failure) }
                } else {
                    Modifier
                },
            )
        }
        if (interrupted != null) {
            Text(separator + interrupted, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = colors.textSecondary, maxLines = 1)
        }
        if (elapsed != null) {
            Text(separator + elapsed, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = colors.textSecondary, maxLines = 1)
        }
        if (header.foldable) {
            Icon(
                Icons.Rounded.ExpandMore,
                contentDescription = null,
                tint = colors.textTertiary,
                modifier = Modifier.padding(start = 4.dp).size(18.dp).rotate(chevronTurn),
            )
        }
    }
}

/**
 * The foot of an open reply's steps (web `TimelineFoot`): `Collapse · 14 steps · 3m 34s`,
 * folding them back — so a long reply read to its end doesn't have to be scrolled back up
 * to its header to be put away.
 */
@Composable
fun ReplyFootBody(foot: NodeContent.ReplyFoot, modifier: Modifier = Modifier) {
    val colors = CodegTheme.colors
    val actions = LocalReplyFoldActions.current
    val res = LocalContext.current.resources
    val separator = stringResource(R.string.activity_separator)
    val what = listOfNotNull(
        stringResource(R.string.activity_collapse),
        foot.steps.takeIf { it > 0 }?.let { res.getQuantityString(R.plurals.activity_steps, it, it) },
        foot.durationMs?.takeIf { it > 0 }?.let(::formatElapsed),
    ).joinToString(separator)
    Row(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(role = Role.Button) { actions.foldBack(foot.replyId) }
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(Icons.Rounded.ExpandLess, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(16.dp))
        Text(what, fontSize = 12.sp, color = colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The time a reply took, as the web's `formatElapsedLabel` gives it: `12s`, `3m 4s`,
 * `1h 2m 3s`, each unit floored (under a second reads `0s`).
 */
internal fun formatElapsed(ms: Int): String {
    val total = maxOf(0, ms)
    val hours = total / 3_600_000
    val minutes = (total % 3_600_000) / 60_000
    val seconds = (total % 60_000) / 1_000
    return when {
        hours > 0 -> "${hours}h ${minutes}m ${seconds}s"
        minutes > 0 -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}
