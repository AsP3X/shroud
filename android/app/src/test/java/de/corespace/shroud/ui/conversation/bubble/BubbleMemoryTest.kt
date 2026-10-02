package de.corespace.shroud.ui.conversation.bubble

import android.app.Application
import android.view.ViewGroup
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import de.corespace.shroud.core.auth.Session
import de.corespace.shroud.core.crypto.PeerLocks
import de.corespace.shroud.core.messaging.CachedConversation
import de.corespace.shroud.core.messaging.Dtos
import de.corespace.shroud.core.messaging.EngineScopes
import de.corespace.shroud.core.messaging.FakeBackend
import de.corespace.shroud.core.messaging.FakeContacts
import de.corespace.shroud.core.messaging.FakeKeys
import de.corespace.shroud.core.messaging.FakeMediaLoader
import de.corespace.shroud.core.messaging.FakeMessagingStore
import de.corespace.shroud.core.messaging.FakeNotifier
import de.corespace.shroud.core.messaging.FakeOpener
import de.corespace.shroud.core.messaging.FakePeerIdentities
import de.corespace.shroud.core.messaging.FakePrivacy
import de.corespace.shroud.core.messaging.FakeReactionsEngine
import de.corespace.shroud.core.messaging.FakeSendEngine
import de.corespace.shroud.core.messaging.FakeSocket
import de.corespace.shroud.core.messaging.HydratedMessages
import de.corespace.shroud.core.messaging.MessagingController
import de.corespace.shroud.core.messaging.MessagingDependencies
import de.corespace.shroud.core.messaging.RosterSnapshot
import de.corespace.shroud.core.messaging.testSession
import de.corespace.shroud.core.model.ChatMessage
import de.corespace.shroud.core.model.NOTES_PEER_ID
import de.corespace.shroud.core.realtime.RealtimeEvent
import de.corespace.shroud.testing.FakeAppClock
import de.corespace.shroud.ui.components.ComposeHarness
import de.corespace.shroud.ui.conversation.DecodedImageCache
import de.corespace.shroud.ui.conversation.LinkPreviewImageCache
import de.corespace.shroud.ui.conversation.links.MessageLinkText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import java.util.UUID

/**
 * The bubbles' decoded pictures leave memory with the messages they came from (conversation-thread
 * §19, invariant 5): [DecodedImageCache] and [LinkPreviewImageCache] — and the remembered link ranges
 * and transcript folds — are emptied when chats lock and drop a message's entries when it is purged,
 * through the one sink [BubbleMemory] registers with `MessagingController.registerArtifactSink`
 * (`MessageArtifactSinks`; iOS `MessagingController.swift:657-675, 1962-1975`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BubbleMemoryTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val engineScopes = EngineScopes()
    private val hosts = ArrayList<ComposeHarness>()

    private val a = UUID.fromString("0a0a0a0a-0000-4000-8000-000000000001")
    private val b = UUID.fromString("0b0b0b0b-0000-4000-8000-000000000002")

    @After
    fun tearDown() {
        hosts.forEach { host ->
            host.activity.findViewById<ViewGroup>(android.R.id.content).removeAllViews()
            host.idle()
        }
        scope.cancel()
        engineScopes.cancelAll()
        BubbleMemory.clearAll()
        VoiceTranscriptDisclosure.reset()
    }

    private fun services() = RenderBubbleServices(RuntimeEnvironment.getApplication(), scope)

    private fun picture() = ImageBitmap(4, 4)

    /** Pictures, link pictures, link ranges and a transcript fold for [a] and [b]. */
    private fun fillMemory() {
        for (id in listOf(a, b)) {
            DecodedImageCache.store(id, picture(), DecodedImageCache.Source.Full, 100)
            LinkPreviewImageCache.store(id, LinkPreviewImageCache.Variant.Full, 100, picture())
            LinkPreviewImageCache.store(id, LinkPreviewImageCache.Variant.Thumbnail, 10, picture())
            VoiceTranscriptDisclosure.setOpen(true, id)
        }
        MessageLinkText.links("see example.com")
    }

    @Test
    fun installRegistersOneSinkPerServicesAndMovesWithThem() {
        val first = services()
        BubbleMemory.install(first)
        BubbleMemory.install(first)
        assertEquals(1, first.sinks.size)
        assertSame(BubbleMemory.artifactSink, first.sinks.single())

        val second = services()
        BubbleMemory.install(second)
        assertTrue("the old registration is closed", first.sinks.isEmpty())
        assertEquals(1, second.sinks.size)
    }

    @Test
    fun aBubbleRegistersTheSinkBeforeItDraws() {
        val services = services()
        val message = BubbleRenderFixtures.message("hello", mine = false)
        val host = ComposeHarness {
            CompositionLocalProvider(LocalBubbleServices provides services, LocalChatRowWidth provides 380.dp) {
                MessageBubble(BubbleRenderFixtures.row(message), RecordingBubbleContext())
                MessageBubble(BubbleRenderFixtures.row(BubbleRenderFixtures.message("again", mine = true)), RecordingBubbleContext())
            }
        }
        hosts += host
        assertEquals("one registration for every bubble", listOf(BubbleMemory.artifactSink), services.sinks)
        BubbleRenderFixtures.awaitEmojiFont(host)
    }

    @Test
    fun aLockEmptiesEveryPictureLinkRangeAndCache() {
        val services = services()
        BubbleMemory.install(services)
        fillMemory()
        assertEquals(2, DecodedImageCache.count())
        assertEquals(4, LinkPreviewImageCache.count())

        services.sinks.single().onSensitiveMemoryLocked()

        assertEquals(0, DecodedImageCache.count())
        assertEquals(0, LinkPreviewImageCache.count())
        assertEquals(0, MessageLinkText.cachedCount())
    }

    @Test
    fun aPurgeDropsOnlyThePurgedMessages() {
        val services = services()
        BubbleMemory.install(services)
        fillMemory()

        services.sinks.single().onPurged(listOf(a))

        assertNull(DecodedImageCache.image(a))
        assertNull(LinkPreviewImageCache.imageAnySize(a, LinkPreviewImageCache.Variant.Full))
        assertNull(LinkPreviewImageCache.imageAnySize(a, LinkPreviewImageCache.Variant.Thumbnail))
        assertNull(VoiceTranscriptDisclosure.choice(a))
        assertNotNull(DecodedImageCache.image(b))
        assertNotNull(LinkPreviewImageCache.imageAnySize(b, LinkPreviewImageCache.Variant.Full))
        assertEquals(true, VoiceTranscriptDisclosure.choice(b))
    }

    @Test
    fun aRekeyedMessageKeepsItsPictureAndFold() {
        val services = services()
        BubbleMemory.install(services)
        val picture = picture()
        DecodedImageCache.store(a, picture, DecodedImageCache.Source.Full, 100)
        VoiceTranscriptDisclosure.setOpen(false, a)
        val server = UUID.fromString("5e5e5e5e-0000-4000-8000-000000000003")

        services.sinks.single().onMessageRekeyed(a, server)

        assertNull(DecodedImageCache.image(a))
        assertSame(picture, DecodedImageCache.image(server))
        assertEquals(false, VoiceTranscriptDisclosure.choice(server))
        assertTrue(VoiceTranscriptDisclosure.wasHandedOff(server))
    }

    // ---- through the real messaging controller ----

    private val me = UUID.fromString("3f2c8a9e-5b1d-4c7a-9e2f-8d6b4a1c0e7f")
    private val peer = UUID.fromString("9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d")
    private val conversation = UUID.fromString("7c9e6679-7425-40de-944b-e07fc1f90ae7")

    private fun TestScope.controller(store: FakeMessagingStore, socket: FakeSocket, backend: FakeBackend): MessagingController {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return MessagingController(
            MessagingDependencies(
                scope = engineScopes.create(testScheduler),
                session = MutableStateFlow<Session?>(testSession(me)),
                backend = backend,
                socket = socket,
                keys = FakeKeys(),
                opener = FakeOpener(),
                peerLocks = PeerLocks(),
                store = store,
                hasMedia = { false },
                contacts = FakeContacts(),
                peerIdentities = FakePeerIdentities(),
                privacy = FakePrivacy(),
                notifier = FakeNotifier(),
                sendEngine = { FakeSendEngine() },
                reactionsEngine = { FakeReactionsEngine() },
                mediaLoader = { FakeMediaLoader() },
                isOnline = { true },
                isResumed = { true },
                pushCovers = { true },
                wipeKeyRecords = {},
                refreshCallSecrets = {},
                clock = FakeAppClock(),
                io = dispatcher,
                compute = dispatcher,
            ),
        )
    }

    /** The app's path: the bubbles' services register with messaging, and messaging fans its purges and locks out. */
    private class ControllerServices(base: RenderBubbleServices, private val controller: MessagingController) : BubbleServices by base {
        override fun registerArtifactSink(sink: de.corespace.shroud.core.messaging.MessageArtifactSinks): AutoCloseable =
            controller.registerArtifactSink(sink)
    }

    @Test
    fun theControllerPurgesADeletedMessagesPicturesAndEmptiesThemAllOnLock() = runTest {
        val kept = ChatMessage(a, peer, peer, "Photo", Instant.parse("2026-09-20T10:00:00Z"), isMine = false)
        val deleted = ChatMessage(b, peer, peer, "Photo", Instant.parse("2026-09-20T10:01:00Z"), isMine = false)
        val store = FakeMessagingStore()
        store.hydrated = HydratedMessages(
            RosterSnapshot(listOf(CachedConversation(conversation, peer, "bob", Instant.parse("2026-09-01T10:00:00Z"), null, null, null)), emptyList(), emptyList(), emptyMap()),
            mapOf(peer to listOf(kept, deleted), NOTES_PEER_ID to emptyList()),
        )
        val backend = FakeBackend().apply { conversationList = listOf(Dtos.conversation(peer, conversation)) }
        val socket = FakeSocket()
        val controller = controller(store, socket, backend)
        BubbleMemory.install(ControllerServices(services(), controller))
        controller.start()
        runCurrent()
        fillMemory()

        // Deleted for everyone on the other phone: the tombstone purges what this one decoded of it.
        socket.eventsFlow.tryEmit(RealtimeEvent.MessageDeleted(b, conversation))
        runCurrent()
        assertNull(DecodedImageCache.image(b))
        assertNull(LinkPreviewImageCache.imageAnySize(b, LinkPreviewImageCache.Variant.Full))
        assertNotNull(DecodedImageCache.image(a))

        // Chats lock: nothing decoded from a message stays in memory.
        controller.lockSensitiveMemory()
        runCurrent()
        assertEquals(0, DecodedImageCache.count())
        assertEquals(0, LinkPreviewImageCache.count())
        assertEquals(0, MessageLinkText.cachedCount())
        assertFalse(controller.threads.value.containsKey(peer))
        controller.stop(wipeDisk = false)
        engineScopes.cancelAll()
    }
}
