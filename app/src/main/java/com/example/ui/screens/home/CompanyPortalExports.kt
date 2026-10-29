package com.example.ui.screens.home

import com.example.core.environment.ApiSessionScope
import com.example.core.environment.AppEnvironment
import com.example.data.api.ApiClient
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal data class CompanyCredential(val bearer: String, val revision: Long)
internal data class CompanyDocumentRequest(val url: String, val basename: String, val useBearer: Boolean)
internal data class CompanyExport(val filename: String, val mime: String, val bytes: ByteArray, val credential: CompanyCredential)

internal object CompanyExportPolicy {
    const val DOCUMENT_LIMIT = 5 * 1024 * 1024
    const val CSV_LIMIT = 2 * 1024 * 1024
    private val document = Regex("^/review/api/onboarding/applications/DRIVER/[A-Za-z0-9_-]{1,128}/documents/(LICENSE|VEHICLE_REGISTRATION|INSURANCE|PHOTO)$")
    private val ownedTicket = Regex("^/review/api/intercity/company/bookings/[A-Za-z0-9_-]{1,128}/ticket\\.pdf$")
    private val ticket = Regex("^/review/api/intercity/tickets/[A-Za-z0-9_-]{1,128}\\.pdf$")
    private val capability = Regex("^token=[A-Za-z0-9_.-]{1,4096}$")
    private val filename = Regex("^[A-Za-z0-9][A-Za-z0-9._ -]{0,79}$")

    fun document(scope: ApiSessionScope, path: String, name: String): CompanyDocumentRequest? = runCatching {
        if (AppEnvironment.forBaseUrl(scope.baseUrl) != AppEnvironment.DEMO || !filename.matches(name) || ".." in name) return null
        // A JS caller supplies a root-relative path, never an arbitrary origin.
        if (!path.startsWith("/review/api/") || path.length > 4400) return null
        val parsed = URI(path)
        if (parsed.isAbsolute || parsed.rawFragment != null || parsed.rawPath.any { it == '%' || it == '\\' || it == ';' }) return null
        val authenticated = (document.matches(parsed.rawPath) || ownedTicket.matches(parsed.rawPath)) && parsed.rawQuery == null
        val capped = ticket.matches(parsed.rawPath) && capability.matches(parsed.rawQuery.orEmpty())
        if (!authenticated && !capped) return null
        val url = "https://passo.live$path"
        if (!scope.containsUrl(url)) return null
        CompanyDocumentRequest(url, name.substringBeforeLast('.', name), authenticated)
    }.getOrNull()

    fun documentType(mime: String, bytes: ByteArray): String? {
        if (bytes.isEmpty() || bytes.size > DOCUMENT_LIMIT) return null
        fun starts(vararg values: Int) = bytes.size >= values.size && values.indices.all { (bytes[it].toInt() and 255) == values[it] }
        return when (mime.lowercase()) {
            "application/pdf" -> if (starts(37, 80, 68, 70, 45)) ".pdf" else null
            "image/png" -> if (starts(137, 80, 78, 71, 13, 10, 26, 10)) ".png" else null
            "image/jpeg" -> if (starts(255, 216, 255)) ".jpg" else null
            else -> null
        }
    }

    fun csv(value: String, credential: CompanyCredential): CompanyExport? {
        if (value.isEmpty() || value.length > CSV_LIMIT || '\u0000' in value || value.contains(credential.bearer)) return null
        val bytes = value.toByteArray(Charsets.UTF_8)
        if (bytes.size > CSV_LIMIT) return null
        return CompanyExport("PASSO-synthetic-demo-intercity-report.csv", "text/csv", bytes, credential)
    }
}

/** User-initiated downloads only; the system Save dialog chooses the destination. */
internal class CompanyPortalExports(
    private val session: CompanyPortalSession,
    private val onReady: (CompanyExport) -> Unit,
    private val onFailure: () -> Unit,
    private val transport: OkHttpClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build(),
    private val verify: () -> Unit = { ApiClient.verifyPortalEnvironment(session.scope) }
) {
    private val jobs = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val busy = AtomicBoolean(false)

    fun download(request: CompanyDocumentRequest, credential: CompanyCredential): Boolean {
        if (!session.owns(credential) || !busy.compareAndSet(false, true)) return false
        jobs.launch {
            var offered = false
            try {
                verify()
                if (!session.owns(credential)) return@launch
                val response = transport.newCall(Request.Builder().url(request.url)
                    .header("Accept", "application/pdf, image/png, image/jpeg").header("Cache-Control", "no-store").apply {
                        if (request.useBearer) header("Authorization", "Bearer ${credential.bearer}")
                    }.build()).execute()
                response.use {
                    check(response.isSuccessful && !response.isRedirect && response.header("X-Passo-Environment") == "REVIEW")
                    val body = checkNotNull(response.body)
                    check(body.contentLength() <= CompanyExportPolicy.DOCUMENT_LIMIT)
                    val bytes = body.byteStream().use { stream ->
                        val output = ByteArrayOutputStream()
                        val chunk = ByteArray(8192)
                        while (output.size() <= CompanyExportPolicy.DOCUMENT_LIMIT) {
                            val read = stream.read(chunk)
                            if (read < 0) break
                            output.write(chunk, 0, read)
                        }
                        output.toByteArray()
                    }
                    val mime = body.contentType()?.let { type -> "${type.type}/${type.subtype}" }.orEmpty()
                    val extension = CompanyExportPolicy.documentType(mime, bytes)
                    if (extension == null || !session.owns(credential)) { bytes.fill(0); error("Unavailable document") }
                    onReady(CompanyExport(request.basename + extension, mime, bytes, credential))
                    offered = true
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (session.owns(credential)) onFailure() }
            finally { if (!offered) busy.set(false) }
        }
        return true
    }

    fun exportCsv(value: String, credential: CompanyCredential): Boolean {
        if (!session.owns(credential) || !busy.compareAndSet(false, true)) return false
        var offered = false
        return try {
            val export = CompanyExportPolicy.csv(value, credential) ?: return false
            if (!session.owns(credential)) { export.bytes.fill(0); return false }
            onReady(export)
            offered = true
            true
        } finally { if (!offered) busy.set(false) }
    }

    fun release() { busy.set(false) }
    fun close() { jobs.cancel(); busy.set(false); transport.dispatcher.cancelAll(); transport.connectionPool.evictAll() }
}
