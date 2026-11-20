package zsh.android.client.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import zsh.android.client.R
import zsh.android.client.ZshaApp

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as ZshaApp
    val s = app.settings

    var npmPkg by remember { mutableStateOf(s.npmPackage) }
    var nodeVer by remember { mutableStateOf(s.nodeVersion) }
    var ubuntuVer by remember { mutableStateOf(s.ubuntuVersion) }
    var port by remember { mutableStateOf(s.bridgePort.toString()) }
    var registry by remember { mutableStateOf(s.npmRegistry) }
    var permMode by remember { mutableStateOf(s.permMode) }
    var saved by remember { mutableStateOf(false) }
    var wipeAsk by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row {
            TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleLarge)
        }

        OutlinedTextField(npmPkg, { npmPkg = it }, label = { Text(stringResource(R.string.settings_npm_pkg)) }, singleLine = true)
        OutlinedTextField(nodeVer, { nodeVer = it }, label = { Text(stringResource(R.string.settings_node_ver)) }, singleLine = true)
        OutlinedTextField(ubuntuVer, { ubuntuVer = it }, label = { Text("Ubuntu base 版本") }, singleLine = true)
        OutlinedTextField(port, { port = it }, label = { Text(stringResource(R.string.settings_port)) }, singleLine = true)
        OutlinedTextField(registry, { registry = it }, label = { Text(stringResource(R.string.settings_registry)) }, singleLine = true)
        OutlinedTextField(
            permMode, { permMode = it },
            label = { Text(stringResource(R.string.settings_perm_mode)) },
            supportingText = { Text("build / edit / plan / yolo / guarded") },
            singleLine = true,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                s.npmPackage = npmPkg.trim()
                s.nodeVersion = nodeVer.trim()
                s.ubuntuVersion = ubuntuVer.trim()
                s.bridgePort = port.trim().toIntOrNull() ?: s.bridgePort
                s.npmRegistry = registry.trim()
                s.permMode = permMode.trim()
                saved = true
            }) { Text("保存") }
            if (saved) Text(stringResource(R.string.settings_saved), color = MaterialTheme.colorScheme.secondary)
        }

        Text(stringResource(R.string.settings_danger_zone), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = { wipeAsk = true }) {
            Text(stringResource(R.string.settings_wipe), color = MaterialTheme.colorScheme.error)
        }
    }

    if (wipeAsk) {
        AlertDialog(
            onDismissRequest = { wipeAsk = false },
            title = { Text(stringResource(R.string.settings_wipe)) },
            text = { Text(stringResource(R.string.settings_wipe_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    wipeAsk = false
                    app.envManager.paths.root.deleteRecursively()
                }) { Text(stringResource(R.string.ok), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { wipeAsk = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}
