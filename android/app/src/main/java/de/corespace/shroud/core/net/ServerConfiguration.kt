package de.corespace.shroud.core.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** How the app picks its backend (`ServerConfiguration.swift`). Not a secret. */
@Serializable
enum class ServerConnectionMode {
    @SerialName("official") Official,
    @SerialName("selfHosted") SelfHosted,
}

@Serializable
data class ServerConfiguration(
    val mode: ServerConnectionMode,
    /** Host or IP without scheme. */
    val host: String,
    val port: String,
    /** Path prefix with a leading slash. */
    val apiPath: String,
    val useHTTPS: Boolean,
) {
    /** API root without a trailing slash. */
    val resolvedBaseUrl: String
        get() = when (mode) {
            ServerConnectionMode.Official -> OFFICIAL_BASE_URL
            ServerConnectionMode.SelfHosted -> composeUrl(host, port, apiPath, useHTTPS)
        }

    /** Live preview for the self-hosted form (may be invalid while typing). */
    val selfHostedPreview: String get() = composeUrl(host, port, apiPath, useHTTPS)

    /** A message for the user, or null when the configuration can be saved. */
    fun validationError(): String? {
        if (mode == ServerConnectionMode.Official) return null
        if (host.isBlank()) return "Enter a host or IP address."
        val trimmedPort = port.trim()
        if (trimmedPort.isNotEmpty()) {
            val value = trimmedPort.toIntOrNull()
            if (value == null || value !in 1..65535) return "Port must be a number between 1 and 65535."
        }
        val url = resolvedBaseUrl.toHttpUrlOrNull() ?: return "That server address is not a valid URL."
        if (!url.isHttps && !isLocalNetworkHost(url.host)) return PLAIN_HTTP_REFUSED
        return null
    }

    companion object {
        const val PLAIN_HTTP_REFUSED = "Use HTTPS for this server. Plain HTTP works only for local addresses."

        /**
         * Hosts plain HTTP may reach — iOS `NSAllowsLocalNetworking`: unqualified names, `.local`,
         * `localhost`, and loopback, private or link-local IP literals (the emulator's 10.0.2.2).
         */
        fun isLocalNetworkHost(host: String): Boolean {
            val h = host.trim().trim('[', ']').lowercase()
            if (h.isEmpty()) return false
            if (h == "localhost" || h.endsWith(".local") || h.endsWith(".localhost")) return true
            val v4 = h.split('.').map { it.toIntOrNull() }
            if (v4.size == 4 && v4.all { it != null && it in 0..255 }) {
                val (a, b) = v4[0]!! to v4[1]!!
                return a == 10 || a == 127 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254)
            }
            if (':' in h) return h == "::1" || h.startsWith("fc") || h.startsWith("fd") || h.startsWith("fe80")
            return '.' !in h
        }

        /** Managed Shroud service. Release builds use this when the mode is official. */
        const val OFFICIAL_BASE_URL = "https://shroud-app.com/api/v1"

        val official = ServerConfiguration(ServerConnectionMode.Official, "shroud-app.com", "443", "/api/v1", true)

        /** Debug: a local Compose stack seen from the emulator (10.0.2.2 is the host machine). */
        fun localDevelopment(host: String, port: Int) =
            ServerConfiguration(ServerConnectionMode.SelfHosted, host, port.toString(), "/api/v1", false)

        fun composeUrl(host: String, port: String, apiPath: String, useHTTPS: Boolean): String {
            val trimmedHost = host.trim()
            val trimmedPort = port.trim()
            var path = apiPath.trim().ifEmpty { "/api/v1" }
            if (!path.startsWith("/")) path = "/$path"
            while (path.length > 1 && path.endsWith("/")) path = path.dropLast(1)
            val scheme = if (useHTTPS) "https" else "http"
            return when {
                trimmedHost.isEmpty() -> "$scheme://…$path"
                trimmedPort.isEmpty() -> "$scheme://$trimmedHost$path"
                else -> "$scheme://$trimmedHost:$trimmedPort$path"
            }
        }
    }
}
