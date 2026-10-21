package com.example

import com.example.data.api.ApiClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class ApiBaseUrlTest {
    private val originalBase = ApiClient.getBaseUrl()

    @After fun restoreConfiguredHost() { ApiClient.setBaseUrl(originalBase) }

    @Test fun `API and portal URLs follow the build selected deployment host`() {
        assertEquals(BuildConfig.ROUTEPASS_API_BASE_URL, ApiClient.DEFAULT_HTTPS_URL)
        ApiClient.setBaseUrl(BuildConfig.ROUTEPASS_API_BASE_URL)
        val base = BuildConfig.ROUTEPASS_API_BASE_URL.trimEnd('/')
        assertEquals("$base/admin/", ApiClient.portalUrl("admin/"))
        assertEquals("$base/company/", ApiClient.portalUrl("company/"))
        assertEquals("$base/", ApiClient.portalUrl())
    }

    @Test fun `custom deployment URLs preserve their path for portals and map content`() {
        for (base in listOf("https://staging.example/transit", "https://staging.example/transit/")) {
            ApiClient.setBaseUrl(base)
            assertEquals("https://staging.example/transit/admin/", ApiClient.portalUrl("/admin/"))
            assertEquals("https://staging.example/transit/company/", ApiClient.portalUrl("company/"))
            assertEquals("https://staging.example/transit/", ApiClient.portalUrl())
        }
    }
}
