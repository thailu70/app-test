package com.example.core.notifications

import android.content.Intent
import com.example.core.environment.AppEnvironment

/** Push carries references only. Private details are loaded through the existing authenticated APIs. */
data class MobilityPushPayload(val requestId: String, val eventType: String, val scope: String, val kind: String) {
    companion object {
        const val EXTRA_ID = "passo_push_request_id"
        const val EXTRA_EVENT = "passo_push_event_type"
        const val EXTRA_SCOPE = "passo_push_scope"
        const val EXTRA_KIND = "passo_push_kind"
        private val keys = setOf("requestId", "eventType", "scope", "kind")
        private val events = setOf("NEW_REQUEST", "SELECTED", "QUOTES", "STATUS_ASSIGNED", "STATUS_AT_PICKUP", "STATUS_LOADED",
            "STATUS_IN_TRANSIT", "STATUS_AT_DESTINATION", "STATUS_AWAITING_CONFIRMATION", "STATUS_COMPLETED", "STATUS_DISPUTED", "STATUS_CANCELLED")
        fun parse(data: Map<String, String>): MobilityPushPayload? {
            if (data.keys != keys) return null
            val id = data["requestId"] ?: return null
            if (!Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,149}").matches(id)) return null
            val event = data["eventType"]?.takeIf { it in events } ?: return null
            val scope = data["scope"]?.takeIf { it in setOf("DRIVER", "REQUEST") } ?: return null
            val kind = data["kind"]?.takeIf { it in setOf("CARGO", "GROUP") } ?: return null
            return MobilityPushPayload(id, event, scope, kind)
        }
        fun fromIntent(intent: Intent): MobilityPushPayload? {
            if (intent.data != null) return null
            return parse(mapOf("requestId" to intent.getStringExtra(EXTRA_ID).orEmpty(),
                "eventType" to intent.getStringExtra(EXTRA_EVENT).orEmpty(), "scope" to intent.getStringExtra(EXTRA_SCOPE).orEmpty(),
                "kind" to intent.getStringExtra(EXTRA_KIND).orEmpty()))
        }
    }
}

internal fun mobilityPushAllowed(environment: AppEnvironment, notificationsGranted: Boolean, firebaseConfigured: Boolean): Boolean =
    environment == AppEnvironment.PRODUCTION && notificationsGranted && firebaseConfigured

internal fun MobilityPushPayload.notificationCopy(): Pair<String, String> = when (eventType) {
    "NEW_REQUEST" -> "New transport request" to "A transport request is available near your last known location. Open Passo to review it and refresh your location."
    "QUOTES" -> "Driver offers received" to "Drivers have quoted for your request. Open Passo to compare offers."
    "SELECTED", "STATUS_ASSIGNED" -> "Transport booking updated" to "A driver has been selected. Open Passo for the booking details."
    "STATUS_AT_PICKUP" -> "Your driver has arrived" to "Your driver is at the pickup point. Open Passo for details."
    "STATUS_LOADED", "STATUS_IN_TRANSIT" -> "Your journey has started" to "Your transport is underway. Open Passo to follow the request."
    "STATUS_AT_DESTINATION", "STATUS_AWAITING_CONFIRMATION" -> "Delivery or journey arrived" to "Your driver has arrived. Open Passo to check and confirm completion."
    "STATUS_COMPLETED" -> "Transport service completed" to "Your service is complete. Open Passo to view details or leave a review."
    else -> "Passo request updated" to "Your transport request has an update. Open Passo to view it."
}
