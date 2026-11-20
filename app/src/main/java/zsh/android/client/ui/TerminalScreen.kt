package zsh.android.client.ui

import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import zsh.android.client.R
import zsh.android.client.ZshaApp

/** 真 PTY 终端：Termux terminal-emulator（JNI 伪终端），vim / htop / zcode TUI 可用。 */
@Composable
fun TerminalScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as ZshaApp

    val terminalView = remember {
        TerminalView(context, null).also { view ->
            view.setTerminalViewClient(object : TerminalViewClient {
                override fun onScale(scale: Float): Float = scale
                override fun onSingleTapUp(e: MotionEvent) {}
                override fun shouldBackButtonBeMappedToEscape(): Boolean = true
                override fun shouldEnforceCharBasedInput(): Boolean = true
                override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
                override fun isTerminalViewSelected(): Boolean = true
                override fun copyModeChanged(copyMode: Boolean) {}
                override fun onKeyDown(
                    keyCode: Int,
                    e: KeyEvent,
                    session: TerminalSession,
                ): Boolean = false
                override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
                override fun onLongPress(e: MotionEvent): Boolean = false
                override fun readControlKey(): Boolean = false
                override fun readAltKey(): Boolean = false
                override fun readShiftKey(): Boolean = false
                override fun readFnKey(): Boolean = false
                override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false
                override fun onEmulatorSet() {}
                override fun logError(tag: String, message: String) {}
                override fun logWarn(tag: String, message: String) {}
                override fun logInfo(tag: String, message: String) {}
                override fun logDebug(tag: String, message: String) {}
                override fun logVerbose(tag: String, message: String) {}
                override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {}
                override fun logStackTrace(tag: String, e: Exception) {}
            })
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
            Text(stringResource(R.string.terminal_title), style = MaterialTheme.typography.titleLarge)
        }

        // 终端视图（占满剩余空间）
        AndroidView(
            factory = { _ ->
                val started = app.runtimeManager.startPtyTerminal()
                val session = app.runtimeManager.ptySession
                if (started && session != null) {
                    terminalView.attachSession(session)
                }
                terminalView
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )

        // 快捷键行（Ctrl / Esc / Tab / 方向 / 管道常用符）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf(
                "Ctrl" to byteArrayOf(0x03),
                "Esc" to byteArrayOf(0x1B),
                "Tab" to byteArrayOf(0x09),
                "↑" to byteArrayOf(0x1B, 0x5B, 0x41),
                "↓" to byteArrayOf(0x1B, 0x5B, 0x42),
                "←" to byteArrayOf(0x1B, 0x5B, 0x44),
                "→" to byteArrayOf(0x1B, 0x5B, 0x43),
                "|" to "|".toByteArray(),
                "~" to "~".toByteArray(),
                "-" to "-".toByteArray(),
            ).forEach { (label, bytes) ->
                OutlinedButton(onClick = { app.runtimeManager.ptyWrite(bytes) }) {
                    Text(label)
                }
            }
        }
    }
}
