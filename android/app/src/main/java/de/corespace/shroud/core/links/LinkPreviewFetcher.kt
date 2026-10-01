package de.corespace.shroud.core.links

import android.os.LocaleList
import de.corespace.shroud.core.model.Bytes
import de.corespace.shroud.core.net.wire.LinkPreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.ConnectionSpec
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.BufferedSource
import java.io.IOException
import java.net.Proxy
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

/** Fetches link previews; an interface so the composer is tested without a network (iOS `LinkPreviewFetching`, `LinkPreviewFetcher.swift:33-36`). */
fun interface LinkPreviewFetching {
    /** The preview for [url] (as the detector found it), or throws when the page has nothing to show. */
    suspend fun fetchPreview(url: String): LinkPreviewDraft
}

/** Why a page gave no preview (iOS `LinkPreviewFetcher.FetchError`, `:67-73`). The composer shows nothing for every one of them. */
class LinkPreviewException(val reason: Reason) : IOException("link preview: $reason") {
    enum class Reason { NotAllowed, BadResponse, NotHtml, Empty, ImageTooLarge }
}

/**
 * One GET with no redirects followed; [read] runs while the response is open. The fetcher's seam
 * between its rules and OkHttp: production is [OkHttpLinkTransport], JVM tests script responses.
 */
internal interface LinkHttpTransport {
    suspend fun <T> execute(request: Request, read: (Response) -> T): T
}

/**
 * [LinkHttpTransport] on the link-preview [OkHttpClient] ([LinkPreviewFetcher.httpClient]). The
 * response is read on OkHttp's thread inside the callback; cancelling the coroutine cancels the
 * call, which closes the socket and ends a blocked read at once.
 */
internal class OkHttpLinkTransport(private val http: OkHttpClient) : LinkHttpTransport {
    override suspend fun <T> execute(request: Request, read: (Response) -> T): T =
        suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWith(Result.failure(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    continuation.resumeWith(runCatching { response.use(read) })
                }
            })
        }
}

/**
 * Builds a link preview on the sender's phone — iOS `LinkPreviewFetcher`
 * (`ios/shroud/Services/Links/LinkPreviewFetcher.swift:38-229`), media-voice-links §10.4, D11, P18.
 *
 * Only the sender ever talks to the website, only after they typed or pasted the link themselves,
 * and only while Settings → Privacy → link previews is on ([LinkPreviewSettings]; the composer
 * checks). Never the Shroud server, no relay (D12). The recipient gets the result sealed inside the
 * message. To keep the fetch from being turned against the sender:
 *
 * - `https` only, default port, never an IP literal, a local name or a name resolving to a
 *   private, loopback or link-local address ([LinkTargetRules]); every redirect hop is followed
 *   here by hand and held to the same rules (at most [MAX_REDIRECTS] hops, `URLSession`'s limit),
 *   and the client's [PublicAddressDns] connects only to the checked addresses;
 * - an ephemeral client: no cookies, no cache, no credentials, no proxy, no `Referer`;
 * - only the page head is read (≤ [MAX_HEAD_BYTES]), images are capped at [MAX_IMAGE_BYTES] and
 *   40 MP and decoded downsampled ([LinkPreviewImages]).
 *
 * The `WhatsApp/2…` [USER_AGENT] is deliberate (memory *Link preview user agent*): Amazon only
 * answers with OpenGraph tags to a UA that starts with `WhatsApp/`, Reddit and Amazon fail with
 * browser and honest bot UAs; re-run the curl matrix before changing it.
 *
 * Runs off the main thread; the caller's cancellation cancels the calls. URLs are never logged,
 * and no error message names one.
 */
class LinkPreviewFetcher internal constructor(
    private val transport: LinkHttpTransport,
    private val dns: Dns,
    private val images: LinkImagePreparer,
    private val acceptLanguage: () -> String,
    private val io: CoroutineContext = Dispatchers.IO,
    private val cpu: CoroutineContext = Dispatchers.Default,
) : LinkPreviewFetching {
    /**
     * Production wiring ([de.corespace.shroud.di.LinksModule]): [http] comes from [httpClient] with
     * the same [dns], so the pre-checks and the connections use one resolver.
     */
    constructor(http: OkHttpClient, images: LinkImagePreparer, acceptLanguage: () -> String = ::deviceAcceptLanguage) :
        this(OkHttpLinkTransport(http), http.dns, images, acceptLanguage)

    override suspend fun fetchPreview(url: String): LinkPreviewDraft = withContext(io) { fetch(url) }

    /** `LinkPreviewFetcher.swift:114-176`. */
    private suspend fun fetch(url: String): LinkPreviewDraft {
        val target = url.toHttpUrlOrNull()?.let(LinkTargetRules::allowedTarget)
        if (target == null || !LinkTargetRules.resolvesOnlyToPublicAddresses(target.host, dns)) {
            throw LinkPreviewException(LinkPreviewException.Reason.NotAllowed)
        }
        val page = fetchHead(target)
        currentCoroutineContext().ensureActive()

        var metadata = if (page.isImage) {
            // A direct link to a picture: the picture is the preview.
            LinkPageMetadata(imageUrl = page.finalUrl)
        } else {
            LinkPageMetadataParser.parse(page.head, page.finalUrl, page.contentType)
        }
        metadata.imageUrl?.let { imageUrl ->
            // Fetch the upgraded https URL, not the page's original http one.
            val allowed = LinkTargetRules.allowedTarget(imageUrl)
                ?.takeIf { LinkTargetRules.resolvesOnlyToPublicAddresses(it.host, dns) }
            metadata = metadata.copy(imageUrl = allowed)
        }
        if (metadata.isEmpty) throw LinkPreviewException(LinkPreviewException.Reason.Empty)

        var prepared: PreparedLinkImages? = null
        metadata.imageUrl?.let { imageUrl ->
            // A broken image must not cost the whole preview — Telegram shows the text alone. The
            // page's own body is reused when the page is the picture (web does; iOS fetches it again).
            val data = if (page.isImage) page.imageBody else fetchImageOrNull(imageUrl)
            currentCoroutineContext().ensureActive()
            if (data != null) prepared = prepare(data)
        }
        val images = prepared
        if (metadata.title == null && metadata.summary == null && images == null) {
            throw LinkPreviewException(LinkPreviewException.Reason.Empty)
        }

        val preview = LinkPreview(
            url = url,
            siteName = metadata.siteName,
            title = metadata.title,
            summary = metadata.summary,
            thumbnail = images?.thumbnail?.let(Bytes::adopt),
            imageWidth = images?.width,
            imageHeight = images?.height,
            isVideo = metadata.isVideo,
        )
        return LinkPreviewDraft(
            preview = preview,
            largeImage = images?.large?.let(Bytes::adopt),
            largeImageWidth = images?.width,
            largeImageHeight = images?.height,
            prefersLargeImage = prefersLargeImage(images, metadata.isVideo),
        )
    }

    /** What the first request brought back: the head of an HTML page, or a picture. */
    private class Page(val finalUrl: HttpUrl, val contentType: String, val head: ByteArray, val isImage: Boolean, val imageBody: ByteArray?)

    /** Reads the page until `</head>` or [MAX_HEAD_BYTES], whichever comes first (`LinkPreviewFetcher.swift:178-211`). */
    private suspend fun fetchHead(target: HttpUrl): Page = follow(target, PAGE_ACCEPT) { finalUrl, response ->
        if (!response.isSuccessful) throw LinkPreviewException(LinkPreviewException.Reason.BadResponse)
        val contentType = response.header("Content-Type")?.lowercase(Locale.ROOT).orEmpty()
        when {
            contentType.startsWith("image/") -> {
                val body = try {
                    readImage(response)
                } catch (_: LinkPreviewException) {
                    null
                }
                Page(finalUrl, contentType, ByteArray(0), isImage = true, imageBody = body)
            }
            contentType.isEmpty() || "html" in contentType || "xml" in contentType ->
                Page(finalUrl, contentType, readHead(response.body.source()), isImage = false, imageBody = null)
            else -> throw LinkPreviewException(LinkPreviewException.Reason.NotHtml)
        }
    }

    /** `LinkPreviewFetcher.swift:213-229`; every failure but cancellation is "no image". */
    private suspend fun fetchImageOrNull(url: HttpUrl): ByteArray? =
        try {
            follow(url, IMAGE_ACCEPT) { _, response ->
                if (!response.isSuccessful) throw LinkPreviewException(LinkPreviewException.Reason.BadResponse)
                readImage(response)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }

    private suspend fun prepare(data: ByteArray): PreparedLinkImages? =
        withContext(cpu) {
            try {
                images.prepare(data)
            } catch (_: Exception) {
                null
            }
        }

    private sealed interface Hop<out T> {
        class Done<T>(val value: T) : Hop<T>
        class Redirect(val location: HttpUrl?) : Hop<Nothing>
    }

    /**
     * GET [start] (already allowed and checked), following redirects by hand: each hop's `Location`
     * is resolved against the current URL and must pass [LinkTargetRules.allowedTarget] and the
     * address check, as iOS's `RedirectGuard` demands (`LinkPreviewFetcher.swift:455-482`, an
     * `http` hop is upgraded). A refused hop surfaces like iOS's unfollowed 3xx: a bad response.
     */
    private suspend fun <T> follow(start: HttpUrl, accept: String, read: (HttpUrl, Response) -> T): T {
        var current = start
        var hops = 0
        while (true) {
            val url = current
            val hop: Hop<T> = transport.execute(request(url, accept)) { response ->
                val location = response.header("Location")
                if (response.code in REDIRECT_CODES && location != null) {
                    Hop.Redirect(url.resolve(location))
                } else {
                    Hop.Done(read(url, response))
                }
            }
            when (hop) {
                is Hop.Done -> return hop.value
                is Hop.Redirect -> {
                    hops += 1
                    val next = hop.location?.let(LinkTargetRules::allowedTarget)
                    if (hops > MAX_REDIRECTS || next == null || !LinkTargetRules.resolvesOnlyToPublicAddresses(next.host, dns)) {
                        throw LinkPreviewException(LinkPreviewException.Reason.BadResponse)
                    }
                    current = next
                }
            }
        }
    }

    private fun request(url: HttpUrl, accept: String): Request = Request.Builder()
        .url(url)
        .header("User-Agent", USER_AGENT)
        .header("Accept-Language", acceptLanguage())
        .header("Accept", accept)
        .get()
        .build()

    companion object {
        /**
         * Sites hand compact OpenGraph-only pages to known preview fetchers and a consent wall or a
         * JavaScript shell to anything else; this is the UA Signal uses (`LinkPreviewFetcher.swift:52-55`).
         */
        const val USER_AGENT = "WhatsApp/2 (compatible; ShroudBot/1.0)"
        const val MAX_HEAD_BYTES = 512 * 1024
        const val MAX_IMAGE_BYTES = 5 * 1024 * 1024
        const val MAX_REDIRECTS = 16

        internal const val PAGE_ACCEPT = "text/html,application/xhtml+xml;q=0.9,*/*;q=0.5"
        internal const val IMAGE_ACCEPT = "image/avif,image/webp,image/png,image/jpeg,image/*;q=0.8"

        /** Statuses `URLSession` follows when they carry a `Location`. */
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

        /** The head scan checks every 4 KB over the last 4 KB plus the marker (`:197-209`). */
        private const val HEAD_CHECK_INTERVAL = 4096
        private val HEAD_END = "</head".toByteArray(Charsets.US_ASCII)
        private val HEAD_END_UPPER = "</HEAD".toByteArray(Charsets.US_ASCII)

        /**
         * The preview client (media-voice-links §10.4): derived from the app's base client so it
         * shares the dispatcher, but with its own [dns] (a [PublicAddressDns]), no interceptors, no
         * cookies, no cache, no proxy (a proxy would resolve the site itself, past the address
         * check), `https` only (`MODERN_TLS`, no cleartext spec), no automatic redirects, 8 s idle
         * timeouts and a 15 s budget per request (`URLSession` request/resource timeouts,
         * `LinkPreviewFetcher.swift:88-103`), and its own small connection pool. OkHttp sends no
         * `Referer`.
         */
        fun httpClient(base: OkHttpClient, dns: Dns): OkHttpClient = base.newBuilder()
            .apply {
                interceptors().clear()
                networkInterceptors().clear()
            }
            .dns(dns)
            .proxy(Proxy.NO_PROXY)
            .cookieJar(CookieJar.NO_COOKIES)
            .cache(null)
            .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
            .connectionPool(ConnectionPool(2, 1, TimeUnit.MINUTES))
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()

        /**
         * `Telegram/Telegram`'s pick (`LinkPreviewFetcher.swift:164-168`): a big picture for videos
         * and wide card images (≥ 400 px wide and ≥ 1.2 : 1), a small square beside the text for
         * everything else.
         */
        internal fun prefersLargeImage(images: PreparedLinkImages?, isVideo: Boolean): Boolean {
            if (images == null) return false
            if (isVideo) return true
            return images.width >= 400 && images.width.toDouble() / maxOf(images.height, 1).toDouble() >= 1.2
        }

        /**
         * `de-DE,de;q=0.9,en;q=0.8` — the page in the sender's language when it has one
         * (`LinkPreviewFetcher.swift:105-112`, web `acceptLanguageHeader`): the first three
         * languages, the first bare, then `;q=0.9`, `;q=0.8`; none → `en`.
         */
        fun acceptLanguageHeader(languages: List<String>): String {
            val picked = languages.filter { it.isNotEmpty() && it != "und" }.take(3)
            if (picked.isEmpty()) return "en"
            return picked.mapIndexed { index, language ->
                if (index == 0) language else "$language;q=${String.format(Locale.ROOT, "%.1f", 1 - index * 0.1)}"
            }.joinToString(",")
        }

        /** The phone's preferred languages (`Locale.preferredLanguages`), as the header above. */
        fun deviceAcceptLanguage(): String {
            val locales = LocaleList.getDefault()
            return acceptLanguageHeader((0 until locales.size()).map { locales[it].toLanguageTag() })
        }

        /**
         * The page up to `</head` or [MAX_HEAD_BYTES]: read in steps that end on multiples of 4 KB,
         * and after each full step look for `</head`/`</HEAD` in the last 4 KB + 6 bytes, exactly when
         * iOS's byte loop checks (`LinkPreviewFetcher.swift:197-209`).
         */
        internal fun readHead(source: BufferedSource): ByteArray {
            var buffer = ByteArray(64 * 1024)
            var size = 0
            while (size < MAX_HEAD_BYTES) {
                val step = minOf(HEAD_CHECK_INTERVAL - size % HEAD_CHECK_INTERVAL, MAX_HEAD_BYTES - size)
                if (size + step > buffer.size) buffer = buffer.copyOf(minOf(buffer.size * 2, MAX_HEAD_BYTES))
                val read = source.read(buffer, size, step)
                if (read == -1) break
                size += read
                if (size >= MAX_HEAD_BYTES) break
                if (size % HEAD_CHECK_INTERVAL == 0) {
                    val from = maxOf(0, size - HEAD_CHECK_INTERVAL - HEAD_END.size)
                    if (contains(buffer, from, size, HEAD_END) || contains(buffer, from, size, HEAD_END_UPPER)) break
                }
            }
            return buffer.copyOf(size)
        }

        /**
         * The image body, refused when it declares or turns out to be over [MAX_IMAGE_BYTES]
         * (`LinkPreviewFetcher.swift:213-229`).
         */
        internal fun readImage(response: Response): ByteArray {
            val body = response.body
            if (body.contentLength() > MAX_IMAGE_BYTES) throw LinkPreviewException(LinkPreviewException.Reason.ImageTooLarge)
            val source = body.source()
            val out = okio.Buffer()
            while (true) {
                val read = source.read(out, 64L * 1024)
                if (read == -1L) break
                if (out.size > MAX_IMAGE_BYTES) throw LinkPreviewException(LinkPreviewException.Reason.ImageTooLarge)
            }
            return out.readByteArray()
        }

        private fun contains(haystack: ByteArray, from: Int, to: Int, needle: ByteArray): Boolean {
            var i = from
            while (i + needle.size <= to) {
                var match = true
                for (j in needle.indices) {
                    if (haystack[i + j] != needle[j]) {
                        match = false
                        break
                    }
                }
                if (match) return true
                i++
            }
            return false
        }
    }
}
