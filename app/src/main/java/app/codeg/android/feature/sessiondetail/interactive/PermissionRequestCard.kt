package app.codeg.android.feature.sessiondetail.interactive

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Assignment
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.codeg.android.R
import app.codeg.android.core.designsystem.component.CodeBlock
import app.codeg.android.core.designsystem.diff.DiffView
import app.codeg.android.core.designsystem.markdown.MarkdownContent
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.core.model.PermissionOption

/** A tool-permission approval card shown above the composer. Port of iOS `PermissionRequestCard`. */
@Composable
fun PermissionRequestCard(
    parsed: ParsedPermission,
    options: List<PermissionOption>,
    onRespond: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** Requests waiting behind this one. One card shows at a time, so without the count the
     *  rest read as the agent having stopped. */
    queued: Int = 0,
) {
    val colors = CodegTheme.colors
    var submitting by remember(parsed.jsonPreview) { mutableStateOf<String?>(null) }
    val accent = colors.accent
    // What each option would actually grant. Empty for every agent that ships no
    // `_meta.permission` — i.e. everything but codex ≥1.1.8 and claude ≥0.64.1 —
    // and, within one request, for the options the agent chose not to annotate.
    val grants = remember(options) {
        options.mapNotNull { option ->
            parsePermissionOptionChanges(option.meta).takeIf { it.isNotEmpty() }?.let { option to it }
        }
    }

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.bgElevated.copy(alpha = 0.96f))
            .border(0.5.dp, colors.surfaceStroke, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Header.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                if (parsed.isPlan) Icons.AutoMirrored.Rounded.Assignment else Icons.Rounded.GppMaybe,
                contentDescription = null,
                tint = if (parsed.isPlan) accent else colors.danger,
                modifier = Modifier.size(20.dp),
            )
            Column(Modifier.weight(1f)) {
                // A sandbox's network request is only a host, so it gets the CLI's own framing.
                val heading = if (parsed.networkHost != null) {
                    stringResource(R.string.perm_network_request)
                } else {
                    (parsed.description ?: parsed.title).ifBlank { stringResource(R.string.perm_request_title) }
                }
                Text(heading, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                // The agent's own reason, when it gave one, says more than the boilerplate.
                Text(
                    when {
                        parsed.reason != null -> parsed.reason
                        parsed.networkHost != null -> stringResource(R.string.perm_network_question)
                        parsed.isPlan -> stringResource(R.string.perm_plan_subtitle)
                        else -> stringResource(R.string.perm_permission_subtitle)
                    },
                    fontSize = 12.sp,
                    color = colors.textSecondary,
                )
            }
            if (queued > 0) {
                Text(
                    pluralStringResource(R.plurals.perm_queued_count, queued, queued),
                    fontSize = 10.sp,
                    color = colors.textSecondary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(colors.textPrimary.copy(alpha = 0.1f))
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }
            if (!parsed.isPlan) {
                Text(
                    if (parsed.networkHost != null) stringResource(R.string.perm_kind_network) else parsed.kind,
                    fontSize = 10.sp,
                    color = colors.textTertiary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(colors.textPrimary.copy(alpha = 0.06f))
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }
        }

        // Body.
        val scrolls = permissionBodyScrolls(parsed, grants.isNotEmpty())
        Column(
            modifier = if (scrolls) Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()) else Modifier,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PermissionBody(parsed)
            // Last body section: directly above the buttons it describes, and a
            // pathological list scrolls with the rest instead of pushing them
            // off screen.
            if (grants.isNotEmpty()) OptionGrants(grants)
        }

        // Option buttons. A `defaultToNo` ask hands the emphasis to the decline, so the one
        // filled button isn't an approve a stray tap lands on.
        for (option in options) {
            OptionButton(
                option = option,
                emphasized = if (parsed.defaultToNo) option.isReject else !option.isReject,
                submitting = submitting == option.optionId,
                enabled = submitting == null,
                onClick = {
                    submitting = option.optionId
                    onRespond(option.optionId)
                },
            )
        }
    }
}

@Composable
private fun PermissionBody(parsed: ParsedPermission) {
    val colors = CodegTheme.colors
    if (!parsed.hasStructuredBody) {
        CodeBlock(code = parsed.jsonPreview, language = "json")
        return
    }
    parsed.networkHost?.let { host ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Rounded.Public, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(14.dp))
            Text(stringResource(R.string.perm_network_host), fontSize = 12.sp, color = colors.textSecondary)
            Text(host, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = colors.textPrimary, modifier = Modifier.weight(1f))
        }
    }
    parsed.command?.let { cmd ->
        CodeBlock(code = cmd, language = "bash")
        parsed.cwd?.let { Text("cwd: $it", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = colors.textTertiary) }
    }
    if (parsed.diffFiles.isNotEmpty()) DiffView(parsed.diffFiles)
    parsed.planMarkdown?.let { MarkdownContent(it) }
    if (parsed.planEntries.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            for (entry in parsed.planEntries) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("•", color = colors.textTertiary, fontSize = 13.sp)
                    Text(entry.text, fontSize = 13.sp, color = colors.textPrimary, modifier = Modifier.weight(1f))
                }
            }
        }
    }
    if (parsed.allowedPrompts.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (ap in parsed.allowedPrompts) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
                    if (ap.tool.isNotEmpty()) {
                        Text(
                            ap.tool,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = colors.accent,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(colors.accent.copy(alpha = 0.12f))
                                .padding(horizontal = 5.dp, vertical = 1.dp),
                        )
                    }
                    Text(ap.prompt, fontSize = 12.sp, color = colors.textSecondary, modifier = Modifier.weight(1f))
                }
            }
        }
    }
    parsed.modeTarget?.let { Text("→ $it", fontSize = 12.sp, color = colors.textSecondary) }
    if (parsed.url != null || parsed.query != null || (parsed.prompt != null && parsed.command == null)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            parsed.url?.let { Text(it, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = colors.textSecondary) }
            parsed.query?.let { Text(it, fontSize = 12.sp, color = colors.textSecondary) }
            if (parsed.command == null) parsed.prompt?.let { Text(it, fontSize = 12.sp, color = colors.textSecondary) }
        }
    }
}

/**
 * Whether the card body needs its own bounded scroll region. The card floats
 * above the composer with nothing else capping its height, so anything that can
 * grow without bound has to be listed here or it pushes the option buttons off
 * screen — leaving the user unable to answer, and the turn stuck.
 *
 * Grants count regardless of how many there are: each description is allowed up
 * to 200 characters, so even a single one can be many lines tall. Bounding a
 * body that already fits costs nothing — `heightIn` only caps.
 */
internal fun permissionBodyScrolls(parsed: ParsedPermission, hasGrants: Boolean): Boolean =
    hasGrants || parsed.diffFiles.isNotEmpty() || parsed.planMarkdown != null ||
        parsed.planEntries.size > 4 || parsed.allowedPrompts.size > 4

/**
 * What each option would grant, and for how long. Only some options carry a list
 * (claude annotates the always-allow one alone), so every group names its button
 * rather than leaving the user to match sentences to a row of buttons.
 */
@Composable
private fun OptionGrants(grants: List<Pair<PermissionOption, List<PermissionOptionChange>>>) {
    val colors = CodegTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.textPrimary.copy(alpha = 0.04f))
            .padding(9.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(
                Icons.Rounded.VpnKey,
                contentDescription = null,
                tint = colors.textTertiary,
                modifier = Modifier.size(13.dp),
            )
            Text(stringResource(R.string.perm_option_grants), fontSize = 11.sp, color = colors.textSecondary)
        }
        for ((option, changes) in grants) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(option.name, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = colors.textPrimary)
                for (change in changes) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
                        // Duration first, so it reads as a scannable column.
                        change.scope?.let { ScopeBadge(it) }
                        Text(
                            change.description,
                            fontSize = 12.sp,
                            color = colors.textSecondary,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

/**
 * How long one change lasts. Neutral for every scope, like the desktop dialog:
 * the label already spells out the persistence ("This session" vs "Saved to
 * project settings"), and tinting the durable ones would put a warning colour on
 * routine, reversible settings writes.
 */
@Composable
private fun ScopeBadge(scope: PermissionChangeScope) {
    val colors = CodegTheme.colors
    Text(
        stringResource(scope.labelRes),
        fontSize = 10.sp,
        color = colors.textTertiary,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(colors.textPrimary.copy(alpha = 0.06f))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

@Composable
private fun OptionButton(option: PermissionOption, emphasized: Boolean, submitting: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = CodegTheme.colors
    val shape = RoundedCornerShape(10.dp)
    // Keep the actively-submitting button enabled-looking (spinner) while the
    // others disable; clicking is gated by `enabled` regardless.
    val clickable = enabled
    if (!emphasized) {
        OutlinedButton(
            onClick = onClick,
            enabled = clickable,
            modifier = Modifier.fillMaxWidth(),
            shape = shape,
            border = BorderStroke(0.5.dp, colors.surfaceStroke),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.textPrimary),
        ) {
            if (submitting) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = colors.textPrimary)
            } else {
                Text(option.name, fontWeight = FontWeight.SemiBold)
            }
        }
    } else {
        Button(
            onClick = onClick,
            enabled = clickable,
            modifier = Modifier.fillMaxWidth(),
            shape = shape,
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.accent,
                contentColor = colors.onAccent,
                disabledContainerColor = colors.accent.copy(alpha = 0.4f),
                disabledContentColor = colors.onAccent.copy(alpha = 0.8f),
            ),
        ) {
            if (submitting) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = colors.onAccent)
            } else {
                Text(option.name, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
