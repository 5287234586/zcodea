package zsh.android.client.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import zsh.android.client.ZshaApp
import zsh.android.client.env.EnvPhase
import zsh.android.client.env.EnvStep

@Composable
fun SetupScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val app = context.applicationContext as ZshaApp
    val scope = rememberCoroutineScope()
    val phase by app.envManager.phase.collectAsState()
    val log by app.envManager.log.collectAsState()

    val failedStep = (phase as? EnvPhase.Failed)?.step
    val currentStep = (phase as? EnvPhase.Installing)?.step

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(stringResource(zsh.android.client.R.string.back)) }
            Text(stringResource(zsh.android.client.R.string.setup_title), style = MaterialTheme.typography.titleLarge)
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            EnvStep.entries.forEach { step ->
                val status = when {
                    phase is EnvPhase.Ready -> "done"
                    currentStep == step -> "running"
                    failedStep == step -> "failed"
                    phase is EnvPhase.Failed && step.ordinal < (failedStep?.ordinal ?: 0) -> "done"
                    phase is EnvPhase.Ready || (phase !is EnvPhase.Failed && currentStep != step) -> "pending"
                    else -> "pending"
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    when (status) {
                        "done" -> Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                        "running" -> Icon(Icons.Filled.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        "failed" -> Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        else -> Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.surfaceVariant,
                        )
                    }
                    Text(
                        text = when (step) {
                            EnvStep.PROOT -> stringResource(zsh.android.client.R.string.setup_step_proot)
                            EnvStep.ROOTFS -> stringResource(zsh.android.client.R.string.setup_step_rootfs)
                            EnvStep.NODE -> stringResource(zsh.android.client.R.string.setup_step_node)
                            EnvStep.ZCODE -> stringResource(zsh.android.client.R.string.setup_step_zcode)
                            EnvStep.VERIFY -> stringResource(zsh.android.client.R.string.setup_step_verify)
                        },
                        modifier = Modifier.padding(start = 10.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        if (phase is EnvPhase.Failed || phase is EnvPhase.Idle) {
            Button(onClick = { scope.launch(Dispatchers.IO) { app.envManager.install() } }) {
                Text(
                    if (phase is EnvPhase.Failed) stringResource(zsh.android.client.R.string.action_reinstall)
                    else stringResource(zsh.android.client.R.string.action_install),
                )
            }
        }
        if (phase is EnvPhase.Ready) {
            Text(stringResource(zsh.android.client.R.string.setup_done), color = MaterialTheme.colorScheme.secondary)
        }

        Text(stringResource(zsh.android.client.R.string.setup_log), style = MaterialTheme.typography.titleMedium)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .weight(1f),
        ) {
            log.forEach { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(vertical = 1.dp),
                )
            }
        }
    }
}
