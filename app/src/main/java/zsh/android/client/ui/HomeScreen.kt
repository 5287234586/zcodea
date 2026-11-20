package zsh.android.client.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import zsh.android.client.R
import zsh.android.client.ZshaApp
import zsh.android.client.env.EnvPhase
import zsh.android.client.runtime.RuntimeState
import zsh.android.client.service.RuntimeService

@Composable
fun HomeScreen(
    onOpenSetup: () -> Unit,
    onOpenWeb: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as ZshaApp
    val scope = rememberCoroutineScope()

    val phase by app.envManager.phase.collectAsState()
    val runtime by app.runtimeManager.state.collectAsState()
    val log by app.envManager.log.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.home_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.app_subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        // ---- 环境状态 ----
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.env_status_title), style = MaterialTheme.typography.titleMedium)
                when (val p = phase) {
                    is EnvPhase.AbiUnsupported -> Text(
                        stringResource(R.string.abi_unsupported, p.abi),
                        color = MaterialTheme.colorScheme.error,
                    )
                    EnvPhase.Idle -> Text(stringResource(R.string.env_not_installed))
                    is EnvPhase.Installing -> Text(
                        stringResource(R.string.env_installing) + "（${p.step.key}）",
                        color = MaterialTheme.colorScheme.primary,
                    )
                    EnvPhase.Ready -> Text(stringResource(R.string.env_ready), color = MaterialTheme.colorScheme.secondary)
                    is EnvPhase.Failed -> Column {
                        Text(
                            stringResource(R.string.env_failed) + "（${p.step?.key}）：${p.message}",
                            color = MaterialTheme.colorScheme.error,
                        )
                        log.lastOrNull()?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when {
                        phase is EnvPhase.Ready -> {}
                        phase is EnvPhase.Failed -> Button(onClick = { scope.launch(Dispatchers.IO) { app.envManager.install() } }) {
                            Text(stringResource(R.string.action_reinstall))
                        }
                        else -> Button(
                            enabled = phase !is EnvPhase.Installing && phase !is EnvPhase.AbiUnsupported,
                            onClick = { scope.launch(Dispatchers.IO) { app.envManager.install() } },
                        ) { Text(stringResource(R.string.action_install)) }
                    }
                    OutlinedButton(onClick = onOpenSetup) { Text(stringResource(R.string.setup_title)) }
                }
            }
        }

        // ---- 运行时状态 ----
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("服务", style = MaterialTheme.typography.titleMedium)
                when (val s = runtime) {
                    RuntimeState.Stopped -> Text(stringResource(R.string.runtime_stopped))
                    RuntimeState.Starting -> Text(stringResource(R.string.runtime_starting))
                    is RuntimeState.Running -> Text(stringResource(R.string.runtime_running, "127.0.0.1:${s.port}"), color = MaterialTheme.colorScheme.secondary)
                    RuntimeState.Crashed -> Text(stringResource(R.string.runtime_crashed), color = MaterialTheme.colorScheme.error)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val running = runtime is RuntimeState.Running
                    if (running) {
                        OutlinedButton(onClick = {
                            context.stopService(Intent(context, RuntimeService::class.java))
                        }) { Text(stringResource(R.string.action_stop_service)) }
                        Button(onClick = onOpenWeb, enabled = running) { Text(stringResource(R.string.action_open_web)) }
                    } else {
                        Button(onClick = {
                            ContextCompat.startForegroundService(context, Intent(context, RuntimeService::class.java))
                        }) { Text(stringResource(R.string.action_start_service)) }
                    }
                    OutlinedButton(onClick = onOpenTerminal) { Text(stringResource(R.string.action_terminal)) }
                }
            }
        }

        // ---- 快速指南 ----
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.quick_guide_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.quick_guide_body), style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
                OutlinedButton(onClick = { openAccessibilitySettings(context as androidx.activity.ComponentActivity) }) {
                    Text(stringResource(R.string.action_open_a11y))
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onOpenSettings) { Text(stringResource(R.string.action_settings)) }
        }
    }
}
