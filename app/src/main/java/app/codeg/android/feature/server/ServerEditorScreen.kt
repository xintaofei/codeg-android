package app.codeg.android.feature.server

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Switch
import androidx.compose.ui.Alignment
import app.codeg.android.BuildConfig
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import app.codeg.android.R
import app.codeg.android.core.designsystem.component.CodegTextField
import app.codeg.android.core.designsystem.component.PrimaryButton
import app.codeg.android.core.designsystem.component.SecretField
import app.codeg.android.core.designsystem.theme.CodegTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerEditorScreen(
    onDone: () -> Unit,
    viewModel: ServerEditorViewModel = hiltViewModel(),
) {
    val colors = CodegTheme.colors
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(if (viewModel.isEditing) R.string.server_edit else R.string.server_add))
                },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_cancel), tint = colors.textSecondary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = colors.textPrimary,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionHeader(stringResource(R.string.server_section_connection))
            CodegTextField(
                value = viewModel.name,
                onValueChange = viewModel::onNameChange,
                label = stringResource(R.string.server_name),
            )
            CodegTextField(
                value = viewModel.url,
                onValueChange = viewModel::onUrlChange,
                label = stringResource(R.string.server_url),
                placeholder = stringResource(R.string.server_url_placeholder),
                keyboardType = KeyboardType.Uri,
                mono = true,
            )

            if (BuildConfig.EASYTIER_ENABLED || viewModel.useMesh) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.mesh_use), Modifier.weight(1f))
                    Switch(checked = viewModel.useMesh, onCheckedChange = viewModel::onUseMeshChange,
                        enabled = BuildConfig.EASYTIER_ENABLED || viewModel.useMesh)
                }
                if (viewModel.useMesh) {
                    Text(stringResource(if (BuildConfig.EASYTIER_ENABLED) R.string.mesh_description else R.string.mesh_unavailable),
                        style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                    CodegTextField(value = viewModel.networkName, onValueChange = viewModel::onNetworkNameChange, label = stringResource(R.string.mesh_network))
                    SecretField(value = viewModel.networkPassword, onValueChange = viewModel::onNetworkPasswordChange, label = stringResource(R.string.mesh_password))
                    CodegTextField(value = viewModel.peers, onValueChange = viewModel::onPeersChange, label = stringResource(R.string.mesh_peers), mono = true, singleLine = false)
                    CodegTextField(value = viewModel.ipv4, onValueChange = viewModel::onIpv4Change, label = stringResource(R.string.mesh_ipv4), mono = true)
                }
            }

            SectionHeader(stringResource(R.string.server_section_auth))
            SecretField(
                value = viewModel.token,
                onValueChange = viewModel::onTokenChange,
                label = stringResource(R.string.server_token),
            )

            PrimaryButton(
                text = stringResource(R.string.server_test_connection),
                onClick = viewModel::testConnection,
                loading = viewModel.test is TestState.Testing,
                enabled = viewModel.canTest,
            )
            when (val test = viewModel.test) {
                is TestState.Success -> Text(
                    stringResource(R.string.server_connection_ok, test.version),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF85D18F),
                    fontFamily = FontFamily.Monospace,
                )
                is TestState.Failure -> Text(
                    stringResource(R.string.server_connection_failed, test.message),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.danger,
                )
                else -> Unit
            }

            PrimaryButton(
                text = stringResource(R.string.server_save),
                onClick = { viewModel.save(onDone) },
                enabled = viewModel.canSave,
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = CodegTheme.colors.textTertiary,
        modifier = Modifier.padding(start = 4.dp),
    )
}
