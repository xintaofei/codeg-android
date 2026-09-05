package app.codeg.android.feature.settings

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.codeg.android.R
import app.codeg.android.core.designsystem.component.AgentAvatar
import app.codeg.android.core.designsystem.component.CodegTextField
import app.codeg.android.core.designsystem.component.CopyButton
import app.codeg.android.core.designsystem.component.LoadingView
import app.codeg.android.core.designsystem.component.PrimaryButton
import app.codeg.android.core.designsystem.component.SecretField
import app.codeg.android.core.designsystem.theme.CodegColors
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.core.model.AgentConfig
import app.codeg.android.core.model.AgentType
import app.codeg.android.core.model.ClaudeAuthMode
import app.codeg.android.core.model.CodeBuddyEnvironment
import app.codeg.android.core.model.ClaudeEffortLevel
import app.codeg.android.core.model.CodexAuthMode
import app.codeg.android.core.model.CodexReasoningEffort
import app.codeg.android.core.model.CursorAuthMethod
import app.codeg.android.core.model.CursorAuthStatus
import app.codeg.android.core.model.CursorConfig
import app.codeg.android.core.model.CursorModelInfo
import app.codeg.android.core.model.GeminiAuthMode
import app.codeg.android.core.model.HermesProviderKind
import app.codeg.android.core.model.ModelProviderInfo
import app.codeg.android.core.model.clineProviders
import app.codeg.android.core.model.codexReasoningEffortOptions
import app.codeg.android.core.model.hermesProvider
import app.codeg.android.core.model.hermesProviders

/**
 * Per-agent configuration editor — a native Material 3 port of the iOS
 * `AgentDetailView`. Rendered inside the Settings content area (the shared top app
 * bar handles the title + back); a sticky bottom bar holds the Save action.
 */
@Composable
fun AgentDetailContent(
    agentType: AgentType,
    onClose: () -> Unit,
    viewModel: AgentsViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val detail = ui.detail

    // (Re)build the draft whenever the target agent changes.
    androidx.compose.runtime.LaunchedEffect(agentType) { viewModel.openDetail(agentType) }

    if (detail == null || detail.agentType != agentType) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingView(stringResource(R.string.common_loading)) }
        return
    }

    // Kimi & Pi drive their own dedicated backends and carry their own save button(s)
    // — like Hermes' raw editor, they hide the host "Save" and the generic
    // native-config editor.
    val selfContained = agentType == AgentType.KIMI_CODE || agentType == AgentType.PI
    Column(Modifier.fillMaxSize().imePadding().navigationBarsPadding()) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            AgentHeader(detail, onToggle = { viewModel.setEnabled(detail.agent, it) })

            when (agentType) {
                AgentType.CLAUDE_CODE -> ClaudeSection(detail, viewModel)
                AgentType.CODEX -> CodexSection(detail, viewModel)
                AgentType.GEMINI -> GeminiSection(detail, viewModel)
                AgentType.OPEN_CLAW -> OpenClawSection(detail, viewModel)
                AgentType.CLINE -> ClineSection(detail, viewModel)
                AgentType.OPEN_CODE -> OpenCodeSection(detail, viewModel)
                AgentType.HERMES -> HermesSection(detail, viewModel)
                AgentType.CODE_BUDDY -> CodeBuddySection(detail, viewModel)
                AgentType.GROK -> GrokSection(detail, viewModel)
                AgentType.CURSOR -> CursorSection(detail, viewModel)
                AgentType.KIMI_CODE -> AgentConfigKimiView(detail.agent, viewModel)
                AgentType.PI -> AgentConfigPiView(detail.agent, viewModel)
            }

            if (agentType != AgentType.HERMES && !selfContained) AdvancedSection(detail, viewModel)
        }

        if (!selfContained) {
            // Surface WHY Save is disabled for the one gate a user can't otherwise
            // guess: API-key mode with nothing typed would persist a credential-less
            // auth mode and silently fall back to the browser login.
            val gateError = stringResource(R.string.agents_cursor_api_key_required)
                .takeIf { detail.missingCursorApiKey }
            SaveBar(
                canSave = detail.canSave,
                saving = detail.saving,
                error = detail.saveError ?: gateError,
                onSave = { viewModel.save(onClose) },
            )
        }
    }
}

// region Header + Save bar

@Composable
private fun AgentHeader(state: AgentDetailState, onToggle: (Boolean) -> Unit) {
    val colors = CodegTheme.colors
    val agent = state.agent
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.bgElevated.copy(alpha = 0.5f)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            AgentAvatar(agent.agentType, size = 48.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    agent.name.ifEmpty { agent.agentType.displayName },
                    fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                VersionLine(state)
            }
            Switch(
                checked = agent.enabled,
                onCheckedChange = onToggle,
                enabled = agent.available,
                colors = SwitchDefaults.colors(checkedTrackColor = colors.accent, checkedThumbColor = colors.onAccent),
            )
        }
        if (agent.description.isNotEmpty()) {
            Text(agent.description, fontSize = 13.sp, color = colors.textTertiary)
        }
        if (!agent.available) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Rounded.Warning, contentDescription = null, tint = colors.danger, modifier = Modifier.size(16.dp))
                Text(stringResource(R.string.agents_detail_unavailable), fontSize = 12.sp, color = colors.danger)
            }
        }
        agent.configFilePath?.takeIf { it.isNotBlank() }?.let { path ->
            Text(path, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun VersionLine(state: AgentDetailState) {
    val colors = CodegTheme.colors
    val installed = state.agent.installedVersion
    val latest = state.agent.registryVersion
    val text = when {
        installed.isNullOrBlank() -> stringResource(R.string.agents_detail_not_installed)
        latest != null && latest.isNotBlank() && latest != installed ->
            stringResource(R.string.agents_detail_version_latest, installed, latest)
        else -> "v$installed"
    }
    Text(text, fontSize = 12.sp, color = colors.textTertiary)
}

@Composable
private fun SaveBar(canSave: Boolean, saving: Boolean, error: String?, onSave: () -> Unit) {
    val colors = CodegTheme.colors
    Surface(color = colors.bg) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HorizontalDivider(color = colors.hairline)
            error?.let { Text(it, fontSize = 12.sp, color = colors.danger) }
            PrimaryButton(text = stringResource(R.string.common_save), onClick = onSave, enabled = canSave, loading = saving)
        }
    }
}

// endregion

// region Per-agent sections

@Composable
private fun ClaudeSection(state: AgentDetailState, vm: AgentsViewModel) {
    val d = state.draft
    FormSection(stringResource(R.string.agents_section_configuration), footer = stringResource(claudeFooterRes(d.claudeAuthMode))) {
        SelectField(
            label = stringResource(R.string.agents_field_auth_mode),
            value = d.claudeAuthMode,
            options = listOf(
                ClaudeAuthMode.OFFICIAL_SUBSCRIPTION to stringResource(R.string.agents_claude_auth_official),
                ClaudeAuthMode.CUSTOM to stringResource(R.string.agents_auth_custom_endpoint),
                ClaudeAuthMode.MODEL_PROVIDER to stringResource(R.string.agents_field_model_provider),
            ),
            onSelect = vm::setClaudeAuthMode,
        )
        when (d.claudeAuthMode) {
            ClaudeAuthMode.CUSTOM -> {
                CodegTextField(d.apiBaseUrl, { v -> vm.editDraft { it.copy(apiBaseUrl = v) } }, stringResource(R.string.agents_field_api_url), placeholder = "https://…", keyboardType = KeyboardType.Uri, mono = true)
                SecretField(d.apiKey, { v -> vm.editDraft { it.copy(apiKey = v) } }, stringResource(R.string.agents_field_api_key))
            }
            ClaudeAuthMode.MODEL_PROVIDER -> ProviderPicker(state, vm)
            ClaudeAuthMode.OFFICIAL_SUBSCRIPTION -> Unit
        }
        if (d.claudeAuthMode != ClaudeAuthMode.MODEL_PROVIDER) {
            CodegTextField(d.claudeMainModel, { v -> vm.editDraft { it.copy(claudeMainModel = v) } }, stringResource(R.string.agents_field_main_model), mono = true)
            CodegTextField(d.claudeReasoningModel, { v -> vm.editDraft { it.copy(claudeReasoningModel = v) } }, stringResource(R.string.agents_field_reasoning_model), mono = true)
            ExpandableGroup(stringResource(R.string.agents_claude_default_models)) {
                CodegTextField(d.claudeDefaultHaikuModel, { v -> vm.editDraft { it.copy(claudeDefaultHaikuModel = v) } }, stringResource(R.string.agents_claude_haiku_model), mono = true)
                CodegTextField(d.claudeDefaultSonnetModel, { v -> vm.editDraft { it.copy(claudeDefaultSonnetModel = v) } }, stringResource(R.string.agents_claude_sonnet_model), mono = true)
                CodegTextField(d.claudeDefaultOpusModel, { v -> vm.editDraft { it.copy(claudeDefaultOpusModel = v) } }, stringResource(R.string.agents_claude_opus_model), mono = true)
            }
        }
        SelectField(
            label = stringResource(R.string.agents_field_reasoning_effort),
            value = d.claudeEffortLevel,
            options = listOf(
                ClaudeEffortLevel.DEFAULT to stringResource(R.string.agents_effort_default),
                ClaudeEffortLevel.LOW to stringResource(R.string.agents_effort_low),
                ClaudeEffortLevel.MEDIUM to stringResource(R.string.agents_effort_medium),
                ClaudeEffortLevel.HIGH to stringResource(R.string.agents_effort_high),
                ClaudeEffortLevel.XHIGH to stringResource(R.string.agents_effort_xhigh),
            ),
            onSelect = { level -> vm.editDraft { it.copy(claudeEffortLevel = level) } },
        )
    }
}

@StringRes
private fun claudeFooterRes(mode: ClaudeAuthMode): Int = when (mode) {
    ClaudeAuthMode.OFFICIAL_SUBSCRIPTION -> R.string.agents_claude_footer_official
    ClaudeAuthMode.CUSTOM -> R.string.agents_claude_footer_custom
    ClaudeAuthMode.MODEL_PROVIDER -> R.string.agents_footer_model_provider
}

@Composable
private fun CodexSection(state: AgentDetailState, vm: AgentsViewModel) {
    val colors = CodegTheme.colors
    val context = LocalContext.current
    val d = state.draft
    FormSection(stringResource(R.string.agents_section_configuration)) {
        if (d.codexAuthMode == CodexAuthMode.CHATGPT_SUBSCRIPTION) {
            Text(stringResource(R.string.agents_codex_chatgpt_signed_in), fontSize = 13.sp, color = colors.textSecondary)
            TonalAction(stringResource(R.string.agents_codex_switch_to_api_key)) { vm.setCodexAuthMode(CodexAuthMode.API_KEY) }
        } else {
            SelectField(
                label = stringResource(R.string.agents_field_auth_mode),
                value = d.codexAuthMode,
                options = listOf(
                    CodexAuthMode.API_KEY to stringResource(R.string.agents_auth_custom_endpoint),
                    CodexAuthMode.MODEL_PROVIDER to stringResource(R.string.agents_field_model_provider),
                ),
                onSelect = vm::setCodexAuthMode,
            )
            when (d.codexAuthMode) {
                CodexAuthMode.API_KEY -> {
                    CodegTextField(d.apiBaseUrl, { v -> vm.editDraft { it.copy(apiBaseUrl = v) } }, stringResource(R.string.agents_field_api_url), placeholder = "https://…", keyboardType = KeyboardType.Uri, mono = true)
                    SecretField(d.apiKey, { v -> vm.editDraft { it.copy(apiKey = v) } }, stringResource(R.string.agents_field_api_key))
                    CodegTextField(d.model, { v -> vm.editDraft { it.copy(model = v) } }, stringResource(R.string.agents_field_model), mono = true)
                }
                CodexAuthMode.MODEL_PROVIDER -> ProviderPicker(state, vm)
                CodexAuthMode.CHATGPT_SUBSCRIPTION -> Unit
            }
        }
        SelectField(
            label = stringResource(R.string.agents_field_reasoning_effort),
            value = d.codexReasoningEffort,
            options = codexReasoningEffortOptions.map { it.value to context.getString(codexEffortLabelRes(it.value)) },
            onSelect = { e -> vm.editDraft { it.copy(codexReasoningEffort = e) } },
        )
        ToggleRow(stringResource(R.string.agents_codex_enable_websocket), d.codexSupportsWebsockets) { v -> vm.editDraft { it.copy(codexSupportsWebsockets = v) } }
        ToggleRow(stringResource(R.string.agents_codex_enable_skills), d.codexSkills) { v -> vm.editDraft { it.copy(codexSkills = v) } }
        ToggleRow(stringResource(R.string.agents_codex_enable_fast), d.codexServiceTierFast) { v -> vm.editDraft { it.copy(codexServiceTierFast = v) } }
    }
}

@StringRes
private fun codexEffortLabelRes(e: CodexReasoningEffort): Int = when (e) {
    CodexReasoningEffort.LOW -> R.string.agents_effort_low
    CodexReasoningEffort.MEDIUM -> R.string.agents_effort_medium
    CodexReasoningEffort.HIGH -> R.string.agents_effort_high
    CodexReasoningEffort.XHIGH -> R.string.agents_effort_xhigh
}

@Composable
private fun GeminiSection(state: AgentDetailState, vm: AgentsViewModel) {
    val colors = CodegTheme.colors
    val d = state.draft
    FormSection(stringResource(R.string.agents_section_auth_config)) {
        SelectField(
            label = stringResource(R.string.agents_field_auth_mode),
            value = d.geminiAuthMode,
            options = listOf(
                GeminiAuthMode.CUSTOM to stringResource(R.string.agents_auth_custom_endpoint),
                GeminiAuthMode.LOGIN_GOOGLE to stringResource(R.string.agents_gemini_auth_oauth),
                GeminiAuthMode.GEMINI_API_KEY to stringResource(R.string.agents_gemini_api_key),
                GeminiAuthMode.VERTEX_ADC to stringResource(R.string.agents_gemini_auth_vertex_adc),
                GeminiAuthMode.VERTEX_SERVICE_ACCOUNT to stringResource(R.string.agents_gemini_auth_vertex_sa),
                GeminiAuthMode.VERTEX_API_KEY to stringResource(R.string.agents_gemini_auth_vertex_api_key),
                GeminiAuthMode.MODEL_PROVIDER to stringResource(R.string.agents_field_model_provider),
            ),
            onSelect = vm::setGeminiAuthMode,
        )
        when (d.geminiAuthMode) {
            GeminiAuthMode.LOGIN_GOOGLE ->
                Text(stringResource(R.string.agents_gemini_oauth_hint), fontSize = 12.sp, color = colors.textTertiary)
            GeminiAuthMode.CUSTOM -> {
                CodegTextField(d.apiBaseUrl, { v -> vm.editDraft { it.copy(apiBaseUrl = v) } }, stringResource(R.string.agents_field_api_url), placeholder = "https://…", keyboardType = KeyboardType.Uri, mono = true)
                SecretField(d.geminiApiKey, { v -> vm.editDraft { it.copy(geminiApiKey = v) } }, stringResource(R.string.agents_gemini_api_key))
            }
            GeminiAuthMode.GEMINI_API_KEY ->
                SecretField(d.geminiApiKey, { v -> vm.editDraft { it.copy(geminiApiKey = v) } }, stringResource(R.string.agents_gemini_api_key))
            GeminiAuthMode.VERTEX_API_KEY -> {
                SecretField(d.googleApiKey, { v -> vm.editDraft { it.copy(googleApiKey = v) } }, stringResource(R.string.agents_gemini_google_api_key))
                CodegTextField(d.googleCloudProject, { v -> vm.editDraft { it.copy(googleCloudProject = v) } }, stringResource(R.string.agents_gemini_cloud_project), mono = true)
                CodegTextField(d.googleCloudLocation, { v -> vm.editDraft { it.copy(googleCloudLocation = v) } }, stringResource(R.string.agents_gemini_cloud_location), mono = true)
            }
            GeminiAuthMode.VERTEX_ADC -> {
                CodegTextField(d.googleCloudProject, { v -> vm.editDraft { it.copy(googleCloudProject = v) } }, stringResource(R.string.agents_gemini_cloud_project), mono = true)
                CodegTextField(d.googleCloudLocation, { v -> vm.editDraft { it.copy(googleCloudLocation = v) } }, stringResource(R.string.agents_gemini_cloud_location), mono = true)
            }
            GeminiAuthMode.VERTEX_SERVICE_ACCOUNT -> {
                CodegTextField(d.googleCloudProject, { v -> vm.editDraft { it.copy(googleCloudProject = v) } }, stringResource(R.string.agents_gemini_cloud_project), mono = true)
                CodegTextField(d.googleCloudLocation, { v -> vm.editDraft { it.copy(googleCloudLocation = v) } }, stringResource(R.string.agents_gemini_cloud_location), mono = true)
                CodegTextField(d.googleApplicationCredentials, { v -> vm.editDraft { it.copy(googleApplicationCredentials = v) } }, stringResource(R.string.agents_gemini_credentials_path), mono = true)
            }
            GeminiAuthMode.MODEL_PROVIDER -> ProviderPicker(state, vm)
        }
        // In model-provider mode the provider owns the model (it's scrubbed on save),
        // so the provider summary shows it read-only instead of an editable field.
        if (d.geminiAuthMode != GeminiAuthMode.MODEL_PROVIDER) {
            CodegTextField(d.model, { v -> vm.editDraft { it.copy(model = v) } }, stringResource(R.string.agents_field_model), placeholder = "gemini-2.5-pro", mono = true)
        }
    }
}

@Composable
private fun OpenClawSection(state: AgentDetailState, vm: AgentsViewModel) {
    val d = state.draft
    FormSection(stringResource(R.string.agents_section_gateway_config)) {
        CodegTextField(d.openClawGatewayUrl, { v -> vm.editDraft { it.copy(openClawGatewayUrl = v) } }, stringResource(R.string.agents_openclaw_gateway_url), placeholder = "wss://…", keyboardType = KeyboardType.Uri, mono = true)
        SecretField(d.openClawGatewayToken, { v -> vm.editDraft { it.copy(openClawGatewayToken = v) } }, stringResource(R.string.agents_openclaw_gateway_token))
        CodegTextField(d.openClawSessionKey, { v -> vm.editDraft { it.copy(openClawSessionKey = v) } }, stringResource(R.string.agents_openclaw_session_key), placeholder = "agent:main:main", mono = true)
    }
}

@Composable
private fun ClineSection(state: AgentDetailState, vm: AgentsViewModel) {
    val d = state.draft
    FormSection(stringResource(R.string.agents_section_cline)) {
        SelectField(
            label = stringResource(R.string.agents_field_provider),
            value = d.clineProvider,
            options = clineProviders,
            onSelect = { p -> vm.editDraft { it.copy(clineProvider = p) } },
        )
        SecretField(d.clineApiKey, { v -> vm.editDraft { it.copy(clineApiKey = v) } }, stringResource(R.string.agents_field_api_key))
        CodegTextField(d.clineModel, { v -> vm.editDraft { it.copy(clineModel = v) } }, stringResource(R.string.agents_field_model), mono = true)
        CodegTextField(d.clineBaseUrl, { v -> vm.editDraft { it.copy(clineBaseUrl = v) } }, stringResource(R.string.agents_field_api_url), placeholder = "https://…", keyboardType = KeyboardType.Uri, mono = true)
    }
}

@Composable
private fun OpenCodeSection(state: AgentDetailState, vm: AgentsViewModel) {
    val d = state.draft
    FormSection(stringResource(R.string.agents_section_models), footer = stringResource(R.string.agents_opencode_footer)) {
        CodegTextField(d.openCodeMainModel, { v -> vm.editDraft { it.copy(openCodeMainModel = v) } }, stringResource(R.string.agents_field_main_model), mono = true)
        CodegTextField(d.openCodeSmallModel, { v -> vm.editDraft { it.copy(openCodeSmallModel = v) } }, stringResource(R.string.agents_opencode_small_model), mono = true)
    }
}

@Composable
private fun HermesSection(state: AgentDetailState, vm: AgentsViewModel) {
    val d = state.draft
    val option = hermesProvider(d.hermesProvider)
    FormSection(stringResource(R.string.agents_section_configuration), footer = stringResource(hermesFooterRes(option?.kind))) {
        HermesProviderField(d.hermesProvider, vm::setHermesProvider)
        if (option?.kind == HermesProviderKind.API_KEY) {
            SecretField(d.apiKey, { v -> vm.editDraft { it.copy(apiKey = v) } }, stringResource(R.string.agents_hermes_api_key_keep))
        }
        if (option?.needsBaseUrl == true) {
            CodegTextField(d.apiBaseUrl, { v -> vm.editDraft { it.copy(apiBaseUrl = v) } }, stringResource(R.string.agents_field_api_url), placeholder = "https://…", keyboardType = KeyboardType.Uri, mono = true)
        }
        CodegTextField(d.model, { v -> vm.editDraft { it.copy(model = v) } }, stringResource(R.string.agents_field_model), placeholder = "moonshotai/kimi-k2", mono = true)
    }
}

@StringRes
private fun hermesFooterRes(kind: HermesProviderKind?): Int = when (kind) {
    HermesProviderKind.API_KEY, null -> R.string.agents_hermes_footer_api_key
    HermesProviderKind.OAUTH -> R.string.agents_hermes_footer_oauth
    HermesProviderKind.AWS -> R.string.agents_hermes_footer_aws
}

/**
 * CodeBuddy authenticates purely through env vars, so this binds the shared draft
 * (like OpenClaw) — the host "Save" writes them via `acp_update_agent_env`. The
 * environment picker drives `CODEBUDDY_INTERNET_ENVIRONMENT`; "Self-hosted" swaps
 * the region hint for a validated `CODEBUDDY_BASE_URL` field. Port of iOS
 * `CodeBuddyConfigSection`.
 */
@Composable
private fun CodeBuddySection(state: AgentDetailState, vm: AgentsViewModel) {
    val colors = CodegTheme.colors
    val d = state.draft
    val selfHosted = d.codeBuddyEnvironment == CodeBuddyEnvironment.SELF_HOSTED
    FormSection(
        stringResource(R.string.agents_codebuddy_section),
        footer = stringResource(if (selfHosted) R.string.agents_codebuddy_footer_self_hosted else R.string.agents_codebuddy_footer),
    ) {
        SecretField(d.apiKey, { v -> vm.editDraft { it.copy(apiKey = v) } }, stringResource(R.string.agents_field_api_key))
        SelectField(
            label = stringResource(R.string.agents_codebuddy_environment),
            value = d.codeBuddyEnvironment,
            options = listOf(
                CodeBuddyEnvironment.OVERSEAS to stringResource(R.string.agents_codebuddy_env_overseas),
                CodeBuddyEnvironment.INTERNAL to stringResource(R.string.agents_codebuddy_env_internal),
                CodeBuddyEnvironment.IOA to stringResource(R.string.agents_codebuddy_env_ioa),
                CodeBuddyEnvironment.SELF_HOSTED to stringResource(R.string.agents_codebuddy_env_self_hosted),
            ),
            onSelect = { env -> vm.editDraft { it.copy(codeBuddyEnvironment = env) } },
        )
        if (selfHosted) {
            CodegTextField(
                d.codeBuddyBaseUrl,
                { v -> vm.editDraft { it.copy(codeBuddyBaseUrl = v) } },
                stringResource(R.string.agents_codebuddy_deployment_url),
                placeholder = "https://codebuddy.your-company.com",
                keyboardType = KeyboardType.Uri,
                mono = true,
            )
            val invalid = d.codeBuddyBaseUrl.trim().isNotEmpty() && !AgentConfig.isValidCodeBuddyBaseUrl(d.codeBuddyBaseUrl)
            if (invalid) {
                Text(stringResource(R.string.agents_codebuddy_url_invalid), fontSize = 11.sp, color = colors.danger, modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
}

/**
 * Grok is a hybrid: `XAI_API_KEY` rides the env (like CodeBuddy), while the two
 * structured controls — permission mode + reasoning effort — plus the raw
 * `config.toml` escape hatch (the Advanced editor) persist via
 * `acp_update_agent_config` (grokStructured / grokConfigToml), which the server
 * merges into `~/.grok/config.toml`. Binds the shared draft; the host "Save" writes
 * env then config in one shot. `""` is the "use default" sentinel for each dropdown.
 * Port of iOS `GrokConfigSection`.
 */
@Composable
private fun GrokSection(state: AgentDetailState, vm: AgentsViewModel) {
    val colors = CodegTheme.colors
    val d = state.draft
    val useDefault = stringResource(R.string.agents_grok_use_default)
    FormSection(
        stringResource(R.string.agents_grok_section),
        footer = stringResource(R.string.agents_grok_footer),
    ) {
        SelectField(
            label = stringResource(R.string.agents_grok_permission_mode),
            value = d.grokPermissionMode,
            options = listOf(
                "" to useDefault,
                "ask" to stringResource(R.string.agents_grok_permission_ask),
                "always-approve" to stringResource(R.string.agents_grok_permission_always),
            ),
            onSelect = { v -> vm.editDraft { it.copy(grokPermissionMode = v) } },
        )
        SelectField(
            label = stringResource(R.string.agents_field_reasoning_effort),
            value = d.grokReasoningEffort,
            options = listOf(
                "" to useDefault,
                "low" to stringResource(R.string.agents_grok_effort_low),
                "medium" to stringResource(R.string.agents_grok_effort_medium),
                "high" to stringResource(R.string.agents_grok_effort_high),
                "xhigh" to stringResource(R.string.agents_grok_effort_max),
            ),
            onSelect = { v -> vm.editDraft { it.copy(grokReasoningEffort = v) } },
        )
        SecretField(d.apiKey, { v -> vm.editDraft { it.copy(apiKey = v) } }, stringResource(R.string.agents_field_api_key))
        val configured = d.apiKey.trim().isNotEmpty()
        Text(
            stringResource(if (configured) R.string.agents_grok_api_key_configured else R.string.agents_grok_api_key_none),
            fontSize = 11.sp, color = colors.textTertiary, modifier = Modifier.padding(start = 4.dp),
        )
        Text(
            stringResource(R.string.agents_grok_api_key_caption),
            fontSize = 11.sp, color = colors.textTertiary, modifier = Modifier.padding(start = 4.dp),
        )
    }
}

/**
 * Cursor is a hybrid like Grok, but with two write surfaces instead of one: the auth
 * method, credential, model and Run Everything knob ride the env, while the sandbox
 * mode + permission rules are a structured PATCH the backend merges onto
 * `~/.cursor/cli-config.json` (so keys the Cursor CLI's own `/config` UI wrote
 * survive), with the raw file as the Advanced escape hatch. Binds the shared draft;
 * the host "Save" writes env then config in one shot.
 *
 * The two probes (`cursor-agent status` / `models`) are local read-only state: they
 * test the credential CURRENTLY ON SCREEN, not the saved one, and re-run when the
 * method changes because an API key and a browser login can list different catalogs.
 * Port of iOS `CursorConfigSection` / web `CursorConfigPanel`.
 */
@Composable
private fun CursorSection(state: AgentDetailState, vm: AgentsViewModel) {
    val colors = CodegTheme.colors
    val d = state.draft
    val custom = d.cursorAuthMode == CursorAuthMethod.CUSTOM

    var auth by remember { mutableStateOf<CursorAuthStatus?>(null) }
    var authLoading by remember { mutableStateOf(false) }
    var models by remember { mutableStateOf<List<CursorModelInfo>>(emptyList()) }
    var modelsError by remember { mutableStateOf<String?>(null) }
    var modelsLoading by remember { mutableStateOf(false) }
    // The credential the probes should test: the typed key in API-key mode, or empty
    // in subscription mode (which forces the browser-login credential).
    val probeKey = if (custom) d.apiKey.trim() else ""
    var refreshTick by remember { mutableStateOf(0) }

    // Bake the on-screen state into `envText` up front: the panel shows Run Everything
    // ON for a fresh agent, so without this a save that touched nothing else would
    // persist an env that doesn't match what's displayed.
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.rebakeDraft() }

    androidx.compose.runtime.LaunchedEffect(d.cursorAuthMode, refreshTick) {
        // Drop the stale catalog first — it belongs to the previous method.
        models = emptyList()
        modelsError = null
        authLoading = true
        // A transport failure leaves the card in its previous state; a failed *probe*
        // reports itself through `auth.error`.
        val status = runCatching { vm.cursorAuthStatus(probeKey) }.getOrNull()
        if (status != null) auth = status
        authLoading = false
        if (status?.isAuthenticated == true) {
            modelsLoading = true
            runCatching { vm.cursorListModels(probeKey) }
                .onSuccess { models = it.models; modelsError = it.error }
                .onFailure { modelsError = it.message }
            modelsLoading = false
        }
    }

    FormSection(
        stringResource(R.string.agents_cursor_section),
        footer = stringResource(R.string.agents_cursor_footer),
    ) {
        SelectField(
            label = stringResource(R.string.agents_cursor_auth_mode),
            value = d.cursorAuthMode,
            options = listOf(
                CursorAuthMethod.SUBSCRIPTION to stringResource(R.string.agents_cursor_mode_subscription),
                CursorAuthMethod.CUSTOM to stringResource(R.string.agents_cursor_mode_api_key),
            ),
            onSelect = { m -> vm.editDraft { it.copy(cursorAuthMode = m) } },
        )
        Caption(
            stringResource(
                if (custom) R.string.agents_cursor_mode_api_key_hint else R.string.agents_cursor_mode_subscription_hint,
            ),
        )
    }

    FormSection(stringResource(R.string.agents_cursor_account)) {
        CursorStatusRow(auth, authLoading) { refreshTick++ }
        // The managed binary lives in codeg's cache and is NOT on PATH, so hand over
        // the fully-resolved command rather than a bare `cursor-agent`.
        if (!custom && auth?.installed == true && auth?.isAuthenticated == false) {
            Caption(stringResource(R.string.agents_cursor_login_hint))
            val command = CursorConfig.loginCommand(auth?.binaryPath)
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(colors.codeSurface)
                    .padding(start = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    command,
                    fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                CopyButton(command)
            }
        }
        if (custom) {
            SecretField(
                d.apiKey,
                { v -> vm.editDraft { it.copy(apiKey = v) } },
                stringResource(R.string.agents_cursor_api_key),
            )
            Caption(stringResource(R.string.agents_cursor_api_key_hint))
        }
        auth?.error?.takeIf { it.isNotEmpty() }?.let {
            Text(it, fontSize = 11.sp, color = colors.danger, modifier = Modifier.padding(start = 4.dp))
        }
    }

    // The saved model stays selectable even when the catalog didn't load (or doesn't
    // list it), so a hand-set value isn't silently dropped on the next save.
    val savedModel = d.cursorModel.trim()
    if (models.isNotEmpty() || savedModel.isNotEmpty()) {
        FormSection(stringResource(R.string.agents_cursor_model), footer = stringResource(R.string.agents_cursor_model_footer)) {
            val options = buildList {
                add("" to stringResource(R.string.agents_cursor_model_default))
                if (savedModel.isNotEmpty() && models.none { it.id == savedModel }) add(savedModel to savedModel)
                models.forEach { add(it.id to it.displayLabel) }
            }
            SelectField(
                label = stringResource(R.string.agents_cursor_model),
                value = d.cursorModel,
                options = options,
                onSelect = { v -> vm.editDraft { it.copy(cursorModel = v) } },
            )
            if (modelsLoading) Caption(stringResource(R.string.agents_cursor_models_loading))
            modelsError?.takeIf { it.isNotEmpty() }?.let {
                Text(it, fontSize = 11.sp, color = colors.danger, modifier = Modifier.padding(start = 4.dp))
            }
        }
    }

    FormSection(stringResource(R.string.agents_cursor_permissions), footer = stringResource(R.string.agents_cursor_permissions_footer)) {
        ToggleRow(stringResource(R.string.agents_cursor_run_everything), d.cursorForce) { v ->
            vm.editDraft { it.copy(cursorForce = v) }
        }
        Caption(
            stringResource(
                if (d.cursorForce) R.string.agents_cursor_run_everything_on else R.string.agents_cursor_run_everything_off,
            ),
        )
        SelectField(
            label = stringResource(R.string.agents_cursor_sandbox),
            value = d.cursorSandboxMode,
            options = listOf(
                "" to stringResource(R.string.agents_cursor_option_default),
                "enabled" to stringResource(R.string.agents_cursor_sandbox_enabled),
                "disabled" to stringResource(R.string.agents_cursor_sandbox_disabled),
            ),
            onSelect = { v -> vm.editDraft { it.copy(cursorSandboxMode = v) } },
        )
        RuleEditor(
            label = stringResource(R.string.agents_cursor_allow_rules),
            rules = d.cursorAllowRules,
            placeholder = "Shell(ls)",
            tone = colors.textPrimary,
            onChange = { rules -> vm.editDraft { it.copy(cursorAllowRules = rules) } },
        )
        RuleEditor(
            label = stringResource(R.string.agents_cursor_deny_rules),
            rules = d.cursorDenyRules,
            placeholder = "Shell(rm)",
            tone = colors.danger,
            onChange = { rules -> vm.editDraft { it.copy(cursorDenyRules = rules) } },
        )
        Caption(stringResource(R.string.agents_cursor_rules_syntax))
    }
}

/** Cursor's account line: a status dot + label, a plan chip, and a refresh action.
 *  Same status vocabulary the preflight rows use — accent = pass, amber = actionable,
 *  muted = unknown/absent. */
@Composable
private fun CursorStatusRow(auth: CursorAuthStatus?, loading: Boolean, onRefresh: () -> Unit) {
    val colors = CodegTheme.colors
    val installed = auth?.installed == true
    val signedIn = auth?.isAuthenticated == true
    val tint = when {
        signedIn -> colors.accent
        installed -> CursorActionableAmber
        else -> colors.textTertiary.copy(alpha = 0.5f)
    }
    val text = when {
        loading && auth == null -> stringResource(R.string.agents_cursor_status_checking)
        !installed -> stringResource(R.string.agents_cursor_status_missing)
        !signedIn -> stringResource(R.string.agents_cursor_status_signed_out)
        else -> auth?.email?.takeIf { it.isNotEmpty() } ?: stringResource(R.string.agents_cursor_status_signed_in)
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(tint))
        Text(
            text,
            fontSize = 14.sp, color = colors.textPrimary, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        auth?.membership?.takeIf { it.isNotEmpty() }?.let {
            Text(
                it,
                fontSize = 11.sp, fontWeight = FontWeight.Medium, color = colors.accent,
                modifier = Modifier.clip(RoundedCornerShape(50))
                    .background(colors.accent.copy(alpha = 0.12f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        IconButton(onClick = onRefresh, enabled = !loading, modifier = Modifier.size(32.dp)) {
            if (loading) {
                CircularProgressIndicator(color = colors.accent, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
            } else {
                Icon(
                    Icons.Rounded.Refresh,
                    contentDescription = stringResource(R.string.agents_cursor_refresh),
                    tint = colors.accent,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** An editable list of permission rules: one text field per rule + delete, and an add
 *  row. Indices are stable for the lifetime of an edit (rows are only appended or
 *  removed) and rules are freely duplicable strings, so the value can't be the key. */
@Composable
private fun RuleEditor(
    label: String,
    rules: List<String>,
    placeholder: String,
    tone: Color,
    onChange: (List<String>) -> Unit,
) {
    val colors = CodegTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = colors.textSecondary)
        rules.forEachIndexed { index, rule ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = rule,
                    onValueChange = { v -> onChange(rules.toMutableList().also { it[index] = v }) },
                    placeholder = { Text(placeholder, fontFamily = FontFamily.Monospace, fontSize = 13.sp) },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    colors = dropdownColors(colors),
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { onChange(rules.toMutableList().also { it.removeAt(index) }) },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        Icons.Rounded.Delete,
                        contentDescription = stringResource(R.string.common_delete),
                        tint = tone,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        TonalAction(stringResource(R.string.agents_cursor_add_rule)) { onChange(rules + "") }
    }
}

/** "Actionable, not broken" — the amber the status badges already use for a state the
 *  user can fix (here: cursor-agent installed but not signed in). */
private val CursorActionableAmber = Color(0xFFF5BD5C)

// endregion

// region Model-provider picker

@Composable
private fun ColumnScope.ProviderPicker(state: AgentDetailState, vm: AgentsViewModel) {
    val colors = CodegTheme.colors
    if (state.providers.isEmpty()) {
        Text(
            stringResource(R.string.agents_provider_none),
            fontSize = 12.sp, color = colors.textTertiary,
        )
        return
    }
    SelectField(
        label = stringResource(R.string.agents_field_model_provider),
        value = state.draft.modelProviderId,
        options = state.providers.map { it.id as Int? to it.name },
        onSelect = { id -> vm.setModelProvider(id) },
    )
    state.provider?.let { p -> ProviderSummary(p) }
}

@Composable
private fun ProviderSummary(p: ModelProviderInfo) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        ReadonlyRow(stringResource(R.string.agents_provider_endpoint), p.apiUrl)
        if (p.apiKeyMasked.isNotEmpty()) ReadonlyRow(stringResource(R.string.agents_field_api_key), p.apiKeyMasked)
        p.model?.takeIf { it.isNotBlank() }?.let { ReadonlyRow(stringResource(R.string.agents_field_model), it) }
    }
}

@Composable
private fun ReadonlyRow(label: String, value: String) {
    val colors = CodegTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, fontSize = 12.sp, color = colors.textTertiary)
        Text(value, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// endregion

// region Reusable form building blocks

@Composable
internal fun FormSection(title: String, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val colors = CodegTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = colors.textTertiary, modifier = Modifier.padding(start = 4.dp))
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.bgElevated.copy(alpha = 0.5f)).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
        footer?.let { Text(it, fontSize = 11.sp, color = colors.textTertiary, modifier = Modifier.padding(start = 4.dp)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T> SelectField(label: String, value: T, options: List<Pair<T, String>>, onSelect: (T) -> Unit) {
    val colors = CodegTheme.colors
    var expanded by remember { mutableStateOf(false) }
    val display = options.firstOrNull { it.first == value }?.second ?: ""
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = display,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = dropdownColors(colors),
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (id, lbl) ->
                DropdownMenuItem(text = { Text(lbl) }, onClick = { onSelect(id); expanded = false })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HermesProviderField(value: String, onSelect: (String) -> Unit) {
    val colors = CodegTheme.colors
    var expanded by remember { mutableStateOf(false) }
    val display = hermesProvider(value)?.label ?: ""
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = display,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.agents_field_provider)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = dropdownColors(colors),
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            var lastKind: HermesProviderKind? = null
            hermesProviders.forEach { opt ->
                if (opt.kind != lastKind) {
                    lastKind = opt.kind
                    Text(
                        stringResource(kindLabelRes(opt.kind)),
                        fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = colors.textTertiary,
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp),
                    )
                }
                DropdownMenuItem(text = { Text(opt.label) }, onClick = { onSelect(opt.id); expanded = false })
            }
        }
    }
}

@StringRes
private fun kindLabelRes(kind: HermesProviderKind): Int = when (kind) {
    HermesProviderKind.API_KEY -> R.string.agents_hermes_kind_api_key
    HermesProviderKind.OAUTH -> R.string.agents_hermes_kind_oauth
    HermesProviderKind.AWS -> R.string.agents_hermes_kind_aws
}

@Composable
private fun ToggleRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val colors = CodegTheme.colors
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, fontSize = 14.sp, color = colors.textPrimary, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = colors.accent, checkedThumbColor = colors.onAccent),
        )
    }
}

@Composable
internal fun ColumnScope.ExpandableGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    val colors = CodegTheme.colors
    var open by remember { mutableStateOf(false) }
    val rotation by animateFloatAsState(if (open) 180f else 0f, label = "chevron")
    Row(
        Modifier.fillMaxWidth().clickable { open = !open },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, fontSize = 13.sp, color = colors.textSecondary)
        Icon(Icons.Rounded.ExpandMore, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(20.dp).rotate(rotation))
    }
    AnimatedVisibility(open) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
    }
}

@Composable
private fun TonalAction(text: String, onClick: () -> Unit) {
    val colors = CodegTheme.colors
    androidx.compose.material3.FilledTonalButton(
        onClick = onClick,
        colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
            containerColor = colors.accent.copy(alpha = 0.14f),
            contentColor = colors.accent,
        ),
    ) { Text(text) }
}

@Composable
private fun AdvancedSection(state: AgentDetailState, vm: AgentsViewModel) {
    val colors = CodegTheme.colors
    var open by remember { mutableStateOf(false) }
    val rotation by animateFloatAsState(if (open) 180f else 0f, label = "advanced")
    val label = when (state.agentType) {
        AgentType.CODEX, AgentType.GROK -> "config.toml"
        AgentType.CURSOR -> "cli-config.json"
        else -> "config.json"
    }
    val text = when (state.agentType) {
        AgentType.CODEX -> state.draft.codexConfigTomlText
        AgentType.GROK -> state.draft.grokConfigTomlText
        AgentType.CURSOR -> state.draft.cursorCliConfigText
        else -> state.draft.configText
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.bgElevated.copy(alpha = 0.5f))
                .clickable { open = !open }.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(stringResource(R.string.agents_advanced), fontSize = 14.sp, color = colors.textPrimary)
                Text(label, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = colors.textTertiary)
            }
            Icon(Icons.Rounded.ExpandMore, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(20.dp).rotate(rotation))
        }
        AnimatedVisibility(open) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = vm::editRawConfig,
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.accent,
                        unfocusedBorderColor = colors.surfaceStroke,
                        focusedTextColor = colors.textPrimary,
                        unfocusedTextColor = colors.textPrimary,
                        cursorColor = colors.accent,
                    ),
                )
                state.configError?.let { Text(it, fontSize = 11.sp, color = colors.danger, modifier = Modifier.padding(start = 4.dp)) }
                Text(stringResource(R.string.agents_advanced_override_hint), fontSize = 11.sp, color = colors.textTertiary, modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
}

@Composable
private fun dropdownColors(colors: CodegColors) = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = colors.accent,
    unfocusedBorderColor = colors.surfaceStroke,
    focusedLabelColor = colors.accent,
    unfocusedLabelColor = colors.textTertiary,
    focusedTextColor = colors.textPrimary,
    unfocusedTextColor = colors.textPrimary,
    focusedTrailingIconColor = colors.textTertiary,
    unfocusedTrailingIconColor = colors.textTertiary,
)

// endregion
