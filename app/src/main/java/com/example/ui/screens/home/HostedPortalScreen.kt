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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.example.data.api.ApiClient
import com.example.core.environment.AppEnvironment
import androidx.compose.ui.unit.dp

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostedPortalScreen(path: String, title: String, onExit: () -> Unit) {
    // A URL prefix cannot isolate browser localStorage for the same origin.
    // Review accounts use the native driver/passenger screens only.
    if (ApiClient.environment() == AppEnvironment.DEMO) {
        BackHandler(onBack = onExit)
        Surface(Modifier.fillMaxSize(), color = Color(0xFFFFFBF4)) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Company portal", style = MaterialTheme.typography.headlineSmall)
                Text("Company administration is unavailable in demo. Use the supplied company-driver demo account in Driver sign-in to review assigned intercity journeys.")
                TextButton(onClick = onExit) { Text("Back") }
            }
        }
        return
    }
    val context = LocalContext.current
    val base = ApiClient.getBaseUrl().trimEnd('/')
    val scope = ApiClient.sessionScope()
    var webView by remember { mutableStateOf<WebView?>(null) }
    var pageError by remember { mutableStateOf<String?>(null) }
    fun back() { if (webView?.canGoBack() == true) webView?.goBack() else onExit() }
    BackHandler { back() }
    DisposableEffect(Unit) { onDispose { webView?.destroy(); webView = null } }
    Scaffold(topBar = {
        TopAppBar(
            title = { Column { Text("PASSO", fontWeight = androidx.compose.ui.text.font.FontWeight.Black); Text(title, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = .82f)) } },
            navigationIcon = { TextButton(onClick = { back() }, colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) { Text("Back") } },
            actions = { TextButton(onClick = { pageError = null; webView?.reload() }, colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) { Text("Refresh") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF142D55), titleContentColor = Color.White)
        )
    }) { padding ->
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
                            if (scope.containsUrl(uri.toString())) return false
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
                        if (scope.containsUrl(uri.toString())) context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                    }
                    loadUrl(base + path)
                    webView = this
                }
            })
        }
    }
}
