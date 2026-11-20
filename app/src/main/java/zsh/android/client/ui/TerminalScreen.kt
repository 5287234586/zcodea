package zsh.android.client.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import zsh.android.client.R
import zsh.android.client.ZshaApp
import zsh.android.client.runtime.AnsiStrip

@Composable
fun TerminalScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as ZshaApp

    var bufferVersion by remember { mutableIntStateOf(0) }
    var started by remember { mutableStateOf(app.runtimeManager.terminalRunning) }
    var input by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        while (true) {
            delay(400)
            bufferVersion++
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
            Text(stringResource(R.string.terminal_title), style = MaterialTheme.typography.titleLarge)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (started) {
                OutlinedButton(onClick = {
                    app.runtimeManager.stopTerminal(); started = false
                }) { Text(stringResource(R.string.terminal_stop)) }
            } else {
                OutlinedButton(onClick = {
                    started = app.runtimeManager.startTerminal()
                }) { Text(stringResource(R.string.terminal_start)) }
            }
        }

        if (!started) {
            Text(stringResource(R.string.terminal_not_started), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            // 读取缓冲（key 变化触发重组）
            val lines = remember(bufferVersion) { app.runtimeManager.termBuffer() }
            Text(
                AnsiStrip.strip(lines.joinToString("\n")),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.terminal_hint), style = MaterialTheme.typography.bodySmall) },
                singleLine = true,
            )
            OutlinedButton(onClick = {
                if (input.isNotBlank()) {
                    app.runtimeManager.terminalInput(input)
                    input = ""
                }
            }) { Text(stringResource(R.string.send)) }
        }
    }
}
