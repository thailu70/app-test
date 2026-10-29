package com.example.ui.screens.home

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.example.data.api.ApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostedPortalScreen(path: String, title: String, onExit: () -> Unit) {
    val context = LocalContext.current
    val scope = remember { ApiClient.sessionScope() }
    val session = remember(scope) { CompanyPortalSession(scope) }
    val url = remember(scope, path) { ApiClient.portalUrl(path) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var pageError by remember { mutableStateOf<String?>(null) }
    var pendingExport by remember { mutableStateOf<CompanyExport?>(null) }
    var previousCookies by remember { mutableStateOf<Boolean?>(null) }
    var previousWorkerSettings by remember { mutableStateOf<Triple<Boolean, Boolean, Boolean>?>(null) }
    val saving = rememberCoroutineScope()
    val main = remember { Handler(Looper.getMainLooper()) }
    val saveDocument = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val export = pendingExport
        pendingExport = null
        val uri = result.data?.data?.takeIf { result.resultCode == android.app.Activity.RESULT_OK }
        if (export != null && uri != null && session.owns(export.credential)) saving.launch(Dispatchers.IO) {
            try {
                if (!session.owns(export.credential)) return@launch
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    var offset = 0
                    while (offset < export.bytes.size) {
                        check(session.owns(export.credential))
                        val count = minOf(8192, export.bytes.size - offset)
                        output.write(export.bytes, offset, count)
                        offset += count
                    }
                } ?: error("Could not open the selected file")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                main.post { if (session.isActive()) pageError = "Could not save this file. Please try again." }
            } finally { export.bytes.fill(0); session.releaseExport() }
        } else { export?.bytes?.fill(0); session.releaseExport() }
    }
    val exports = remember(session) { CompanyPortalExports(session,
        onReady = { export -> main.post {
            if (!session.owns(export.credential) || pendingExport != null) { export.bytes.fill(0); session.releaseExport() }
            else {
                pendingExport = export
                try {
                    saveDocument.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType(export.mime).putExtra(Intent.EXTRA_TITLE, export.filename))
                } catch (_: Exception) {
                    pendingExport = null; export.bytes.fill(0)
                    session.releaseExport()
                    pageError = "Could not open Save file. Please try again."
                }
            }
        } },
        onFailure = { main.post { if (session.isActive()) pageError = "Could not download this document. Refresh and try again." } }
    ).also { session.attachExports(it) } }
    val client = remember(session) {
        CompanyPortalWebClient(session,
            onPageError = { webView?.post { if (session.isActive()) pageError = "Could not load this page. Check your connection and tap Refresh." } },
            onDial = { value -> context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse(value))) })
    }
    val closeSession: () -> Unit = remember(session) { {
        client.close()
        exports.close()
        pendingExport?.bytes?.fill(0)
        pendingExport = null
        webView?.apply {
            stopLoading()
            removeJavascriptInterface(CompanyPortalSession.BRIDGE_NAME)
            onPause()
            clearHistory()
            removeAllViews()
            destroy()
        }
        webView = null
        previousCookies?.let { CookieManager.getInstance().setAcceptCookie(it) }
        previousCookies = null
        previousWorkerSettings?.let { previous -> ServiceWorkerController.getInstance().apply {
            serviceWorkerWebSettings.blockNetworkLoads = previous.first
            serviceWorkerWebSettings.allowFileAccess = previous.second
            serviceWorkerWebSettings.allowContentAccess = previous.third
            setServiceWorkerClient(null)
        } }
        previousWorkerSettings = null
    } }
    fun back() { if (webView?.canGoBack() == true) webView?.goBack() else onExit() }
    BackHandler { back() }
    DisposableEffect(session) {
        CompanyPortalSessions.register(closeSession)
        onDispose { CompanyPortalSessions.unregister(closeSession); closeSession() }
    }
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
                    // Live retains its original sessionStorage. Demo cannot read
                    // ANY browser storage; its company bearer lives in memory.
                    settings.domStorageEnabled = !session.review
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    settings.setSupportMultipleWindows(false)
                    settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
                    settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    @Suppress("DEPRECATION")
                    settings.saveFormData = false
                    // Demo never reads, erases, copies or accepts browser cookies.
                    // Live retains its original company/payment cookie behavior.
                    if (session.review) {
                        val cookies = CookieManager.getInstance()
                        previousCookies = cookies.acceptCookie()
                        cookies.setAcceptCookie(false)
                        cookies.setAcceptThirdPartyCookies(this, false)
                        ServiceWorkerController.getInstance().apply {
                            previousWorkerSettings = Triple(serviceWorkerWebSettings.blockNetworkLoads,
                                serviceWorkerWebSettings.allowFileAccess, serviceWorkerWebSettings.allowContentAccess)
                            serviceWorkerWebSettings.blockNetworkLoads = true
                            serviceWorkerWebSettings.allowFileAccess = false
                            serviceWorkerWebSettings.allowContentAccess = false
                            setServiceWorkerClient(object : ServiceWorkerClient() {
                                override fun shouldInterceptRequest(request: WebResourceRequest?): WebResourceResponse = CompanyPortalWebClient.blocked()
                            })
                        }
                    }
                    if (session.review) addJavascriptInterface(session, CompanyPortalSession.BRIDGE_NAME)
                    webViewClient = client
                    setDownloadListener { value, _, _, _, _ ->
                        if (session.allows(value, mainFrame = true)) context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(value)))
                    }
                    webView = this
                    if (session.allows(url, mainFrame = true)) loadUrl(url)
                }
            })
        }
    }
}
