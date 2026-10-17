package com.example.ui.screens.home

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.example.data.api.ApiClient

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostedPortalScreen(path: String, title: String, onExit: () -> Unit) {
    val context = LocalContext.current
    val base = ApiClient.getBaseUrl().trimEnd('/')
    val ownHost = Uri.parse(base).host
    var webView by remember { mutableStateOf<WebView?>(null) }
    var pageError by remember { mutableStateOf<String?>(null) }
    fun back() { if (webView?.canGoBack() == true) webView?.goBack() else onExit() }
    BackHandler { back() }
    DisposableEffect(Unit) { onDispose { webView?.destroy(); webView = null } }
    Scaffold(topBar = { TopAppBar(title = { Text(title) }, navigationIcon = { TextButton(onClick = { back() }) { Text("Back") } }, actions = { TextButton(onClick = { pageError = null; webView?.reload() }) { Text("Refresh") } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            pageError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            AndroidView(modifier = Modifier.fillMaxSize(), factory = {
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val uri = request?.url ?: return true
                            if (uri.scheme == "https" && uri.host == ownHost) return false
                            val paymentHost = uri.host in listOf("developerportal.ethiotelebirr.et", "superapp.ethiomobilemoney.et")
                            if (uri.scheme == "https" && paymentHost) return false
                            if (uri.scheme == "tel") { context.startActivity(Intent(Intent.ACTION_DIAL, uri)); return true }
                            return true
                        }
                        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?) {
                            if (request?.isForMainFrame == true) pageError = "Could not load this page. Check your connection and tap Refresh."
                        }
                    }
                    setDownloadListener { url, _, _, _, _ ->
                        val uri = Uri.parse(url)
                        if (uri.scheme == "https" && uri.host == ownHost) context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                    }
                    loadUrl(base + path)
                    webView = this
                }
            })
        }
    }
}
