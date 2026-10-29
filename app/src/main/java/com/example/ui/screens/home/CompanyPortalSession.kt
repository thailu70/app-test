package com.example.ui.screens.home

import android.webkit.JavascriptInterface
import com.example.core.environment.ApiSessionScope
import com.example.core.environment.AppEnvironment
import com.example.data.api.ApiClient
import java.net.URI
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

/** One company browsing session, never a native passenger/driver bearer session. */
internal class CompanyPortalSession(
    val scope: ApiSessionScope,
    private val current: () -> Boolean = { ApiClient.isCurrent(scope) }
) {
    val review = AppEnvironment.forBaseUrl(scope.baseUrl) == AppEnvironment.DEMO
    private val closed = AtomicBoolean(false)
    private var token = ""
    private var tokenRevision = 0L
    private var exports: CompanyPortalExports? = null

    fun isActive(): Boolean = !closed.get() && current()

    fun allows(url: String, method: String = "GET", mainFrame: Boolean = false): Boolean {
        if (!isActive() || method.uppercase() !in METHODS) return false
        if (!scope.containsUrl(url)) return !review && (livePaymentUrl(url) ||
            (!mainFrame && method in setOf("GET", "HEAD") && liveMapTileUrl(url)))
        return runCatching {
            val uri = URI(url)
            // These pages and APIs have ASCII paths. Never accept an encoded,
            // double-encoded, backslash or parameterized route to another scope.
            val path = uri.rawPath
            if (path.any { it == '%' || it == '\\' || it == ';' || it.code < 32 }) return false
            if (!review) return true
            if (path.startsWith("/review/api/")) return !mainFrame || method in setOf("GET", "HEAD")
            if (!path.startsWith("/review/company/")) return false
            !mainFrame || path in setOf("/review/company/", "/review/company/index.html", "/review/company/intercity.html")
        }.getOrDefault(false)
    }

    @Synchronized fun close() { closed.set(true); token = ""; tokenRevision++; exports?.close(); exports = null }

    @Synchronized fun attachExports(value: CompanyPortalExports) { check(isActive()); exports = value }
    @Synchronized fun releaseExport() { exports?.release() }
    @Synchronized fun credential(): CompanyCredential? =
        if (review && isActive() && token.isNotBlank()) CompanyCredential(token, tokenRevision) else null
    @Synchronized fun owns(value: CompanyCredential): Boolean = review && isActive() &&
        value.revision == tokenRevision && token.isNotBlank() && token == value.bearer

    // Demo DOM storage is disabled. This memory-only bridge survives navigation
    // between the two company documents, but not exit or an environment change.
    @JavascriptInterface @Synchronized fun getToken(): String = if (review && isActive()) token else ""
    @JavascriptInterface @Synchronized fun setToken(value: String): Boolean {
        if (!review || !isActive() || value.isBlank() || value.length > 8192 || value.any { it.code < 32 }) return false
        token = value
        tokenRevision++
        return true
    }
    @JavascriptInterface @Synchronized fun clearToken(): Boolean {
        token = ""
        tokenRevision++
        return review && isActive()
    }

    @JavascriptInterface fun openDocument(path: String, filename: String): Boolean {
        val owner = credential() ?: return false
        val request = CompanyExportPolicy.document(scope, path, filename) ?: return false
        return exports?.download(request, owner) == true
    }
    @JavascriptInterface fun exportCsv(csv: String): Boolean {
        val owner = credential() ?: return false
        return exports?.exportCsv(csv, owner) == true
    }

    companion object {
        const val BRIDGE_NAME = "PassoReviewSession"
        private val METHODS = setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
        fun livePaymentUrl(url: String): Boolean = trustedHttps(url)?.host in setOf("developerportal.ethiotelebirr.et", "superapp.ethiomobilemoney.et")
        fun liveMapTileUrl(url: String): Boolean = trustedHttps(url)?.let {
            it.host == "tile.openstreetmap.org" && Regex("^/[0-9]{1,2}/[0-9]{1,8}/[0-9]{1,8}\\.png$").matches(it.rawPath) && it.rawQuery == null
        } == true
        private fun trustedHttps(url: String): URI? = runCatching { URI(url).takeIf {
            it.scheme == "https" && it.userInfo == null && it.port in setOf(-1, 443) && it.normalize().path == it.path
        } }.getOrNull()
    }
}

/** The activity calls this before changing API generation, even before disposal. */
internal object CompanyPortalSessions {
    private val closers = CopyOnWriteArraySet<() -> Unit>()
    fun register(close: () -> Unit) { closers.add(close) }
    fun unregister(close: () -> Unit) { closers.remove(close) }
    fun closeAll() { closers.toList().forEach { it() } }
}
