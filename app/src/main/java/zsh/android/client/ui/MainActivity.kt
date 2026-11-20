package zsh.android.client.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import zsh.android.client.R
import zsh.android.client.ZshaApp
import zsh.android.client.service.RuntimeService

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as ZshaApp
        // 调试 / CI 钩子：am start --es zsha_action install|start_service
        when (intent?.getStringExtra("zsha_action")) {
            "install" -> {
                ContextCompat.startForegroundService(this, Intent(this, RuntimeService::class.java))
                CoroutineScope(Dispatchers.IO).launch { app.envManager.install() }
            }
            "start_service" ->
                ContextCompat.startForegroundService(this, Intent(this, RuntimeService::class.java))
        }
        setContent {
            ZshaTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Root()
                }
            }
        }
    }

    sealed interface Screen {
        data object Home : Screen
        data object Setup : Screen
        data object Terminal : Screen
        data object Settings : Screen
        data object Web : Screen
    }

    @Composable
    private fun Root() {
        var screen by remember { mutableStateOf<Screen>(Screen.Home) }
        when (screen) {
            Screen.Home -> HomeScreen(
                onOpenSetup = { screen = Screen.Setup },
                onOpenWeb = { screen = Screen.Web },
                onOpenTerminal = { screen = Screen.Terminal },
                onOpenSettings = { screen = Screen.Settings },
            )
            Screen.Setup -> SetupScreen(onBack = { screen = Screen.Home })
            Screen.Terminal -> TerminalScreen(onBack = { screen = Screen.Home })
            Screen.Settings -> SettingsScreen(onBack = { screen = Screen.Home })
            Screen.Web -> WebViewScreen(onBack = { screen = Screen.Home })
        }
    }
}

fun openAccessibilitySettings(activity: ComponentActivity) {
    activity.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
}
