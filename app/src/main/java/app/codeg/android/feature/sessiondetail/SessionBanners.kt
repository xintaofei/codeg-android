package app.codeg.android.feature.sessiondetail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.codeg.android.R
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.core.model.RemoteControlInfo
import app.codeg.android.feature.sessiondetail.rendering.ToolWarning
import kotlinx.coroutines.delay

/**
 * "Restart to apply" (web `SessionConfigStaleBanner`): the agent's settings changed after it was
 * spawned, so this session still runs on the old ones. Reconnecting ends the agent process —
 * never mid-turn — and the next message resumes the conversation on the new config.
 */
@Composable
fun ConfigStaleBanner(
    stale: ConfigStaleUi,
    turnRunning: Boolean,
    onReconnect: () -> Unit,
    onDismiss: () -> Unit,
    remoteControlOn: Boolean = false,
) {
    val colors = CodegTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .background(ToolWarning.copy(alpha = 0.12f))
            .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = ToolWarning, modifier = Modifier.padding(top = 2.dp).size(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(if (stale.kind == "model_provider") R.string.config_stale_provider_title else R.string.config_stale_agent_title),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                )
                Text(stringResource(R.string.config_stale_description), fontSize = 12.sp, color = colors.textSecondary)
                // Reconnecting ends the agent process, and Remote Control runs in it.
                if (remoteControlOn) Text(stringResource(R.string.config_stale_ends_remote_control), fontSize = 12.sp, color = ToolWarning)
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.common_dismiss), tint = colors.textTertiary, modifier = Modifier.size(16.dp))
            }
        }
        BannerAction(Icons.Rounded.Refresh, stringResource(R.string.config_stale_reconnect), ToolWarning, enabled = !turnRunning, onClick = onReconnect)
    }
}

/**
 * The session couldn't be loaded (web `acpLoadErrorBanner`), docked where the composer sits —
 * a send would only fail the same way. The transcript stays readable above it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SessionLoadErrorBanner(error: SessionLoadErrorUi, onReload: () -> Unit, onNewSession: (() -> Unit)?) {
    val colors = CodegTheme.colors
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1500); copied = false } }
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.danger.copy(alpha = 0.1f))
            // In the composer's place, so above the gesture bar like it.
            .navigationBarsPadding()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = colors.danger, modifier = Modifier.padding(top = 1.dp).size(16.dp))
            Text(error.message, fontSize = 12.sp, color = colors.textPrimary, modifier = Modifier.weight(1f))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // Outside the other two's conditions: copying a string needs no conversation.
            error.command?.let { command ->
                BannerAction(
                    if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                    stringResource(if (copied) R.string.session_load_command_copied else R.string.session_load_copy_command),
                    colors.danger,
                ) {
                    clipboard.setText(AnnotatedString(command))
                    copied = true
                }
            }
            BannerAction(Icons.Rounded.Refresh, stringResource(R.string.session_load_reload), colors.danger, onClick = onReload)
            onNewSession?.let { BannerAction(Icons.Rounded.Add, stringResource(R.string.session_load_new), colors.danger, onClick = it) }
        }
    }
}

/**
 * Claude Code's Remote Control is on (web `RemoteControlStrip`): this session can be continued
 * from claude.ai/code or the Claude app, and the agent keeps running for it after this screen is
 * gone. The link opens or copies; turning off is Claude Code's own command, so it waits for a
 * running turn. Not kept in the transcript, so a reload that finds it on shows only this.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RemoteControlStrip(info: RemoteControlInfo, onTurnOff: (() -> Unit)?, turnRunning: Boolean) {
    val colors = CodegTheme.colors
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1500); copied = false } }
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.accent.copy(alpha = 0.1f))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.Smartphone, contentDescription = null, tint = colors.accent, modifier = Modifier.size(16.dp))
            Text(stringResource(R.string.remote_control_title), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
            val url = info.sessionUrl
            if (url != null) {
                Text(
                    url,
                    fontSize = 12.sp,
                    color = colors.accent,
                    textDecoration = TextDecoration.Underline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .clickable(onClickLabel = stringResource(R.string.remote_control_open)) { runCatching { uriHandler.openUri(url) } },
                )
            } else {
                Text(
                    stringResource(R.string.remote_control_no_url),
                    fontSize = 12.sp,
                    color = colors.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            info.sessionUrl?.let { url ->
                BannerAction(
                    if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                    stringResource(if (copied) R.string.remote_control_copied else R.string.remote_control_copy),
                    colors.accent,
                ) {
                    clipboard.setText(AnnotatedString(url))
                    copied = true
                }
            }
            if (onTurnOff != null) {
                BannerAction(
                    Icons.Rounded.PowerSettingsNew,
                    stringResource(if (turnRunning) R.string.remote_control_turn_off_busy else R.string.remote_control_turn_off),
                    colors.accent,
                    enabled = !turnRunning,
                    onClick = onTurnOff,
                )
            }
        }
    }
}

@Composable
private fun BannerAction(icon: ImageVector, label: String, tone: Color, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 0.dp),
        modifier = Modifier.padding(0.dp),
        border = androidx.compose.foundation.BorderStroke(0.5.dp, tone.copy(alpha = if (enabled) 0.5f else 0.2f)),
    ) {
        Icon(icon, contentDescription = null, tint = tone, modifier = Modifier.size(14.dp))
        Text(label, fontSize = 12.sp, color = tone, modifier = Modifier.padding(start = 4.dp))
    }
}
