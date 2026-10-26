package de.corespace.shroud.core.messaging

import de.corespace.shroud.core.crypto.B64
import de.corespace.shroud.core.crypto.CryptoFixtures
import de.corespace.shroud.core.crypto.InMemoryRatchetSessionRecords
import de.corespace.shroud.core.crypto.MessageCrypto
import de.corespace.shroud.core.crypto.OpenAs
import de.corespace.shroud.core.crypto.PeerLocks
import de.corespace.shroud.core.crypto.TestIdentity
import de.corespace.shroud.core.model.Bytes
import de.corespace.shroud.core.model.ChatMessage
import de.corespace.shroud.core.model.ChatMessageKind
import de.corespace.shroud.core.model.ReceiptStatus
import de.corespace.shroud.core.net.ContentType
import de.corespace.shroud.core.net.ConversationItemDto
import de.corespace.shroud.core.net.wire.Icu4jTextUnitsRule
import de.corespace.shroud.core.net.wire.LinkPreview
import de.corespace.shroud.core.net.wire.MediaMessagePayload
import de.corespace.shroud.core.net.wire.MessageReplyReference
import de.corespace.shroud.core.net.wire.MessageTextPayload
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.util.UUID

/**
 * The decode pipeline (`MessageDecoder.swift`; messaging-core §9) and its Android trap: a page decode
 * and an ingest of the same message open it **once** (the ratchet is one-shot, messaging-core §23).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MessageDecoderTest {
    @get:Rule
    val textUnits = Icu4jTextUnitsRule()

    private val me = UUID.randomUUID()
    private val peer = UUID.randomUUID()
    private val store = FakeMessagingStore()
    private val keys = FakeKeys()
    private val opener = FakeOpener()
    private val peerKeys = FakePeerIdentities()
    private val mediaOnDevice = HashSet<UUID>()

    private fun TestScope.decoder(opener: EnvelopeOpener = this@MessageDecoderTest.opener): MessageDecoder {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return MessageDecoder(store, keys, opener, PeerLocks(), { it in mediaOnDevice }, dispatcher, dispatcher)
    }

    private fun context(threads: Map<UUID, List<ChatMessage>> = emptyMap(), conversations: List<ConversationItemDto> = emptyList()) =
        MessageDecoder.Context(me, conversations, threads) { sender ->
            if (sender == me) keys.publicKey.copyOf() else peerKeys.resolvePublicKey(sender)
        }

    private fun media(payload: MediaMessagePayload): String = B64.encode(payload.encoded())

    // --- Tombstones and placeholders --------------------------------------------------------------

    @Test
    fun aTombstoneSaysMessageDeleted() = runTest { // MessageDecoder.swift:44-58
        val dto = Dtos.message(sender = peer, contentType = ContentType.MEDIA, mediaObjectId = UUID.randomUUID(), deleted = true)
        val message = decoder().decode(dto, context())
        assertEquals("Message deleted", message.text)
        assertTrue(message.deleted)
        assertEquals(ChatMessageKind.Image, message.kind)
        assertEquals(dto.mediaObjectId, message.mediaObjectId)
        assertEquals("2026-09-24T12:00:00.123456Z", message.createdAtWire)
        assertTrue(opener.calls.isEmpty())
    }

    @Test
    fun noCiphertextIsUnreadable() = runTest { // :168-184
        val text = decoder().decode(Dtos.message(sender = peer, ciphertext = null), context())
        assertEquals("[Unable to decrypt]", text.text)
        val notBase64 = decoder().decode(Dtos.message(sender = peer, ciphertext = "%%%"), context())
        assertEquals("[Unable to decrypt]", notBase64.text)
        val photo = decoder().decode(Dtos.message(sender = peer, contentType = ContentType.MEDIA, ciphertext = null, mediaObjectId = UUID.randomUUID()), context())
        assertEquals("Photo", photo.text)
        assertEquals(ChatMessageKind.Image, photo.kind)
    }

    // --- Opening ------------------------------------------------------------------------------------

    @Test
    fun aPeersMessageOpensAsRecipientWithTheirPinnedKeyAndIsCached() = runTest { // :200-216
        peerKeys.keys[peer] = ByteArray(32) { 9 }
        val reply = MessageReplyReference(UUID.randomUUID(), me, MessageReplyReference.Kind.Text, "where?")
        val wire = MessageTextPayload.wire("over here", reply)
        val dto = Dtos.message(sender = peer, ciphertext = Dtos.sealed(wire))
        val message = decoder().decode(dto, context())
        assertEquals("over here", message.text)
        assertEquals(reply, message.replyTo)
        assertEquals(peer, message.peerUserId)
        assertFalse(message.isMine)
        assertEquals(ReceiptStatus.Sent, message.receipt)
        assertEquals(1, opener.calls.size)
        assertEquals(OpenAs.Recipient, opener.calls[0].role)
        assertEquals(peer, opener.calls[0].peerUserId)
        assertArrayEquals(ByteArray(32) { 9 }, opener.calls[0].senderPublic)
        assertArrayEquals(wire.toByteArray(), store.plaintexts[dto.id])
    }

    @Test
    fun ourOwnMessageOpensFromTheSelfBoxUnderTheConversationsPeer() = runTest { // :186-199
        val conversation = Dtos.conversation(peer)
        val dto = Dtos.message(sender = me, conversation = conversation.id, ciphertext = Dtos.sealed("mine"), delivered = true, read = true)
        val message = decoder().decode(dto, context(conversations = listOf(conversation)))
        assertEquals("mine", message.text)
        assertTrue(message.isMine)
        assertEquals(peer, message.peerUserId)
        assertEquals(ReceiptStatus.Read, message.receipt)
        assertEquals(OpenAs.Sender, opener.calls.single().role)
        assertArrayEquals(keys.publicKey, opener.calls.single().senderPublic)
        assertTrue(peerKeys.resolved.isEmpty())
    }

    @Test
    fun aNoteToSelfOpensAsSenderForOurOwnId() = runTest { // :189-199
        val dto = Dtos.message(sender = me, ciphertext = Dtos.sealed("note"), delivered = true)
        val message = decoder().decode(dto, context())
        assertEquals(me, message.peerUserId)
        assertEquals(ReceiptStatus.Delivered, message.receipt)
        assertEquals(me, opener.calls.single().peerUserId)
        assertEquals(OpenAs.Sender, opener.calls.single().role)
    }

    @Test
    fun receiptsComeFromTheDto() { // :20-24
        assertEquals(ReceiptStatus.Read, MessageDecoder.receiptStatus(Dtos.message(sender = me, delivered = true, read = true)))
        assertEquals(ReceiptStatus.Delivered, MessageDecoder.receiptStatus(Dtos.message(sender = me, delivered = true, read = false)))
        assertEquals(ReceiptStatus.Sent, MessageDecoder.receiptStatus(Dtos.message(sender = me)))
    }

    @Test
    fun theCachedPlaintextIsUsedInsteadOfAnOpen() = runTest { // :138-166
        val dto = Dtos.message(sender = peer, ciphertext = Dtos.sealed("FAIL spent key"))
        store.plaintexts[dto.id] = "from the cache".toByteArray()
        val message = decoder().decode(dto, context())
        assertEquals("from the cache", message.text)
        assertTrue(opener.calls.isEmpty())
    }

    @Test
    fun aMediaMessageIgnoresACachedPlaintextThatIsNoPayload() = runTest { // :138-140
        val payload = MediaMessagePayload(t = "image", mime = "image/jpeg", w = 4, h = 3, k = "a2V5", c = "At the lake")
        val dto = Dtos.message(sender = peer, contentType = ContentType.MEDIA, ciphertext = media(payload), mediaObjectId = UUID.randomUUID())
        store.plaintexts[dto.id] = "not a payload".toByteArray()
        val message = decoder().decode(dto, context())
        assertEquals("At the lake", message.text)
        assertEquals(1, opener.calls.size)
    }

    @Test
    fun aFailedOpenFallsBackToTheCacheOrThePlaceholder() = runTest { // :246-293
        val unreadable = decoder().decode(Dtos.message(sender = peer, ciphertext = Dtos.sealed("FAIL")), context())
        assertEquals("[Unable to decrypt]", unreadable.text)

        val mediaId = UUID.randomUUID()
        val mediaDto = Dtos.message(sender = peer, contentType = ContentType.MEDIA, ciphertext = Dtos.sealed("FAIL"), mediaObjectId = mediaId)
        mediaOnDevice += mediaDto.id
        val placeholder = decoder().decode(mediaDto, context())
        assertEquals("Media", placeholder.text)
        assertEquals(ChatMessageKind.Image, placeholder.kind)
        assertEquals(mediaId, placeholder.mediaObjectId)
        assertTrue(placeholder.hasFullMedia)
    }

    @Test
    fun plaintextThatIsNotUtf8IsABinaryMessage() = runTest { // :151, 231
        val dto = Dtos.message(sender = peer, ciphertext = B64.encode(byteArrayOf(0xC3.toByte(), 0x28)))
        assertEquals("[Binary message]", decoder().decode(dto, context()).text)
    }

    @Test
    fun forcePeerKeysTheMessageUnderTheThread() = runTest { // MessagingController.swift:4476-4504
        val dto = Dtos.message(sender = me, ciphertext = Dtos.sealed("x"))
        val message = decoder().decode(dto, context(), forcePeer = peer)
        assertEquals(peer, message.peerUserId)
    }

    // --- Senders outside the chat (security audit M7) ----------------------------------------------

    /**
     * The server lists a message account X sealed in the chat with the peer: it is not opened, X's key
     * is neither fetched nor pinned, nothing is cached, and it shows as unreadable in the peer's chat.
     */
    @Test
    fun aMessageFromOutsideTheChatIsNeverOpened() = runTest {
        val stranger = UUID.randomUUID()
        val dto = Dtos.message(sender = stranger, ciphertext = Dtos.sealed("this is Bob"))
        val message = decoder().decode(dto, context(), forcePeer = peer, chatPeer = peer)
        assertEquals("[Unable to decrypt]", message.text)
        assertTrue(ThreadMessageMerge.isFailedDecryptText(message.text))
        assertEquals(peer, message.peerUserId)
        assertFalse(message.isMine)
        assertTrue(opener.calls.isEmpty())
        assertTrue(peerKeys.resolved.isEmpty())
        assertNull(store.plaintexts[dto.id])
    }

    /** Another chat's message re-listed here: neither the copy this phone holds nor the cache is shown. */
    @Test
    fun anotherChatsMessageListedHereIsNotShown() = runTest {
        val alice = UUID.randomUUID()
        val dto = Dtos.message(sender = alice, ciphertext = Dtos.sealed("for your eyes"))
        store.plaintexts[dto.id] = "for your eyes".toByteArray()
        val alices = ChatMessage(dto.id, alice, alice, "for your eyes", Instant.now(), isMine = false, receipt = ReceiptStatus.Sent)
        val message = decoder().decode(dto, context(threads = mapOf(alice to listOf(alices))), forcePeer = peer, chatPeer = peer)
        assertEquals("[Unable to decrypt]", message.text)
        assertEquals(peer, message.peerUserId)
        assertTrue(opener.calls.isEmpty())
    }

    /** Media from outside the chat leaves nothing to download or show. */
    @Test
    fun mediaFromOutsideTheChatHasNothingToFetch() = runTest {
        val payload = MediaMessagePayload(t = "image", mime = "image/jpeg", w = 4, h = 3, k = "a2V5", c = "At the lake")
        val dto = Dtos.message(sender = UUID.randomUUID(), contentType = ContentType.MEDIA, ciphertext = media(payload), mediaObjectId = UUID.randomUUID())
        mediaOnDevice += dto.id
        val message = decoder().decode(dto, context(), forcePeer = peer, chatPeer = peer)
        assertEquals("[Unable to decrypt]", message.text)
        assertEquals(ChatMessageKind.Text, message.kind)
        assertNull(message.mediaObjectId)
        assertFalse(message.hasFullMedia)
        assertTrue(opener.calls.isEmpty())
    }

    /** Ours and the peer's still open with the chat named; in Notes the chat's peer is our own id. */
    @Test
    fun oursAndThePeersStillOpenWithTheChatNamed() = runTest {
        val decoder = decoder()
        val theirs = decoder.decode(Dtos.message(sender = peer, ciphertext = Dtos.sealed("from them")), context(), forcePeer = peer, chatPeer = peer)
        assertEquals("from them", theirs.text)
        val conversation = Dtos.conversation(peer)
        val mine = decoder.decode(
            Dtos.message(sender = me, conversation = conversation.id, ciphertext = Dtos.sealed("from me")),
            context(conversations = listOf(conversation)),
            forcePeer = peer,
            chatPeer = peer,
        )
        assertEquals("from me", mine.text)
        val note = decoder.decode(Dtos.message(sender = me, ciphertext = Dtos.sealed("note")), context(), chatPeer = me)
        assertEquals("note", note.text)
        assertEquals(listOf(OpenAs.Recipient, OpenAs.Sender, OpenAs.Sender), opener.calls.map { it.role })
    }

    // --- The sealed recipient (security audit M7, the `self` box) -----------------------------------

    private val alice = UUID.randomUUID()

    /**
     * The server lists our message to Alice in the chat with the peer: our self box opens, but the
     * plaintext names Alice, so it shows as unreadable there, is not cached and is refused (no ack).
     */
    @Test
    fun ourMessageToSomeoneElseListedInThisChatIsRefused() = runTest {
        val dto = Dtos.message(sender = me, ciphertext = Dtos.sealed(MessageTextPayload.wire("only for Alice", null, null, alice)))
        val decoded = decoder().decodeChecked(dto, context(), forcePeer = peer, chatPeer = peer)
        assertTrue(decoded.refused)
        assertEquals("[Unable to decrypt]", decoded.message.text)
        assertTrue(decoded.message.isMine)
        assertEquals(peer, decoded.message.peerUserId)
        assertNull(decoded.message.replyTo)
        assertNull(store.plaintexts[dto.id])
        assertEquals(listOf(OpenAs.Sender), opener.calls.map { it.role })
    }

    @Test
    fun ourMessageToSomeoneElseIsRefusedInNotesAndShownInTheirChat() = runTest {
        val decoder = decoder()
        val dto = Dtos.message(sender = me, ciphertext = Dtos.sealed(MessageTextPayload.wire("only for Alice", null, null, alice)))
        val inNotes = decoder.decodeChecked(dto, context(), forcePeer = de.corespace.shroud.core.model.NOTES_PEER_ID, chatPeer = me)
        assertTrue(inNotes.refused)
        assertEquals("[Unable to decrypt]", inNotes.message.text)
        assertNull(store.plaintexts[dto.id])
        val atHome = decoder.decodeChecked(dto, context(), forcePeer = alice, chatPeer = alice)
        assertFalse(atHome.refused)
        assertEquals("only for Alice", atHome.message.text)
        assertEquals(MessageTextPayload.wire("only for Alice", null, null, alice), String(store.plaintexts.getValue(dto.id)))
    }

    @Test
    fun aNoteNamingUsOpensInNotesOnly() = runTest {
        val decoder = decoder()
        val dto = Dtos.message(sender = me, ciphertext = Dtos.sealed(MessageTextPayload.bound("[todo:0]Buy milk", me)))
        assertTrue(decoder.decodeChecked(dto, context(), forcePeer = peer, chatPeer = peer).refused)
        val note = decoder.decodeChecked(dto, context(), forcePeer = de.corespace.shroud.core.model.NOTES_PEER_ID, chatPeer = me)
        assertFalse(note.refused)
        assertEquals("[todo:0]Buy milk", note.message.text)
    }

    @Test
    fun thePeersMessageNamingSomeoneElseIsRefused() = runTest {
        val dto = Dtos.message(sender = peer, ciphertext = Dtos.sealed(MessageTextPayload.wire("meant for Alice", null, null, alice)))
        val decoded = decoder().decodeChecked(dto, context(), forcePeer = peer, chatPeer = peer)
        assertTrue(decoded.refused)
        assertEquals("[Unable to decrypt]", decoded.message.text)
        assertFalse(decoded.message.isMine)
        assertNull(store.plaintexts[dto.id])

        val forUs = Dtos.message(sender = peer, ciphertext = Dtos.sealed(MessageTextPayload.wire("for you", null, null, me)))
        val opened = decoder().decodeChecked(forUs, context(), forcePeer = peer, chatPeer = peer)
        assertFalse(opened.refused)
        assertEquals("for you", opened.message.text)
    }

    /** A body cached for this id and sender in another chat is checked like an envelope. */
    @Test
    fun aCachedBodyNamingSomeoneElseIsNotShown() = runTest {
        val dto = Dtos.message(sender = me, ciphertext = Dtos.sealed("FAIL"))
        store.plaintexts[dto.id] = MessageTextPayload.wire("only for Alice", null, null, alice).toByteArray()
        val decoded = decoder().decodeChecked(dto, context(), forcePeer = peer, chatPeer = peer)
        assertTrue(decoded.refused)
        assertEquals("[Unable to decrypt]", decoded.message.text)
        assertTrue(opener.calls.isEmpty())
        // Alice's chat keeps its body.
        assertTrue(store.plaintexts[dto.id] != null)
    }

    /** Our copy held in Alice's thread is not reused for the same id listed in the peer's chat. */
    @Test
    fun ourCopyHeldInAnotherChatIsNotReused() = runTest {
        val dto = Dtos.message(sender = me, ciphertext = Dtos.sealed(MessageTextPayload.wire("only for Alice", null, null, alice)))
        val alices = ChatMessage(dto.id, alice, me, "only for Alice", Instant.now(), isMine = true, receipt = ReceiptStatus.Sent)
        val decoded = decoder().decodeChecked(dto, context(threads = mapOf(alice to listOf(alices))), forcePeer = peer, chatPeer = peer)
        assertTrue(decoded.refused)
        assertEquals("[Unable to decrypt]", decoded.message.text)
        // Opened for real (the self box), and checked.
        assertEquals(listOf(OpenAs.Sender), opener.calls.map { it.role })
    }

    @Test
    fun mediaAndAnnotationsNamingSomeoneElseAreRefused() = runTest {
        val payload = MediaMessagePayload(t = "image", mime = "image/jpeg", w = 4, h = 3, k = "a2V5", c = "for Alice", to = alice)
        val photo = Dtos.message(sender = me, contentType = ContentType.MEDIA, ciphertext = media(payload), mediaObjectId = UUID.randomUUID())
        mediaOnDevice += photo.id
        val refusedPhoto = decoder().decodeChecked(photo, context(), forcePeer = peer, chatPeer = peer)
        assertTrue(refusedPhoto.refused)
        assertEquals("[Unable to decrypt]", refusedPhoto.message.text)
        assertEquals(ChatMessageKind.Text, refusedPhoto.message.kind)
        assertNull(refusedPhoto.message.mediaObjectId)
        assertFalse(refusedPhoto.message.hasFullMedia)

        val transcript = de.corespace.shroud.core.net.wire.MessageAnnotation.transcript("Alice's words", UUID.randomUUID(), to = alice)
        val annotation = Dtos.message(sender = me, contentType = "annotation", ciphertext = B64.encode(transcript.encoded()))
        val refusedAnnotation = decoder().decodeChecked(annotation, context(), forcePeer = peer, chatPeer = peer)
        assertTrue(refusedAnnotation.refused)
        assertNull(de.corespace.shroud.core.net.wire.MessageAnnotation.parseTranscript(refusedAnnotation.message.text))
    }

    /** Messages from builds before the recipient was sealed open wherever they are listed, as before. */
    @Test
    fun aPlaintextNamingNobodyStillOpens() = runTest {
        val decoder = decoder()
        val mine = decoder.decodeChecked(Dtos.message(sender = me, ciphertext = Dtos.sealed("older build")), context(), forcePeer = peer, chatPeer = peer)
        assertFalse(mine.refused)
        assertEquals("older build", mine.message.text)
        val legacyPhoto = MediaMessagePayload(t = "image", mime = "image/jpeg", w = 4, h = 3, k = "a2V5", c = "At the lake")
        val photo = decoder.decodeChecked(
            Dtos.message(sender = peer, contentType = ContentType.MEDIA, ciphertext = media(legacyPhoto), mediaObjectId = UUID.randomUUID()),
            context(),
            forcePeer = peer,
            chatPeer = peer,
        )
        assertFalse(photo.refused)
        assertEquals("At the lake", photo.message.text)
    }

    // --- A copy the thread already shows (`:60-136`) -------------------------------------------------

    private fun held(id: UUID, text: String, kind: ChatMessageKind = ChatMessageKind.Text, isMine: Boolean = false, receipt: ReceiptStatus = ReceiptStatus.Sent) =
        ChatMessage(id, peer, if (isMine) me else peer, text, Instant.now(), isMine = isMine, receipt = receipt, kind = kind)

    @Test
    fun aReadableHeldCopyIsKeptAndItsTextReCached() = runTest {
        val dto = Dtos.message(sender = me, ciphertext = Dtos.sealed("FAIL"), delivered = true)
        val reply = MessageReplyReference(UUID.randomUUID(), peer, MessageReplyReference.Kind.Text, "q")
        val existing = held(dto.id, "hello", isMine = true).copy(replyTo = reply)
        val message = decoder().decode(dto, context(threads = mapOf(peer to listOf(existing))))
        assertEquals("hello", message.text)
        assertEquals(ReceiptStatus.Delivered, message.receipt) // raised, never lowered
        assertTrue(opener.calls.isEmpty())
        assertEquals(MessageTextPayload.wire("hello", reply), String(store.plaintexts.getValue(dto.id)))
    }

    @Test
    fun anEnvelopeAnOldBuildStoredAsTextIsReadBack() = runTest { // :87-96
        val reply = MessageReplyReference(UUID.randomUUID(), peer, MessageReplyReference.Kind.Image, "")
        val dto = Dtos.message(sender = peer)
        val existing = held(dto.id, MessageTextPayload.wire("body", reply))
        val message = decoder().decode(dto, context(threads = mapOf(peer to listOf(existing))))
        assertEquals("body", message.text)
        assertEquals(reply, message.replyTo)
    }

    @Test
    fun mediaThatReachedTheCacheIsNoted() = runTest { // :97-116
        val dto = Dtos.message(sender = peer, contentType = ContentType.MEDIA, mediaObjectId = UUID.randomUUID())
        mediaOnDevice += dto.id
        val existing = held(dto.id, "Voice message", kind = ChatMessageKind.Voice)
        assertTrue(decoder().decode(dto, context(threads = mapOf(UUID.randomUUID() to listOf(existing)))).hasFullMedia)
    }

    @Test
    fun aVoiceNoteStampedAsAPhotoIsRepaired() = runTest { // :117-133
        val dto = Dtos.message(sender = peer, contentType = ContentType.MEDIA, mediaObjectId = UUID.randomUUID())
        store.plaintexts[dto.id] = MediaMessagePayload(t = "voice", mime = "audio/mp4", w = 0, h = 0, k = "a2V5", d = 3000).encoded()
        val existing = held(dto.id, "Photo", kind = ChatMessageKind.Image)
        val message = decoder().decode(dto, context(threads = mapOf(peer to listOf(existing))))
        assertEquals(ChatMessageKind.Voice, message.kind)
        assertEquals("Voice message", message.text)
        assertEquals(3000, message.durationMs)
    }

    /** Review W2 (invariant 12): an id the server re-serves for another sender never reuses what this device holds. */
    @Test
    fun aMessageIdReServedForAnotherSenderIsOpenedForReal() = runTest {
        val bob = UUID.randomUUID()
        peerKeys.keys[peer] = ByteArray(32) { 9 }
        peerKeys.keys[bob] = ByteArray(32) { 5 }
        val decoder = decoder()
        // Alice's message: opened once, held in her chat and cached for her.
        val fromAlice = Dtos.message(sender = peer, ciphertext = Dtos.sealed("for your eyes"))
        val alices = decoder.decode(fromAlice, context())
        assertEquals("for your eyes", alices.text)

        // The server serves the same id as Bob's message: neither the held copy nor the cache answers.
        val forged = Dtos.message(id = fromAlice.id, sender = bob, ciphertext = Dtos.sealed("FAIL"))
        val withHeld = decoder.decode(forged, context(threads = mapOf(peer to listOf(alices))))
        assertEquals("[Unable to decrypt]", withHeld.text)
        assertEquals(bob, withHeld.senderUserId)
        assertEquals("[Unable to decrypt]", decoder.decode(forged, context()).text)
        assertEquals(bob, opener.calls.last().peerUserId)

        // Alice's own copy still reads, from the cache.
        val opens = opener.calls.size
        assertEquals("for your eyes", decoder.decode(fromAlice.copy(ciphertext = Dtos.sealed("FAIL")), context()).text)
        assertEquals(opens, opener.calls.size)
    }

    @Test
    fun aFailedHeldCopyIsDecodedAgain() = runTest {
        val dto = Dtos.message(sender = peer, ciphertext = Dtos.sealed("now readable"))
        val existing = held(dto.id, "[Unable to decrypt]")
        assertEquals("now readable", decoder().decode(dto, context(threads = mapOf(peer to listOf(existing)))).text)
    }

    // --- Media payloads (`decodeMedia`, `:301-434`) ---------------------------------------------------

    @Test
    fun aLinkWithALargeImageIsATextBubble() = runTest {
        val preview = LinkPreview(url = "https://example.com/a", title = "Example")
        val reply = MessageReplyReference(UUID.randomUUID(), peer, MessageReplyReference.Kind.Text, "q")
        val payload = MediaMessagePayload(t = "link", mime = "image/jpeg", w = 1200, h = 630, k = "a2V5", c = "see https://example.com/a", th = "qqo=", s = 4096, re = reply, lp = preview)
        val dto = Dtos.message(sender = peer, contentType = ContentType.MEDIA, ciphertext = media(payload), mediaObjectId = UUID.randomUUID())
        val message = decoder().decode(dto, context())
        assertEquals(ChatMessageKind.Text, message.kind)
        assertEquals("see https://example.com/a", message.text)
        assertEquals(preview, message.linkPreview)
        assertEquals(reply, message.replyTo)
        assertEquals(1200, message.imageWidth)
        assertEquals(Bytes.of(byteArrayOf(0xaa.toByte(), 0xaa.toByte())), message.previewJpeg)
        assertEquals(4096L, message.mediaByteCount)
        assertFalse(message.hasFullMedia)
    }

    @Test
    fun aVoiceNoteCarriesItsTranscriptAndWaveform() = runTest {
        val payload = MediaMessagePayload(t = "voice", mime = "audio/mp4", w = 0, h = 0, k = "a2V5", c = "  see you  \n", d = 1200, wf = B64.encode(byteArrayOf(10, 200.toByte(), 30)))
        val dto = Dtos.message(sender = peer, contentType = ContentType.MEDIA, ciphertext = media(payload), mediaObjectId = UUID.randomUUID())
        val message = decoder().decode(dto, context())
        assertEquals(ChatMessageKind.Voice, message.kind)
        assertEquals("see you", message.text)
        assertEquals("see you", message.transcript)
        assertEquals(1200, message.durationMs)
        assertEquals(Bytes.of(byteArrayOf(10, 200.toByte(), 30)), message.voiceWaveform)

        val silent = MediaMessagePayload(t = "voice", mime = "audio/mp4", w = 0, h = 0, k = "a2V5", wf = "")
        val bare = decoder().decode(Dtos.message(sender = peer, contentType = ContentType.MEDIA, ciphertext = media(silent)), context())
        assertEquals("Voice message", bare.text)
        assertNull(bare.voiceWaveform)
    }

    @Test
    fun aVideoShowsItsPosterOnlyOnceTheVideoIsHere() = runTest {
        val payload = MediaMessagePayload(t = "video", mime = "video/mp4", w = 1280, h = 720, k = "a2V5", th = "qqo=", d = 9000, s = 1_000_000)
        val notHere = Dtos.message(sender = peer, contentType = ContentType.MEDIA, ciphertext = media(payload), mediaObjectId = UUID.randomUUID())
        val remote = decoder().decode(notHere, context())
        assertEquals(ChatMessageKind.Video, remote.kind)
        assertEquals("Video", remote.text)
        assertNull(remote.posterJpeg)
        assertTrue(remote.previewJpeg != null)
        assertFalse(remote.hasFullMedia)

        val here = Dtos.message(sender = peer, contentType = ContentType.MEDIA, ciphertext = media(payload), mediaObjectId = UUID.randomUUID())
        mediaOnDevice += here.id
        val local = decoder().decode(here, context())
        assertEquals(local.previewJpeg, local.posterJpeg)
        assertTrue(local.hasFullMedia)
        assertEquals(9000, local.durationMs)
    }

    @Test
    fun aFilePayloadIsAFileBeforeAnyMimeSniffingAndItsNameIsCleanedAgain() = runTest { // docs/file-sharing.md §1, §5
        for (mime in listOf("application/pdf", "image/png", "video/mp4", "audio/mp4")) {
            val reply = MessageReplyReference(UUID.randomUUID(), me, MessageReplyReference.Kind.Text, "q")
            val payload = MediaMessagePayload(t = "file", mime = mime, w = 0, h = 0, k = "a2V5", c = " notes ", s = 2_400_000, re = reply, n = "../../invoice\u202Efdp.exe")
            val dto = Dtos.message(sender = peer, contentType = ContentType.MEDIA, ciphertext = media(payload), mediaObjectId = UUID.randomUUID())
            val message = decoder().decode(dto, context())
            assertEquals(mime, ChatMessageKind.File, message.kind)
            assertEquals("invoicefdp.exe", message.fileName)
            assertEquals("notes", message.text)
            assertEquals(2_400_000L, message.mediaByteCount)
            assertEquals(reply, message.replyTo)
            assertFalse(message.hasFullMedia)
        }
        val plain = MediaMessagePayload(t = "file", mime = "application/pdf", w = 0, h = 0, k = "a2V5", s = 10, n = "a.pdf")
        val here = Dtos.message(sender = peer, contentType = ContentType.MEDIA, ciphertext = media(plain), mediaObjectId = UUID.randomUUID())
        mediaOnDevice += here.id
        val local = decoder().decode(here, context())
        assertEquals("", local.text)
        assertEquals("a.pdf", local.fileName)
        assertTrue(local.hasFullMedia)
        // No name at all: the cleaning's stand-in, which has no supported extension.
        val nameless = MediaMessagePayload(t = "file", mime = "application/pdf", w = 0, h = 0, k = "a2V5", s = 10)
        assertEquals("file", decoder().decode(Dtos.message(sender = peer, contentType = ContentType.MEDIA, ciphertext = media(nameless)), context()).fileName)
    }

    @Test
    fun aPhotoWithAndWithoutCaption() = runTest {
        val captioned = MediaMessagePayload(t = "image", mime = "image/jpeg", w = 4, h = 3, k = "a2V5", c = " At the lake ")
        assertEquals("At the lake", decoder().decode(Dtos.message(sender = peer, contentType = ContentType.MEDIA, ciphertext = media(captioned)), context()).text)
        val plain = MediaMessagePayload(t = "image", mime = "image/jpeg", w = 4, h = 3, k = "a2V5")
        val photo = decoder().decode(Dtos.message(sender = peer, contentType = ContentType.MEDIA, ciphertext = media(plain)), context())
        assertEquals("Photo", photo.text)
        assertEquals(4, photo.imageWidth)
        assertEquals(3, photo.imageHeight)
    }

    @Test
    fun noPayloadAndNoBytesIsMedia() = runTest { // :398-412
        val dto = Dtos.message(sender = peer, contentType = ContentType.MEDIA, ciphertext = Dtos.sealed("not json"), mediaObjectId = UUID.randomUUID())
        // The opened plaintext is no payload, so it is not even cached as one.
        val message = decoder().decode(dto, context())
        assertEquals("Media", message.text)
        assertEquals(ChatMessageKind.Image, message.kind)
    }

    // --- The one-shot trap (messaging-core §23 item 2) ---------------------------------------------

    @Test
    fun aConcurrentIngestAndPageDecodeOpenTheMessageOnce() = runTest {
        val gate = CompletableDeferred<Unit>()
        peerKeys.gate = gate
        val decoder = decoder()
        val dto = Dtos.message(sender = peer, ciphertext = Dtos.sealed("once"))
        val ingest = async { decoder.decode(dto, context()) }
        val page = async { decoder.decode(dto, context()) }
        advanceUntilIdle()
        // Both passed the first cache check and wait for the sender's key.
        assertEquals(2, peerKeys.resolved.size)
        gate.complete(Unit)
        assertEquals("once", ingest.await().text)
        assertEquals("once", page.await().text)
        assertEquals(1, opener.calls.size)
    }

    // --- Real envelopes (W1-CRYPTO) ------------------------------------------------------------------

    @Test
    fun realEnvelopesOpenForTheRecipientAndFromOurSelfBox() = runTest {
        val (aliceId, bobId) = CryptoFixtures.sortedUserIds()
        val alice = TestIdentity.random()
        val bob = TestIdentity.random()
        val aliceCrypto = MessageCrypto(InMemoryRatchetSessionRecords())
        val bobCrypto = MessageCrypto(InMemoryRatchetSessionRecords())
        // Alice is the initiator: a v3 ratchet envelope.
        val envelope = aliceCrypto.seal("hi bob".toByteArray(), bobId, bob.public, alice.private, alice.public, aliceId)

        val bobKeys = object : OwnKeys {
            override val isUnlocked = true
            override fun <T> withKeys(block: (ByteArray, ByteArray) -> T): T = block(bob.private, bob.public)
        }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val bobDecoder = MessageDecoder(FakeMessagingStore(), bobKeys, EnvelopeOpener(bobCrypto::open), PeerLocks(), { false }, dispatcher, dispatcher)
        val dto = Dtos.message(sender = aliceId, ciphertext = MessageCrypto.toWire(envelope))
        val bobContext = MessageDecoder.Context(bobId, emptyList(), emptyMap()) { alice.public }
        assertEquals("hi bob", bobDecoder.decode(dto, bobContext).text)

        // Alice's other device reads her own message from the self box.
        val aliceKeys = object : OwnKeys {
            override val isUnlocked = true
            override fun <T> withKeys(block: (ByteArray, ByteArray) -> T): T = block(alice.private, alice.public)
        }
        val aliceDecoder = MessageDecoder(FakeMessagingStore(), aliceKeys, EnvelopeOpener(MessageCrypto(InMemoryRatchetSessionRecords())::open), PeerLocks(), { false }, dispatcher, dispatcher)
        val aliceContext = MessageDecoder.Context(aliceId, emptyList(), emptyMap()) { alice.public }
        val own = aliceDecoder.decode(dto, aliceContext, forcePeer = bobId)
        assertEquals("hi bob", own.text)
        assertTrue(own.isMine)
        assertEquals(bobId, own.peerUserId)
    }

    @Test
    fun anAudioFileKeepsItsLengthAndTagsCleanedAgain() = runTest { // docs/file-sharing.md §11.2
        val payload = MediaMessagePayload(
            t = "file", mime = "audio/mpeg", w = 160, h = 160, k = "a2V5", th = "qqo=", d = 243_400, s = 9_700_000,
            n = "track01.mp3", ti = "Midnight\u202E  City", ar = "\u200BM83",
        )
        val dto = Dtos.message(sender = peer, contentType = ContentType.MEDIA, ciphertext = media(payload), mediaObjectId = UUID.randomUUID())
        val message = decoder().decode(dto, context())
        assertEquals(ChatMessageKind.File, message.kind)
        assertEquals(243_400, message.durationMs)
        assertEquals("Midnight City", message.audioTitle)
        assertEquals("M83", message.audioArtist)
        assertTrue(message.previewJpeg != null)
        assertEquals(160, message.imageWidth)
        val zero = decoder().decode(Dtos.message(sender = peer, contentType = ContentType.MEDIA, ciphertext = media(payload.copy(d = 0, ti = " ", ar = null))), context())
        assertNull(zero.durationMs)
        assertNull(zero.audioTitle)
        assertNull(zero.audioArtist)
    }
}
