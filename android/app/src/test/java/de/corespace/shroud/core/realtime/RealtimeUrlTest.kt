package de.corespace.shroud.core.realtime

import de.corespace.shroud.core.net.ServerConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** iOS `RealtimeClientURLTests` (3) plus the Android cleartext rule (api-realtime §11.1, §13). */
class RealtimeUrlTest {
    // ---- RealtimeClientURLTests.swift:6-25, verbatim ----

    @Test
    fun buildsWssFromHttpsApiBase() {
        assertEquals("wss://shroud.corespace.de/api/v1/ws", RealtimeClient.webSocketUrl("https://shroud.corespace.de/api/v1"))
    }

    @Test
    fun buildsWsFromHttpLocal() {
        assertEquals("ws://127.0.0.1:8080/api/v1/ws", RealtimeClient.webSocketUrl("http://127.0.0.1:8080/api/v1"))
    }

    @Test
    fun doesNotDoubleAppendWs() {
        assertEquals("wss://example.com/api/v1/ws", RealtimeClient.webSocketUrl("https://example.com/api/v1/ws"))
    }

    // ---- RealtimeClient.swift:207-231 rules ----

    @Test
    fun emulatorHostBecomesPlainWs() {
        val base = "http://10.0.2.2:8080/api/v1"
        assertEquals("ws://10.0.2.2:8080/api/v1/ws", RealtimeClient.webSocketUrl(base))
        assertEquals(RealtimeClient.Target.Url("ws://10.0.2.2:8080/api/v1/ws"), RealtimeClient.target(base))
    }

    @Test
    fun trailingSlashesQueryAndFragmentAreDropped() {
        assertEquals("wss://example.com/api/v1/ws", RealtimeClient.webSocketUrl("https://example.com/api/v1///?x=1#top"))
        assertEquals("wss://example.com/ws", RealtimeClient.webSocketUrl("https://example.com"))
    }

    @Test
    fun socketSchemesAreKeptAndOthersBecomeWs() {
        assertEquals("wss://example.com/api/v1/ws", RealtimeClient.webSocketUrl("wss://example.com/api/v1"))
        assertEquals("ws://192.168.1.20/api/v1/ws", RealtimeClient.webSocketUrl("ws://192.168.1.20/api/v1"))
        assertEquals("ws://192.168.1.20/api/v1/ws", RealtimeClient.webSocketUrl("ftp://192.168.1.20/api/v1"))
        assertEquals("wss://example.com/api/v1/ws", RealtimeClient.webSocketUrl("HTTPS://Example.com/api/v1"))
    }

    @Test
    fun notAUrlIsNull() {
        assertNull(RealtimeClient.webSocketUrl("not a url"))
        assertNull(RealtimeClient.webSocketUrl(""))
        assertEquals(RealtimeClient.Target.Invalid, RealtimeClient.target("https://"))
    }

    // ---- Android: plain ws:// only to local hosts, as REST (ApiClient.url) ----

    @Test
    fun plainHttpToAPublicHostIsRefused() {
        assertEquals(RealtimeClient.Target.PlainHttpRefused, RealtimeClient.target("http://api.example.com/api/v1"))
        assertEquals(RealtimeClient.Target.PlainHttpRefused, RealtimeClient.target("ws://8.8.8.8/api/v1"))
        // The mapping itself stays iOS's; only the policy refuses.
        assertEquals("ws://api.example.com/api/v1/ws", RealtimeClient.webSocketUrl("http://api.example.com/api/v1"))
    }

    @Test
    fun localHostsMayUsePlainWs() {
        for (base in listOf("http://localhost:8080/api/v1", "http://192.168.0.5/api/v1", "http://shroud.local/api/v1", "http://[::1]:8080/api/v1")) {
            val target = RealtimeClient.target(base)
            assertTrue("$base → $target", target is RealtimeClient.Target.Url)
        }
        assertEquals(RealtimeClient.Target.Url("wss://api.shroud.app/api/v1/ws"), RealtimeClient.target(ServerConfiguration.OFFICIAL_BASE_URL))
    }
}
