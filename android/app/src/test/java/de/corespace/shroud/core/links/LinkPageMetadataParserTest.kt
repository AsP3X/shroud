package de.corespace.shroud.core.links

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

/**
 * The sender builds previews from a page's `<head>`; these pin down what is read and what is
 * ignored. Ports `ios/shroudTests/LinkPageMetadataParserTests.swift:9-126` (the fetch-rule and
 * image cases live in `LinkTargetRulesTest` and `LinkPreviewImagesTest`) and the page-metadata part
 * of `web/src/linkPreview/linkPreview.selftest.ts:85-160`; HTML verbatim.
 */
class LinkPageMetadataParserTest {
    private val page = "https://www.example.com/articles/42".toHttpUrl()

    private fun parse(html: String, contentType: String? = "text/html; charset=utf-8"): LinkPageMetadata =
        LinkPageMetadataParser.parse(html.toByteArray(Charsets.UTF_8), page, contentType)

    @Test
    fun openGraphWins() { // :15-35
        val html = """
            <html><head>
            <title>Fallback title</title>
            <meta property="og:site_name" content="Example">
            <meta property="og:title" content="The real title">
            <meta name="description" content="Plain description">
            <meta property="og:description" content="OG description">
            <meta property="og:image" content="https://cdn.example.com/card.jpg">
            <meta property="og:image:width" content="1200"><meta property="og:image:height" content="630">
            </head><body></body></html>
        """.trimIndent()
        val metadata = parse(html)
        assertEquals("Example", metadata.siteName)
        assertEquals("The real title", metadata.title)
        assertEquals("OG description", metadata.summary)
        assertEquals("https://cdn.example.com/card.jpg", metadata.imageUrl.toString())
        assertEquals(1200, metadata.imageWidth)
        assertEquals(630, metadata.imageHeight)
        assertFalse(metadata.isVideo)
    }

    @Test
    fun fallsBackToTwitterAndTitle() { // :37-48
        val html = """
            <head><TITLE>Only a &amp; title</TITLE>
            <meta name="twitter:description" content='Single &#39;quoted&#39; &#x2014; ok'>
            <meta name=twitter:image content=/img/card.png></head>
        """.trimIndent()
        val metadata = parse(html)
        assertEquals("Only a & title", metadata.title)
        assertEquals("Single 'quoted' — ok", metadata.summary)
        assertEquals("https://www.example.com/img/card.png", metadata.imageUrl.toString())
        assertNull(metadata.siteName)
    }

    @Test
    fun videoPagesAreFlagged() { // :50-53
        assertTrue(parse("""<head><meta property="og:type" content="video.other"><meta property="og:title" content="A"></head>""").isVideo)
        assertTrue(parse("""<head><meta property="og:video:url" content="https://x.com/v"><meta property="og:title" content="A"></head>""").isVideo)
    }

    @Test
    fun insecureAndRelativeImagesAreResolvedToHttps() { // :55-62
        val insecure = parse("""<head><meta property="og:image" content="http://cdn.example.com/a.jpg"></head>""")
        assertEquals("https", insecure.imageUrl?.scheme)
        assertEquals("https://cdn.example.com/a.jpg", insecure.imageUrl.toString())
        val protocolRelative = parse("""<head><meta property="og:image" content="//cdn.example.com/b.jpg"></head>""")
        assertEquals("https://cdn.example.com/b.jpg", protocolRelative.imageUrl.toString())
        val dataUrl = parse("""<head><meta property="og:image" content="data:image/png;base64,AAAA"></head>""")
        assertNull(dataUrl.imageUrl)
    }

    @Test
    fun scriptUrlsAreDropped() { // linkPreview.selftest.ts:160
        assertNull(LinkPageMetadataParser.secureUrl("javascript:alert(1)", page))
        assertNull(LinkPageMetadataParser.secureUrl("ftp://example.com/a.jpg", page))
    }

    @Test
    fun tagsAfterTheHeadAreIgnored() { // :64-69
        val metadata = parse("""<head><meta property="og:title" content="Head"></head><body><meta property="og:description" content="Injected"></body>""")
        assertEquals("Head", metadata.title)
        assertNull(metadata.summary)
    }

    @Test
    fun bodyEndsTheHeadWhenThereIsNoClosingTag() {
        val metadata = parse("""<meta property="og:title" content="Head"><body><meta property="og:description" content="Injected">""")
        assertEquals("Head", metadata.title)
        assertNull(metadata.summary)
    }

    @Test
    fun metadataTagIsNotMeta() { // :71-74
        assertEquals("Real", parse("""<head><metadata content="x"></metadata><meta property="og:title" content="Real"></head>""").title)
    }

    @Test
    fun latin1PagesDecode() { // :76-81
        val html = "<head><meta charset=\"iso-8859-1\"><meta property=\"og:title\" content=\"Grüße\"></head>"
        val data = html.toByteArray(Charsets.ISO_8859_1)
        assertEquals("Grüße", LinkPageMetadataParser.parse(data, page, "text/html").title)
    }

    @Test
    fun latin1FromTheHeader() { // linkPreview.selftest.ts:140-145
        val data = "<head><meta property=\"og:title\" content=\"Gr".toByteArray() +
            byteArrayOf(0xFC.toByte(), 0xDF.toByte(), 0x65) +
            "\"></head>".toByteArray()
        assertEquals("Grüße", LinkPageMetadataParser.parse(data, page, "text/html; charset=iso-8859-1").title)
    }

    @Test
    fun emptyPageHasNothing() { // :83-85
        assertTrue(parse("<html><head></head><body>Hello</body></html>").isEmpty)
    }

    @Test
    fun greaterThanInsideAQuotedValueKeepsTheWholeText() { // :87-91
        val metadata = parse("""<head><meta property="og:title" content="Rust > Go? A comparison"><meta name='description' content='Home > Shop -> Sale'></head>""")
        assertEquals("Rust > Go? A comparison", metadata.title)
        assertEquals("Home > Shop -> Sale", metadata.summary)
    }

    @Test
    fun brokenQuotesDoNotSwallowTheNextTag() { // :93-96
        val metadata = parse("""<head><meta name="description" content="He said "hi"" ><meta property="og:title" content="After"></head>""")
        assertEquals("After", metadata.title)
    }

    @Test
    fun registeredLegacyCharsetsDecode() { // :98-115
        val cyrillic = "<head><meta charset=\"windows-1251\"><meta property=\"og:title\" content=\"Привет, мир\"></head>"
            .toByteArray(Charset.forName("windows-1251"))
        assertEquals("Привет, мир", LinkPageMetadataParser.parse(cyrillic, page, "text/html").title)

        val chinese = "<head><meta property=\"og:title\" content=\"你好世界\"></head>".toByteArray(Charset.forName("GB18030"))
        assertEquals("你好世界", LinkPageMetadataParser.parse(chinese, page, "text/html; charset=GBK").title)
    }

    @Test
    fun windows1251BytesFromTheWebVector() { // linkPreview.selftest.ts:133-139
        val data = "<head><meta charset=\"windows-1251\"><meta property=\"og:title\" content=\"".toByteArray() +
            byteArrayOf(0xCF.toByte(), 0xF0.toByte(), 0xE8.toByte(), 0xE2.toByte(), 0xE5.toByte(), 0xF2.toByte()) +
            "\"></head>".toByteArray()
        assertEquals("Привет", LinkPageMetadataParser.parse(data, page, "text/html").title)
    }

    @Test
    fun multiByteCharacterCutAtTheEndKeepsTheEncoding() { // :117-126
        val full = "<head><meta charset=\"shift_jis\"><meta property=\"og:title\" content=\"日本語のタイトル\"></head><body>日本"
            .toByteArray(Charset.forName("Shift_JIS"))
        val data = full.copyOf(full.size - 1)
        assertEquals("日本語のタイトル", LinkPageMetadataParser.parse(data, page, "text/html").title)
    }

    @Test
    fun utf8CutMidCharacterIsStillUtf8() { // linkPreview.selftest.ts:147-152
        val full = "<head><meta property=\"og:title\" content=\"Grüße\"></head><body>€".toByteArray()
        val cut = full.copyOf(full.size - 1)
        assertEquals("Grüße", LinkPageMetadataParser.parse(cut, page, "text/html; charset=utf-8").title)
    }

    @Test
    fun aQuotedCharsetInTheHeaderFallsBackToTheSniff() {
        // iOS reads `charset="…"` from the header as an empty label, then sniffs the document.
        val data = "<head><meta charset=\"iso-8859-1\"><title>Gr".toByteArray() + byteArrayOf(0xFC.toByte()) + "n</title></head>".toByteArray()
        assertEquals("Grün", LinkPageMetadataParser.parse(data, page, "text/html; charset=\"utf-8\"").title)
    }

    @Test
    fun undeclaredLegacyBytesDecodeLossily() {
        val data = "<head><title>A".toByteArray() + byteArrayOf(0xFF.toByte()) + "B</title></head>".toByteArray()
        assertEquals("A�B", LinkPageMetadataParser.parse(data, page, null).title)
    }

    @Test
    fun entities() { // linkPreview.selftest.ts:158
        assertEquals("a & b A B &nope; &", LinkPageMetadataParser.decodeEntities("a &amp; b &#65; &#x42; &nope; &"))
    }

    @Test
    fun entityRules() {
        // The `;` must come within 12 characters of the `&`.
        assertEquals("&abcdefghijkl;", LinkPageMetadataParser.decodeEntities("&abcdefghijkl;"))
        assertEquals("€ € — ‘", LinkPageMetadataParser.decodeEntities("&euro; &#8364; &#X2014; &lsquo;"))
        // Not a Unicode scalar: a surrogate or past U+10FFFF stays as written.
        assertEquals("&#xD800; &#1114112;", LinkPageMetadataParser.decodeEntities("&#xD800; &#1114112;"))
        assertEquals("😀", LinkPageMetadataParser.decodeEntities("&#x1F600;"))
        // Named entities are case-sensitive.
        assertEquals("Ä &AMP;", LinkPageMetadataParser.decodeEntities("&Auml; &AMP;"))
        assertEquals("ab", LinkPageMetadataParser.decodeEntities("a&shy;b"))
    }

    @Test
    fun attributesFirstOccurrenceWinsAndNamesAreLowercased() {
        val attributes = LinkPageMetadataParser.attributes(""" Property="og:title" content='A' content="B" async data-x = y /""")
        assertEquals("og:title", attributes["property"])
        assertEquals("A", attributes["content"])
        assertEquals("", attributes["async"])
        assertEquals("y", attributes["data-x"])
    }

    @Test
    fun theFirstMetaForANameWins() {
        val metadata = parse("""<head><meta property="og:title" content="First"><meta property="og:title" content="Second"></head>""")
        assertEquals("First", metadata.title)
    }

    @Test
    fun blankContentFallsThroughToTheNextSource() {
        val metadata = parse("""<head><title>Doc</title><meta property="og:title" content="   "><meta name="twitter:title" content=""></head>""")
        assertEquals("Doc", metadata.title)
    }

    @Test
    fun declaredImageSizeIsAStrictInteger() {
        val metadata = parse("""<head><meta property="og:image" content="/a.png"><meta property="og:image:width" content=" 1200"><meta property="og:image:height" content="١٢"></head>""")
        assertNull(metadata.imageWidth)
        assertNull(metadata.imageHeight)
        assertEquals("https://www.example.com/a.png", metadata.imageUrl.toString())
    }

    @Test
    fun siteNameFallsBackToApplicationName() {
        assertEquals("App", parse("""<head><meta name="application-name" content="App"><meta property="og:title" content="T"></head>""").siteName)
    }

    @Test
    fun itempropCountsAsAName() {
        assertEquals("Micro", parse("""<head><meta itemprop="description" content="Micro"></head>""").summary)
    }

    @Test
    fun twitterPlayerIsAVideo() {
        assertTrue(parse("""<head><meta name="twitter:player" content="https://x.com/p"><meta property="og:title" content="A"></head>""").isVideo)
    }

    @Test
    fun metadataNeverPrintsPageTexts() {
        val text = parse("""<head><meta property="og:title" content="Secret title"></head>""").toString()
        assertFalse(text.contains("Secret"))
    }
}
