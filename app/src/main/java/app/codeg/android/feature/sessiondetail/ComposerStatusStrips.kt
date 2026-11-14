package app.codeg.android.feature.sessiondetail

import androidx.annotation.StringRes
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.codeg.android.R
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.core.model.AcpEvent
import app.codeg.android.core.model.SessionFailureRecord
import app.codeg.android.feature.sessiondetail.rendering.ToolWarning
import kotlinx.coroutines.delay

/** How long the muted "recovered" line stays before it takes itself down. */
private const val RECOVERED_VISIBLE_MS = 10_000L

/** The localized label of an AIR failure category, the stand-in for a blank title. */
@StringRes
internal fun failureCategoryLabel(category: String): Int = when (category) {
    "connection" -> R.string.failure_category_connection
    "access" -> R.string.failure_category_access
    "limit" -> R.string.failure_category_limit
    "request" -> R.string.failure_category_request
    "service" -> R.string.failure_category_service
    else -> R.string.failure_category_unknown
}

/**
 * What is in progress beyond the reply, docked above the composer (web `ComposerStatusStrips`):
 * the retry line of an agent retrying on its own, the collapsed strip of the AIR retry incidents
 * still in flight, and — once one recovered by itself — a muted "recovered" line that takes
 * itself down. Advisories and terminal failures are news, told once as a notice instead.
 */
@Composable
fun ComposerStatusStrips(
    retry: AcpEvent.ApiRetry?,
    failures: List<SessionFailureRecord>,
    /** A turn is running: nothing can be retrying otherwise. */
    running: Boolean,
    onDismissFailures: (List<String>) -> Unit,
) {
    val retryLine = retry?.let { retryLineText(it) }
    // The incident strip gives way to the richer retry line telling the same retry.
    val incidents = SessionFailures.activeRetryIncidents(failures)
    val shownIncident = incidents.incident?.takeIf { retryLine == null && running }
    val recovered = if (incidents.incident != null || SessionFailures.latestActiveTerminal(failures) != null) {
        null
    } else {
        SessionFailures.mostRecentRecovered(failures)
    }
    if (shownIncident != null) {
        DockStrip(
            title = shownIncident.title.trim().ifEmpty { stringResource(failureCategoryLabel(shownIncident.category)) },
            details = shownIncident.details?.trim()?.takeIf { it.isNotEmpty() },
            meta = incidents.hiddenCount.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.failure_more_incidents, it, it) },
            working = true,
            onDismiss = { onDismissFailures(incidents.ids) },
        )
    }
    if (recovered != null) {
        val id = recovered.id
        val dismiss by rememberUpdatedState(onDismissFailures)
        LaunchedEffect(id, recovered.revision) {
            delay(RECOVERED_VISIBLE_MS)
            dismiss(listOf(id))
        }
        val title = recovered.title.trim().ifEmpty { stringResource(failureCategoryLabel(recovered.category)) }
        DockStrip(
            title = stringResource(R.string.failure_recovered) + " · " + title,
            details = null,
            meta = null,
            working = false,
            onDismiss = { onDismissFailures(listOf(id)) },
        )
    }
    if (retryLine != null) DockStrip(title = retryLine, details = null, meta = null, working = true, onDismiss = null)
}

/**
 * The one line an agent's retry reads as: what failed, on which attempt, and when the next
 * one goes — each part only when the agent reported it (web `useRetryLineText`).
 */
@Composable
private fun retryLineText(retry: AcpEvent.ApiRetry): String {
    val attempt = retry.attempt
    val max = retry.maxRetries
    val retrying = when {
        attempt != null && max != null -> stringResource(R.string.retry_retrying_with_max, attempt, max)
        attempt != null -> stringResource(R.string.retry_retrying_attempt, attempt)
        else -> stringResource(R.string.retry_retrying)
    }
    val delay = retry.retryDelayMs?.let { stringResource(R.string.retry_next_in, String.format(java.util.Locale.ROOT, "%.1f", it / 1000.0)) }
    val error = retry.message ?: if (retry.reportsError) stringResource(R.string.retry_fallback_error) else null
    val status = retry.errorStatus?.let { stringResource(R.string.retry_http_status, it) }.orEmpty()
    val tail = if (delay != null) stringResource(R.string.retry_with_delay, retrying, delay) else retrying
    return if (error == null && status.isEmpty()) tail else stringResource(R.string.retry_line, (error ?: "") + status, tail)
}

/** One amber (or, settled, muted) strip of the composer dock. Details, when there are any, open on tap. */
@Composable
private fun DockStrip(title: String, details: String?, meta: String?, working: Boolean, onDismiss: (() -> Unit)?) {
    val colors = CodegTheme.colors
    var open by remember(title) { mutableStateOf(false) }
    val tone = if (working) ToolWarning else colors.textTertiary
    Column(
        Modifier
            .fillMaxWidth()
            .background(if (working) ToolWarning.copy(alpha = 0.12f) else colors.bgElevated.copy(alpha = 0.92f))
            .then(if (details != null) Modifier.clickable(role = Role.Button) { open = !open } else Modifier)
            .animateContentSize()
            .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (working) {
                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = tone)
            } else {
                Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = tone, modifier = Modifier.size(14.dp))
            }
            Text(
                title,
                fontSize = 12.sp,
                color = if (working) colors.textPrimary else colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            meta?.let { Text(it, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = colors.textSecondary, maxLines = 1) }
            if (onDismiss != null) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.common_dismiss),
                    tint = colors.textTertiary,
                    modifier = Modifier
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .clickable(role = Role.Button, onClick = onDismiss)
                        .padding(8.dp)
                        .size(16.dp),
                )
            }
        }
        if (open && details != null) {
            Text(details, fontSize = 12.sp, color = colors.textSecondary, modifier = Modifier.padding(start = 20.dp, end = 10.dp, bottom = 4.dp))
        }
    }
}
