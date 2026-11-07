package com.example.systemset

import org.webrtc.PeerConnection
import java.io.IOException

internal object IceServerProvider {
    fun fetch(
        tenantKey: String,
        deviceUserSlug: String
    ): List<PeerConnection.IceServer> =
        when (BuildConfig.ICE_MODE) {
            "turn" -> TurnCredentialProvider.fetch(tenantKey, deviceUserSlug)
            "stun" -> createStunServers(BuildConfig.STUN_SERVER_URLS.split(","))
            else -> throw IOException("Unsupported ICE mode: ${BuildConfig.ICE_MODE}.")
        }

    internal fun createStunServers(urls: List<String>): List<PeerConnection.IceServer> {
        val configuredUrls = urls.map(String::trim).filter(String::isNotEmpty)
        require(configuredUrls.isNotEmpty()) { "At least one STUN server URL is required." }
        require(configuredUrls.all { url ->
            url.startsWith("stun:", ignoreCase = true) ||
                url.startsWith("stuns:", ignoreCase = true)
        }) {
            "STUN mode only accepts stun: or stuns: URLs."
        }

        return configuredUrls.map { url ->
            PeerConnection.IceServer.builder(url).createIceServer()
        }
    }
}
