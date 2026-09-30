package de.corespace.shroud.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Mirrors `ios/shroudTests/ServerConfigurationTests.swift`. */
class ServerConfigurationTest {
    private fun selfHosted(host: String, port: String, path: String, https: Boolean) =
        ServerConfiguration(ServerConnectionMode.SelfHosted, host, port, path, https)

    @Test
    fun composesUrls() {
        assertEquals("http://192.168.1.20:8080/api/v1", selfHosted("192.168.1.20", "8080", "/api/v1", false).resolvedBaseUrl)
        assertEquals("https://chat.example.com:443/api/v1", selfHosted("chat.example.com", "443", "api/v1/", true).resolvedBaseUrl)
        assertEquals("https://chat.example.com/api/v1", selfHosted(" chat.example.com ", "", "", true).resolvedBaseUrl)
        assertEquals("http://…/api/v1", selfHosted("", "8080", "/api/v1", false).selfHostedPreview.replace(":8080", ""))
        assertEquals(ServerConfiguration.OFFICIAL_BASE_URL, ServerConfiguration.official.resolvedBaseUrl)
    }

    @Test
    fun validates() {
        assertEquals("Enter a host or IP address.", selfHosted(" ", "8080", "/api/v1", false).validationError())
        assertEquals("Port must be a number between 1 and 65535.", selfHosted("h", "70000", "/api/v1", false).validationError())
        assertEquals("Port must be a number between 1 and 65535.", selfHosted("h", "0", "/api/v1", false).validationError())
        assertNull(selfHosted("10.0.2.2", "8080", "/api/v1", false).validationError())
        assertNull(ServerConfiguration.official.validationError())
    }

    @Test
    fun plainHttpOnlyForLocalHosts() {
        for (host in listOf("10.0.2.2", "127.0.0.1", "192.168.1.20", "172.20.0.4", "localhost", "nas.local", "homeserver", "[::1]")) {
            assertNull(host, selfHosted(host, "8080", "/api/v1", false).validationError())
        }
        for (host in listOf("chat.example.com", "8.8.8.8", "172.32.0.1")) {
            assertEquals(host, ServerConfiguration.PLAIN_HTTP_REFUSED, selfHosted(host, "8080", "/api/v1", false).validationError())
            assertNull(host, selfHosted(host, "443", "/api/v1", true).validationError())
        }
    }
}
