package com.example.core.environment

import android.content.Context
import com.example.data.api.ApiClient
import java.net.URI

/** Only the owner-selected build origin and the isolated, clearly labelled review service. */
enum class AppEnvironment {
    PRODUCTION, DEMO;

    fun storageName(productionName: String): String =
        if (this == PRODUCTION) productionName else "${productionName}_review"

    fun baseUrl(): String = if (this == DEMO) DEMO_BASE_URL else ApiClient.DEFAULT_HTTPS_URL

    companion object {
        const val DEMO_BASE_URL = "https://passo.live/review/"
        fun forBaseUrl(url: String): AppEnvironment =
            if (url.trimEnd('/') == DEMO_BASE_URL.trimEnd('/')) DEMO else PRODUCTION
    }
}

object EnvironmentPreferences {
    private const val NAME = "passo_environment"
    fun selected(context: Context): AppEnvironment =
        if (context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE).getString("selected", null) == "DEMO")
            AppEnvironment.DEMO else AppEnvironment.PRODUCTION

    fun save(context: Context, value: AppEnvironment) {
        check(context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putString("selected", value.name).commit()) { "Could not save the environment. Please try again." }
    }
}

/** Immutable scope is checked before AND after every API call, including a return to the same host. */
data class ApiSessionScope(val baseUrl: String, val generation: Long) {
    fun containsUrl(value: String): Boolean = runCatching {
        val base = URI(baseUrl)
        val candidate = URI(value)
        val basePath = base.path.trimEnd('/') + "/"
        val path = candidate.path
        fun port(uri: URI) = if (uri.port != -1) uri.port else if (uri.scheme == "https") 443 else 80
        candidate.scheme == base.scheme && candidate.host.equals(base.host, ignoreCase = true) && port(candidate) == port(base) &&
            candidate.userInfo == null && path.startsWith(basePath) && candidate.normalize().path == path &&
            // Production must never absorb the review prefix merely because it shares an origin.
            !(basePath == "/" && (path == "/review" || path.startsWith("/review/"))) &&
            !candidate.rawPath.contains("%2f", true) && !candidate.rawPath.contains("%5c", true) &&
            !candidate.rawPath.contains("%2e", true)
    }.getOrDefault(false)
}

/** Existing production capabilities keep their exact filenames and authenticated context. */
internal fun capabilityContext(namespace: String, environment: AppEnvironment): ByteArray {
    require(namespace in setOf("intercity", "mobility"))
    val original = if (namespace == "intercity") "RoutePass.Intercity.BookingAccess.v1" else "RoutePass.Mobility.PrivateAccess.v1"
    return (original + if (environment == AppEnvironment.DEMO) ".REVIEW" else "").toByteArray(Charsets.UTF_8)
}

internal fun isIsolatedReviewDescriptor(value: Map<*, *>?): Boolean = value != null &&
    value["success"] == true && value["environment"] == "REVIEW" && value["synthetic"] == true &&
    value["paymentsEnabled"] == false && value["smsEnabled"] == false
