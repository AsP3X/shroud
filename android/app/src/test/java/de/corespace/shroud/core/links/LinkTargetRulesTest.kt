package de.corespace.shroud.core.links

import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * The fetch rules that keep a pasted link from probing the local network. Ports
 * `ios/shroudTests/LinkPageMetadataParserTests.swift:128-161` and the target part of
 * `web/src/linkPreview/linkPreview.selftest.ts:192-208`. Name resolution runs against a fake
 * resolver (no network in unit tests); IP literals go through the system resolver, which parses
 * them without a lookup.
 */
class LinkTargetRulesTest {
    /** Answers from [table]; anything else is "no such host". */
    private class FakeDns(private val table: Map<String, List<String>>) : Dns {
        val asked = ArrayList<String>()

        override fun lookup(hostname: String): List<InetAddress> {
            asked += hostname
            val addresses = table[hostname] ?: throw UnknownHostException("no such host")
            return addresses.map { InetAddress.getByName(it) } // literals only: no lookup
        }
    }

    @Test
    fun onlyPublicHttpsTargetsAreFetched() { // :130-148
        assertEquals("https://example.com/a", LinkTargetRules.allowedTarget("http://example.com/a").toString())
        for (blocked in listOf(
            "https://localhost/a",
            "https://192.168.1.1/",
            "https://10.0.0.8:443/",
            "https://[::1]/",
            "https://printer.local/",
            "https://nas.home/",
            "https://intranet/",
            "https://example.com:8443/",
            "ftp://example.com/",
        )) {
            assertNull(blocked, LinkTargetRules.allowedTarget(blocked))
        }
    }

    @Test
    fun webAlsoRefuses() { // linkPreview.selftest.ts:194-207
        for (blocked in listOf("https://127.0.0.1/", "https://10.0.0.8/", "not a url")) {
            assertNull(blocked, LinkTargetRules.allowedTarget(blocked))
        }
    }

    @Test
    fun credentialsAreStripped() { // linkPreview.selftest.ts:208
        assertEquals("https://example.com/", LinkTargetRules.allowedTarget("https://user:pass@example.com/").toString())
    }

    @Test
    fun theDefaultPortIsKeptAndPathQueryAndCaseSurvive() {
        assertEquals("https://example.com/A?b=C", LinkTargetRules.allowedTarget("https://Example.COM:443/A?b=C").toString())
        assertEquals("https://example.com/x", LinkTargetRules.allowedTarget("http://example.com:443/x").toString())
        assertNull(LinkTargetRules.allowedTarget("http://example.com:8080/x"))
    }

    @Test
    fun localNamesAndNumericHostsAreNotPublic() {
        assertFalse(LinkTargetRules.isPublicHostName("router.lan"))
        assertFalse(LinkTargetRules.isPublicHostName("box.internal"))
        assertFalse(LinkTargetRules.isPublicHostName("x.corp"))
        assertFalse(LinkTargetRules.isPublicHostName("1.2.3.4"))
        assertFalse(LinkTargetRules.isPublicHostName("127.1"))
        assertFalse(LinkTargetRules.isPublicHostName("localhost."))
        assertFalse(LinkTargetRules.isPublicHostName("."))
        assertTrue(LinkTargetRules.isPublicHostName("example.com."))
        assertTrue(LinkTargetRules.isPublicHostName("1.example"))
    }

    @Test
    fun ipv4Ranges() { // :151-155
        assertTrue(LinkTargetRules.isGloballyRoutableIPv4(0x01010101u))
        assertTrue(LinkTargetRules.isGloballyRoutableIPv4(0x08080808u))
        for (blocked in listOf(0x00000000u, 0x0A000001u, 0x7F000001u, 0x64400001u, 0xA9FE0001u, 0xAC100001u, 0xC0A80001u, 0xE0000001u, 0xFFFFFFFFu)) {
            assertFalse(blocked.toString(16), LinkTargetRules.isGloballyRoutableIPv4(blocked))
        }
    }

    @Test
    fun ipv4RangeEdges() {
        assertTrue(LinkTargetRules.isGloballyRoutableIPv4(0x643FFFFFu)) // 100.63.255.255
        assertFalse(LinkTargetRules.isGloballyRoutableIPv4(0x64400000u)) // 100.64.0.0
        assertFalse(LinkTargetRules.isGloballyRoutableIPv4(0x647FFFFFu)) // 100.127.255.255
        assertTrue(LinkTargetRules.isGloballyRoutableIPv4(0xAC200000u)) // 172.32.0.0
        assertFalse(LinkTargetRules.isGloballyRoutableIPv4(0xAC1FFFFFu)) // 172.31.255.255
        assertFalse(LinkTargetRules.isGloballyRoutableIPv4(0xC6130001u)) // 198.19.0.1
        assertTrue(LinkTargetRules.isGloballyRoutableIPv4(0xC6140001u)) // 198.20.0.1
        assertFalse(LinkTargetRules.isGloballyRoutableIPv4(0xC0000201u)) // 192.0.2.1
        assertTrue(LinkTargetRules.isGloballyRoutableIPv4(0xC0000301u)) // 192.0.3.1
        assertFalse(LinkTargetRules.isGloballyRoutableIPv4(0xC6336401u)) // 198.51.100.1
        assertFalse(LinkTargetRules.isGloballyRoutableIPv4(0xCB007101u)) // 203.0.113.1
        assertTrue(LinkTargetRules.isGloballyRoutableIPv4(0xDFFFFFFFu)) // 223.255.255.255
    }

    @Test
    fun ipv6Ranges() {
        fun routable(literal: String) = LinkTargetRules.isGloballyRoutableIPv6(InetAddress.getByName(literal).address.let {
            if (it.size == 4) ByteArray(10) + byteArrayOf(-1, -1) + it else it
        })
        assertFalse(routable("::"))
        assertFalse(routable("::1"))
        assertFalse(routable("fe80::1"))
        assertFalse(routable("febf::1"))
        assertFalse(routable("fc00::1"))
        assertFalse(routable("fd12:3456::1"))
        assertFalse(routable("ff02::1"))
        assertFalse(routable("2001:db8::1"))
        assertTrue(routable("2606:4700:4700::1111"))
        assertTrue(routable("2001:4860:4860::8888"))
        assertTrue(routable("fec0::1"))
        // IPv4-mapped: only as public as the IPv4 inside (the JVM hands mapped literals back as v4).
        assertFalse(routable("::ffff:127.0.0.1"))
        assertTrue(routable("::ffff:1.1.1.1"))
        assertFalse(LinkTargetRules.isGloballyRoutableIPv6(ByteArray(4)))
    }

    @Test
    fun resolvedPrivateAddressesAreNotFetched() { // :156-160
        val names = FakeDns(mapOf("public.example" to listOf("1.1.1.1", "2606:4700:4700::1111")))
        val dns = PublicAddressDns(names)
        for (blocked in listOf("127.0.0.1", "10.1.2.3", "192.168.0.8", "172.16.5.5", "169.254.1.1", "0.0.0.0", "::1")) {
            assertFalse(blocked, LinkTargetRules.resolvesOnlyToPublicAddresses(blocked, PublicAddressDns()))
        }
        assertFalse(LinkTargetRules.resolvesOnlyToPublicAddresses("not-a-real-host.invalid", dns))
        assertTrue(LinkTargetRules.resolvesOnlyToPublicAddresses("1.1.1.1", PublicAddressDns()))
        assertTrue(LinkTargetRules.resolvesOnlyToPublicAddresses("8.8.8.8", PublicAddressDns()))
        assertTrue(LinkTargetRules.resolvesOnlyToPublicAddresses("public.example", dns))
    }

    @Test
    fun oneBadAddressRefusesTheWholeName() {
        val names = FakeDns(
            mapOf(
                "rebind.example" to listOf("1.1.1.1", "192.168.1.1"),
                "empty.example" to listOf(),
                "mapped.example" to listOf("::ffff:10.0.0.1"),
            ),
        )
        val dns = PublicAddressDns(names)
        assertThrows(UnknownHostException::class.java) { dns.lookup("rebind.example") }
        assertThrows(UnknownHostException::class.java) { dns.lookup("empty.example") }
        assertThrows(UnknownHostException::class.java) { dns.lookup("mapped.example") }
        assertFalse(LinkTargetRules.resolvesOnlyToPublicAddresses("rebind.example", dns))
    }

    @Test
    fun theFilterHandsOkHttpExactlyTheCheckedAddresses() {
        val checked = listOf(InetAddress.getByName("1.1.1.1"), InetAddress.getByName("8.8.8.8"))
        val dns = PublicAddressDns { checked }
        assertSame(checked, dns.lookup("public.example"))
    }

    @Test
    fun refusalsNeverNameTheHost() {
        val dns = PublicAddressDns(FakeDns(mapOf("secret-host.example" to listOf("10.0.0.1"))))
        val error = assertThrows(UnknownHostException::class.java) { dns.lookup("secret-host.example") }
        assertFalse(error.message.orEmpty().contains("secret"))
    }
}
