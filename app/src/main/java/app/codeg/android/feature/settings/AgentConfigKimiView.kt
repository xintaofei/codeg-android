package app.codeg.android.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.codeg.android.R
import app.codeg.android.core.designsystem.component.CodegTextField
import app.codeg.android.core.designsystem.component.PrimaryButton
import app.codeg.android.core.designsystem.component.SecretField
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.core.model.AcpAgentInfo
import app.codeg.android.core.model.KIMI_DEFAULT_MAX_CONTEXT_SIZE
import app.codeg.android.core.model.KIMI_MODEL_PLACEHOLDER
import app.codeg.android.core.model.KimiAuthMode
import app.codeg.android.core.model.KimiEndpointRegion
import app.codeg.android.core.model.KimiInterfaceType
import app.codeg.android.core.model.KimiManagedConfig
import app.codeg.android.core.model.KimiNativeAuthType
import app.codeg.android.core.model.UpdateKimiCodeConfigBody
import app.codeg.android.core.model.kimiBaseUrlForRegion
import app.codeg.android.core.model.kimiEndpointRegionFromBaseUrl
import app.codeg.android.core.model.kimiInitialMode
import app.codeg.android.core.model.kimiInterfaceMeta
import app.codeg.android.core.model.modelsBaseUrl
import app.codeg.android.core.model.kimiInterfaceTypes
import app.codeg.android.core.network.displayMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val WarnAmber = Color(0xFFF0A030)
private val SuccessGreen = Color(0xFF4CBC75)

private data class KimiBanner(val text: String, val isError: Boolean)

/**
 * Self-contained Kimi Code settings panel (port of iOS `KimiConfigSection`). Kimi has
 * a dedicated backend (`acp_update_kimi_code_config`) and per-section state that
 * doesn't live in the shared config.json/env draft, so — unlike the draft-bound
 * agents — it owns its local state (seeded from the projected [AcpAgentInfo.configJson])
 * and its own Save button(s); [AgentDetailContent] hides the host "Save" for Kimi.
 * Two authoritative modes (API key / login) plus a raw config.toml escape hatch.
 *
 * [agent] is passed fresh each recomposition so the gate-status banner reflects the
 * backend state after a save (the ViewModel refreshes the open detail on reload).
 */
@Composable
fun AgentConfigKimiView(agent: AcpAgentInfo, viewModel: AgentsViewModel) {
    val colors = CodegTheme.colors
    val scope = rememberCoroutineScope()

    // Seed the editable state ONCE from the projected config (later saves refresh only
    // the gate banner via `liveConfig`, never these fields).
    val seed = remember { KimiManagedConfig.parse(agent.configJson) }
    val seedInterface = remember { seed.interfaceType ?: KimiInterfaceType.KIMI }
    var mode by remember { mutableStateOf(kimiInitialMode(seed)) }
    var interfaceType by remember { mutableStateOf(seedInterface) }
    var region by remember { mutableStateOf(kimiEndpointRegionFromBaseUrl(seed.baseUrl ?: "")) }
    var baseUrl by remember { mutableStateOf(seed.baseUrl ?: kimiInterfaceMeta(seedInterface).defaultBaseUrl) }
    var authType by remember { mutableStateOf(seed.authType ?: KimiNativeAuthType.API_KEY) }
    var apiKey by remember { mutableStateOf(seed.key ?: "") }
    var modelId by remember { mutableStateOf(seed.modelId ?: "") }
    // Kimi's schema requires `max_context_size`, so the backend substitutes its own
    // default when the field is blank. Seed that default instead of an empty box, so
    // the form shows the number that will actually be written.
    var maxContext by remember {
        mutableStateOf((seed.maxContextSize ?: KIMI_DEFAULT_MAX_CONTEXT_SIZE).toString())
    }
    var vertexProject by remember { mutableStateOf(seed.vertexProject ?: "") }
    var vertexLocation by remember { mutableStateOf(seed.vertexLocation ?: "") }
    var rawConfig by remember { mutableStateOf(seed.rawConfigToml ?: "") }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var saving by remember { mutableStateOf(false) }
    var fetchingModels by remember { mutableStateOf(false) }
    var banner by remember { mutableStateOf<KimiBanner?>(null) }

    val meta = kimiInterfaceMeta(interfaceType)
    val isKimi = interfaceType == KimiInterfaceType.KIMI
    val isVertex = interfaceType == KimiInterfaceType.VERTEXAI
    val effectiveBaseUrl = if (isKimi) kimiBaseUrlForRegion(region, baseUrl) else baseUrl.trim()
    val liveConfig = KimiManagedConfig.parse(agent.configJson)

    // Auto-dismiss the result banner after 4s.
    LaunchedEffect(banner) {
        if (banner != null) { delay(4000); banner = null }
    }

    // Strings captured for use inside coroutines.
    val savedMsg = stringResource(R.string.agents_kimi_saved)
    val modelRequiredMsg = stringResource(R.string.agents_kimi_model_required)
    val enterKeyMsg = stringResource(R.string.agents_kimi_enter_key_endpoint)
    val modelsNoneMsg = stringResource(R.string.agents_kimi_models_none)
    val modelsErrMsg = stringResource(R.string.agents_kimi_models_error)
    val modelsFoundFmt = stringResource(R.string.agents_kimi_models_found)

    fun runSave(body: UpdateKimiCodeConfigBody) {
        saving = true
        scope.launch {
            try {
                viewModel.saveKimiCodeConfig(body)
                banner = KimiBanner(savedMsg, false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                banner = KimiBanner(e.displayMessage(), true)
            }
            saving = false
        }
    }

    fun handleSave() {
        if (mode == KimiAuthMode.LOGIN) {
            runSave(UpdateKimiCodeConfigBody(mode = KimiAuthMode.LOGIN.wire))
            return
        }
        if (modelId.trim().isEmpty()) {
            banner = KimiBanner(modelRequiredMsg, true)
            return
        }
        runSave(
            UpdateKimiCodeConfigBody(
                // Keyed on by `AgentsViewModel.saveKimiCodeConfig` to decide whether the
                // reasoning declaration has to be carried over — keep it the enum's wire value.
                mode = KimiAuthMode.APIKEY.wire,
                interfaceType = interfaceType.wire,
                authType = if (meta.usesApiKey) authType.wire else null,
                baseUrl = effectiveBaseUrl,
                apiKey = if (meta.usesApiKey) apiKey else null,
                model = modelId,
                maxContextSize = maxContext.trim().toIntOrNull(),
                vertexProject = if (isVertex) vertexProject else null,
                vertexLocation = if (isVertex) vertexLocation else null,
                // The reasoning declaration is deliberately absent here: this panel has no
                // editor for it, and the ViewModel fills it from a re-read of the LIVE
                // config right before the write (`kimiBodyForSave`). Filling it from this
                // screen's snapshot would revert a change made elsewhere since it opened.
            ),
        )
    }

    fun fetchModels() {
        val protocol = meta.modelsProtocol ?: return
        // A blank endpoint goes where the interface's SDK itself would (Anthropic / Gemini
        // have a default; an OpenAI-compatible endpoint doesn't).
        val url = meta.modelsBaseUrl(effectiveBaseUrl)
        val key = apiKey.trim()
        if (url.isEmpty() || key.isEmpty()) {
            banner = KimiBanner(enterKeyMsg, true)
            return
        }
        fetchingModels = true
        scope.launch {
            try {
                val list = viewModel.fetchKimiModels(protocol, url, key)
                models = list
                banner = KimiBanner(if (list.isEmpty()) modelsNoneMsg else modelsFoundFmt.format(list.size), false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                banner = KimiBanner(modelsErrMsg, true)
            }
            fetchingModels = false
        }
    }

    fun setInterface(next: KimiInterfaceType) {
        interfaceType = next
        models = emptyList()
        if (next == KimiInterfaceType.KIMI) {
            region = KimiEndpointRegion.INTERNATIONAL
            baseUrl = ""
        } else {
            baseUrl = kimiInterfaceMeta(next).defaultBaseUrl
        }
    }

    FormSection(stringResource(R.string.agents_kimi_section), footer = stringResource(R.string.agents_kimi_footer)) {
        KimiGateBanner(present = liveConfig.credentialPresent == true, mode = mode)
        banner?.let { KimiResultBanner(it) }
        HorizontalDivider(color = colors.hairline)

        SelectField(
            label = stringResource(R.string.agents_kimi_auth_method),
            value = mode,
            options = listOf(
                KimiAuthMode.APIKEY to stringResource(R.string.agents_kimi_auth_apikey),
                KimiAuthMode.LOGIN to stringResource(R.string.agents_kimi_auth_login),
            ),
            onSelect = { mode = it },
        )
        Caption(stringResource(R.string.agents_kimi_auth_caption))

        if (mode == KimiAuthMode.APIKEY) {
            HorizontalDivider(color = colors.hairline)
            // Provider type
            SelectField(
                label = stringResource(R.string.agents_kimi_provider_type),
                value = interfaceType,
                options = kimiInterfaceTypes.map { it.value to it.label },
                onSelect = { setInterface(it) },
            )
            Caption(stringResource(R.string.agents_kimi_provider_caption))

            // Endpoint / base URL
            if (isKimi) {
                SelectField(
                    label = stringResource(R.string.agents_kimi_endpoint),
                    value = region,
                    options = listOf(
                        KimiEndpointRegion.INTERNATIONAL to stringResource(R.string.agents_kimi_endpoint_international),
                        KimiEndpointRegion.CHINA to stringResource(R.string.agents_kimi_endpoint_china),
                        KimiEndpointRegion.CUSTOM to stringResource(R.string.agents_kimi_endpoint_custom),
                    ),
                    onSelect = { region = it },
                )
                if (region == KimiEndpointRegion.CUSTOM) {
                    CodegTextField(baseUrl, { baseUrl = it }, stringResource(R.string.agents_kimi_base_url), placeholder = "https://api.example.com/v1", keyboardType = KeyboardType.Uri, mono = true)
                }
                Caption(stringResource(R.string.agents_kimi_endpoint_caption))
            } else {
                CodegTextField(baseUrl, { baseUrl = it }, stringResource(R.string.agents_kimi_base_url), placeholder = "https://api.example.com/v1", keyboardType = KeyboardType.Uri, mono = true)
                Caption(stringResource(R.string.agents_kimi_base_url_caption))
            }

            // Credentials
            if (meta.usesApiKey) {
                SecretField(apiKey, { apiKey = it }, stringResource(R.string.agents_field_api_key))
                Caption(stringResource(R.string.agents_kimi_api_key_caption))
                ExpandableGroup(stringResource(R.string.agents_kimi_placement_disclosure)) {
                    SelectField(
                        label = stringResource(R.string.agents_kimi_placement_disclosure),
                        value = authType,
                        options = listOf(
                            KimiNativeAuthType.API_KEY to stringResource(R.string.agents_kimi_placement_inline),
                            KimiNativeAuthType.ENV to stringResource(R.string.agents_kimi_placement_env),
                        ),
                        onSelect = { authType = it },
                    )
                    Caption(stringResource(R.string.agents_kimi_placement_caption))
                }
            } else {
                CodegTextField(vertexProject, { vertexProject = it }, stringResource(R.string.agents_kimi_vertex_project), placeholder = "my-gcp-project", mono = true)
                CodegTextField(vertexLocation, { vertexLocation = it }, stringResource(R.string.agents_kimi_vertex_location), placeholder = "us-central1", mono = true)
                Caption(stringResource(R.string.agents_kimi_vertex_caption))
            }

            // Model + fetch
            CodegTextField(modelId, { modelId = it }, stringResource(R.string.agents_kimi_model), placeholder = KIMI_MODEL_PLACEHOLDER, mono = true)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { fetchModels() },
                    enabled = !saving && !fetchingModels && meta.modelsProtocol != null,
                ) {
                    if (fetchingModels) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = colors.accent)
                    else Text(stringResource(R.string.agents_kimi_fetch), color = colors.accent)
                }
                if (models.isNotEmpty()) {
                    Box {
                        var expanded by remember { mutableStateOf(false) }
                        TextButton(onClick = { expanded = true }) {
                            Text(stringResource(R.string.agents_kimi_models_count, models.size), color = colors.textSecondary)
                        }
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            models.forEach { m ->
                                DropdownMenuItem(text = { Text(m) }, onClick = { modelId = m; expanded = false })
                            }
                        }
                    }
                }
            }
            Caption(stringResource(R.string.agents_kimi_model_caption))

            CodegTextField(maxContext, { maxContext = it }, stringResource(R.string.agents_kimi_max_context), placeholder = "262144", keyboardType = KeyboardType.Number)
            Caption(stringResource(R.string.agents_kimi_max_context_caption))
        } else {
            Caption(stringResource(R.string.agents_kimi_login_caption))
        }

        HorizontalDivider(color = colors.hairline)
        PrimaryButton(
            text = stringResource(R.string.agents_kimi_save),
            onClick = { handleSave() },
            enabled = !saving,
            loading = saving,
        )

        HorizontalDivider(color = colors.hairline)
        ExpandableGroup(stringResource(R.string.agents_kimi_raw_disclosure)) {
            OutlinedTextField(
                value = rawConfig,
                onValueChange = { rawConfig = it },
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
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
            Caption(stringResource(R.string.agents_kimi_raw_caption))
            OutlinedButton(
                onClick = { runSave(UpdateKimiCodeConfigBody(mode = "raw", rawConfigToml = rawConfig)) },
                enabled = !saving,
            ) { Text(stringResource(R.string.agents_kimi_save_raw), color = colors.accent) }
        }
    }
}

@Composable
private fun KimiGateBanner(present: Boolean, mode: KimiAuthMode) {
    val colors = CodegTheme.colors
    val tint = if (present) colors.accent else WarnAmber
    val text = when {
        !present -> stringResource(R.string.agents_kimi_gate_none)
        mode == KimiAuthMode.LOGIN -> stringResource(R.string.agents_kimi_gate_login)
        else -> stringResource(R.string.agents_kimi_gate_apikey)
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(tint.copy(alpha = 0.10f)).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(if (present) Icons.Rounded.Verified else Icons.Rounded.WarningAmber, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Text(text, fontSize = 12.sp, color = colors.textSecondary)
    }
}

@Composable
private fun KimiResultBanner(banner: KimiBanner) {
    val colors = CodegTheme.colors
    val tint = if (banner.isError) colors.danger else SuccessGreen
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(tint.copy(alpha = 0.12f)).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(if (banner.isError) Icons.Rounded.ErrorOutline else Icons.Rounded.CheckCircle, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Text(banner.text, fontSize = 12.sp, color = if (banner.isError) colors.danger else colors.textSecondary)
    }
}

/** Small tertiary help text used throughout the Kimi (and Pi) panels. */
@Composable
internal fun Caption(text: String) {
    Text(text, fontSize = 11.sp, color = CodegTheme.colors.textTertiary, modifier = Modifier.padding(start = 2.dp))
}
