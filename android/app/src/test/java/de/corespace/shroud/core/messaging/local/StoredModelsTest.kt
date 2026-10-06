package de.corespace.shroud.core.messaging.local

import de.corespace.shroud.core.messaging.CachedConversation
import de.corespace.shroud.core.model.Bytes
import de.corespace.shroud.core.model.ChatMessage
import de.corespace.shroud.core.model.ChatMessageKind
import de.corespace.shroud.core.model.MessageReaction
import de.corespace.shroud.core.model.ReceiptStatus
import de.corespace.shroud.core.net.ContactItemDto
import de.corespace.shroud.core.net.ContactRequestDto
import de.corespace.shroud.core.net.UserCardDto
import de.corespace.shroud.core.net.wire.Icu4jTextUnitsRule
import de.corespace.shroud.core.net.wire.LinkPreview
import de.corespace.shroud.core.net.wire.MessageReplyReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.util.UUID

/** The JSON of the sealed message files (`LocalMessageStore.swift:80-189, 482-541`; messaging-core §22.2). */
class StoredModelsTest {
    @get:Rule
    val textUnits = Icu4jTextUnitsRule()

    // ---- StoredReplyTests (`MessageReplyTests.swift:231-273`) ----

    /** `testStoredMessageKeepsTheQuote` (`MessageReplyTests.swift:233-258`). */
    @Test
    fun storedMessageKeepsTheQuote() {
        val quote = MessageReplyReference(messageId = UUID.randomUUID(), senderUserId = UUID.randomUUID(), kind = MessageReplyReference.Kind.Video, snippet = "On the ridge")
        val message = ChatMessage(
            id = UUID.randomUUID(),
            peerUserId = UUID.randomUUID(),
            senderUserId = UUID.randomUUID(),
            text = "Look at this",
            createdAt = Instant.now(),
            isMine = true,
            deleted = false,
            replyTo = quote,
        )

        val stored = StoredMessage.from(message)
        val data = LocalStoreJson.encodeToString(StoredMessage.serializer(), stored)
        val decoded = LocalStoreJson.decodeFromString(StoredMessage.serializer(), data)
        val restored = decoded.toChatMessage { false }

        assertEquals(quote, restored.replyTo)
        assertEquals("Look at this", restored.text)
    }

    /** `testLegacyStoredMessageDecodesWithoutQuote` (`MessageReplyTests.swift:260-273`): rows written before replies. */
    @Test
    fun legacyStoredMessageDecodesWithoutQuote() {
        val legacy = "{\"createdAt\":\"2026-09-19T10:00:00.000Z\",\"deleted\":false,\"id\":\"${uuidString()}\"," +
            "\"isMine\":true,\"kind\":\"text\",\"peerUserID\":\"${uuidString()}\",\"receipt\":\"sent\"," +
            "\"senderUserID\":\"${uuidString()}\",\"text\":\"before replies\"}"
        val decoded = LocalStoreJson.decodeFromString(StoredMessage.serializer(), legacy)
        assertNull(decoded.replyTo)
        assertEquals("before replies", decoded.text)
        assertEquals(Instant.parse("2026-09-19T10:00:00Z"), decoded.createdAt)
    }

    // ---- Files (docs/file-sharing.md) ----

    @Test
    fun aFileRowKeepsItsNameAndSizeAndAsksTheMediaCache() {
        val id = UUID.randomUUID()
        val message = ChatMessage(
            id = id,
            peerUserId = UUID.randomUUID(),
            senderUserId = UUID.randomUUID(),
            text = "",
            createdAt = Instant.parse("2026-10-05T10:00:00Z"),
            isMine = true,
            receipt = ReceiptStatus.Failed,
            kind = ChatMessageKind.File,
            mediaByteCount = 2_400_000,
            fileName = "Quarterly report 2026.pdf",
            sendError = "Waiting for connection…",
            pendingSync = true,
        )
        val data = LocalStoreJson.encodeToString(StoredMessage.serializer(), StoredMessage.from(message))
        assertTrue(data.contains("\"kind\":\"file\""))
        val restored = LocalStoreJson.decodeFromString(StoredMessage.serializer(), data).toChatMessage { it == id }
        assertEquals(message.copy(hasFullMedia = true), restored)
        // Other kinds never write the file keys.
        val photo = StoredMessage.from(message.copy(kind = ChatMessageKind.Image))
        assertNull(photo.fileName)
        assertNull(photo.fileSize)
    }

    // ---- Android: the row format ----

    @Test
    fun everyFieldRoundTripsAndTheDerivedOnesAreLeftOut() {
        val reply = MessageReplyReference(UUID.randomUUID(), UUID.randomUUID(), MessageReplyReference.Kind.Image, "At the trailhead")
        val preview = LinkPreview(
            url = "https://komoot.com/tour/1398273",
            siteName = "komoot",
            title = "Herzogstand – Heimgarten ridge walk",
            summary = "Intermediate hike · 13.6 km",
            thumbnail = Bytes.of(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10, 0x4A, 0x46)),
            imageWidth = 1200,
            imageHeight = 630,
            isVideo = true,
            showsAboveText = true,
        )
        val me = UUID.randomUUID()
        val message = ChatMessage(
            id = UUID.randomUUID(),
            peerUserId = UUID.randomUUID(),
            senderUserId = me,
            text = "the body",
            createdAt = Instant.parse("2026-09-24T12:00:00.123456Z"),
            createdAtWire = "2026-09-24T12:00:00.123456Z",
            isMine = true,
            receipt = ReceiptStatus.Delivered,
            kind = ChatMessageKind.Voice,
            mediaObjectId = UUID.randomUUID(),
            imageWidth = 4,
            imageHeight = 3,
            hasFullMedia = true,
            posterJpeg = Bytes.of(byteArrayOf(1, 2)),
            previewJpeg = Bytes.of(byteArrayOf(3, 4)),
            mediaByteCount = 1500,
            durationMs = 2300,
            voiceWaveform = Bytes.of(byteArrayOf(0, 127, 128.toByte(), 255.toByte())),
            transcript = "Bis gleich!",
            sendError = "Couldn’t send",
            todoDone = true,
            pendingSync = true,
            replyTo = reply,
            linkPreview = preview,
            reactions = listOf(MessageReaction(me, listOf("🔥", "👍"), 42, pending = true), MessageReaction(UUID.randomUUID(), emptyList(), 7)),
        )
        val json = LocalStoreJson.encodeToString(StoredMessage.serializer(), StoredMessage.from(message))
        val restored = LocalStoreJson.decodeFromString(StoredMessage.serializer(), json).toChatMessage { true }

        // Derived on hydrate, never stored: previews, poster, byte count (`MessagingLocalRepository.swift:87-103`).
        assertEquals(
            message.copy(posterJpeg = null, previewJpeg = null, mediaByteCount = null, reactions = message.reactions.map { it.copy(pending = false) }),
            restored,
        )
        // iOS key names; microseconds kept; pending is not a stored key.
        assertTrue(json.contains("\"peerUserID\":") && json.contains("\"senderUserID\":") && json.contains("\"userID\":"))
        assertTrue(json.contains("\"voiceDurationMs\":2300") && json.contains("\"voiceWaveform\":[0,127,128,255]"))
        assertTrue(json.contains("\"createdAt\":\"2026-09-24T12:00:00.123456Z\""))
        assertTrue(json.contains("\"pendingSync\":true"))
        assertFalse(json.contains("pending\""))
        assertFalse(json.contains("previewJpeg") || json.contains("posterJpeg") || json.contains("hasFullMedia") || json.contains("mediaByteCount"))
        // The quote and preview keep their sealed wire keys.
        assertTrue(json.contains("\"replyTo\":{\"id\":") && json.contains("\"k\":\"image\""))
        assertTrue(json.contains("\"linkPreview\":{\"ab\":true") && json.contains("\"u\":\"https://komoot.com/tour/1398273\""))
    }

    @Test
    fun optionalsAreLeftOutAndDatesCarryMilliseconds() {
        val message = ChatMessage(
            id = UUID.fromString("3f2504e0-4f89-41d3-9a0c-0305e82c3301"),
            peerUserId = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8"),
            senderUserId = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8"),
            text = "hello",
            createdAt = Instant.parse("2026-09-19T10:00:00Z"),
            isMine = false,
        )
        assertEquals(
            "{\"id\":\"3f2504e0-4f89-41d3-9a0c-0305e82c3301\",\"peerUserID\":\"6ba7b810-9dad-11d1-80b4-00c04fd430c8\"," +
                "\"senderUserID\":\"6ba7b810-9dad-11d1-80b4-00c04fd430c8\",\"text\":\"hello\",\"createdAt\":\"2026-09-19T10:00:00.000Z\"," +
                "\"isMine\":false,\"deleted\":false,\"receipt\":\"sent\",\"kind\":\"text\"}",
            LocalStoreJson.encodeToString(StoredMessage.serializer(), StoredMessage.from(message)),
        )
        assertEquals("2026-09-19T10:00:00.120Z", LocalInstantSerializer.format(Instant.parse("2026-09-19T10:00:00.12Z")))
        assertEquals("2026-09-19T10:00:00.000001Z", LocalInstantSerializer.format(Instant.parse("2026-09-19T10:00:00.000001Z")))
    }

    @Test
    fun unknownKindReadsAsTextAndUnknownReceiptAsSent() {
        // `toChatMessage`: `ChatMessageKind(storageKey:) ?? .text`, `MessageReceiptStatus(storageKey:) ?? .sent` (`:141, 170`).
        val row = row(kind = "sticker", receipt = "seen")
        val message = row.toChatMessage { true }
        assertEquals(ChatMessageKind.Text, message.kind)
        assertEquals(ReceiptStatus.Sent, message.receipt)
        assertFalse(message.hasFullMedia) // plain text never asks the media cache
    }

    @Test
    fun onlyMediaKindsAndLargeLinkImagesAskTheMediaCache() {
        val asked = ArrayList<String>()
        for (kind in listOf("image", "voice", "video", "text", "todo")) {
            row(kind = kind).toChatMessage { asked += kind; true }
        }
        assertEquals(listOf("image", "voice", "video"), asked)

        // `:153-157`: a text message with a link preview and a media id has its large image cached like a photo.
        val link = row(kind = "text").copy(mediaObjectId = UUID.randomUUID(), linkPreview = LinkPreview("https://example.com/a").wire())
        assertTrue(link.toChatMessage { true }.hasFullMedia)
        assertFalse(link.copy(mediaObjectId = null).toChatMessage { true }.hasFullMedia)
        assertFalse(link.copy(linkPreview = null).toChatMessage { true }.hasFullMedia)
    }

    @Test
    fun brokenQuotePreviewOrWaveformDropOnlyThemselves() {
        val json = "{\"id\":\"${uuidString()}\",\"peerUserID\":\"${uuidString()}\",\"senderUserID\":\"${uuidString()}\"," +
            "\"text\":\"kept\",\"createdAt\":\"2026-09-19T10:00:00Z\",\"isMine\":false,\"deleted\":false,\"receipt\":\"sent\"," +
            "\"kind\":\"voice\",\"replyTo\":{\"id\":\"nope\",\"u\":\"x\"},\"linkPreview\":{\"u\":\"ftp://example.com\"}," +
            "\"voiceWaveform\":[0,300],\"future\":{\"a\":1}}"
        val message = LocalStoreJson.decodeFromString(StoredMessage.serializer(), json).toChatMessage { false }
        assertEquals("kept", message.text)
        assertNull(message.replyTo)
        assertNull(message.linkPreview)
        assertNull(message.voiceWaveform)
    }

    @Test
    fun rosterRowsBridgeTheDtos() {
        val conversation = CachedConversation(UUID.randomUUID(), UUID.randomUUID(), "alice", Instant.parse("2026-09-01T08:00:00.5Z"), null, 12, 3)
        assertEquals(conversation, StoredConversation.from(conversation).toCached())

        val contact = ContactItemDto(UUID.randomUUID(), "bob", Instant.parse("2026-09-02T08:00:00Z"))
        assertEquals(contact, CachedContact.from(contact).toDto())

        // `CachedContactRequest.toDTO` (`:530-540`): the card comes back as (fromUserId, username, no share code).
        val from = UUID.randomUUID()
        val request = ContactRequestDto(UUID.randomUUID(), from, UUID.randomUUID(), "pending", Instant.parse("2026-09-03T08:00:00Z"), null, UserCardDto(from, "carol", "SHARE"))
        assertEquals(request.copy(user = UserCardDto(from, "carol", null)), CachedContactRequest.from(request).toDto())
        assertNull(CachedContactRequest.from(request.copy(user = null)).toDto().user)

        // Rosters written before reactions have no reaction keys.
        val old = "{\"id\":\"${uuidString()}\",\"peerID\":\"${uuidString()}\",\"peerUsername\":\"dave\",\"createdAt\":\"2026-09-01T08:00:00.000Z\"}"
        val decoded = LocalStoreJson.decodeFromString(StoredConversation.serializer(), old)
        assertNull(decoded.reactionSeq)
        assertNull(decoded.lastMessageAt)
    }

    @Test
    fun toStringNeverPrintsContentOrNames() {
        val row = row(kind = "text").copy(text = "the secret", transcript = "whisper")
        val roster = Roster(
            conversations = listOf(StoredConversation(UUID.randomUUID(), UUID.randomUUID(), "alice", Instant.EPOCH)),
            contacts = listOf(CachedContact(UUID.randomUUID(), "bob", Instant.EPOCH)),
            incomingRequests = listOf(CachedContactRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "pending", Instant.EPOCH, null, "carol")),
        )
        val printed = listOf(row, roster, ThreadFile(peerId = UUID.randomUUID(), messages = listOf(row))).joinToString()
        for (secret in listOf("the secret", "whisper", "alice", "bob", "carol")) assertFalse(secret, printed.contains(secret))
    }

    private fun row(kind: String, receipt: String = "sent") = StoredMessage(
        id = UUID.randomUUID(),
        peerUserId = UUID.randomUUID(),
        senderUserId = UUID.randomUUID(),
        text = "hello",
        createdAt = Instant.parse("2026-09-19T10:00:00Z"),
        isMine = true,
        deleted = false,
        receipt = receipt,
        kind = kind,
    )

    /** Swift `UUID().uuidString`: upper case. */
    private fun uuidString(): String = UUID.randomUUID().toString().uppercase()

    @Test
    fun anAudioFileRowKeepsItsTitleArtistAndDuration() { // docs/file-sharing.md §11.2
        val id = UUID.randomUUID()
        val message = ChatMessage(
            id = id,
            peerUserId = UUID.randomUUID(),
            senderUserId = UUID.randomUUID(),
            text = "",
            createdAt = Instant.parse("2026-10-06T10:00:00Z"),
            isMine = true,
            receipt = ReceiptStatus.Failed,
            kind = ChatMessageKind.File,
            mediaByteCount = 9_700_000,
            fileName = "track01.mp3",
            durationMs = 243_400,
            audioTitle = "Midnight City",
            audioArtist = "M83",
            pendingSync = true,
        )
        val row = StoredMessage.from(message)
        assertEquals("Midnight City", row.fileTitle)
        assertEquals("M83", row.fileArtist)
        val data = LocalStoreJson.encodeToString(StoredMessage.serializer(), row)
        val restored = LocalStoreJson.decodeFromString(StoredMessage.serializer(), data).toChatMessage { it == id }
        assertEquals(message.copy(hasFullMedia = true), restored)
        // Other kinds never write the tag keys, and a row from before audio files reads without them.
        val text = StoredMessage.from(message.copy(kind = ChatMessageKind.Text))
        assertNull(text.fileTitle)
        assertNull(text.fileArtist)
        val old = LocalStoreJson.decodeFromString(StoredMessage.serializer(), data.replace("\"fileTitle\"", "\"x1\"").replace("\"fileArtist\"", "\"x2\""))
        assertNull(old.toChatMessage { false }.audioTitle)
    }
}
