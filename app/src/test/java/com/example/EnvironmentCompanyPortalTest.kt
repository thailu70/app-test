package com.example

import android.net.Uri
import android.webkit.WebResourceRequest
import com.example.core.environment.ApiSessionScope
import com.example.core.environment.AppEnvironment
import com.example.data.api.ApiClient
import com.example.ui.screens.home.CompanyPortalSession
import com.example.ui.screens.home.CompanyPortalSessions
import com.example.ui.screens.home.CompanyPortalWebClient
import com.example.ui.screens.home.CompanyCredential
import com.example.ui.screens.home.CompanyExport
import com.example.ui.screens.home.CompanyExportPolicy
import com.example.ui.screens.home.CompanyPortalExports
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EnvironmentCompanyPortalTest {
    private val originalBase = ApiClient.getBaseUrl()
    private val base = AppEnvironment.DEMO_BASE_URL
    @After fun restore() { CompanyPortalSessions.closeAll(); ApiClient.setAuthToken(null); ApiClient.setBaseUrl(originalBase) }

    @Test fun `company documents resources and private API URLs stay inside the selected environment`() {
        val demo = CompanyPortalSession(ApiSessionScope(base, 1)) { true }
        assertTrue(demo.allows(base + "company/", mainFrame = true))
        assertTrue(demo.allows(base + "company/intercity.html#roster", mainFrame = true))
        assertTrue(demo.allows(base + "company/review-session.js?v=1"))
        assertTrue(demo.allows(base + "api/companies/vehicles", "POST"))
        assertTrue(demo.allows(base + "api/intercity/company/bookings/demo/ticket?token=a%2Fb%2Bz", mainFrame = true))
        for (url in listOf(
            "https://passo.live/company/", "https://passo.live/api/companies/login",
            "https://other.example/review/company/", "https://passo.live:444/review/company/",
            "https://passo.live@other.example/review/company/", "http://passo.live/review/company/",
            base + "../api/companies/login", base + "company/../../api/companies/login",
            base + "%2e%2e/api/companies/login", base + "company/%252e%252e/api/companies/login",
            base + "company/%2fapi/companies/login", base + "company/%255capi/companies/login",
            base + "company/;other/page", base + "admin/", base + "shared/applications.js",
            base + "join/", "javascript:alert(1)", "file:///data/private", "data:text/html,private"
        )) assertFalse(url, demo.allows(url))
        assertFalse(demo.allows(base + "company/company.js", mainFrame = true))
        assertFalse(demo.allows(base + "api/companies/login", "POST", mainFrame = true))
        // A URL in a query is data, not permission to navigate to that URL.
        assertTrue(demo.allows(base + "company/?next=https%3A%2F%2Fpasso.live%2Fcompany%2F", mainFrame = true))
        assertFalse(demo.allows("https://passo.live/company/?next=/review/company/", mainFrame = true))
        val live = CompanyPortalSession(ApiSessionScope("https://passo.live/", 2)) { true }
        assertTrue(live.allows("https://passo.live/company/", mainFrame = true))
        assertTrue(live.allows("https://passo.live/shared/fleet-map.js"))
        assertFalse(live.allows(base + "company/", mainFrame = true))
        assertFalse(live.allows(base + "api/companies/login", "POST"))
        val tile = "https://tile.openstreetmap.org/12/2487/1940.png"
        assertTrue(live.allows(tile))
        assertFalse(live.allows(tile, mainFrame = true))
        assertFalse(live.allows(tile, "POST"))
        assertFalse(demo.allows(tile))
        for (host in listOf("developerportal.ethiotelebirr.et", "superapp.ethiomobilemoney.et")) {
            assertTrue(live.allows("https://$host/checkout", mainFrame = true))
            assertTrue(live.allows("https://$host/checkout", "POST", mainFrame = true))
            assertFalse(demo.allows("https://$host/checkout", mainFrame = true))
            assertFalse(live.allows("https://$host.attacker.example/checkout", mainFrame = true))
        }
    }

    @Test fun `company bridge holds only a demo bearer and clears before switching even back to same origin`() {
        ApiClient.setBaseUrl(base)
        val demo = CompanyPortalSession(ApiClient.sessionScope())
        assertTrue(demo.setToken("synthetic-company-bearer"))
        assertEquals("synthetic-company-bearer", demo.getToken())
        assertNull(ApiClient.getAuthToken())
        assertFalse(demo.setToken("bad\ntoken"))
        assertFalse(demo.setToken("x".repeat(8193)))
        val close: () -> Unit = { demo.close() }
        CompanyPortalSessions.register(close)
        try {
            CompanyPortalSessions.closeAll()
            assertEquals("", demo.getToken())
            assertFalse(demo.setToken("late-company-bearer"))
            assertFalse(demo.allows(base + "company/", mainFrame = true))
            ApiClient.setBaseUrl(ApiClient.DEFAULT_HTTPS_URL)
            val live = CompanyPortalSession(ApiClient.sessionScope())
            assertFalse(live.setToken("cannot-install-review-bridge-in-live"))
            assertEquals("", live.getToken())
            ApiClient.setBaseUrl(base)
            assertFalse(demo.isActive())
            assertEquals("", CompanyPortalSession(ApiClient.sessionScope()).getToken())
        } finally { CompanyPortalSessions.unregister(close) }
    }

    @Test fun `private resource proxy preserves only the scoped JS bearer and never uses cookies or native auth`() {
        ApiClient.setBaseUrl(base)
        ApiClient.setAuthToken("native-passenger-bearer-must-not-be-used")
        val session = CompanyPortalSession(ApiClient.sessionScope())
        var observed: okhttp3.Request? = null
        var handshakes = 0
        val client = CompanyPortalWebClient(session, {}, transport = transport { request ->
            observed = request
            response(request).newBuilder().header("Set-Cookie", "live=must-not-store").build()
        }, verify = { handshakes++ })
        try {
            val result = client.shouldInterceptRequest(null, resource(base + "api/companies/profile-photo", headers = mapOf(
                "Authorization" to "Bearer synthetic-company-bearer", "Cookie" to "live=must-not-send",
                "Referer" to "https://passo.live/company/", "Accept-Encoding" to "gzip")))!!
            assertEquals(200, result.statusCode)
            assertEquals(1, handshakes)
            assertEquals("Bearer synthetic-company-bearer", observed!!.header("Authorization"))
            assertNull(observed!!.header("Cookie"))
            assertNull(observed!!.header("Referer"))
            assertFalse(result.responseHeaders.keys.any { it.equals("Set-Cookie", true) })
            assertEquals("synthetic resource", result.data.bufferedReader().readText())
            observed = null
            assertEquals(403, client.shouldInterceptRequest(null, resource("https://passo.live/api/companies/profile-photo"))!!.statusCode)
            assertNull(observed)
        } finally { client.close() }
    }

    @Test fun `redirects missing review markers and stale responses cannot fall through to Chromium`() {
        for (mode in listOf("redirect", "missing-marker", "stale", "handshake-failed")) {
            var current = true
            val session = CompanyPortalSession(ApiSessionScope(base, 3)) { current }
            var calls = 0
            val client = CompanyPortalWebClient(session, {}, transport = transport { request ->
                calls++
                if (mode == "stale") current = false
                response(request).newBuilder().apply {
                    if (mode == "redirect") code(302).header("Location", "https://passo.live/company/")
                    if (mode == "missing-marker") removeHeader("X-Passo-Environment")
                }.build()
            }, verify = { if (mode == "handshake-failed") throw java.io.IOException("Unavailable") })
            try {
                assertEquals(mode, 403, client.shouldInterceptRequest(null, resource(base + "company/", main = true))!!.statusCode)
                assertEquals(mode, if (mode == "handshake-failed") 0 else 1, calls)
            } finally { client.close() }
        }
    }

    @Test fun `mutations preserve the WebView request body only inside verified current API scope`() {
        var verified = 0
        val session = CompanyPortalSession(ApiSessionScope(base, 4)) { true }
        val client = CompanyPortalWebClient(session, {}, transport = transport { error("Mutation body must stay with Chromium") }, verify = { verified++ })
        try {
            assertNull(client.shouldInterceptRequest(null, resource(base + "api/companies/login", method = "POST")))
            assertEquals(1, verified)
            assertEquals(403, client.shouldInterceptRequest(null, resource("https://passo.live/api/companies/login", method = "POST"))!!.statusCode)
            assertEquals(1, verified)
            client.close()
            assertEquals(403, client.shouldInterceptRequest(null, resource(base + "api/companies/login", method = "POST"))!!.statusCode)
        } finally { client.close() }
    }

    @Test fun `live tiles strip bearer and cookies while existing company and payment browsing stays native to Chromium`() {
        val live = CompanyPortalSession(ApiSessionScope("https://passo.live/", 7)) { true }
        var observed: okhttp3.Request? = null
        val client = CompanyPortalWebClient(live, {}, transport = transport { request -> observed = request; response(request) }, verify = {})
        try {
            assertNull(client.shouldInterceptRequest(null, resource("https://passo.live/api/companies/me")))
            assertNull(client.shouldInterceptRequest(null, resource("https://superapp.ethiomobilemoney.et/checkout", main = true)))
            assertNull(observed)
            val tile = "https://tile.openstreetmap.org/12/2487/1940.png"
            assertEquals(200, client.shouldInterceptRequest(null, resource(tile, headers = mapOf(
                "Authorization" to "Bearer live-company-must-not-leak", "Cookie" to "live=must-not-leak")))!!.statusCode)
            assertNull(observed!!.header("Authorization"))
            assertNull(observed!!.header("Cookie"))
            assertEquals(403, client.shouldInterceptRequest(null, resource(base + "api/companies/me"))!!.statusCode)
        } finally { client.close() }
    }

    @Test fun `exports accept only exact review driver documents or company tickets and safe basenames`() {
        val scope = ApiSessionScope(base, 1)
        val document = "/review/api/onboarding/applications/DRIVER/review_driver/documents/LICENSE"
        assertTrue(CompanyExportPolicy.document(scope, document, "Driver-license.pdf")!!.useBearer)
        assertTrue(CompanyExportPolicy.document(scope, "/review/api/intercity/company/bookings/review-ticket/ticket.pdf", "Ticket.pdf")!!.useBearer)
        val capability = "/review/api/intercity/tickets/review-ticket.pdf?token=header.payload.signature"
        assertFalse(CompanyExportPolicy.document(scope, capability, "Ticket.pdf")!!.useBearer)
        for (path in listOf(
            "https://passo.live$document", document.replace("/review/", "/"),
            document.replace("DRIVER", "PASSENGER"), document.replace("LICENSE", "UNKNOWN"),
            document + "?token=anything", document + "#other", document.replace("review_driver", "%252e%252e"),
            "/review/api/companies/vehicles", capability + "&other=1",
            "/review/api/intercity/tickets/review-ticket.pdf?token=https://other.example"
        )) assertNull(path, CompanyExportPolicy.document(scope, path, "document.pdf"))
        for (name in listOf("../private.pdf", "sub/file.pdf", "a\\b.pdf", ".hidden.pdf", "x".repeat(81), "a\n.pdf"))
            assertNull(name, CompanyExportPolicy.document(scope, document, name))
        assertNull(CompanyExportPolicy.document(ApiSessionScope("https://passo.live/", 2), document, "file.pdf"))
    }

    @Test fun `document MIME signatures and five MiB bound reject substituted active content`() {
        val pdf = "%PDF-1.4\nSynthetic test document".toByteArray()
        assertEquals(".pdf", CompanyExportPolicy.documentType("application/pdf", pdf))
        assertEquals(".png", CompanyExportPolicy.documentType("image/png", byteArrayOf(137.toByte(),80,78,71,13,10,26,10)))
        assertEquals(".jpg", CompanyExportPolicy.documentType("image/jpeg", byteArrayOf(255.toByte(),216.toByte(),255.toByte(),0)))
        assertNull(CompanyExportPolicy.documentType("text/html", pdf))
        assertNull(CompanyExportPolicy.documentType("application/pdf", "<script>active</script>".toByteArray()))
        assertNull(CompanyExportPolicy.documentType("image/png", pdf))
        assertNull(CompanyExportPolicy.documentType("application/pdf", ByteArray(CompanyExportPolicy.DOCUMENT_LIMIT + 1)))
    }

    @Test fun `CSV export has fixed type and name with a UTF8 byte limit and excludes the bearer`() {
        val owner = CompanyCredential("synthetic-company-token", 1)
        val export = CompanyExportPolicy.csv("name,seat\nDemo Adult,1\n", owner)!!
        assertEquals("text/csv", export.mime)
        assertEquals("PASSO-synthetic-demo-intercity-report.csv", export.filename)
        assertEquals("name,seat\nDemo Adult,1\n", export.bytes.toString(Charsets.UTF_8))
        assertNull(CompanyExportPolicy.csv("private," + owner.bearer, owner))
        assertNull(CompanyExportPolicy.csv("\u0000unsafe", owner))
        assertNull(CompanyExportPolicy.csv("é".repeat(CompanyExportPolicy.CSV_LIMIT / 2 + 1), owner))
        assertNotNull(CompanyExportPolicy.csv("x".repeat(CompanyExportPolicy.CSV_LIMIT), owner))
    }

    @Test fun `bridge permits one user chosen export at a time and revokes its result after logout`() {
        val session = CompanyPortalSession(ApiSessionScope(base, 5)) { true }
        var pending: CompanyExport? = null
        val exports = CompanyPortalExports(session, { pending = it }, {})
        session.attachExports(exports)
        assertFalse(session.exportCsv("name,seat\nDemo,1"))
        assertTrue(session.setToken("synthetic-owner-bearer"))
        assertTrue(session.exportCsv("name,seat\nDemo,1"))
        val owner = pending!!.credential
        assertTrue(session.owns(owner))
        assertFalse(session.exportCsv("name,seat\nDemo,2"))
        session.releaseExport()
        assertTrue(session.exportCsv("name,seat\nDemo,2"))
        session.clearToken()
        assertFalse(session.owns(owner))
        assertFalse(session.exportCsv("name,seat\nDemo,3"))
        session.close()
        assertFalse(session.openDocument("/review/api/onboarding/applications/DRIVER/demo/documents/LICENSE", "license.pdf"))
    }

    @Test fun `private document downloads preserve exact PDF bytes with isolated bearer and no redirect fallback`() {
        for (mode in listOf("success", "capability", "redirect", "missing-marker")) {
            val session = CompanyPortalSession(ApiSessionScope(base, 6)) { true }
            session.setToken("synthetic-owner-bearer")
            val result = AtomicReference<CompanyExport?>()
            val done = CountDownLatch(1)
            var observed: okhttp3.Request? = null
            var handshakes = 0
            val bytes = "%PDF-1.4\nSynthetic exact bytes".toByteArray()
            val exports = CompanyPortalExports(session, { result.set(it); done.countDown() }, { done.countDown() },
                transport = transport { request ->
                    observed = request
                    response(request).newBuilder().body(bytes.toResponseBody("application/pdf".toMediaType())).apply {
                        if (mode == "redirect") code(302).header("Location", "https://passo.live/api/private")
                        if (mode == "missing-marker") removeHeader("X-Passo-Environment")
                    }.build()
                }, verify = { handshakes++ })
            session.attachExports(exports)
            try {
                val path = if (mode == "capability") "/review/api/intercity/tickets/demo.pdf?token=header.payload.signature"
                    else "/review/api/onboarding/applications/DRIVER/demo/documents/LICENSE"
                assertTrue(session.openDocument(path, "synthetic.pdf"))
                assertTrue(mode, done.await(4, TimeUnit.SECONDS))
                assertEquals(1, handshakes)
                assertEquals(if (mode == "capability") null else "Bearer synthetic-owner-bearer", observed!!.header("Authorization"))
                assertNull(observed!!.header("Cookie"))
                if (mode in listOf("success", "capability")) assertArrayEquals(bytes, result.get()!!.bytes)
                else assertNull(mode, result.get())
            } finally { session.close() }
        }
    }

    private fun transport(reply: (okhttp3.Request) -> Response) = OkHttpClient.Builder().followRedirects(false)
        .followSslRedirects(false).addInterceptor { chain -> reply(chain.request()) }.build()
    private fun response(request: okhttp3.Request) = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
        .code(200).message("OK").header("X-Passo-Environment", "REVIEW")
        .body("synthetic resource".toResponseBody("text/plain; charset=utf-8".toMediaType())).build()
    private fun resource(url: String, method: String = "GET", main: Boolean = false, headers: Map<String, String> = emptyMap()) = object : WebResourceRequest {
        override fun getUrl() = Uri.parse(url)
        override fun isForMainFrame() = main
        override fun isRedirect() = false
        override fun hasGesture() = false
        override fun getMethod() = method
        override fun getRequestHeaders() = headers
    }
}
