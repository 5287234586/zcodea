package zsh.android.client.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkColors = darkColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFF4F8FF7),
    secondary = androidx.compose.ui.graphics.Color(0xFF2EA36B),
    background = androidx.compose.ui.graphics.Color(0xFF0B1220),
    surface = androidx.compose.ui.graphics.Color(0xFF111A2C),
)

private val LightColors = lightColorScheme()

@Composable
fun ZshaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
