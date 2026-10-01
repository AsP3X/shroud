package de.corespace.shroud.core.links

import de.corespace.shroud.core.model.Bytes
import de.corespace.shroud.core.net.wire.Icu4jTextUnitsRule
import de.corespace.shroud.core.net.wire.LinkPreview
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The composer's link preview state machine, driven by a fake fetcher (no network) on virtual time.
 * Ports `ios/shroudTests/LinkPreviewComposerTests.swift:46-155` (debounce 0, as there) and the
 * composer part of `web/src/linkPreview/linkPreview.selftest.ts:210-288`, plus the 450 ms debounce
 * of `LinkPreviewComposer.swift:37-43` (media-voice-links §10.6).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LinkPreviewComposerTest {
    @get:Rule
    val textUnits = Icu4jTextUnitsRule()

    /** Returns [draft] for every URL with the URL filled in, or fails when it is null (`:9-23`). */
    private class FakeFetcher(var draft: LinkPreviewDraft?) : LinkPreviewFetching {
        val requested = ArrayList<String>()
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun fetchPreview(url: String): LinkPreviewDraft {
            requested += url
            gate?.await()
            val template = draft ?: throw LinkPreviewException(LinkPreviewException.Reason.BadResponse)
            val p = template.preview
            val preview = LinkPreview(
                url = url,
                siteName = p.siteName,
                title = p.title,
                summary = p.summary,
                thumbnail = p.thumbnail,
                imageWidth = p.imageWidth,
                imageHeight = p.imageHeight,
                isVideo = p.isVideo,
                showsAboveText = p.showsAboveText,
            )
            return template.copy(preview = preview)
        }
    }

    /** `LinkPreviewComposerTests.swift:25-38`. */
    private fun draft(large: Boolean) = LinkPreviewDraft(
        preview = LinkPreview(url = "https://example.com", siteName = "Example", title = "A page", thumbnail = Bytes.of(byteArrayOf(1, 2, 3))),
        largeImage = if (large) Bytes.of(byteArrayOf(4, 5, 6)) else null,
        largeImageWidth = if (large) 1200 else null,
        largeImageHeight = if (large) 630 else null,
        prefersLargeImage = large,
    )

    private fun TestScope.composer(fetcher: FakeFetcher, debounceMs: Long = 0) =
        LinkPreviewComposer(fetcher, this, isEnabled = { true }, debounceMs = debounceMs)

    @Test
    fun loadsAPreviewForTheFirstLink() = runTest(StandardTestDispatcher()) { // :46-53
        val fetcher = FakeFetcher(draft(large = false))
        val composer = composer(fetcher)
        composer.draftChanged("look https://example.com and https://other.org", enabled = true)
        advanceUntilIdle()
        assertEquals("https://example.com", composer.draft?.preview?.url)
        assertEquals(listOf("https://example.com"), fetcher.requested)
    }

    @Test
    fun plainTextShowsNothing() = runTest(StandardTestDispatcher()) { // :55-62
        val fetcher = FakeFetcher(draft(large = false))
        val composer = composer(fetcher)
        composer.draftChanged("no links here", enabled = true)
        advanceUntilIdle()
        assertEquals(LinkPreviewComposer.Phase.Idle, composer.phase)
        assertTrue(fetcher.requested.isEmpty())
    }

    @Test
    fun switchedOffNeverFetches() = runTest(StandardTestDispatcher()) { // :64-71
        val fetcher = FakeFetcher(draft(large = false))
        val composer = composer(fetcher)
        composer.draftChanged("https://example.com", enabled = false)
        advanceUntilIdle()
        assertEquals(LinkPreviewComposer.Phase.Idle, composer.phase)
        assertTrue(fetcher.requested.isEmpty())
    }

    @Test
    fun theSettingIsReadOnEveryDraftChange() = runTest(StandardTestDispatcher()) {
        val fetcher = FakeFetcher(draft(large = false))
        var enabled = false
        val composer = LinkPreviewComposer(fetcher, this, isEnabled = { enabled }, debounceMs = 0)
        composer.draftChanged("https://example.com")
        advanceUntilIdle()
        assertTrue(fetcher.requested.isEmpty())
        enabled = true
        composer.draftChanged("https://example.com")
        advanceUntilIdle()
        assertEquals(listOf("https://example.com"), fetcher.requested)
    }

    @Test
    fun failedFetchHidesTheBar() = runTest(StandardTestDispatcher()) { // :73-78
        val composer = composer(FakeFetcher(null))
        composer.draftChanged("https://example.com", enabled = true)
        runCurrent()
        assertEquals(LinkPreviewComposer.Phase.Idle, composer.phase)
    }

    @Test
    fun dismissedLinkStaysDismissedButANewLinkLoads() = runTest(StandardTestDispatcher()) { // :80-99
        val fetcher = FakeFetcher(draft(large = false))
        val composer = composer(fetcher)
        composer.draftChanged("https://example.com", enabled = true)
        advanceUntilIdle()
        assertNotNull(composer.draft)
        composer.dismiss()
        assertEquals(LinkPreviewComposer.Phase.Idle, composer.phase)

        composer.draftChanged("https://example.com is great", enabled = true)
        advanceUntilIdle()
        assertEquals(LinkPreviewComposer.Phase.Idle, composer.phase)

        composer.draftChanged("https://example.com vs https://other.org", enabled = true)
        advanceUntilIdle()
        assertEquals("the first link decides, and it was dismissed", LinkPreviewComposer.Phase.Idle, composer.phase)

        composer.draftChanged("try https://other.org", enabled = true)
        advanceUntilIdle()
        assertEquals("https://other.org", composer.draft?.preview?.url)
    }

    @Test
    fun clearingTheDraftForgetsDismissedLinks() = runTest(StandardTestDispatcher()) {
        val fetcher = FakeFetcher(draft(large = false))
        val composer = composer(fetcher)
        composer.draftChanged("https://example.com", enabled = true)
        advanceUntilIdle()
        composer.dismiss()
        composer.draftChanged("no link left", enabled = true)
        composer.draftChanged("https://example.com", enabled = true)
        advanceUntilIdle()
        assertEquals("dismissed while the draft has text", LinkPreviewComposer.Phase.Idle, composer.phase)
        composer.draftChanged("", enabled = true)
        composer.draftChanged("https://example.com", enabled = true)
        advanceUntilIdle()
        assertNotNull("an emptied draft starts over", composer.draft)
    }

    @Test
    fun dismissingWhileLoadingDropsTheResult() = runTest(StandardTestDispatcher()) {
        val fetcher = FakeFetcher(draft(large = false)).apply { gate = CompletableDeferred() }
        val composer = composer(fetcher)
        composer.draftChanged("https://example.com", enabled = true)
        runCurrent()
        assertTrue(composer.phase is LinkPreviewComposer.Phase.Loading)
        composer.dismiss()
        fetcher.gate?.complete(Unit)
        advanceUntilIdle()
        assertEquals(LinkPreviewComposer.Phase.Idle, composer.phase)
        composer.draftChanged("https://example.com again", enabled = true)
        advanceUntilIdle()
        assertEquals(LinkPreviewComposer.Phase.Idle, composer.phase)
        assertEquals(1, fetcher.requested.size)
    }

    @Test
    fun attachmentNeedsTheLinkStillInTheText() = runTest(StandardTestDispatcher()) { // :101-113
        val composer = composer(FakeFetcher(draft(large = false)))
        composer.draftChanged("https://example.com", enabled = true)
        advanceUntilIdle()
        assertNull(composer.takeAttachment("the link is gone"))
        assertEquals("sending resets the composer", LinkPreviewComposer.Phase.Idle, composer.phase)

        composer.draftChanged("https://example.com", enabled = true)
        advanceUntilIdle()
        val attachment = composer.takeAttachment("https://example.com")
        assertEquals("https://example.com", attachment?.preview?.url)
        assertNull(attachment?.largeImage)
        assertEquals(LinkPreviewComposer.Phase.Idle, composer.phase)
    }

    @Test
    fun aPreviewStillLoadingIsLeftOff() = runTest(StandardTestDispatcher()) {
        val fetcher = FakeFetcher(draft(large = false)).apply { gate = CompletableDeferred() }
        val composer = composer(fetcher)
        composer.draftChanged("https://example.com", enabled = true)
        runCurrent()
        assertNull("sending never waits on a website", composer.takeAttachment("https://example.com"))
        fetcher.gate?.complete(Unit)
        advanceUntilIdle()
        assertEquals(LinkPreviewComposer.Phase.Idle, composer.phase)
    }

    @Test
    fun hostCaseIsTheSameLinkButPathCaseIsNot() = runTest(StandardTestDispatcher()) { // :115-134
        val fetcher = FakeFetcher(draft(large = false))
        val composer = composer(fetcher)
        composer.draftChanged("https://Example.com/Tour", enabled = true)
        advanceUntilIdle()
        assertNotNull(composer.draft)

        // The same page with its host retyped: no second fetch, and the preview still goes out.
        composer.draftChanged("https://example.com/Tour", enabled = true)
        advanceUntilIdle()
        assertEquals(1, fetcher.requested.size)
        assertNotNull(composer.takeAttachment("https://example.com/Tour"))

        // Paths are case-sensitive (YouTube ids, …): another page gets its own preview.
        composer.draftChanged("https://example.com/Tour", enabled = true)
        advanceUntilIdle()
        assertNotNull(composer.draft)
        composer.draftChanged("https://example.com/tour", enabled = true)
        advanceUntilIdle()
        assertEquals(2, fetcher.requested.size)
        assertEquals("https://example.com/tour", fetcher.requested.last())
        assertEquals("https://example.com/tour", composer.draft?.preview?.url)
    }

    @Test
    fun optionsShapeTheAttachment() = runTest(StandardTestDispatcher()) { // :136-155
        val composer = composer(FakeFetcher(draft(large = true)))
        composer.draftChanged("https://example.com", enabled = true)
        advanceUntilIdle()
        assertTrue(composer.usesLargeImage)
        assertTrue(composer.canToggleImageSize)

        composer.toggleShowsAboveText()
        composer.toggleImageSize()
        assertFalse(composer.usesLargeImage)
        val small = composer.takeAttachment("https://example.com")
        assertEquals(true, small?.preview?.showsAboveText)
        assertNull("smaller image sends the inline thumbnail only", small?.largeImage)
        assertNull(small?.largeImageWidth)

        composer.draftChanged("https://example.com", enabled = true)
        advanceUntilIdle()
        val large = composer.takeAttachment("https://example.com")
        assertArrayEquals(byteArrayOf(4, 5, 6), large?.largeImage?.toByteArray())
        assertEquals(1200, large?.largeImageWidth)
        assertEquals(630, large?.largeImageHeight)
        assertEquals("options reset per preview", false, large?.preview?.showsAboveText)
    }

    @Test
    fun theSizeToggleNeedsBothLayouts() = runTest(StandardTestDispatcher()) {
        val composer = composer(FakeFetcher(draft(large = false)))
        composer.draftChanged("https://example.com", enabled = true)
        advanceUntilIdle()
        assertFalse(composer.canToggleImageSize)
        composer.toggleImageSize()
        assertNull(composer.state.value.largeImageOverride)
        assertFalse(composer.usesLargeImage)
    }

    @Test
    fun aRetypedLinkComesFromTheCacheWithFreshOptions() = runTest(StandardTestDispatcher()) {
        val fetcher = FakeFetcher(draft(large = true))
        val composer = composer(fetcher)
        composer.draftChanged("https://example.com", enabled = true)
        advanceUntilIdle()
        composer.toggleShowsAboveText()
        composer.draftChanged("https://other.org", enabled = true)
        advanceUntilIdle()
        composer.draftChanged("https://example.com", enabled = true)
        assertEquals("no debounce for a cached link", "https://example.com", composer.draft?.preview?.url)
        assertFalse(composer.showsAboveText)
        assertEquals(2, fetcher.requested.size)
    }

    @Test
    fun theCacheHoldsEightLinks() = runTest(StandardTestDispatcher()) {
        val fetcher = FakeFetcher(draft(large = false))
        val composer = composer(fetcher)
        for (i in 0..8) {
            composer.draftChanged("https://example.com/$i", enabled = true)
            advanceUntilIdle()
        }
        assertEquals(9, fetcher.requested.size)
        composer.draftChanged("https://example.com/8 x", enabled = true) // showing: nothing to do
        composer.draftChanged("https://example.com/1", enabled = true) // cached
        assertEquals(9, fetcher.requested.size)
        composer.draftChanged("https://example.com/0", enabled = true) // evicted (oldest)
        advanceUntilIdle()
        assertEquals(10, fetcher.requested.size)
    }

    @Test
    fun typingIsDebouncedByFourHundredFiftyMilliseconds() = runTest(StandardTestDispatcher()) {
        val fetcher = FakeFetcher(draft(large = false))
        val composer = composer(fetcher, debounceMs = LinkPreviewComposer.DEBOUNCE_MS)
        composer.draftChanged("https://example.c", enabled = true)
        advanceTimeBy(300)
        composer.draftChanged("https://example.co", enabled = true)
        advanceTimeBy(300)
        composer.draftChanged("https://example.com", enabled = true)
        advanceTimeBy(449)
        runCurrent()
        assertTrue("nothing while typing", fetcher.requested.isEmpty())
        assertEquals(LinkPreviewComposer.Phase.Idle, composer.phase)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf("https://example.com"), fetcher.requested)
        advanceUntilIdle()
        assertNotNull(composer.draft)
    }

    @Test
    fun aLinkLeavingTheTextCancelsThePendingFetch() = runTest(StandardTestDispatcher()) {
        val fetcher = FakeFetcher(draft(large = false))
        val composer = composer(fetcher, debounceMs = LinkPreviewComposer.DEBOUNCE_MS)
        composer.draftChanged("https://example.com", enabled = true)
        advanceTimeBy(200)
        composer.draftChanged("", enabled = true)
        advanceUntilIdle()
        assertTrue(fetcher.requested.isEmpty())
    }

    @Test
    fun aNewLinkWhileLoadingReplacesTheFetch() = runTest(StandardTestDispatcher()) {
        val fetcher = FakeFetcher(draft(large = false)).apply { gate = CompletableDeferred() }
        val composer = composer(fetcher)
        composer.draftChanged("https://example.com", enabled = true)
        runCurrent()
        composer.draftChanged("https://other.org", enabled = true)
        runCurrent()
        fetcher.gate?.complete(Unit)
        advanceUntilIdle()
        assertEquals("https://other.org", composer.draft?.preview?.url)
    }

    @Test
    fun resetClearsEverything() = runTest(StandardTestDispatcher()) {
        val fetcher = FakeFetcher(draft(large = true))
        val composer = composer(fetcher)
        composer.draftChanged("https://example.com", enabled = true)
        advanceUntilIdle()
        composer.toggleShowsAboveText()
        composer.dismiss()
        composer.reset()
        assertEquals(LinkPreviewComposer.State(), composer.state.value)
        composer.draftChanged("https://example.com", enabled = true)
        advanceUntilIdle()
        assertNotNull("reset forgets dismissed links", composer.draft)
    }

    @Test
    fun theLoadingPhaseNeverPrintsTheLink() {
        assertFalse(LinkPreviewComposer.Phase.Loading("https://secret.example.org").toString().contains("secret"))
    }

    @Test
    fun linkKeys() {
        assertEquals(LinkPreviewComposer.key("https://Example.com/Tour"), LinkPreviewComposer.key("https://example.com/Tour"))
        assertTrue(LinkPreviewComposer.key("https://example.com/Tour") != LinkPreviewComposer.key("https://example.com/tour"))
        assertEquals(LinkPreviewComposer.key("HTTPS://EXAMPLE.COM"), LinkPreviewComposer.key("https://example.com"))
        assertEquals("not a url", LinkPreviewComposer.key("not a url"))
    }
}
