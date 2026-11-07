package com.example.systemset

import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.RequestBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class BackendApiInstrumentedTest {

    // Project significance: checking the method, URL, and tenant header guards the
    // Android sender/backend boundary against accidental signaling contract regressions.
    @Test
    fun getRequestIncludesTenantAuthenticationAndNoBody() {
        val request = BackendApi.request(
            tenantKey = TEST_TENANT_KEY,
            path = BackendApi.TENANT_PAYLOAD_ENDPOINT,
            queryParameters = mapOf(
                "key" to "device-one",
                "payload_type" to "webrtc-answer"
            )
        )

        assertEquals(
            "${BackendApi.BASE_URL}/api/v1/tenant/payload?key=device-one&payload_type=webrtc-answer",
            request.url.toString()
        )
        assertEquals("GET", request.method)
        assertEquals("application/json", request.header("Accept"))
        assertEquals(TEST_TENANT_KEY, request.header("X-Tenant-Key"))
        assertNull(request.body)
    }

    // Project significance: checking the serialized envelope protects the payload shape
    // consumed by the central hub and receiving application.
    @Test
    fun postRequestEncodesJsonBodyAndAuthenticationHeaders() {
        val payload = org.json.JSONObject()
            .put("key", "device-one")
            .put("payload_type", "webrtc-offer")
            .put(
                "payload",
                org.json.JSONObject()
                    .put("type", "webrtc-offer")
                    .put("call_id", "session-123")
            )

        val request = BackendApi.request(
            tenantKey = TEST_TENANT_KEY,
            path = "/api/v1/tenant/payload",
            method = "POST",
            body = payload
        )

        assertEquals("POST", request.method)
        assertEquals(TEST_TENANT_KEY, request.header("X-Tenant-Key"))
        assertEquals("application/json", request.header("Accept"))
        assertTrue(request.header("Content-Type")!!.startsWith("application/json"))
        assertEquals(payload.toString(), request.body!!.readUtf8())
    }

    // Project significance: body-less writes must remain valid HTTP requests when backend
    // endpoints use POST/PUT semantics without a JSON payload.
    @Test
    fun postWithoutJsonStillHasAnEmptyRequestBody() {
        val request = BackendApi.request(
            tenantKey = TEST_TENANT_KEY,
            path = "/api/v1/tenant/payload",
            method = "POST"
        )

        assertEquals("POST", request.method)
        assertEquals(TEST_TENANT_KEY, request.header("X-Tenant-Key"))
        assertEquals(0L, request.body!!.contentLength())
    }

    @Test
    fun putWithoutJsonStillHasAnEmptyRequestBody() {
        val request = BackendApi.request(
            tenantKey = TEST_TENANT_KEY,
            path = "/api/v1/tenant/payload/device-one",
            method = "PUT"
        )

        assertEquals("PUT", request.method)
        assertEquals(0L, request.body!!.contentLength())
    }

    @Test
    fun parseSuccessfulJsonReturnsParsedObject() {
        val parsed = BackendApi.parseSuccessfulJson(
            """{"status":"success","count":2}""",
            "/api/v1/tenant/payload"
        )

        assertEquals("success", parsed.getString("status"))
        assertEquals(2, parsed.getInt("count"))
    }

    // Project significance: malformed hub responses must fail explicitly instead of being
    // mistaken for usable signaling data by callers.
    @Test
    fun parseSuccessfulJsonThrowsPathAwareIOExceptionForInvalidJson() {
        val error = assertThrows(IOException::class.java) {
            BackendApi.parseSuccessfulJson(
                """{"status":""",
                "/api/v1/tenant/payload"
            )
        }

        assertTrue(error.message!!.contains("/api/v1/tenant/payload"))
        assertTrue(error.message!!.contains("invalid JSON"))
    }

    @Test
    fun turnCredentialsAreParsedOnlyWhenCredentialedTurnServerIsPresent() {
        val servers = TurnCredentialProvider.parse(
            """{"status":"success","payload":{"ttl":300,"iceServers":[{"urls":["stun:stun.example.test:3478","turn:turn.example.test:3478?transport=udp"],"username":"temporary-user","credential":"temporary-password"}]}}"""
        )

        assertTrue(servers.any { server ->
            server.urls.any { it.startsWith("turn:", ignoreCase = true) } &&
                server.username == "temporary-user" &&
                server.password == "temporary-password"
        })
    }

    @Test
    fun turnCredentialParserRejectsStunOnlyConfiguration() {
        val error = assertThrows(IOException::class.java) {
            TurnCredentialProvider.parse(
                """{"status":"success","payload":{"ttl":300,"iceServers":[{"urls":["stun:stun.example.test:3478"]}]}}"""
            )
        }

        assertTrue(error.message!!.contains("credentialed TURN/TURNS"))
    }

    @Test
    fun stunModeBuildsStunServersAndRejectsTurnUrls() {
        val servers = IceServerProvider.createStunServers(
            listOf(" stun:stun.example.test:3478 ", "stuns:secure.example.test:5349")
        )

        assertEquals(2, servers.size)
        assertEquals("stun:stun.example.test:3478", servers[0].urls.single())
        assertEquals("stuns:secure.example.test:5349", servers[1].urls.single())
        assertThrows(IllegalArgumentException::class.java) {
            IceServerProvider.createStunServers(listOf("turn:turn.example.test:3478"))
        }
    }

    private fun RequestBody.readUtf8(): String {
        val buffer = Buffer()
        writeTo(buffer)
        return buffer.readUtf8()
    }

    private companion object {
        const val TEST_TENANT_KEY = "test-tenant-key"
    }
}