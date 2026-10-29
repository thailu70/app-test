package com.example.ui.screens.home

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.example.data.api.ApiClient
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/** All resource loads are fenced, including fetch/XHR, not just main-frame links. */
internal class CompanyPortalWebClient(
    private val session: CompanyPortalSession,
    private val onPageError: () -> Unit,
    private val onDial: (String) -> Unit = {},
    private val transport: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build(),
    private val verify: () -> Unit = { ApiClient.verifyPortalEnvironment(session.scope) }
) : WebViewClient() {

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        if (request != null && session.isActive() && request.url.scheme == "tel") {
            onDial(request.url.toString())
            return true
        }
        return request == null || !session.allows(request.url.toString(), request.method, mainFrame = true)
    }

    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        if (request == null || !session.allows(request.url.toString(), request.method, request.isForMainFrame)) return blocked()
        return try {
            verify()
            if (!session.isActive()) return blocked()
            if (!session.review) {
                // Preserve the existing live company's browser session and
                // payment-provider cookies/redirects. Demo never takes this path.
                if (session.scope.containsUrl(request.url.toString())) return null
                if (CompanyPortalSession.livePaymentUrl(request.url.toString()) &&
                    (request.method !in setOf("GET", "HEAD") || request.requestHeaders.keys.none { it.equals("Authorization", true) })) return null
            }
            // Chromium owns mutation bodies; the company's scoped fetch adapter
            // also uses credentials:omit and redirect:error for these requests.
            if (request.method !in setOf("GET", "HEAD")) return null
            val call = transport.newCall(Request.Builder().url(request.url.toString()).apply {
                request.requestHeaders.forEach { (name, value) ->
                    if (name.lowercase() !in REQUEST_HEADERS_TO_DROP &&
                        (session.scope.containsUrl(request.url.toString()) || !name.equals("Authorization", true))) header(name, value)
                }
                header("Cache-Control", "no-store")
                method(request.method, null)
            }.build())
            call.execute().use { response ->
                if (!session.isActive() || response.isRedirect ||
                    (session.review && response.header("X-Passo-Environment") != "REVIEW")) return blocked()
                val body = response.body
                if ((body?.contentLength() ?: 0L) > MAX_RESOURCE_BYTES) throw IOException("Resource too large")
                val bytes = body?.byteStream()?.let { stream ->
                    val output = ByteArrayOutputStream()
                    val chunk = ByteArray(8192)
                    while (output.size() <= MAX_RESOURCE_BYTES) {
                        val read = stream.read(chunk)
                        if (read < 0) break
                        output.write(chunk, 0, read)
                    }
                    output.toByteArray()
                } ?: byteArrayOf()
                if (bytes.size > MAX_RESOURCE_BYTES || !session.isActive()) return blocked()
                val type = body?.contentType()
                val headers = response.headers.names().filter { it.lowercase() !in RESPONSE_HEADERS_TO_DROP }
                    .associateWith { response.header(it).orEmpty() } + ("Cache-Control" to "no-store")
                WebResourceResponse(
                    type?.let { "${it.type}/${it.subtype}" } ?: "application/octet-stream",
                    type?.charset()?.name() ?: "UTF-8", response.code,
                    response.message.ifBlank { "Response" }, headers, ByteArrayInputStream(bytes)
                )
            }
        } catch (_: Exception) {
            if (request.isForMainFrame) onPageError()
            blocked()
        }
    }

    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?) {
        if (request?.isForMainFrame == true && session.isActive()) onPageError()
    }

    fun close() {
        session.close()
        transport.dispatcher.cancelAll()
        transport.connectionPool.evictAll()
    }

    companion object {
        private const val MAX_RESOURCE_BYTES = 16 * 1024 * 1024
        private val REQUEST_HEADERS_TO_DROP = setOf("cookie", "host", "origin", "referer", "accept-encoding", "if-none-match", "if-modified-since")
        private val RESPONSE_HEADERS_TO_DROP = setOf("set-cookie", "set-cookie2", "location", "content-length", "content-encoding")
        fun blocked() = WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", mapOf("Cache-Control" to "no-store"),
            ByteArrayInputStream("This page is unavailable in the selected environment.".toByteArray()))
    }
}
