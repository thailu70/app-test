package com.example

import com.example.core.notifications.MobilityPushPayload
import com.example.core.notifications.savedRequestNotificationAllowed
import org.junit.Assert.*
import org.junit.Test

class MobilityNotificationNavigationTest {
    private val payload = MobilityPushPayload("request-1", "QUOTES", "REQUEST", "CARGO")

    @Test fun `unknown device request and invalid scope cannot open private request details`() {
        assertFalse(savedRequestNotificationAllowed(payload, emptySet(), "request-1", "CARGO"))
        assertFalse(savedRequestNotificationAllowed(payload.copy(scope = "DRIVER"), setOf("request:request-1"), "request-1", "CARGO"))
        assertFalse(savedRequestNotificationAllowed(payload, setOf("request:request-1"), "request-other", "CARGO"))
        assertFalse(savedRequestNotificationAllowed(payload, setOf("request:request-1"), "request-1", "GROUP"))
    }

    @Test fun `known guest capability and matching server kind are sufficient without an account`() {
        assertTrue(savedRequestNotificationAllowed(payload, setOf("request:request-1"), "request-1", "CARGO"))
        assertNull(MobilityPushPayload.parse(mapOf("requestId" to "request-1", "eventType" to "QUOTES", "scope" to "ADMIN", "kind" to "CARGO")))
    }
}
