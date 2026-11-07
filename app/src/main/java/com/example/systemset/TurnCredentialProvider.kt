package com.example.systemset

import org.json.JSONArray
import org.webrtc.PeerConnection
import java.io.IOException
import okhttp3.OkHttpClient

internal object TurnCredentialProvider {
    private val httpClient = OkHttpClient()

    fun fetch(
        tenantKey: String,
        deviceUserSlug: String
    ): List<PeerConnection.IceServer> {
        val request = BackendApi.request(
            tenantKey = tenantKey,
            path = BackendApi.TURN_CREDENTIALS_ENDPOINT,
            queryParameters = mapOf("device_user_slug" to deviceUserSlug)
        )

        val response = httpClient.newCall(request).execute()
        response.use {
            val responseBody = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                throw IOException("TURN credential request failed with HTTP ${it.code}.")
            }
            return parse(responseBody)
        }
    }

    internal fun parse(responseBody: String): List<PeerConnection.IceServer> {
        val envelope = BackendApi.parseSuccessfulJson(
            responseBody,
            BackendApi.TURN_CREDENTIALS_ENDPOINT
        )
        val payload = envelope.optJSONObject("payload")
            ?: throw IOException("TURN credential response has no payload.")
        val iceServerJson = payload.optJSONArray("iceServers")
            ?: throw IOException("TURN credential response has no iceServers array.")
        val iceServers = iceServerJson.toIceServers()
        if (iceServers.none { server ->
                server.urls.any { url ->
                    (url.startsWith("turn:", true) || url.startsWith("turns:", true)) &&
                        server.username.isNotBlank() && server.password.isNotBlank()
                }
            }
        ) {
            throw IOException("TURN response contains no credentialed TURN/TURNS server.")
        }
        return iceServers
    }

    private fun JSONArray.toIceServers(): List<PeerConnection.IceServer> =
        (0 until length()).mapNotNull { index ->
            val item = optJSONObject(index) ?: return@mapNotNull null
            val urls = when (val value = item.opt("urls")) {
                is String -> listOf(value)
                is JSONArray -> (0 until value.length()).mapNotNull { urlIndex ->
                    value.optString(urlIndex).takeIf(String::isNotBlank)
                }
                else -> emptyList()
            }
            if (urls.isEmpty()) return@mapNotNull null

            PeerConnection.IceServer.builder(urls)
                .setUsername(item.optString("username"))
                .setPassword(item.optString("credential"))
                .createIceServer()
        }
}
