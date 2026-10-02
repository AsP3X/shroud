package de.corespace.shroud.ui.conversation.bubble

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.provider.Settings
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import de.corespace.shroud.core.model.ChatMessageKind
import de.corespace.shroud.core.model.ChatPeerActivity
import de.corespace.shroud.core.model.MediaTransfer
import de.corespace.shroud.core.model.ReceiptStatus
import de.corespace.shroud.core.net.wire.LinkPreview
import de.corespace.shroud.core.net.wire.MessageReplyReference
import de.corespace.shroud.core.transcription.TranscriptionInstallState
import de.corespace.shroud.core.model.Bytes
import de.corespace.shroud.ui.components.ComposeHarness
import de.corespace.shroud.ui.conversation.DecodedImageCache
import de.corespace.shroud.ui.conversation.LinkPreviewImageCache
import de.corespace.shroud.ui.conversation.MessageRowModel
import de.corespace.shroud.ui.conversation.bubble.BubbleRenderFixtures.ME
import de.corespace.shroud.ui.conversation.bubble.BubbleRenderFixtures.PEER
import de.corespace.shroud.ui.conversation.bubble.BubbleRenderFixtures.PEER_NAME
import de.corespace.shroud.ui.conversation.bubble.BubbleRenderFixtures.at
import de.corespace.shroud.ui.conversation.bubble.BubbleRenderFixtures.envelope
import de.corespace.shroud.ui.conversation.bubble.BubbleRenderFixtures.landscape
import de.corespace.shroud.ui.conversation.bubble.BubbleRenderFixtures.message
import de.corespace.shroud.ui.conversation.bubble.BubbleRenderFixtures.reaction
import de.corespace.shroud.ui.conversation.bubble.BubbleRenderFixtures.row
import de.corespace.shroud.ui.theme.ShroudTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.UUID

/**
 * Renders every bubble kind and state (conversation-thread §4–§14, W3-THREAD-BUBBLES acceptance), light
 * and dark, to PNGs under `android/app/build/outputs/c10-screens/` for the design pass to lay next to
 * the frames `hc3Jf` (Conversation), `wFOwa` (· Dark), `jBwNA` (Link Previews), `X3XGO` (Links),
 * `RueI9` / `V67iGb` (replies), `ap7Vi` (Video Message), `SkPBh` (Transcript Open), `NOg29` (Typing)
 * and `nPQKZ` (Notes). The pictures are not committed.
 *
 * Each sheet is one column of rows on the chat background, in a 412 dp phone's thread (row width
 * 380 dp, so a bubble caps at 324 dp), 2× density. Motion is reduced so every animation has settled
 * (the spinners and the typing ink draw still). 24-hour clock.
 *
 * Runs only when asked — `SHROUD_RENDER_SCREENS=1` in the environment of the Gradle call
 * (`SHROUD_RENDER_SCREENS=1 gw :app:testDebugUnitTest --tests '*BubbleScreensRenderTest'`) — so the
 * shared unit-test JVM is not loaded with full-sheet renders on every run.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h1900dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BubbleScreensRenderTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val hosts = ArrayList<ComposeHarness>()
    private val rendersWanted: Boolean = System.getenv(RENDER_ENV) == "1"

    @Before
    fun onlyWhenAsked() {
        assumeTrue("set $RENDER_ENV=1 to render the bubble states", rendersWanted)
        Settings.System.putString(RuntimeEnvironment.getApplication().contentResolver, Settings.System.TIME_12_24, "24")
    }

    @After
    fun tearDown() {
        scope.cancel()
        hosts.forEach { host ->
            host.activity.findViewById<ViewGroup>(android.R.id.content).removeAllViews()
            host.idle()
        }
        Thread.sleep(SETTLE_REAL_MS)
        hosts.firstOrNull()?.idle()
        DecodedImageCache.clear()
        LinkPreviewImageCache.clear()
        VoiceTranscriptDisclosure.reset()
    }

    // ---- pictures ----

    private val photoJpeg = landscape(1200, 900)
    private val portraitJpeg = landscape(900, 1200, warm = true)
    private val thumbJpeg = landscape(40, 30, quality = 60)
    private val portraitThumb = landscape(30, 40, quality = 60, warm = true)
    private val linkThumb = landscape(108, 108, quality = 70, warm = true)
    private val linkLarge = landscape(1200, 630)
    private val linkPlaceholder = landscape(32, 17, quality = 50)

    // ---- the host ----

    private class Sheet(val services: RenderBubbleServices, val context: RecordingBubbleContext)

    private fun sheet(name: String, dark: Boolean, setup: (Sheet) -> Unit = {}, rows: List<@Composable (Sheet) -> Unit>, save: Boolean = true): ComposeHarness {
        val sheet = Sheet(RenderBubbleServices(RuntimeEnvironment.getApplication(), scope), RecordingBubbleContext())
        setup(sheet)
        var contentHeight = 0
        val ui = ComposeHarness(dark = dark) {
            CompositionLocalProvider(LocalBubbleServices provides sheet.services, LocalChatRowWidth provides ROW_WIDTH) {
                Box(Modifier.fillMaxSize().background(ShroudTheme.colors.backgroundChat)) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .onSizeChanged { contentHeight = it.height }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(ROW_GAP),
                    ) {
                        rows.forEach { it(sheet) }
                    }
                }
            }
        }
        hosts += ui
        settle(ui)
        val root = ui.root
        val full = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(full))
        val height = contentHeight.coerceIn(1, full.height)
        val cropped = Bitmap.createBitmap(full, 0, 0, full.width, height)
        assertTrue("$name drew nothing", height > 1)
        val dir = File("build/outputs/c10-screens").apply { mkdirs() }
        if (save) File(dir, "$name-${if (dark) "dark" else "light"}.png").outputStream().use { cropped.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return ui
    }

    /** Effects, off-main decodes and their recompositions, then a draw so layout is current. */
    private fun settle(ui: ComposeHarness) {
        repeat(4) {
            ui.idle()
            Thread.sleep(DECODE_REAL_MS)
        }
        ui.root.draw(Canvas(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)))
        ui.idle()
    }

    private fun bubble(model: MessageRowModel): @Composable (Sheet) -> Unit = { sheet -> MessageBubble(model, sheet.context) }

    private fun typing(activity: ChatPeerActivity): @Composable (Sheet) -> Unit = { _ -> TypingIndicatorBubble(activity) }

    /**
     * A downloaded photo: its bytes, decoded here once, one at a time, into [DecodedImageCache] — as a
     * bubble scrolling back into view finds it. Robolectric's native decoder is not safe to run for
     * several pictures at once (concurrent decodes come back as the thumbnail), which a phone's is.
     */
    private fun Sheet.photo(id: UUID, bytes: ByteArray) {
        services.media[id] = bytes
        val image = runBlocking { BubbleImages.decodeSampled(bytes, PREDECODE_EDGE_PX) } ?: error("fixture does not decode")
        DecodedImageCache.store(id, image, DecodedImageCache.Source.Full, bytes.size)
    }

    /** A link preview's large picture, decoded into [LinkPreviewImageCache] the same way. */
    private fun Sheet.linkImage(id: UUID, bytes: ByteArray) {
        services.media[id] = bytes
        val image = runBlocking { BubbleImages.decodeSampled(bytes, PREDECODE_EDGE_PX) } ?: error("fixture does not decode")
        LinkPreviewImageCache.store(id, LinkPreviewImageCache.Variant.Full, bytes.size, image)
    }

    private fun both(name: String, setup: (Sheet) -> Unit = {}, rows: () -> List<@Composable (Sheet) -> Unit>) {
        sheet(name, dark = false, setup = setup, rows = rows())
        sheet(name, dark = true, setup = setup, rows = rows())
    }

    // ---- 01 text (hc3Jf, the iOS preview "Telegram-style bubbles") ----

    @Test
    fun text() = both("01-text") {
        listOf(
            bubble(row(message("a", mine = false, time = at(11, 0)))),
            bubble(row(message("👍", mine = true, time = at(11, 1), receipt = ReceiptStatus.Sent))),
            bubble(row(message("Ok", mine = true, time = at(11, 3), receipt = ReceiptStatus.Delivered))),
            bubble(row(message("Two lines\nand the time must clear the second one", mine = false, time = at(11, 6)))),
            bubble(
                row(
                    message(
                        "Hey! Are we still on for tomorrow? Parking near the trailhead fills up fast on weekends so let's leave early.",
                        mine = true,
                        time = at(12, 10),
                        receipt = ReceiptStatus.Read,
                    ),
                ),
            ),
            bubble(row(message("Sounds good, see you tomorrow! I'll bring snacks.", mine = false, time = at(12, 11)))),
            bubble(
                row(
                    message(
                        "Key takeaways:\nMetric\tValue\nDurchschnittsgehalt\t41.000 € brutto / Jahr\nMonatsgehalt\t≈ 3.417 €\n\t•\tPersonalverantwortung bringt im Schnitt +19 %.",
                        mine = false,
                        time = at(15, 58),
                    ),
                ),
            ),
            bubble(row(message("longword ".repeat(12), mine = true, time = at(12, 15), receipt = ReceiptStatus.Sending))),
            bubble(row(message("Just a quick one that ends near the edge of the line", mine = false, time = at(12, 16)))),
            bubble(row(message("And this one ends exactly where the time wants to sit", mine = true, time = at(12, 17)))),
            bubble(row(message("gone", mine = false, time = at(12, 20), deleted = true))),
            bubble(row(message("gone", mine = true, time = at(12, 21), deleted = true))),
        )
    }

    // ---- 02 receipts ----

    @Test
    fun receipts() = both("02-receipts") {
        listOf(
            bubble(row(message("Sending", mine = true, time = at(9, 41), receipt = ReceiptStatus.Sending))),
            bubble(row(message("Sent", mine = true, time = at(9, 41), receipt = ReceiptStatus.Sent))),
            bubble(row(message("Delivered", mine = true, time = at(9, 41), receipt = ReceiptStatus.Delivered))),
            bubble(row(message("Read", mine = true, time = at(9, 41), receipt = ReceiptStatus.Read))),
            bubble(row(message("Failed", mine = true, time = at(9, 41), receipt = ReceiptStatus.Failed, sendError = "Couldn't send."))),
            bubble(row(message("A note to myself", mine = true, time = at(9, 42), receipt = ReceiptStatus.Read, peer = ME), isNotes = true)),
        )
    }

    // ---- 03 replies (RueI9, V67iGb; components h6YqVO / UZDu5) ----

    @Test
    fun replies() {
        val thumb = BitmapFactory.decodeByteArray(thumbJpeg, 0, thumbJpeg.size).asImageBitmap()
        val quotedText = ReplyQuoteContent("You", "Are we still on for tomorrow?", isStandIn = false, thumbnail = null, symbol = null)
        val quotedLong = ReplyQuoteContent(PEER_NAME, "Parking near the trailhead fills up fast on weekends so let's leave early", isStandIn = false, thumbnail = null, symbol = null)
        val quotedPhoto = ReplyQuoteContent(PEER_NAME, "Photo", isStandIn = true, thumbnail = thumb, symbol = null)
        val quotedPhotoSymbol = ReplyQuoteContent("You", "Photo", isStandIn = true, thumbnail = null, symbol = de.corespace.shroud.ui.theme.ShroudIcons.Image)
        val quotedVoice = ReplyQuoteContent(PEER_NAME, "Voice message", isStandIn = true, thumbnail = null, symbol = de.corespace.shroud.ui.theme.ShroudIcons.Waveform)
        val quotedVideo = ReplyQuoteContent(PEER_NAME, "Video", isStandIn = true, thumbnail = null, symbol = de.corespace.shroud.ui.theme.ShroudIcons.Video)
        val quotedDeleted = ReplyQuoteContent(PEER_NAME, "Message deleted", isStandIn = true, thumbnail = null, symbol = null)
        val reference = MessageReplyReference(UUID.randomUUID(), PEER, MessageReplyReference.Kind.Text, "x")
        both("03-replies") {
            listOf(
                bubble(row(message("Yes! 8 sharp.", mine = false, time = at(9, 50), replyTo = reference), quote = quotedText)),
                bubble(row(message("Ok", mine = true, time = at(9, 51), replyTo = reference), quote = quotedLong)),
                bubble(row(message("Gorgeous light", mine = true, time = at(9, 52), replyTo = reference), quote = quotedPhoto)),
                bubble(row(message("Which one?", mine = false, time = at(9, 53), replyTo = reference), quote = quotedPhotoSymbol)),
                bubble(row(message("Listened, sounds good", mine = true, time = at(9, 54), replyTo = reference), quote = quotedVoice)),
                bubble(row(message("Ha, the dog at the end", mine = false, time = at(9, 55), replyTo = reference), quote = quotedVideo)),
                bubble(row(message("What did it say?", mine = true, time = at(9, 56), replyTo = reference), quote = quotedDeleted)),
                bubble(
                    row(
                        message(
                            "Then we take the long way round the lake and stop for coffee at the boathouse before the climb.",
                            mine = false,
                            time = at(9, 57),
                            replyTo = reference,
                        ),
                        quote = quotedText,
                    ),
                ),
            )
        }
    }

    // ---- 04 links (X3XGO, jBwNA; components ph7dN / OCen0 / D5uOf1) ----

    @Test
    fun links() {
        val reference = MessageReplyReference(UUID.randomUUID(), ME, MessageReplyReference.Kind.Text, "x")
        val quote = ReplyQuoteContent("You", "Where was that hike again?", isStandIn = false, thumbnail = null, symbol = null)
        val small = LinkPreview(
            url = "https://www.komoot.com/tour/123",
            siteName = "komoot",
            title = "Trailhead loop via the ridge",
            summary = "A 14 km loop with 620 m of climbing, great views from the ridge and a lake for a swim at the end.",
            thumbnail = Bytes.of(linkThumb),
        )
        val bare = LinkPreview(url = "https://example.com/article", title = "An article without a picture", summary = "Short description.")
        val above = LinkPreview(url = "https://www.komoot.com/tour/123", siteName = "komoot", title = "Trailhead loop via the ridge", showsAboveText = true, thumbnail = Bytes.of(linkThumb))
        val large = LinkPreview(url = "https://www.youtube.com/watch?v=1", siteName = "YouTube", title = "Ridge walk at sunrise — 4K", imageWidth = 1200, imageHeight = 630)
        val largeVideo = LinkPreview(url = "https://www.youtube.com/watch?v=2", siteName = "YouTube", title = "Timelapse over the lake", imageWidth = 1200, imageHeight = 630, isVideo = true)
        val largeFull = message("https://www.youtube.com/watch?v=1", mine = false, time = at(14, 3), linkPreview = large, mediaObjectId = UUID.randomUUID(), imageWidth = 1200, imageHeight = 630, hasFullMedia = true, previewJpeg = linkPlaceholder)
        both("04-links", setup = { it.linkImage(largeFull.id, linkLarge) }) {
            listOf(
                bubble(row(message("see example.com now", mine = false, time = at(14, 0)))),
                bubble(row(message("example.com and mail me at jane@example.com", mine = true, time = at(14, 0)))),
                bubble(row(message("https://www.komoot.com/tour/123", mine = false, time = at(14, 1), linkPreview = small))),
                bubble(row(message("Here it is: https://www.komoot.com/tour/123", mine = true, time = at(14, 1), linkPreview = small))),
                bubble(row(message("https://example.com/article", mine = true, time = at(14, 2), linkPreview = bare))),
                bubble(row(message("This one, with the preview above", mine = false, time = at(14, 2), linkPreview = above))),
                bubble(row(largeFull)),
                bubble(row(message("https://www.youtube.com/watch?v=2", mine = true, time = at(14, 4), linkPreview = largeVideo, mediaObjectId = UUID.randomUUID(), imageWidth = 1200, imageHeight = 630, previewJpeg = linkPlaceholder))),
                bubble(row(message("https://www.komoot.com/tour/123", mine = false, time = at(14, 5), linkPreview = small, replyTo = reference), quote = quote)),
            )
        }
    }

    // ---- 05 photos ----

    @Test
    fun photos() {
        val reference = MessageReplyReference(UUID.randomUUID(), ME, MessageReplyReference.Kind.Text, "x")
        val quote = ReplyQuoteContent("You", "Send me the one from the top", isStandIn = false, thumbnail = null, symbol = null)
        val downloaded = message("Photo", mine = true, time = at(16, 2), kind = ChatMessageKind.Image, mediaObjectId = UUID.randomUUID(), imageWidth = 1200, imageHeight = 900, hasFullMedia = true, previewJpeg = thumbJpeg, mediaByteCount = 812_000)
        val captioned = message("The view from the top", mine = true, time = at(16, 3), kind = ChatMessageKind.Image, mediaObjectId = UUID.randomUUID(), imageWidth = 900, imageHeight = 1200, hasFullMedia = true, previewJpeg = portraitThumb, mediaByteCount = 1_200_000)
        val replied = message("Photo", mine = false, time = at(16, 4), kind = ChatMessageKind.Image, mediaObjectId = UUID.randomUUID(), imageWidth = 1200, imageHeight = 900, hasFullMedia = true, previewJpeg = thumbJpeg, replyTo = reference)
        val reacted = message("Sunset", mine = false, time = at(16, 5), kind = ChatMessageKind.Image, mediaObjectId = UUID.randomUUID(), imageWidth = 1200, imageHeight = 900, hasFullMedia = true, previewJpeg = thumbJpeg, reactions = listOf(reaction(ME, "❤️"), reaction(PEER, "🔥", seq = 2)))
        val reactedNoCaption = message("Photo", mine = true, time = at(16, 6), kind = ChatMessageKind.Image, mediaObjectId = UUID.randomUUID(), imageWidth = 1200, imageHeight = 900, hasFullMedia = true, previewJpeg = thumbJpeg, reactions = listOf(reaction(PEER, "😮")))
        val setup: (Sheet) -> Unit = { sheet ->
            sheet.photo(downloaded.id, photoJpeg)
            sheet.photo(captioned.id, portraitJpeg)
            sheet.photo(replied.id, photoJpeg)
            sheet.photo(reacted.id, photoJpeg)
            sheet.photo(reactedNoCaption.id, photoJpeg)
        }
        // Two sheets: a software-drawn layer taller than about 4,000 px is drawn downsampled.
        both("05a-photos", setup) {
            listOf(
                bubble(row(message("Photo", mine = false, time = at(16, 0), kind = ChatMessageKind.Image, mediaObjectId = UUID.randomUUID(), imageWidth = 1200, imageHeight = 900, previewJpeg = thumbJpeg, mediaByteCount = 812_000))),
                bubble(row(message("Photo", mine = false, time = at(16, 1), kind = ChatMessageKind.Image, mediaObjectId = UUID.randomUUID(), imageWidth = 900, imageHeight = 1200, previewJpeg = portraitThumb, mediaByteCount = 2_400_000), transfer = MediaTransfer(MediaTransfer.Phase.Transferring, isUpload = false, fraction = 0.42, totalBytes = 2_400_000))),
                bubble(row(downloaded)),
                bubble(row(captioned)),
            )
        }
        both("05b-photos", setup) {
            listOf(
                bubble(row(replied, quote = quote)),
                bubble(row(message("Photo", mine = true, time = at(16, 7), kind = ChatMessageKind.Image, receipt = ReceiptStatus.Failed, imageWidth = 1200, imageHeight = 900, previewJpeg = thumbJpeg, sendError = "Couldn't upload the photo. Check your connection and try again."))),
                bubble(row(message("Photo", mine = false, time = at(16, 8), kind = ChatMessageKind.Image, mediaObjectId = UUID.randomUUID(), imageWidth = 1200, imageHeight = 900))),
                bubble(row(reacted)),
                bubble(row(reactedNoCaption)),
            )
        }
    }

    // ---- 06 videos (ap7Vi) ----

    @Test
    fun videos() {
        fun video(mine: Boolean, minute: Int, text: String = "Video", full: Boolean = false, poster: Boolean = true, receipt: ReceiptStatus = if (mine) ReceiptStatus.Read else ReceiptStatus.Sent, sendError: String? = null, media: Boolean = true) =
            message(
                text,
                mine = mine,
                time = at(17, minute),
                kind = ChatMessageKind.Video,
                receipt = receipt,
                mediaObjectId = if (media) UUID.randomUUID() else null,
                imageWidth = 1280,
                imageHeight = 720,
                hasFullMedia = full,
                previewJpeg = if (poster) thumbJpeg else null,
                mediaByteCount = 4_200_000,
                durationMs = 12_400,
                sendError = sendError,
            )
        both("06-videos") {
            listOf(
                bubble(row(video(mine = false, minute = 0))),
                bubble(row(video(mine = false, minute = 1), transfer = MediaTransfer(MediaTransfer.Phase.Transferring, isUpload = false, fraction = 0.26, totalBytes = 4_200_000))),
                bubble(row(video(mine = true, minute = 2, receipt = ReceiptStatus.Sending, media = false), transfer = MediaTransfer(MediaTransfer.Phase.Preparing, isUpload = true, fraction = 0.5))),
                bubble(row(video(mine = true, minute = 3, receipt = ReceiptStatus.Sending, media = false), transfer = MediaTransfer(MediaTransfer.Phase.Transferring, isUpload = true, fraction = 0.6, totalBytes = 4_200_000))),
                bubble(row(video(mine = true, minute = 4, full = true))),
                bubble(row(video(mine = false, minute = 5, text = "The dog at the end 😂", full = true))),
                bubble(row(video(mine = true, minute = 6, receipt = ReceiptStatus.Failed, sendError = "Couldn't upload the video.", media = false))),
                bubble(row(video(mine = false, minute = 7, poster = false), transfer = MediaTransfer(MediaTransfer.Phase.Finishing, isUpload = false))),
            )
        }
    }

    // ---- 07 voice (SkPBh) ----

    @Test
    fun voice() {
        fun note(mine: Boolean, minute: Int, seconds: Int, full: Boolean = true, transcript: String? = null, reactions: List<de.corespace.shroud.core.model.MessageReaction> = emptyList(), replyTo: MessageReplyReference? = null) =
            message(
                "Voice message",
                mine = mine,
                time = at(18, minute),
                kind = ChatMessageKind.Voice,
                mediaObjectId = UUID.randomUUID(),
                hasFullMedia = full,
                durationMs = seconds * 1000,
                voiceWaveform = envelope(),
                transcript = transcript,
                reactions = reactions,
                replyTo = replyTo,
            )
        val loading = note(mine = false, minute = 0, seconds = 4, full = false)
        val short = note(mine = true, minute = 1, seconds = 3)
        val long = note(mine = false, minute = 2, seconds = 24)
        val active = note(mine = false, minute = 3, seconds = 18)
        val open = note(mine = false, minute = 4, seconds = 9, transcript = "Hey, I'm running ten minutes late, start without me and I'll catch up at the lake.")
        val reacted = note(mine = true, minute = 5, seconds = 6, reactions = listOf(reaction(PEER, "👍")))
        val reference = MessageReplyReference(UUID.randomUUID(), PEER, MessageReplyReference.Kind.Text, "x")
        val replied = note(mine = true, minute = 6, seconds = 8, replyTo = reference)
        val quote = ReplyQuoteContent(PEER_NAME, "Can you call me back?", isStandIn = false, thumbnail = null, symbol = null)
        both(
            "07-voice",
            setup = { sheet ->
                sheet.services.media[active.id] = byteArrayOf(1, 2, 3)
                // Loaded and scrubbed to 40 %, paused: the speed chip shows, the time reads the playhead.
                sheet.services.playback.seek(active.id, byteArrayOf(1, 2, 3), 0.4)
            },
        ) {
            listOf(
                bubble(row(loading)),
                bubble(row(short)),
                bubble(row(long)),
                bubble(row(active)),
                bubble(row(open, transcriptTail = true)),
                bubble(row(reacted)),
                bubble(row(replied, quote = quote)),
            )
        }
    }

    /** The voice note's transcript states: retry disc, transcribing, the model download, no speech. */
    @Test
    fun voiceTranscripts() {
        fun note(mine: Boolean, minute: Int, full: Boolean = true) = message(
            "Voice message",
            mine = mine,
            time = at(19, minute),
            kind = ChatMessageKind.Voice,
            mediaObjectId = UUID.randomUUID(),
            hasFullMedia = full,
            durationMs = 7_000,
            voiceWaveform = envelope(),
        )
        val failedLoad = note(mine = false, minute = 0, full = false)
        val working = note(mine = false, minute = 1)
        val downloading = note(mine = true, minute = 2)
        val silent = note(mine = false, minute = 3)
        for (dark in listOf(false, true)) {
            val ui = sheet(
                "08-voice-transcripts",
                dark = dark,
                setup = { sheet ->
                    // The audio fetch ends without the audio: the disc offers a retry.
                    sheet.services.voiceLoad = {}
                    sheet.services.transcribeAnswer = { message -> if (message.id == silent.id) "" else kotlinx.coroutines.awaitCancellation() }
                    sheet.services.install.value = TranscriptionInstallState(TranscriptionInstallState.Phase.Downloading, 0.42, isDeterminate = true, languageName = "German", messageId = downloading.id)
                },
                rows = listOf(bubble(row(failedLoad)), bubble(row(working)), bubble(row(downloading)), bubble(row(silent))),
                save = false,
            )
            // "→A" on the three loaded notes, the way TalkBack's "Transcribe" / "Show transcript" actions do it.
            for (label in listOf("Transcribe", "Show transcript")) {
                ui.nodes()
                    .mapNotNull { node -> node.config.getOrNull(SemanticsActions.CustomActions)?.firstOrNull { it.label == label } }
                    .forEach { action -> ui.activity.runOnUiThread { action.action() } }
            }
            settle(ui)
            val root = ui.root
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            val cropped = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, contentHeightOf(ui, bitmap))
            File("build/outputs/c10-screens", "08-voice-transcripts-${if (dark) "dark" else "light"}.png").outputStream().use {
                cropped.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    /** The lowest row of non-background pixels, plus the sheet's bottom padding. */
    private fun contentHeightOf(ui: ComposeHarness, bitmap: Bitmap): Int {
        val background = bitmap.getPixel(1, bitmap.height - 2)
        var last = 0
        for (y in 0 until bitmap.height step 2) {
            for (x in 0 until bitmap.width step 4) {
                if (bitmap.getPixel(x, y) != background) {
                    last = y
                    break
                }
            }
        }
        val pad = (14 * ui.activity.resources.displayMetrics.density).toInt()
        return (last + pad).coerceAtMost(bitmap.height)
    }

    // ---- 09 reactions ----

    @Test
    fun reactions() = both("09-reactions") {
        listOf(
            bubble(row(message("Made it to the top!", mine = false, time = at(20, 0), reactions = listOf(reaction(ME, "❤️"))))),
            bubble(row(message("Congrats 🎉", mine = true, time = at(20, 1), reactions = listOf(reaction(PEER, "🔥", "👍", seq = 1), reaction(ME, "😮", seq = 2))))),
            bubble(row(message("Same", mine = false, time = at(20, 2), reactions = listOf(reaction(PEER, "❤️", "🔥", seq = 1), reaction(ME, "🔥", "❤️", seq = 2))))),
            bubble(
                row(
                    message(
                        "Everyone loved it",
                        mine = true,
                        time = at(20, 3),
                        reactions = listOf(reaction(PEER, "❤️", "🔥", "👍", "😂", "😮", seq = 1), reaction(ME, "🙏", "👎", "😢", seq = 2)),
                    ),
                ),
            ),
            bubble(row(message("ok", mine = false, time = at(20, 4), reactions = listOf(reaction(PEER, "🙏"))))),
        )
    }

    // ---- 10 typing (NOg29) and Notes (nPQKZ) ----

    @Test
    fun typingAndNotes() = both("10-typing-notes") {
        listOf(
            typing(ChatPeerActivity.Typing),
            typing(ChatPeerActivity.Recording),
            bubble(row(message("Buy oat milk", mine = true, time = at(8, 0), kind = ChatMessageKind.Todo, todoDone = false, peer = ME), isNotes = true)),
            bubble(row(message("Book the train for Saturday morning, the early one", mine = true, time = at(8, 1), kind = ChatMessageKind.Todo, todoDone = true, peer = ME), isNotes = true)),
            bubble(row(message("Gate code 4471", mine = true, time = at(8, 2), peer = ME), isNotes = true)),
        )
    }

    private companion object {
        const val RENDER_ENV = "SHROUD_RENDER_SCREENS"
        const val SETTLE_REAL_MS = 30L
        const val DECODE_REAL_MS = 120L
        const val PREDECODE_EDGE_PX = 640

        /** A 412 dp phone's thread less 16 dp on each side (`ChatRowWidth.of`). */
        val ROW_WIDTH = 380.dp
        val ROW_GAP = 8.dp
    }
}
