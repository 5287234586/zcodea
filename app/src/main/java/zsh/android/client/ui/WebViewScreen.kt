package zsh.android.client.ui

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import zsh.android.client.R
import zsh.android.client.ZshaApp
import zsh.android.client.runtime.RuntimeState

@Composable
fun WebViewScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as ZshaApp
    val runtime by app.runtimeManager.state.collectAsState()
    val port = (runtime as? RuntimeState.Running)?.port ?: app.settings.bridgePort

    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()
            loadUrl("http://127.0.0.1:$port")
        }
    }

    BackHandler(enabled = true) {
        if (webView.canGoBack()) webView.goBack() else onBack()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TextButton(onClick = onBack, modifier = Modifier.padding(4.dp)) {
            Text(stringResource(R.string.back))
        }
        AndroidView(
            factory = { webView },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
