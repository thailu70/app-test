package com.example.core.network

import java.io.IOException
import retrofit2.HttpException

/** Hides hosts and transport details while preserving actionable booking/validation messages. */
fun userFacingNetworkError(error: Throwable?): String {
    var cause = error
    while (cause != null) {
        if (cause is IOException || (cause is HttpException && cause.code() >= 500)) {
            return "Unable to connect. Please connect to the internet."
        }
        cause = cause.cause
    }

    val message = error?.message?.trim().orEmpty()
    val technical = Regex(
        "(?i)(https?://|\\bpasso\\.live\\b|\\bvps\\b|internal server error|" +
            "service unavailable|bad gateway|gateway timeout|unknown host|" +
            "connection (?:refused|reset|failed)|unable to resolve|" +
            "failed to connect|network error|timed out|timeout|\\b(?:http|status)\\s*\\d{3}\\b)"
    )
    if (message.isBlank() || technical.containsMatchIn(message)) {
        return "Unable to connect. Please connect to the internet."
    }
    return message.take(240)
}
