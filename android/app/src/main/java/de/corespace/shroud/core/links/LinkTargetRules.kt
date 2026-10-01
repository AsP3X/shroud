package de.corespace.shroud.core.links

import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Which URLs a link preview may fetch — iOS `LinkPreviewFetcher` target rules
 * (`ios/shroud/Services/Links/LinkPreviewFetcher.swift:231-339`), media-voice-links §10.4, D11.
 *
 * A pasted link must not turn the sender's phone into a probe of the home network: only `https` on
 * the default port, never an IP literal, a local name or a name that resolves to a private,
 * loopback or link-local address. Redirects are held to the same rules ([LinkPreviewFetcher]).
 */
object LinkTargetRules {
    /** Suffixes of names that only exist on a local network (`LinkPreviewFetcher.swift:333`). */
    private val LOCAL_SUFFIXES = listOf(".local", ".localhost", ".internal", ".lan", ".home", ".arpa", ".intranet", ".corp")

    /**
     * The URL to fetch, or null when it must not be fetched (`LinkPreviewFetcher.swift:233-249`): a
     * typed `http://` link is upgraded to `https://` rather than fetched in the clear; any port but
     * 443, an IP literal or a local name is refused; user and password are stripped.
     *
     * OkHttp keeps no record of whether a port was written out, so `http://host:80/` counts as "no
     * port" here (upgraded to 443) where iOS refuses the explicit 80 — the fetch still only ever
     * goes to 443.
     */
    fun allowedTarget(url: HttpUrl): HttpUrl? {
        val port = when (url.scheme) {
            "https" -> url.port
            "http" -> if (url.port == HttpUrl.defaultPort("http")) HTTPS_PORT else url.port
            else -> return null
        }
        if (port != HTTPS_PORT) return null
        if (!isPublicHostName(url.host.lowercase())) return null
        return url.newBuilder().scheme("https").port(HTTPS_PORT).username("").password("").build()
    }

    /** [allowedTarget] of a URL string; null when it is not an `http`/`https` URL at all. */
    fun allowedTarget(url: String): HttpUrl? = url.toHttpUrlOrNull()?.let(::allowedTarget)

    /**
     * True for a dotted DNS name that is not local and not an address literal
     * (`LinkPreviewFetcher.swift:329-339`). `localhost` and `intranet` fail on "contains a dot".
     * OkHttp hosts are ASCII (IDN as punycode), so "all digits" is ASCII digits.
     */
    fun isPublicHostName(host: String): Boolean {
        val name = if (host.endsWith('.')) host.dropLast(1) else host
        if ('.' !in name || ':' in name || '%' in name) return false
        if (LOCAL_SUFFIXES.any { name.endsWith(it) }) return false
        // Dotted-quad IPv4 (and the all-digits shorthands some resolvers accept). Swift's `split`
        // drops empty labels.
        val labels = name.split('.').filter { it.isNotEmpty() }
        if (labels.all { label -> label.all { it in '0'..'9' } }) return false
        return true
    }

    /**
     * True when every address [host] resolves to is a public unicast address
     * (`LinkPreviewFetcher.swift:251-275`): fail closed when the lookup fails, finds nothing or
     * any address is private, loopback, link-local, multicast or otherwise not globally routable.
     * Blocking; [dns] is normally a [PublicAddressDns], which does the checking.
     */
    fun resolvesOnlyToPublicAddresses(host: String, dns: Dns): Boolean =
        try {
            val addresses = dns.lookup(host)
            addresses.isNotEmpty() && addresses.all(::isGloballyRoutable)
        } catch (_: IOException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        } catch (_: SecurityException) {
            false // no INTERNET permission: nothing can be fetched anyway
        }

    /** The address check behind [PublicAddressDns] (`LinkPreviewFetcher.swift:296-309`). */
    fun isGloballyRoutable(address: InetAddress): Boolean = when (address) {
        is Inet4Address -> isGloballyRoutableIPv4(address.address.toIPv4())
        is Inet6Address -> isGloballyRoutableIPv6(address.address)
        else -> false
    }

    /**
     * [ip] in host order (`1.1.1.1` is `0x01010101u`) — `LinkPreviewFetcher.swift:277-294`. Blocked:
     * 0/8, 10/8, 127/8, 100.64/10, 169.254/16, 172.16/12, 192.0.0/24, 192.0.2/24, 192.168/16,
     * 198.18/15, 198.51.100/24, 203.0.113/24 and everything from 224/4 up (multicast, reserved,
     * broadcast).
     */
    fun isGloballyRoutableIPv4(ip: UInt): Boolean {
        val a = (ip shr 24) and 0xFFu
        val b = (ip shr 16) and 0xFFu
        val c = (ip shr 8) and 0xFFu
        if (a == 0u || a == 10u || a == 127u) return false
        if (a == 100u && (b and 0xC0u) == 64u) return false
        if (a == 169u && b == 254u) return false
        if (a == 172u && (b and 0xF0u) == 16u) return false
        if (a == 192u && b == 0u && c == 0u) return false
        if (a == 192u && b == 0u && c == 2u) return false
        if (a == 192u && b == 168u) return false
        if (a == 198u && (b and 0xFEu) == 18u) return false
        if (a == 198u && b == 51u && c == 100u) return false
        if (a == 203u && b == 0u && c == 113u) return false
        if (a >= 224u) return false
        return true
    }

    /**
     * `LinkPreviewFetcher.swift:311-327`: `::`, `::1`, link-local `fe80::/10`, unique-local
     * `fc00::/7`, multicast `ff00::/8` and documentation `2001:db8::/32` are blocked; an IPv4-mapped
     * address (`::ffff:a.b.c.d`) is only as public as the IPv4 address inside it.
     */
    fun isGloballyRoutableIPv6(bytes: ByteArray): Boolean {
        if (bytes.size != 16) return false
        val u = IntArray(16) { bytes[it].toInt() and 0xFF }
        if (u.all { it == 0 }) return false
        if ((0 until 15).all { u[it] == 0 } && u[15] == 1) return false
        if ((0 until 10).all { u[it] == 0 } && u[10] == 0xFF && u[11] == 0xFF) {
            return isGloballyRoutableIPv4(bytes.copyOfRange(12, 16).toIPv4())
        }
        if (u[0] == 0xFE && (u[1] and 0xC0) == 0x80) return false
        if ((u[0] and 0xFE) == 0xFC) return false
        if (u[0] == 0xFF) return false
        if (u[0] == 0x20 && u[1] == 0x01 && u[2] == 0x0D && u[3] == 0xB8) return false
        return true
    }

    private const val HTTPS_PORT = 443

    private fun ByteArray.toIPv4(): UInt =
        ((this[0].toUInt() and 0xFFu) shl 24) or ((this[1].toUInt() and 0xFFu) shl 16) or
            ((this[2].toUInt() and 0xFFu) shl 8) or (this[3].toUInt() and 0xFFu)
}

/**
 * The link-preview client's resolver (media-voice-links D11): resolves with [delegate], throws
 * [UnknownHostException] when it finds nothing or any address fails
 * [LinkTargetRules.isGloballyRoutable], and otherwise returns exactly the checked list — OkHttp then
 * connects only to those addresses. iOS checks with `getaddrinfo` and lets `URLSession` resolve
 * again; answering OkHttp from the checked list closes that DNS-rebinding window.
 *
 * Messages never name the host (no URLs in logs or crash reports).
 */
class PublicAddressDns(private val delegate: Dns = Dns.SYSTEM) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = delegate.lookup(hostname)
        if (addresses.isEmpty()) throw UnknownHostException("link preview host has no address")
        if (!addresses.all(LinkTargetRules::isGloballyRoutable)) throw UnknownHostException("link preview host is not public")
        return addresses
    }
}
